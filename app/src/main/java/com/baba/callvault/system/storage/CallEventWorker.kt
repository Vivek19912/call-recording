/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.WacrmSession
import com.baba.callvault.data.recordings.db.RecordingDatabase
import com.baba.callvault.data.recordings.db.WacrmUploadState
import com.baba.callvault.services.call.CallOutcomeMapper
import com.baba.callvault.services.recording.RecordingNotificationHelper
import com.baba.callvault.utils.AppLogger

/**
 * Uploads one persisted call-outcome event to WACRM
 * (`POST {base}/api/v1/call-events`, JSON, HTTPS).
 *
 * No audio, no Storage, no recording — a missed/rejected call has
 * none, and this worker never needs one. Offline gaps are covered
 * by the persisted Room row plus WorkManager (`NetworkType.CONNECTED`,
 * LINEAR 30s backoff, same [CloudCopyPolicy] budget as uploads).
 *
 * Authentication is the logged-in WhatsApp Max user session, with
 * the same pre-flight refresh and single-refresh-on-401 discipline
 * as [WacrmUploadWorker]. The bearer token travels in the header
 * only and is never logged (see LogRedaction).
 *
 * Outcome contract:
 * - HTTP 200/201 → [WacrmUploadState.UPLOADED], `Result.success()`.
 *   200 is the server's idempotent-retry answer (already stored) —
 *   equally terminal success.
 * - HTTP 409 → success too: the event is stored under our
 *   idempotency key (a server that conflicts instead of deduping
 *   still proves receipt).
 * - HTTP 401 → at most one refresh + one repost; a second 401 (or
 *   a failed refresh) is terminal + reconnect notice.
 * - Other HTTP 4xx → terminal FAILED (malformed event can never
 *   succeed on retry).
 * - Network errors and HTTP 5xx → `Result.retry()` while the
 *   [CloudCopyPolicy] budget lasts, then FAILED.
 */
class CallEventWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val eventId = inputData.getString(KEY_EVENT_ID) ?: return Result.failure()
        val dao = RecordingDatabase.get(applicationContext).callEventDao()
        val row = dao.findById(eventId) ?: return Result.failure()
        if (row.uploadState == WacrmUploadState.UPLOADED) {
            AppLogger.i(TAG, "Call event is already marked uploaded; not posting again")
            return Result.success()
        }

        val prefs = AppPreferences(applicationContext)
        if (!prefs.isWacrmEnabled()) {
            AppLogger.i(TAG, "WACRM call events disabled; not posting")
            return Result.failure()
        }
        val baseUrl = WacrmApi.normalizeBaseUrl(prefs.getWacrmUrl())
        if (baseUrl == null) {
            AppLogger.w(TAG, "Call event post failed: server URL is missing or not HTTPS")
            return Result.failure()
        }
        val storedSession = WacrmSession.get(applicationContext)
        if (storedSession == null) {
            notifyReconnect()
            return terminal(eventId, "WhatsApp Max needs to be connected")
        }
        var accessToken = storedSession.accessToken
        if (WacrmAuth.shouldRefresh(storedSession.expiresAtMs, System.currentTimeMillis())) {
            val renewed = refreshSession(baseUrl)
            if (renewed == null) {
                notifyReconnect()
                return terminal(eventId, "the WhatsApp Max session expired")
            }
            accessToken = renewed.accessToken
        }

        // The event fields. `call_type` is always phone: outcome
        // telemetry has no other reliable source (see CallOutcomeMapper —
        // WhatsApp events are never synthesized). Absent values are
        // omitted, never invented.
        val fields = eventFields(
            clientEventId = row.clientEventId,
            direction = row.direction,
            outcome = row.outcome,
            phoneNumber = row.phoneNumber,
            durationSeconds = row.durationSeconds,
            occurredAtMillis = row.occurredAtMillis,
        )

        fun postOnce(token: String): WacrmApi.UploadResult? {
            return try {
                WacrmApi.postCallEvent(baseUrl, token, fields)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Call event post errored (${e.javaClass.simpleName}); retrying while the budget lasts")
                null
            }
        }

        var status = postOnce(accessToken) ?: return retryOrGiveUp(eventId)
        if (status.statusCode == 401) {
            AppLogger.i(TAG, "Call event post got 401; refreshing the session once")
            val renewed = refreshSession(baseUrl)
            status = if (renewed != null) {
                postOnce(renewed.accessToken) ?: return retryOrGiveUp(eventId)
            } else {
                notifyReconnect()
                return terminal(eventId, "the WhatsApp Max session expired")
            }
        }

        return when {
            status.statusCode == 200 || status.statusCode == 201 || status.statusCode == 409 -> {
                AppLogger.i(TAG, "Call event posted (${row.direction}/${row.outcome})")
                dao.setState(eventId, WacrmUploadState.UPLOADED)
                Result.success()
            }
            status.statusCode == 401 -> {
                notifyReconnect()
                terminal(eventId, "the server rejected the session (HTTP 401)")
            }
            status.statusCode in 400..499 -> {
                AppLogger.w(TAG, "Call event rejected (HTTP ${status.statusCode}): ${status.bodyExcerpt}")
                terminal(eventId, "the server rejected the event (HTTP ${status.statusCode})")
            }
            else -> {
                AppLogger.w(TAG, "Call event post failed (HTTP ${status.statusCode}): ${status.bodyExcerpt}; retrying while the budget lasts")
                retryOrGiveUp(eventId)
            }
        }
    }

    private fun refreshSession(baseUrl: String): WacrmSession.Session? {
        val current = WacrmSession.get(applicationContext) ?: return null
        val config = WacrmAuth.fetchMobileConfig(baseUrl) ?: return null
        return when (val outcome = WacrmAuth.refresh(config, current.refreshToken)) {
            is WacrmAuth.AuthOutcome.Ok -> {
                WacrmSession.save(
                    applicationContext,
                    outcome.session.accessToken,
                    outcome.session.refreshToken,
                    outcome.session.expiresAtMs,
                    current.userId,
                    current.userEmail,
                    current.userName,
                    current.accountId,
                    current.accountName,
                    current.role,
                )
                WhatsappSourceSync.syncAfterAuth(
                    applicationContext,
                    baseUrl,
                    outcome.session.accessToken
                )
                WacrmSession.get(applicationContext)
            }
            else -> null
        }
    }

    private fun notifyReconnect() {
        AppLogger.w(TAG, "WACRM call event needs reconnect")
        runCatching {
            RecordingNotificationHelper(applicationContext)
                .showErrorNotification(applicationContext.getString(R.string.wacrm_reconnect_required))
        }
    }

    private suspend fun terminal(eventId: String, reason: String): Result {
        AppLogger.w(TAG, "Call event will not be retried: $reason")
        RecordingDatabase.get(applicationContext).callEventDao().setState(eventId, WacrmUploadState.FAILED)
        return Result.failure()
    }

    private suspend fun retryOrGiveUp(eventId: String): Result {
        if (!CloudCopyPolicy.isLastAttempt(runAttemptCount)) return Result.retry()
        return terminal(eventId, "the retry budget is spent")
    }

    companion object {
        private const val TAG = "CV:CallEvent"
        const val KEY_EVENT_ID = "clientEventId"

        /**
         * The JSON fields for one event post. Pure — unit tested.
         * Absent values are omitted, never invented: a missed call
         * without a readable number posts exactly like one with.
         */
        fun eventFields(
            clientEventId: String,
            direction: String,
            outcome: String,
            phoneNumber: String?,
            durationSeconds: Long?,
            occurredAtMillis: Long,
        ): Map<String, String> = buildMap {
            put("client_event_id", clientEventId)
            // Phone only: outcome telemetry has no other reliable source
            // (see CallOutcomeMapper — WhatsApp events are never synthesized).
            put("call_type", "phone")
            if (direction == CallOutcomeMapper.DIRECTION_IN || direction == CallOutcomeMapper.DIRECTION_OUT) {
                put("direction", direction)
            }
            put("outcome", outcome)
            if (!phoneNumber.isNullOrBlank()) put("phone_number", phoneNumber.trim())
            if (durationSeconds != null && durationSeconds >= 0L) {
                put("duration_seconds", durationSeconds.toString())
            }
            put("occurred_at", CallEvents.occurredAtIso(occurredAtMillis))
        }
    }
}
