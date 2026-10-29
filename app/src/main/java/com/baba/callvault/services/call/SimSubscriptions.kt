/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import android.content.Context
import android.content.Intent
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * One usable SIM subscription on this device, for the "Record calls from" picker.
 *
 * The [subscriptionId] is the stable Android subscription identifier — NOT the slot label. Slot
 * assignments can move between boots; the subscription id is what the phone-state broadcast and
 * the call log key on, so it is what is persisted and compared.
 */
data class SimInfo(
    val subscriptionId: Int,
    val slotIndex: Int,
    val carrierName: CharSequence?,
    val number: String?,
    /**
     * `SubscriptionManager.getPhoneNumber(subscriptionId)` for the
     * same subscription, or null when unreadable. SECONDARY source:
     * on some devices (verified Vivo I2304: SIM 1) the manager API
     * returns empty while [number] (`SubscriptionInfo.getNumber`)
     * is present — so [number] is always consulted first and this
     * is only a fallback. Never logged; same sensitivity as [number].
     */
    val numberFallback: String? = null,
)

/**
 * Discovers the device's SIM subscriptions and extracts a call's subscription from broadcasts.
 *
 * Everything here degrades to empty/unknown rather than throwing: telephony lookups need
 * `READ_PHONE_STATE` (requested at onboarding), and the line number additionally needs the phone
 * number permission on newer Android — a SIM without a readable number is still selectable, it
 * just shows without one.
 */
object SimSubscriptions {

    /** Persisted value meaning "record calls on every SIM" — the pre-feature behaviour. */
    const val ALL_SUBSCRIPTIONS = SubscriptionManager.INVALID_SUBSCRIPTION_ID

    /**
     * The extra the phone-state broadcast carries for which subscription changed state. This is
     * `PhoneConstants.SUBSCRIPTION_KEY` ("subscription") in AOSP's TelephonyRegistry, which is
     * hidden API, hence the literal. Present on both halves of the double broadcast on AOSP —
     * but OMITTED on some OEM ROMs (observed in the field: calls arriving with no extra at
     * all), and never present on the post-boot live-listener path. A null return therefore
     * means "unknown", never "SIM 1". See [SimCallStateTracker] for the listener fallback.
     */
    private const val EXTRA_SUBSCRIPTION = "subscription"

    /** The call's subscription id from a phone-state intent, or null when the ROM did not say. */
    fun extractFromIntent(intent: Intent): Int? {
        if (!intent.hasExtra(EXTRA_SUBSCRIPTION)) return null
        val subId = intent.getIntExtra(EXTRA_SUBSCRIPTION, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        return subId.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
    }

    /**
     * The single subscription that is ringing or off-hook, or null when zero or several are.
     * Pure — unit tested. Comparison is on telephony states, never on slot order.
     *
     * This is the decision primitive for [SimCallStateTracker]'s listener states. It must NOT
     * be fed [TelephonyManager.getCallState] snapshots: that API reports the device-wide
     * state and ignores subscription pinning, so on dual-SIM every subscription looks active
     * and this correctly — but uselessly — returns null. Live per-subscription callbacks are
     * the only reliable input.
     */
    fun pickSingleActiveCall(subscriptionStates: Map<Int, Int>): Int? {
        val active = subscriptionStates
            .filterValues {
                it == TelephonyManager.CALL_STATE_OFFHOOK ||
                    it == TelephonyManager.CALL_STATE_RINGING
            }
            .keys
        return active.singleOrNull()
    }

    /**
     * Reconciles the broadcast subscription value with the radio-stack answer into one call
     * subscription id — or null when they cannot be safely reconciled.
     *
     * Why this exists: the `"subscription"` extra is documented AOSP behavior, but some OEM
     * ROMs put a *slot index* (or phone id) in it instead of a subscription id. Trusting such
     * a value blindly blocks every call (it never equals the selected subscription id) — the
     * exact failure observed in the field. So a broadcast value is only usable when the OS
     * itself confirms its meaning:
     * - value ∈ active subscription ids → a subscription id (AOSP meaning wins, even if the
     *   number also looks like a slot — the documented semantic takes precedence);
     * - value ∉ subscription ids but ∈ active slot indexes → the slot's subscription, via the
     *   OS's own active list (a real mapping, never an assumed `slot+1 == subId`);
     * - otherwise (garbage, -1, stale id of a removed SIM) → discarded.
     *
     * The surviving broadcast candidate and [resolved] (the listener-derived single active
     * subscription, already a subscription id by construction) must then AGREE: one distinct id wins; two different ids mean conflicting
     * evidence (e.g. a second call starting mid-decision) and yield unknown rather than a
     * guess. Pure — unit tested.
     */
    fun interpretCallSubscription(
        broadcast: Int?,
        activeSubs: List<SimInfo>,
        resolved: Int?,
    ): Int? {
        val subIds = activeSubs.map { it.subscriptionId }.toSet()
        val candidates = mutableSetOf<Int>()
        if (broadcast != null && broadcast != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            when {
                broadcast in subIds -> candidates.add(broadcast)
                else -> activeSubs.firstOrNull { it.slotIndex == broadcast }?.let {
                    candidates.add(it.subscriptionId)
                }
            }
        }
        if (resolved != null && resolved in subIds) candidates.add(resolved)
        return candidates.singleOrNull()
    }

    /** The currently usable subscriptions, in slot order. Empty when permission is missing or there are no SIMs. */
    fun list(context: Context): List<SimInfo> {
        val manager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            ?: return emptyList()
        val infos: List<SubscriptionInfo> =
            runCatching { manager.activeSubscriptionInfoList }.getOrNull().orEmpty()
        return infos
            .filter { it.subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
            .sortedBy { it.simSlotIndex }
            .map { info ->
                SimInfo(
                    subscriptionId = info.subscriptionId,
                    slotIndex = info.simSlotIndex,
                    carrierName = runCatching { info.carrierName }.getOrNull(),
                    // Needs READ_PHONE_NUMBERS/READ_SMS on recent Android; a SIM without a
                    // readable number is still listed, just numberless.
                    number = runCatching { info.number }.getOrNull()?.takeIf { !it.isNullOrBlank() },
                    // Fallback for devices where SubscriptionInfo.getNumber is
                    // empty but the manager API answers (and vice versa —
                    // verified Vivo I2304 where the manager API returns empty
                    // for SIM 1 while SubscriptionInfo.getNumber works, so the
                    // primary [number] always wins). Absent on API < 33
                    // (NoSuchMethodError is caught) — primary still applies.
                    numberFallback = runCatching { manager.getPhoneNumber(info.subscriptionId) }
                        .getOrNull()?.takeIf { !it.isNullOrBlank() },
                )
            }
    }

    /** Just the ids, for the record/don't-record gate's stale-selection check. */
    fun activeSubscriptionIds(context: Context): Set<Int> =
        list(context).map { it.subscriptionId }.toSet()
}
