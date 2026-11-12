/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.WacrmSession
import com.baba.callvault.data.recordings.CallType
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.recordings.db.WacrmUploadState
import com.baba.callvault.services.recording.RecordingNotificationHelper
import com.baba.callvault.transcription.AudioDecoder
import com.baba.callvault.utils.AppLogger
import java.time.Instant

/**
 * Uploads one finalized recording to WACRM (`POST {base}/api/v1/recordings`, multipart, HTTPS).
 *
 * Enqueued as unique work per recording by [WacrmUploads]; survives process death, reboot and
 * offline gaps via WorkManager (`NetworkType.CONNECTED`, LINEAR 30s backoff).
 *
 * Authentication is the logged-in WhatsApp Max user session (Phase 2): the access token goes
 * into the `Authorization: Bearer` header. The legacy API key is deliberately never consulted —
 * a stored key without an explicit Connect must not upload silently.
 *
 * Outcome contract:
 * - HTTP 201 → [WacrmUploadState.UPLOADED], `Result.success()`. Nothing else counts as success.
 * - HTTP 4xx (bad request, unsupported MIME, oversize, no permission) → terminal FAILED.
 *   Authentication failures (401, expired/unrefreshable session) are terminal too, plus a
 *   reconnect notification — retrying cannot mint a new login.
 * - Network errors and HTTP 5xx → `Result.retry()` while the [CloudCopyPolicy] budget lasts, then
 *   FAILED + `Result.failure()`.
 * - A 401 first triggers at most one in-worker token refresh + one repost; a second 401 (or a
 *   failed refresh) is terminal, never a loop. No duplicate risk beyond the documented window:
 *   the repost only happens when no 201 was ever received.
 *
 * The local recording is never modified or deleted here, whatever the outcome — a failed upload
 * leaves the file exactly where finalization put it.
 *
 * Duplicate note: the row is stamped UPLOADED only after the server answers 201. If the process
 * dies in between, the retry uploads the same bytes again (new server row). Unique work + the
 * pre-flight stamp check make that window as small as a client alone can; true exactly-once would
 * need a server idempotency key, which Phase 1 deliberately does not add.
 */
class WacrmUploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    /** Outcome bucket for one recording POST status. Pure — unit tested. */
    enum class PostDisposition { SUCCESS, AUTH, TERMINAL, RETRY }

    override suspend fun doWork(): Result {
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()
        val uri = inputData.getString(KEY_URI)?.toUri() ?: return Result.failure()
        val containerMime = inputData.getString(KEY_MIME)
        val knownSize = inputData.getLong(KEY_SIZE, -1L)
        val recordedAtMillis = inputData.getLong(KEY_RECORDED_AT, -1L)

        // Retry queued before the success stamp landed: do not upload twice.
        val row = RecordingCatalog.find(applicationContext, name)
        if (row?.wacrmUploadState == WacrmUploadState.UPLOADED) {
            AppLogger.i(TAG, "'$name' is already marked uploaded; not uploading again")
            return Result.success()
        }

        val prefs = AppPreferences(applicationContext)
        // No upload-enabled boolean: the signed-in session below is the only gate, so an old
        // stored `false` can never block uploads while a valid session exists.
        val baseUrl = WacrmApi.normalizeBaseUrl(prefs.getWacrmUrl())
        if (baseUrl == null) {
            AppLogger.w(TAG, "WACRM upload of '$name' failed: server URL is missing or not HTTPS")
            return Result.failure()
        }
        // Authenticated user session (Phase 2). The legacy API key is deliberately never
        // consulted here: uploads require an explicit Connect, and a stored key must not cause
        // silent uploads. Never logged (see LogRedaction).
        val storedSession = when (val read = WacrmSession.read(applicationContext)) {
            is WacrmSession.SessionRead.Available -> read.session
            is WacrmSession.SessionRead.Unavailable -> {
                // The encrypted store itself is unreadable — transient. Retry
                // without touching the row (never mark FAILED for storage).
                return Result.retry()
            }
            is WacrmSession.SessionRead.SignedOut -> {
                notifyReconnect(name)
                return terminal(name, "WhatsApp Max needs to be connected")
            }
        }
        var accessToken = storedSession.accessToken
        if (WacrmAuth.shouldRefresh(storedSession.expiresAtMs, System.currentTimeMillis())) {
            val renewed = refreshSession(baseUrl)
            if (renewed == null) {
                if (WacrmSession.read(applicationContext) is WacrmSession.SessionRead.Unavailable) {
                    return Result.retry()
                }
                notifyReconnect(name)
                return terminal(name, "the WhatsApp Max session expired")
            }
            accessToken = renewed.accessToken
        }

        val sizeBytes = knownSize.takeIf { it > 0L }
            ?: SafHelper.fileSize(applicationContext, uri)
        if (sizeBytes <= 0L) {
            AppLogger.w(TAG, "WACRM upload of '$name' failed: source is empty or unreadable")
            return terminal(name, "the source recording is empty or unreadable")
        }
        if (sizeBytes > WacrmApi.MAX_BYTES) {
            AppLogger.w(TAG, "WACRM upload of '$name' refused: $sizeBytes bytes exceeds the 100 MB limit")
            return terminal(name, "the recording exceeds the 100 MB upload limit")
        }

        val uploadMime = WacrmApi.mimeForUpload(name, containerMime)
        if (uploadMime == null) {
            AppLogger.w(TAG, "WACRM upload of '$name' refused: no supported MIME mapping")
            return terminal(name, "the recording format is not supported for upload")
        }

        val durationSeconds = row?.durationSeconds?.takeIf { it > 0L }
            ?: readAndCacheDuration(name, uri)

        // Contact-association evidence (Phase 2B): the CallLog number and direction finalized
        // with a carrier call. Absent for VoIP and for unknown numbers — omitted then, so such
        // recordings upload exactly like Phase 1 ones. The server normalizes and matches.
        // The call type travels as its centralized wire value; anything outside the known set is
        // dropped rather than sent (the server would 400 it, burning a retry cycle for nothing).
        val phoneNumber = inputData.getString(KEY_PHONE)?.ifBlank { null }
        val direction = inputData.getString(KEY_DIRECTION)
            ?.takeIf { it == DIRECTION_IN || it == DIRECTION_OUT }
        val callType = inputData.getString(KEY_CALL_TYPE)
            ?.takeIf { it == CallType.PHONE.wireValue || it == CallType.WHATSAPP.wireValue || it == CallType.WHATSAPP_BUSINESS.wireValue }

        val fields = uploadFields(name, recordedAtMillis, durationSeconds, phoneNumber, direction, callType)

        fun postOnce(token: String): WacrmApi.UploadResult? {
            // Null stream = unreadable source (terminal, handled by the caller distinguishing it
            // via a second open attempt is wasteful — instead open here and thread the outcome).
            val audio = try {
                applicationContext.contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                // Network down mid-stream, TLS failure, host unresolvable — transient-shaped.
                // (Server 5xx arrives as a status below, not an exception.)
                AppLogger.w(TAG, "WACRM upload of '$name' errored (${e.message}); retrying while the budget lasts")
                return@postOnce null
            } ?: return null
            return try {
                audio.use { WacrmApi.postRecording(baseUrl, token, audio, name, uploadMime, fields, deviceId = DialDeviceId.get(applicationContext)) }
            } catch (e: Exception) {
                AppLogger.w(TAG, "WACRM upload of '$name' errored (${e.message}); retrying while the budget lasts")
                null
            }
        }

        val audioReadable = try {
            applicationContext.contentResolver.openInputStream(uri)?.close()
            true
        } catch (e: Exception) {
            false
        }
        if (!audioReadable) {
            return terminal(name, "the recording could not be opened for upload")
        }
        var status = postOnce(accessToken) ?: return retryOrGiveUp(name)
        if (status.statusCode == 401) {
            // Possibly a token that died mid-flight: exactly one refresh + one repost. A second
            // 401 (or a failed refresh) is terminal — looping cannot mint a login.
            AppLogger.i(TAG, "WACRM upload of '$name' got 401; refreshing the session once")
            val renewed = refreshSession(baseUrl)
            status = if (renewed != null) {
                postOnce(renewed.accessToken) ?: return retryOrGiveUp(name)
            } else {
                if (WacrmSession.read(applicationContext) is WacrmSession.SessionRead.Unavailable) {
                    return Result.retry()
                }
                notifyReconnect(name)
                return terminal(name, "the WhatsApp Max session expired")
            }
        }

        return when (classifyPostStatus(status.statusCode)) {
            PostDisposition.SUCCESS -> {
                AppLogger.i(TAG, "WACRM upload of '$name' succeeded (${sizeBytes} bytes)")
                RecordingCatalog.setWacrmState(applicationContext, name, WacrmUploadState.UPLOADED)
                Result.success()
            }
            PostDisposition.AUTH -> {
                // Still unauthorized after a fresh token: the account lost permission (or the
                // user was removed). Retrying cannot help; tell the user to reconnect.
                notifyReconnect(name)
                terminal(name, "the server rejected the session (HTTP 401)")
            }
            PostDisposition.TERMINAL -> {
                // Malformed request, rejected MIME, oversize, no permission: retrying cannot help.
                AppLogger.w(TAG, "WACRM upload of '$name' rejected (HTTP ${status.statusCode}): ${status.bodyExcerpt}")
                terminal(name, "the server rejected the upload (HTTP ${status.statusCode})")
            }
            PostDisposition.RETRY -> {
                // 5xx and anything unexpected: transient-shaped until the budget runs out.
                AppLogger.w(TAG, "WACRM upload of '$name' failed (HTTP ${status.statusCode}): ${status.bodyExcerpt}; retrying while the budget lasts")
                retryOrGiveUp(name)
            }
        }
    }

    /**
     * Rotates the stored session via the shared single-flight refresh.
     * Returns the renewed session, or null when refresh is impossible
     * (no session, no config, rejected refresh token, unreadable store).
     * Callers distinguish transient store failure ([WacrmSession.read]
     * → Unavailable → retry) from terminal auth failure themselves.
     * A successful refresh also re-pulls the user's WhatsApp recording source (connectivity is
     * back, so apply the newest server value; failures keep the last known source).
     */
    private fun refreshSession(baseUrl: String): WacrmSession.Session? {
        val config = WacrmAuth.fetchMobileConfig(baseUrl) ?: return null
        return when (
            val outcome = WacrmSessionRefresh.refreshIfNeeded(applicationContext, config, force = true)
        ) {
            is WacrmSessionRefresh.SessionResult.Valid -> {
                WhatsappSourceSync.syncAfterAuth(
                    applicationContext,
                    baseUrl,
                    outcome.session.accessToken,
                    deviceId = DialDeviceId.get(applicationContext)
                )
                outcome.session
            }
            else -> {
                // Refresh failed: the session may have been revoked
                // server-side. Uses the CURRENT access token only;
                // wipes solely on an explicit revoked answer.
                WacrmSession.get(applicationContext)?.accessToken?.let { token ->
                    WacrmDeviceAuth.checkStatusAndWipeIfRevoked(applicationContext, baseUrl, token)
                }
                null
            }
        }
    }

    /** Tells the user uploads are paused until they reconnect — exactly once per failure path. */
    private fun notifyReconnect(displayName: String) {
        AppLogger.w(TAG, "WACRM upload of '$displayName' needs reconnect")
        runCatching {
            RecordingNotificationHelper(applicationContext)
                .showErrorNotification(applicationContext.getString(R.string.wacrm_reconnect_required))
        }
    }

    /**
     * Reads the duration off the container once and remembers it on the catalog row, so neither
     * this upload nor the Home list ever pays for the decode again. Returns null when unknown —
     * the field is simply omitted then, never invented.
     */
    private suspend fun readAndCacheDuration(displayName: String, uri: android.net.Uri): Long? {
        val ms = runCatching { AudioDecoder.durationMs(applicationContext, uri) }.getOrDefault(0L)
        if (ms <= 0L) return null
        val seconds = (ms + 500L) / 1000L
        if (seconds > 0L) RecordingCatalog.setDuration(applicationContext, displayName, seconds)
        return seconds.takeIf { it > 0L }
    }

    /** Terminal outcome: stamp FAILED and stop. Never deletes or modifies the local recording. */
    private suspend fun terminal(displayName: String, reason: String): Result {
        AppLogger.w(TAG, "WACRM upload of '$displayName' will not be retried: $reason")
        RecordingCatalog.setWacrmState(applicationContext, displayName, WacrmUploadState.FAILED)
        return Result.failure()
    }

    /** Transient outcome: another attempt while the shared retry budget lasts, then terminal. */
    private suspend fun retryOrGiveUp(displayName: String): Result {
        if (!CloudCopyPolicy.isLastAttempt(runAttemptCount)) return Result.retry()
        return terminal(displayName, "the retry budget is spent")
    }

    companion object {
        private const val TAG = "CV:WacrmUpload"
        const val KEY_NAME = "displayName"
        const val KEY_URI = "recordingUri"
        const val KEY_MIME = "containerMime"
        const val KEY_SIZE = "sizeBytes"
        const val KEY_RECORDED_AT = "recordedAtMillis"
        const val KEY_PHONE = "phoneNumber"
        const val KEY_DIRECTION = "direction"
        const val KEY_CALL_TYPE = "callType"

        /** Direction values the server accepts. Anything else is dropped, never sent. */
        const val DIRECTION_IN = "in"
        const val DIRECTION_OUT = "out"

        /**
         * Buckets a recording POST status for the final `when` in
         * [doWork]: 201 → success; 401 → terminal auth (after the one
         * refresh + repost above); any other 4xx → terminal; 5xx
         * (including 503 registry outage) and anything unexpected →
         * transient retry, never terminal FAILED due to auth and never
         * a wipe. Recordings stay untouched regardless. Pure — unit
         * tested.
         */
        fun classifyPostStatus(statusCode: Int): PostDisposition = when {
            statusCode == 201 -> PostDisposition.SUCCESS
            statusCode == 401 -> PostDisposition.AUTH
            statusCode in 400..499 -> PostDisposition.TERMINAL
            else -> PostDisposition.RETRY
        }

        /**
         * The multipart text fields for one upload. Pure — unit tested. Absent values are
         * omitted, never invented: a recording without a number uploads exactly like Phase 1.
         */
        fun uploadFields(
            displayName: String,
            recordedAtMillis: Long,
            durationSeconds: Long?,
            phoneNumber: String?,
            direction: String?,
            callType: String? = null,
        ): Map<String, String> = buildMap {
            if (recordedAtMillis > 0L) put("recorded_at", Instant.ofEpochMilli(recordedAtMillis).toString())
            if (durationSeconds != null && durationSeconds > 0L) put("duration_seconds", durationSeconds.toString())
            put("file_name", displayName)
            if (!phoneNumber.isNullOrBlank()) put("phone_number", phoneNumber.trim())
            if (direction == DIRECTION_IN || direction == DIRECTION_OUT) put("direction", direction)
            if (callType == CallType.PHONE.wireValue || callType == CallType.WHATSAPP.wireValue || callType == CallType.WHATSAPP_BUSINESS.wireValue) {
                put("call_type", callType)
            }
        }
    }
}
