/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay

/**
 * Live per-subscription call states for the SIM gate.
 *
 * Why listeners instead of a query: `TelephonyManager.getCallState()` reports the
 * DEVICE-wide state and ignores `createForSubscriptionId()` pinning, so polling it once
 * per subscription yields the same answer twice on dual-SIM — always "ambiguous", never
 * an identification (observed in the field: both explicit selections blocked while All
 * SIMs worked). Subscribed callbacks, in contrast, are routed per subscription id by the
 * telephony service, so each SIM's ringing/off-hook/idle is genuinely distinct.
 *
 * Lifecycle: [ensureStarted] on every non-IDLE phone state (idempotent; re-registers when
 * the SIM set changes), [stop] on IDLE. States arrive as binder callbacks, including an
 * initial report per subscription right after registration — so by the time the record
 * gate evaluates (after enrichment, regularly past the 500ms verification window), the
 * snapshot is normally warm. [awaitSingleActive] covers the cold case with a short bounded
 * wait instead of an arbitrary sleep.
 *
 * Only sanitized ids and statuses are ever logged — never numbers, tokens, or extras.
 */
object SimCallStateTracker {

    private const val TAG = "CV:SimCallStates"

    /** Worst-case extra wait for the initial callback burst, explicit-selection calls only. */
    const val AWAIT_TIMEOUT_MS = 800L
    private const val AWAIT_POLL_MS = 50L

    private val states = ConcurrentHashMap<Int, Int>()

    private data class Registration(
        val telephony: TelephonyManager,
        val callback: Any,
    )

    private var registrations: Map<Int, Registration> = emptyMap()

    /**
     * Starts tracking the currently active subscriptions. Idempotent: a second call with an
     * unchanged SIM set is a no-op; a changed set (SIM swap) stops the old listeners first.
     * Safe to call from any thread; binder work happens inline, so prefer a background thread.
     */
    @Synchronized
    fun ensureStarted(context: Context) {
        val wanted = runCatching { SimSubscriptions.list(context) }.getOrNull().orEmpty()
        val wantedIds = wanted.map { it.subscriptionId }.toSet()
        if (wantedIds == registrations.keys && wantedIds.isNotEmpty()) return
        stopLocked(context)
        if (wanted.isEmpty()) {
            AppLogger.i(TAG, "No active subscriptions to track")
            return
        }
        val fresh = mutableMapOf<Int, Registration>()
        for (sim in wanted) {
            val tm = runCatching {
                val base = context.applicationContext
                    .getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                base?.createForSubscriptionId(sim.subscriptionId)
            }.getOrNull()
            if (tm == null) {
                AppLogger.w(TAG, "Cannot track subscription ${sim.subscriptionId}: telephony unavailable")
                continue
            }
            val registered: Any? = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val callback = SubCallStateCallback(sim.subscriptionId)
                    tm.registerTelephonyCallback(context.applicationContext.mainExecutor, callback)
                    callback as Any
                } else {
                    val listener = SubPhoneStateListener(sim.subscriptionId)
                    @Suppress("DEPRECATION")
                    tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                    listener as Any
                }
            }.getOrNull()
            if (registered == null) {
                AppLogger.w(TAG, "Failed to register call-state listener for subscription ${sim.subscriptionId}")
                continue
            }
            fresh[sim.subscriptionId] = Registration(tm, registered)
        }
        registrations = fresh
        AppLogger.i(TAG, "Tracking call states for subscriptions ${fresh.keys.sorted()}")
    }

    /** Stops all listeners. Idempotent; safe to call on every IDLE. */
    @Synchronized
    fun stop(context: Context) {
        stopLocked(context)
    }

    private fun stopLocked(context: Context) {
        val current = registrations
        registrations = emptyMap()
        if (current.isEmpty()) return
        for ((subId, reg) in current) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && reg.callback is TelephonyCallback) {
                    reg.telephony.unregisterTelephonyCallback(reg.callback)
                } else if (reg.callback is PhoneStateListener) {
                    @Suppress("DEPRECATION")
                    reg.telephony.listen(reg.callback, PhoneStateListener.LISTEN_NONE)
                }
            }.onFailure {
                AppLogger.w(TAG, "Failed to unregister listener for subscription $subId: ${it.javaClass.simpleName}")
            }
        }
        states.clear()
    }

    /** Last-known states by subscription id. Missing entries mean "never reported". */
    fun snapshot(): Map<Int, Int> = states.toMap()

    /**
     * The single subscription currently ringing or off-hook, waiting briefly for the initial
     * callback burst when cold. Null on timeout or when zero/several are active (ambiguous is
     * unknown — an explicit SIM selection then fails closed downstream).
     */
    suspend fun awaitSingleActive(timeoutMs: Long = AWAIT_TIMEOUT_MS): Int? {
        val start = SystemClock.elapsedRealtime()
        do {
            SimSubscriptions.pickSingleActiveCall(snapshot())?.let { return it }
            delay(AWAIT_POLL_MS)
        } while (SystemClock.elapsedRealtime() - start < timeoutMs)
        return null
    }

    /** Records one subscription's latest state. Internal so the nested callbacks can reach it. */
    internal fun onState(subscriptionId: Int, state: Int) {
        states[subscriptionId] = state
    }

    private class SubCallStateCallback(private val subId: Int) : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) = onState(subId, state)
    }

    @Suppress("DEPRECATION")
    private class SubPhoneStateListener(private val subId: Int) : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) = onState(subId, state)
    }
}
