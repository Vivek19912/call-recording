/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.baba.callvault.R
import com.baba.callvault.data.WacrmSession
import com.baba.callvault.utils.AppLogger

/**
 * Keeps the click-to-dial WebSocket alive while the UI is closed.
 *
 * Why a foreground service: Android suspends ordinary background
 * processes, and the requirement is that a desktop click reaches
 * the phone without opening CallVault. The service is START_STICKY
 * and is also started from MainActivity and BOOT_COMPLETED; a Vivo
 * battery manager may still kill it unless the app is allowed to
 * run in the background (the Settings screen links to that grant).
 *
 * No polling lives here: [DialSocketClient] owns one WebSocket and
 * reconnects with backoff. The notification is the price Android
 * charges for a durable background connection; it is low-importance
 * and carries no lead data.
 */
class DialSocketService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        DialSocketClient.start(this)
        AppLogger.i(TAG, "Foreground dial socket service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A sticky restart may arrive without a fresh start() call.
        DialSocketClient.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        DialSocketClient.stop()
        AppLogger.i(TAG, "Foreground dial socket service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Desktop click-to-dial")
            .setContentText("Connected for dial requests from WhatsApp Max")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()

    companion object {
        private const val TAG = "CV:DialWs"
        private const val CHANNEL_ID = "dial_socket"
        private const val NOTIFICATION_ID = 4721

        /** Start when a signed-in account exists; the obsolete upload toggle no longer gates dialer. */
        fun ensureStarted(context: Context) {
            if (WacrmSession.get(context) == null) return
            start(context)
        }

        fun start(context: Context) {
            ensureChannel(context)
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DialSocketService::class.java)
                )
            }.onFailure {
                // Background FGS-start restrictions (Android 12+) surface
                // here when there is no allowed trigger. The next app open
                // or boot retries; never crash the caller.
                AppLogger.w(TAG, "Could not start dial socket service (${it.javaClass.simpleName})")
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DialSocketService::class.java)) }
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Desktop click-to-dial",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { setShowBadge(false) }
                )
            }
        }
    }
}
