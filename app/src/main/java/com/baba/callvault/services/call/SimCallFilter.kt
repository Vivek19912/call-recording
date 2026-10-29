/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

/**
 * Decides whether a carrier call may enter the recording pipeline, given the SIM it is on and
 * the SIM the user picked in "Record calls from".
 *
 * Pure logic, no Android calls, so it is unit-testable. Comparison is on subscription ids, never
 * on "SIM 1" / "SIM 2" slot labels, which can move between boots.
 *
 * Fail-CLOSED on unknown for an explicit selection: when the user picked SIM 1 or SIM 2 and the
 * call's subscription cannot be determined (OEM omitted the broadcast extra and the radio-stack
 * fallback found nothing), the call does NOT record. Silently recording a call the user did not
 * pick is exactly the failure this allowlist exists to prevent — and it is what the previous
 * fail-open design did in the field (a SIM 1 call recorded while SIM 2 was selected, because
 * the subscription arrived as unknown). The one exception is All SIMs: no selection means
 * record everything, exactly the pre-feature behaviour, unknown or not.
 *
 * The remaining fail-open is a stale selection: when the picked SIM is no longer on the
 * device, the allowlist has no target — blocking everything until the user intervenes would
 * silently lose recordings (e.g. after a SIM swap while travelling). So a stale selection
 * records, whatever the call reports, and the picker heals the stored value back to All SIMs.
 */
object SimCallFilter {

    fun shouldRecord(
        callSubscriptionId: Int?,
        selectedSubscriptionId: Int,
        selectedActive: Boolean,
    ): Boolean {
        // No selection: today's behaviour — record on every SIM, known or not.
        if (selectedSubscriptionId == SimSubscriptions.ALL_SUBSCRIPTIONS) return true
        // Stale selection (picked SIM gone from the device): the allowlist has no target, so
        // record rather than silently losing calls until the user re-opens Settings.
        if (!selectedActive) return true
        // Explicit selection, unknowable call SIM: fail CLOSED. Cannot prove the call is on the
        // picked SIM, so it must not enter the recording pipeline.
        if (callSubscriptionId == null ||
            callSubscriptionId == SimSubscriptions.ALL_SUBSCRIPTIONS
        ) {
            return false
        }
        if (callSubscriptionId == selectedSubscriptionId) return true
        // Known mismatch against a present selection: do not record.
        return false
    }
}
