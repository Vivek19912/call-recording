package com.baba.callvault.system.storage

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.TimeUnit

/**
 * Owns the closed-app dial-request poll schedule.
 *
 * Idempotent: [apply] reconciles the current prefs with WorkManager
 * (UPDATE keeps one schedule, cancel removes it), so it is safe to
 * call from the opt-in toggle and from every foreground poll.
 * WorkManager persists the periodic work across reboots itself, so
 * no BOOT_COMPLETED receiver is needed.
 *
 * 15 minutes is WorkManager's minimum periodic interval — a request
 * created while the app is dead therefore waits up to one window.
 * Foreground opens (app opened) still poll immediately.
 */
object DialPollScheduler {

    private const val TAG = "CV:Dial"
    const val WORK_NAME = "cv_dial_poll"

    fun apply(context: Context) {
        val app = context.applicationContext
        val prefs = AppPreferences(app)
        val workManager = runCatching { WorkManager.getInstance(app) }.getOrNull()
        if (workManager == null) {
            AppLogger.w(TAG, "Dial poll schedule: WorkManager unavailable")
            return
        }
        if (!prefs.isWacrmEnabled() || !prefs.isDialAutoOpen()) {
            workManager.cancelUniqueWork(WORK_NAME)
            AppLogger.i(TAG, "Dial poll schedule: background poll off (auto-open disabled)")
            return
        }
        val request = PeriodicWorkRequestBuilder<DialPollWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        AppLogger.i(TAG, "Dial poll schedule: background poll every 15 min while connected")
    }

    fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME) }
    }
}
