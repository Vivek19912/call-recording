package com.baba.callvault.system.storage

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Background (app closed / not visible) click-to-dial opener.
 *
 * Android 10+ forbids background apps from starting activities unless
 * an exemption applies. The exemption this app relies on is the
 * user-granted [android.Manifest.permission.SYSTEM_ALERT_WINDOW]:
 * with it (and, on API 35+, a visible overlay window at launch time)
 * the system permits the start. There is deliberately NO silent
 * bypass here:
 *
 * - Offline or permission-less states fall back to the notification.
 * - The overlay is a visible, labelled "Opening dialer…" pill — it is
 *   both the platform's visibility requirement and honest UI, never a
 *   hidden 1px trick. It is removed right after the launch.
 * - The dialer always opens prefilled; a call is never placed.
 * - Failures (denied permission, blocked start, no window manager)
 *   return false so the caller falls back to the notification
 *   instead of silently dropping the request.
 *
 * Enabling requires the explicit in-app opt-in
 * ([com.baba.callvault.data.AppPreferences.isDialAutoOpen]) *plus*
 * the one-time system Settings grant — neither is assumed.
 */
object DialAutoOpen {

    private const val TAG = "CV:Dial"

    /**
     * Whether a background poll may attempt a direct open (no tap).
     * Pure — unit tested.
     *
     * @param autoOpen the explicit user opt-in.
     * @param canDrawOverlays system Settings grant state.
     * @param lockAllows false when the app lock is on and the user has
     *   not authenticated in this visit (unverifiable from background
     *   work, so those rows stay on the notification path whose tap
     *   flow waits for unlock).
     */
    fun shouldAttemptBackgroundOpen(
        autoOpen: Boolean,
        canDrawOverlays: Boolean,
        lockAllows: Boolean,
    ): Boolean = autoOpen && canDrawOverlays && lockAllows

    /**
     * Opens ACTION_DIAL for an already-validated number from a
     * background context. Call only after [shouldAttemptBackgroundOpen].
     * May block the calling (background) thread briefly; never call on
     * the UI thread.
     *
     * @return true only when the dialer launch was initiated.
     */
    fun openDirectly(appContext: Context, normalizedPhone: String): Boolean {
        if (!DialPhoneValidation.isDialable(normalizedPhone)) return false
        val telUri = DialPhoneValidation.toTelUri(normalizedPhone) ?: return false
        if (!Settings.canDrawOverlays(appContext)) {
            AppLogger.w(TAG, "Dial auto-open blocked: overlay permission not granted")
            return false
        }
        val windowManager =
            appContext.getSystemService(WindowManager::class.java) ?: return false
        val main = Handler(Looper.getMainLooper())

        // Visible, labelled overlay: satisfies the API 35+ "visible
        // overlay window" condition for the SAW background-start path
        // and tells the user what just happened. Never hidden.
        var overlay: View? = null
        val added = CountDownLatch(1)
        var addedOk = false
        main.post {
            try {
                val label = TextView(appContext).apply {
                    text = "Opening dialer…"
                    setPadding(48, 32, 48, 32)
                    setBackgroundColor(0xFF1F2937.toInt())
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 15f
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 220
                }
                windowManager.addView(label, params)
                overlay = label
                addedOk = true
            } catch (e: Exception) {
                AppLogger.w(TAG, "Dial auto-open: overlay failed (${e.javaClass.simpleName})")
            } finally {
                added.countDown()
            }
        }
        if (!added.await(3, TimeUnit.SECONDS) || !addedOk) return false
        return try {
            // Brief beat so the visible overlay exists when the system
            // evaluates the background-start exemption.
            Thread.sleep(400)
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse(telUri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            main.postDelayed({ removeOverlay(windowManager, overlay) }, 1500)
            AppLogger.i(
                TAG,
                "Dial auto-open: dialer launch initiated (${DialRequests.digitCount(normalizedPhone)} digits)"
            )
            true
        } catch (e: Exception) {
            main.post { removeOverlay(windowManager, overlay) }
            AppLogger.w(TAG, "Dial auto-open blocked (${e.javaClass.simpleName}); notification fallback applies")
            false
        }
    }

    private fun removeOverlay(windowManager: WindowManager, view: View?) {
        if (view == null) return
        runCatching { windowManager.removeView(view) }
    }
}
