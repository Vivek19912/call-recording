/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.baba.callvault.MainActivity
import com.baba.callvault.system.AppLock
import com.baba.callvault.utils.AppLogger

/**
 * Opens the native dialer for a validated request — ACTION_DIAL
 * only, prefilled, never ACTION_CALL and never a placed call.
 *
 * Foreground + unlocked: a plain activity start (the app is already
 * in front, so no background-launch restriction applies).
 *
 * Background: relies on [DialAutoOpen]'s visible overlay +
 * SYSTEM_ALERT_WINDOW exemption. Without that grant Android blocks
 * the start; the caller then posts a notification instead of
 * pretending delivery succeeded.
 *
 * App lock on and not unlocked: never flash a lead's number on the
 * lock screen; the notification path waits for the explicit tap.
 */
object DialOpenDispatcher {

    private const val TAG = "CV:Dial"

    fun canOpenWithoutTap(appContext: Context): Boolean {
        if (AppLock.isEnabled(appContext) && !MainActivity.isUnlockedForDial(appContext)) {
            return false
        }
        if (MainActivity.isAppForeground) return true
        return Settings.canDrawOverlays(appContext)
    }

    /** Blocking on the background path; never call on the UI thread. */
    fun open(appContext: Context, request: DialRequests.DialRequest): Boolean {
        if (!DialPhoneValidation.isDialable(request.phoneNumber)) return false
        val telUri = DialPhoneValidation.toTelUri(request.phoneNumber) ?: return false

        if (AppLock.isEnabled(appContext) && !MainActivity.isUnlockedForDial(appContext)) {
            AppLogger.i(TAG, "Dial ${DialRequests.maskId(request.id)}: app locked; notification path only")
            return false
        }

        if (MainActivity.isAppForeground) {
            return runCatching {
                appContext.startActivity(
                    Intent(Intent.ACTION_DIAL, Uri.parse(telUri)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                AppLogger.i(
                    TAG,
                    "Dial ${DialRequests.maskId(request.id)}: dialer opened (foreground, ${DialRequests.digitCount(request.phoneNumber)} digits)"
                )
                true
            }.onFailure {
                AppLogger.w(TAG, "Dial ${DialRequests.maskId(request.id)}: foreground open failed (${it.javaClass.simpleName})")
            }.getOrDefault(false)
        }

        if (!Settings.canDrawOverlays(appContext)) {
            AppLogger.w(
                TAG,
                "Dial ${DialRequests.maskId(request.id)}: no overlay grant; background open blocked, notification fallback"
            )
            return false
        }
        return DialAutoOpen.openDirectly(appContext, request.phoneNumber)
    }
}
