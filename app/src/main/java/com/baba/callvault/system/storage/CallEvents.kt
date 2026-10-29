/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.WacrmSession
import com.baba.callvault.data.recordings.db.RecordingDatabase
import com.baba.callvault.data.recordings.db.CallEventEntry
import com.baba.callvault.data.recordings.db.WacrmUploadState
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Entry point for call-outcome telemetry: call once per ended
 * carrier call, from [CallEventReporter][com.baba.callvault.services.call.CallEventReporter],
 * whether or not a recording exists.
 *
 * Safe to call from any thread: everything past the cheap reads
 * runs on a detached IO scope. Never throws. No audio, no Storage,
 * no catalog involvement — the event is JSON only.
 *
 * Idempotency is three layers:
 * 1. The event id is minted once per call and persisted in Room
 *    before enqueueing; a fingerprint lookup reuses a live row, so
 *    a duplicate IDLE (double broadcast, restart race) cannot mint
 *    a second event for the same call.
 * 2. The worker is enqueued as *unique work* named after the event
 *    id ([ExistingWorkPolicy.KEEP]) — a second enqueue cannot start
 *    a parallel post.
 * 3. The server enforces UNIQUE(account_id, client_event_id): a
 *    retried POST returns the existing row (200 + deduped), which
 *    the worker treats as success.
 */
object CallEvents {

    private const val TAG = "CV:CallEvents"

    /** Unique-work name prefix. The full name is this + the event's idempotency UUID. */
    internal const val WORK_NAME_PREFIX = "call-event-"

    /**
     * Persists and queues one call-outcome event — or skips quietly
     * when uploads are disabled, unconfigured, or WhatsApp Max is
     * not connected. Events require an explicit login at call end;
     * a later Connect does not backfill them (documented).
     *
     * @param direction "in" or "out" wire value (from [CallOutcomeMapper]).
     * @param outcome one of answered/missed/rejected/unknown (from [CallOutcomeMapper]).
     * @param phoneNumber the CallLog number as read, or null when unreadable. Canonicalized
     *   to E.164 downstream when the device region establishes one; matching evidence only.
     * @param durationSeconds the CallLog duration, or null when unreadable.
     * @param occurredAtMillis the CallLog DATE (call start); the stable cross-restart anchor.
     */
    fun reportCallEnd(
        context: Context,
        direction: String,
        outcome: String,
        phoneNumber: String?,
        durationSeconds: Long?,
        occurredAtMillis: Long,
    ) {
        val app = context.applicationContext
        if (!AppPreferences(app).isWacrmEnabled()) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { reportResolved(app, direction, outcome, phoneNumber, durationSeconds, occurredAtMillis) }
                .onFailure { AppLogger.w(TAG, "Could not queue a call event: ${it.javaClass.simpleName}") }
        }
    }

    private suspend fun reportResolved(
        context: Context,
        direction: String,
        outcome: String,
        phoneNumber: String?,
        durationSeconds: Long?,
        occurredAtMillis: Long,
    ) {
        val prefs = AppPreferences(context)
        if (!prefs.isWacrmEnabled()) return
        if (WacrmApi.normalizeBaseUrl(prefs.getWacrmUrl()) == null) {
            AppLogger.w(TAG, "Call event skipped: server URL is missing or not HTTPS")
            return
        }
        // Same rule as recording uploads: telemetry needs an explicit
        // WhatsApp Max login, never a stored key.
        if (!WacrmSession.isConnected(context)) {
            AppLogger.i(TAG, "Call event skipped: WhatsApp Max is not connected")
            return
        }
        val dao = RecordingDatabase.get(context).callEventDao()
        val canonicalPhone = WacrmPhoneNumbers.canonicalize(context, phoneNumber)
        // A live row for this exact call already exists (duplicate
        // IDLE, restart race): reuse its id — never a second event.
        val existing = dao.findLiveByFingerprint(
            canonicalPhone, occurredAtMillis, direction, WacrmUploadState.FAILED
        )
        val clientEventId = existing?.clientEventId ?: UUID.randomUUID().toString()
        if (existing == null) {
            dao.insert(
                CallEventEntry(
                    clientEventId = clientEventId,
                    direction = direction,
                    outcome = outcome,
                    phoneNumber = canonicalPhone,
                    durationSeconds = durationSeconds?.takeIf { it >= 0L },
                    occurredAtMillis = occurredAtMillis,
                    uploadState = null,
                    createdAtMillis = System.currentTimeMillis(),
                )
            )
        }
        val data = Data.Builder()
            .putString(CallEventWorker.KEY_EVENT_ID, clientEventId)
            .build()
        val request = OneTimeWorkRequestBuilder<CallEventWorker>()
            .setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME_PREFIX + clientEventId, ExistingWorkPolicy.KEEP, request)
        AppLogger.i(TAG, "Queued a call event ($direction/$outcome)")
    }

    /** ISO-8601 `occurred_at` for the wire. Pure — unit tested. */
    fun occurredAtIso(occurredAtMillis: Long): String =
        Instant.ofEpochMilli(occurredAtMillis.coerceAtLeast(0L)).toString()
}
