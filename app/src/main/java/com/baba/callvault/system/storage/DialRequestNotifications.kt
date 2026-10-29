package com.baba.callvault.system.storage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.baba.callvault.MainActivity
import com.baba.callvault.R
import com.baba.callvault.ui.navigation.NotificationDestination
import com.baba.callvault.utils.AppLogger

/**
 * Local notification for an authorized click-to-dial request.
 *
 * Tap (or the "Open dialer" action) counts as the explicit user
 * interaction Android requires: it opens the NATIVE dialer with
 * the number prefilled via ACTION_DIAL. Never ACTION_CALL, never
 * automatic placement, never from the background without a tap.
 *
 * Returns true from [show] ONLY when the notification was actually
 * posted — the poller acks `delivered` on true and only on true,
 * so a denied permission or a post failure never marks a request
 * the user never saw. The notification title carries no phone
 * number (lock-screen privacy); the prefilled dialer shows it.
 */
object DialRequestNotifications {

    private const val TAG = "CV:Dial"

    const val CHANNEL_ID = "dial_requests"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Dial requests",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply { description = "Click-to-dial requests from WhatsApp Max" }
                )
            }
        }
    }

    fun show(context: Context, request: DialRequests.DialRequest): Boolean {
        if (!DialPhoneValidation.isDialable(request.phoneNumber)) return false
        val telUri = DialPhoneValidation.toTelUri(request.phoneNumber) ?: return false
        return runCatching {
            ensureChannel(context)
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: return false

            // Explicit user gesture → MainActivity (validates the
            // request is still live, opens the dialer for exactly
            // this request, then acks `opened`).
            val contentIntent = Intent(context, MainActivity::class.java).apply {
                putExtra(NotificationDestination.EXTRA, NotificationDestination.Dial.key)
                putExtra(OpenDialRequest.EXTRA_PHONE, request.phoneNumber)
                putExtra(OpenDialRequest.EXTRA_ID, request.id)
            }
            // Full-Int request codes: PendingIntent identity ignores
            // extras, so two rows sharing a code would share ONE
            // PendingIntent and a tap could dial the WRONG number.
            val baseCode = 0xD1A1 xor request.id.hashCode()
            val contentPending = PendingIntent.getActivity(
                context, baseCode,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Direct dialer action — also user-initiated (notification button).
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse(telUri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val dialPending = PendingIntent.getActivity(
                context, baseCode xor 0x5F3759DF,
                dialIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Dial requested")
                .setContentText("Tap to open the phone dialer. The call is placed by you.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setContentIntent(contentPending)
                .addAction(R.drawable.ic_mic, "Open dialer", dialPending)
                .build()
            manager.notify(request.id.hashCode(), notification)
            true
        }.onFailure {
            AppLogger.w(TAG, "Dial ${DialRequests.maskId(request.id)}: notify failed (${it.javaClass.simpleName})")
        }.getOrDefault(false)
    }
}

/** Phone + id carried on the MainActivity Intent for a dial tap. */
object OpenDialRequest {
    const val EXTRA_PHONE = "com.baba.callvault.extra.DIAL_PHONE"
    const val EXTRA_ID = "com.baba.callvault.extra.DIAL_ID"

    fun fromIntent(phone: String?, relaunchedFromHistory: Boolean): String? {
        if (relaunchedFromHistory) return null
        val normalized = phone?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        if (!DialPhoneValidation.isDialable(normalized)) return null
        return DialPhoneValidation.normalize(normalized)
    }
}
