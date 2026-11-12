/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.baba.callvault.data.recordings.db.RecordingDatabase
import com.baba.callvault.data.recordings.db.WacrmUploadState

/**
 * One-time recovery for call events that terminally failed before
 * the server accepted numeric-string durations (HTTP 400
 * `duration_seconds must be a non-negative integer`).
 *
 * The local database records only the terminal state — no HTTP code
 * or reason — so individually identifying 400s is impossible. The
 * recovery is therefore a single pass over rows already FAILED:
 *
 * - Only `call_events` rows with `uploadState == FAILED` are touched.
 *   UPLOADED rows, never-attempted rows, and everything in the
 *   recording tables are left alone.
 * - Each row is reset to the never-attempted state and re-enqueued
 *   under its existing unique work name ([CallEvents.WORK_NAME_PREFIX]
 *   + event id) with its original `client_event_id`. The server
 *   dedupes on `UNIQUE(account_id, client_event_id)` and answers
 *   409/200 for already-stored events, which the regular worker
 *   treats as success — so a retry can never create a duplicate.
 * - A persistent marker is written only after every eligible row
 *   has been scheduled. A crash or process death before the marker
 *   simply reruns the same idempotent pass; a marker from a
 *   previous run makes every later startup a no-op. No polling, no
 *   repeated resets.
 *
 * Normal worker semantics are unchanged: 200/201/409 succeed, 401
 * refreshes once, other 4xx stay terminal, 5xx/network keep the
 * regular backoff budget.
 */
object CallEventRecovery {

    private const val PREFS_NAME = "call_event_recovery"
    private const val KEY_400_DURATION_RECOVERY_DONE = "duration_string_400_recovery_done_v1"

    interface Marker {
        fun isDone(): Boolean
        fun markDone()
    }

    class PrefsMarker(context: Context) : Marker {
        private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        override fun isDone(): Boolean = prefs.getBoolean(KEY_400_DURATION_RECOVERY_DONE, false)
        override fun markDone() {
            prefs.edit { putBoolean(KEY_400_DURATION_RECOVERY_DONE, true) }
        }
    }

    /**
     * Pure plan: which ids to requeue. Deterministic and stable —
     * the same inputs always yield the same plan, so an
     * interrupted run reproduces it exactly on the next startup.
     */
    fun plan(failedEventIds: List<String>, alreadyDone: Boolean): List<String> =
        if (alreadyDone) emptyList() else failedEventIds.distinct()

    /**
     * Executes one recovery pass. `schedule` resets one row and
     * enqueues its upload; it runs to completion before the marker
     * is written, so a crash mid-pass leaves the marker unset and
     * the next startup safely repeats the pass. Returns the number
     * of requeued events.
     */
    suspend fun runOnce(
        failedEventIds: List<String>,
        marker: Marker,
        schedule: suspend (String) -> Unit,
    ): Int {
        if (marker.isDone()) return 0
        val ids = plan(failedEventIds, alreadyDone = false)
        for (id in ids) schedule(id)
        marker.markDone()
        return ids.size
    }

    /**
     * Android entry point: reads FAILED event ids from Room and
     * requeues them through the regular [CallEventWorker] path.
     * Throws on database/work-scheduling failure (caller logs);
     * throwing before the marker keeps the pass retryable.
     */
    suspend fun maybeRecoverOnce(
        context: Context,
        marker: Marker = PrefsMarker(context),
    ): Int {
        if (marker.isDone()) return 0
        val appContext = context.applicationContext
        val dao = RecordingDatabase.get(appContext).callEventDao()
        val failedIds = dao.failedEventIds(WacrmUploadState.FAILED)
        return runOnce(failedIds, marker) { eventId ->
            dao.clearState(eventId)
            val request = OneTimeWorkRequestBuilder<CallEventWorker>()
                .setInputData(workDataOf(CallEventWorker.KEY_EVENT_ID to eventId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, java.util.concurrent.TimeUnit.SECONDS)
                .addTag(WacrmRemoteLogout.WACRM_WORK_TAG)
                .build()
            WorkManager.getInstance(appContext)
                .enqueueUniqueWork(CallEvents.WORK_NAME_PREFIX + eventId, androidx.work.ExistingWorkPolicy.KEEP, request)
        }
    }
}
