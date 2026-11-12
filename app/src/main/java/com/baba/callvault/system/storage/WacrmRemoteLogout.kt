/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import androidx.work.WorkManager
import com.baba.callvault.data.WacrmSession
import com.baba.callvault.services.recording.RecordingNotificationHelper
import com.baba.callvault.utils.AppLogger

/**
 * Server-driven sign-out (remote wipe).
 *
 * Triggered ONLY by an explicit server revocation: the
 * `logout_device` socket push or a status answer of
 * `revoked == true` (see [WacrmDeviceAuth]). There is no agent
 * self-logout UI anymore — the Settings screen shows the
 * connected state but offers no Disconnect.
 *
 * Deliberately LOCAL clear only: the session is already revoked
 * server-side, and calling [WacrmAuth.signOut] here would hit
 * GoTrue without `scope=local` protection context — a global
 * revocation would sign out every phone and browser on the
 * account. So: clear the store, stop the socket, drop queued
 * WACRM work, and tell the user. Never throws.
 *
 * Idempotent: every step is safe to repeat (clearing an empty
 * store, stopping a stopped service, cancelling absent work, and
 * re-notifying under one fixed notification id).
 */
object WacrmRemoteLogout {

    private const val TAG = "CV:WacrmLogout"

    /**
     * Tag carried by every WACRM upload/event WorkManager request
     * (see [WacrmUploads], [CallEvents]) so the wipe can cancel
     * exactly that work. Recording, transcription, summary, sync,
     * retention, and update work carry no such tag and are never
     * touched.
     */
    const val WACRM_WORK_TAG = "wacrm"

    /**
     * Shown when the administrator signs this device out. A literal,
     * deliberately: the reconnect notices beside it are resources,
     * but one admin-facing sentence is not worth touching all
     * thirteen locales (MissingTranslation is a lint error).
     */
    private const val SIGNED_OUT_BY_ADMIN = "Signed out by your administrator"

    fun applyRemoteLogout(context: Context) {
        val app = context.applicationContext
        AppLogger.w(TAG, "REMOTE_LOGOUT_APPLY_STARTED")
        // The session first: every worker and the socket treat a
        // missing session as "do nothing", so from here on nothing
        // new can authenticate even if the steps below race.
        val cleared = runCatching { WacrmSession.clear(app); true }.getOrDefault(false)
        AppLogger.w(TAG, "LOCAL_SESSION_CLEAR_RESULT ok=$cleared")
        // Owner recording policy goes with the session, but AFTER it: workers treat a
        // missing session as "do nothing", so the session must die first. Clearing here
        // (and not the session semantics) never weakens the remote-logout security flow.
        runCatching { WhatsappSourceSync.clearRecordingPolicy(com.baba.callvault.data.AppPreferences(app)) }
            .onFailure { AppLogger.w(TAG, "Could not clear Owner recording policy (${it.javaClass.simpleName})") }
        // The open UI reads this object, not the store directly —
        // publish the wipe so a visible Settings screen recomposes
        // to the sign-in form immediately instead of showing a
        // stale "Connected" block.
        runCatching { WacrmSessionState.publish(null) }
        AppLogger.w(TAG, "SESSION_STATE_PUBLISHED connected=false")
        runCatching { DialSocketService.stop(app) }
        // Only WACRM-tagged unique work (uploads, call events).
        // Queued workers that survive the cancel still self-terminate
        // on the now-missing session — they never upload signed out.
        runCatching {
            val work = WorkManager.getInstance(app)
            work.cancelAllWorkByTag(WACRM_WORK_TAG)
        }.onFailure {
            AppLogger.w(TAG, "Could not cancel WACRM work (${it.javaClass.simpleName})")
        }
        // Reuses the recording-error channel, like the reconnect
        // notices ([WacrmUploadWorker], [CallEventWorker]).
        runCatching {
            RecordingNotificationHelper(app).showErrorNotification(SIGNED_OUT_BY_ADMIN)
        }.onFailure {
            AppLogger.w(TAG, "Could not notify remote logout (${it.javaClass.simpleName})")
        }
    }
}
