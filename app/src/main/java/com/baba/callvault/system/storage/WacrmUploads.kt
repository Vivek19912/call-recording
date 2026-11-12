/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.WacrmSession
import com.baba.callvault.data.recordings.CallType
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.data.recordings.db.WacrmUploadState
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Entry point for Phase 1 WACRM uploads: call once per finalized recording, from the two
 * finalization gates (carrier `routeFinalRecording`, VoIP `onCallEnded`), right after
 * [StorageRouter.route].
 *
 * Safe to call from any thread (including the service main thread): everything past the cheap
 * in-memory preference reads runs on a detached IO scope. Never throws.
 *
 * Duplicate prevention is three layers, because no single one is airtight:
 * 1. The worker is enqueued as *unique work* named after the recording's [displayName]
 *    ([ExistingWorkPolicy.KEEP]) — repeated enqueues cannot create simultaneous workers.
 * 2. A row already stamped [WacrmUploadState.UPLOADED] is never enqueued again.
 * 3. The worker re-checks the stamp first thing in `doWork`, covering the race where a retry was
 *    already queued before the success stamp landed.
 *
 * What this does NOT claim: mathematical exactly-once. If the process dies after WACRM returns
 * 201 but before the UPLOADED stamp is written, the retry uploads the recording a second time
 * (same bytes, new server row). Retrying is still the right default — the alternative is silently
 * losing recordings — but the window is real and documented, not hand-waved away.
 */
object WacrmUploads {

    private const val TAG = "CV:WacrmUploads"

    /** Unique-work name prefix. The full name is this + the recording's displayName (the PK). */
    internal const val WORK_NAME_PREFIX = "wacrm-upload-"

    /**
     * Queues a WACRM upload for a finalized recording — or skips quietly when not signed in,
     * unconfigured, already done, or the file is over the server limit. Best-effort: never throws.
     *
     * Uploads are enabled by a signed-in account alone: the old upload switch is gone, so no
     * stored boolean is consulted here — only the live session below (plus the server URL).
     *
     * @param sizeBytes best-known size (staged count or document length); re-verified in the worker.
     * @param lastModified best-known epoch millis; `recorded_at` prefers the filename timestamp and
     *   falls back to this.
     * @param phoneNumber the call's phone number as finalized (E.164 when parseable), or null when
     *   unknown (VoIP path always passes null). Canonicalized to E.164 downstream
     *   ([WacrmPhoneNumbers]) when the device region establishes one; matching evidence for
     *   the server only — CallVault never learns contacts.
     * @param direction "in" or "out" for carrier calls, null otherwise. Passed through verbatim;
     *   the server rejects anything else.
     * @param callType the call's kind, or null when unknown. Stored on the catalog row and sent as
     *   `call_type` using [CallType.wireValue] — the single centralized mapping.
     */
    fun enqueueAfterFinalize(
        context: Context,
        uri: Uri,
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
        lastModified: Long,
        phoneNumber: String? = null,
        direction: String? = null,
        callType: CallType? = null,
    ) {
        val app = context.applicationContext
        // Plain-preference reads are memory-cached: safe on the caller thread, even the service main
        // thread. Everything that can touch disk (encrypted store, Room, WorkManager) goes to IO.
        // No upload-enabled boolean: a signed-in account alone enables uploads (checked below).
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { enqueueResolved(app, uri, displayName, mimeType, sizeBytes, lastModified, phoneNumber, direction, callType) }
                .onFailure { AppLogger.w(TAG, "Could not queue a WACRM upload for '$displayName': ${it.message}") }
        }
    }

    private suspend fun enqueueResolved(
        context: Context,
        uri: Uri,
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
        lastModified: Long,
        phoneNumber: String?,
        direction: String?,
        callType: CallType?,
    ) {
        val prefs = AppPreferences(context)
        // No upload-enabled boolean: the signed-in session below is the only gate, so an old
        // stored `false` can never block uploads while a valid session exists.
        if (WacrmApi.normalizeBaseUrl(prefs.getWacrmUrl()) == null) {
            AppLogger.w(TAG, "WACRM upload skipped for '$displayName': server URL is missing or not HTTPS")
            return
        }
        // Session presence is what enables uploads: a signed-in account uploads automatically,
        // and a signed-out (or never signed-in) one queues nothing. Connecting later starts
        // uploads fresh instead of finding rows already stamped FAILED.
        if (!WacrmSession.isConnected(context)) {
            AppLogger.i(TAG, "WACRM upload skipped for '$displayName': WhatsApp Max is not connected")
            return
        }
        if (RecordingCatalog.wacrmState(context, displayName) == WacrmUploadState.UPLOADED) {
            AppLogger.i(TAG, "WACRM upload skipped for '$displayName': already uploaded")
            return
        }
        if (sizeBytes > WacrmApi.MAX_BYTES) {
            AppLogger.w(TAG, "WACRM upload skipped for '$displayName': $sizeBytes bytes exceeds the 100 MB limit")
            RecordingCatalog.setWacrmState(context, displayName, WacrmUploadState.FAILED)
            return
        }
        val recordedAtMillis = RecordingsRepository.parseName(displayName).startedAtMillis
            ?: lastModified.takeIf { it > 0L }
            ?: -1L
        // Canonicalize for server-side matching (E.164 when the device region establishes one;
        // raw otherwise — never dropped). Null stays absent from the bundle.
        val phoneForUpload = WacrmPhoneNumbers.canonicalize(context, phoneNumber)
        val data = Data.Builder()
            .putString(WacrmUploadWorker.KEY_NAME, displayName)
            .putString(WacrmUploadWorker.KEY_URI, uri.toString())
            .putString(WacrmUploadWorker.KEY_MIME, mimeType)
            .putLong(WacrmUploadWorker.KEY_SIZE, sizeBytes)
            .putLong(WacrmUploadWorker.KEY_RECORDED_AT, recordedAtMillis)
            // Nulls are simply absent from the bundle: the worker omits absent fields, so a
            // recording without a number uploads exactly like a Phase 1 one. The call type travels
            // as its centralized wire value; unknown stays absent, never a fake string.
            .putString(WacrmUploadWorker.KEY_PHONE, phoneForUpload)
            .putString(WacrmUploadWorker.KEY_DIRECTION, direction?.ifBlank { null })
            .putString(WacrmUploadWorker.KEY_CALL_TYPE, callType?.wireValue)
            .build()
        val request = OneTimeWorkRequestBuilder<WacrmUploadWorker>()
            .setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .addTag(WacrmRemoteLogout.WACRM_WORK_TAG)
            .build()
        // KEEP: a worker already queued or retrying for this recording wins; a second finalize-time
        // enqueue for the same name cannot start a parallel upload.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME_PREFIX + displayName, ExistingWorkPolicy.KEEP, request)
        // Conditional stamp (only when still unattempted): the worker may have finished between the
        // enqueue and this line, and an unconditional write would un-mark its success.
        RecordingCatalog.markWacrmPending(context, displayName)
        AppLogger.i(TAG, "Queued a WACRM upload for '$displayName'")
    }
}
