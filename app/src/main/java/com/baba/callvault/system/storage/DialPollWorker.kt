package com.baba.callvault.system.storage

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.system.AppLock
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Closed-app click-to-dial poll (foreground-sync fallback — there is
 * no FCM/push channel in this app).
 *
 * Runs at most every 15 minutes (WorkManager's minimum) while the
 * app is closed, fetching the logged-in user's pending dial
 * requests. When direct background open is allowed
 * ([DialAutoOpen.shouldAttemptBackgroundOpen]), the oldest pending
 * row opens the native dialer itself; otherwise rows surface as
 * notifications (tapping one opens the dialer after unlock), exactly
 * like the foreground path in [DialSync].
 *
 * Latency note: without push, a request created while the app is
 * dead waits for the next 15-minute window. This is documented, not
 * hidden — instant background delivery needs FCM (Firebase project
 * + server key + device-token registration), which does not exist.
 */
class DialPollWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        val prefs = AppPreferences(app)
        if (!prefs.isWacrmEnabled() || !prefs.isDialAutoOpen()) {
            // Gated by the explicit opt-in: no auto-open, no background
            // polling at all. Foreground onResume polling still covers
            // the notification path.
            return@withContext Result.success()
        }
        DialSync.checkAndNotify(
            app,
            directOpen = { request -> DialAutoOpen.openDirectly(app, request.phoneNumber) },
            allowDirectOverride = DialAutoOpen.shouldAttemptBackgroundOpen(
                autoOpen = true,
                canDrawOverlays = android.provider.Settings.canDrawOverlays(app),
                lockAllows = !AppLock.isEnabled(app),
            ),
        )
        Result.success()
    }
}
