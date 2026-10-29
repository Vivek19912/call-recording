/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import android.content.Context
import com.baba.callvault.utils.PhoneNumberManager

/**
 * Maps the owner-configured phone number (WhatsApp Max
 * `phone_recording_number`, normalized digits) to the device's
 * CURRENT active subscription id.
 *
 * The configured number is the stable identity; the subscription
 * id is a runtime device value that changes when the SIM moves
 * slots — so the id is resolved fresh at gate time and never
 * persisted as authoritative.
 *
 * Number-source priority (hardware-verified on Vivo I2304, where
 * `SubscriptionManager.getPhoneNumber(subId)` returns EMPTY for
 * SIM 1 while `SubscriptionInfo.getNumber()` works):
 *
 *  1. PRIMARY: `SubscriptionInfo.getNumber()` ([SimInfo.number]).
 *  2. FALLBACK: `SubscriptionManager.getPhoneNumber(subId)`
 *     ([SimInfo.numberFallback]).
 *
 * NEVER use the fallback alone. When both are unavailable the
 * subscription is UNKNOWN and can never match.
 *
 * Fail-closed resolution:
 *  - configured null/blank/malformed → null (NONE).
 *  - exactly one matching active subscription → its id.
 *  - zero matches (SIM swapped out / number not on device) → null.
 *  - multiple matches (same normalized number on two SIMs) →
 *    null (ambiguous — never pick the first).
 *
 * No slot arithmetic, no subscription-id assumptions, no caller-id
 * or filename inference. Pure over inputs — unit tested.
 */
object PhoneNumberResolver {

    /** Digits-only canonical form for comparison (`+91 89530 65369` → `918953065369`). Null when unusable. */
    fun normalizeForMatch(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val digits = PhoneNumberManager.normalisePhoneNumber(raw).filter { it.isDigit() }
        if (digits.length < 7 || digits.length > 15) return null
        if (digits.first() == '0') return null
        return digits
    }

    /**
     * The active subscription currently owning [configuredNumber],
     * or null when there is no single safe match. See class docs.
     */
    fun resolveSubscriptionId(
        configuredNumber: String?,
        activeSubs: List<SimInfo>,
    ): Int? {
        val want = normalizeForMatch(configuredNumber) ?: return null
        val matches = activeSubs.filter { sub ->
            normalizeForMatch(sub.number) == want ||
                normalizeForMatch(sub.numberFallback) == want
        }
        return matches.singleOrNull()?.subscriptionId
    }

    /** Live entry point: active list from the OS, then [resolveSubscriptionId]. */
    fun resolveLive(context: Context, configuredNumber: String?): Int? =
        resolveSubscriptionId(configuredNumber, SimSubscriptions.list(context))

    /** Readable display (`918953065369` → `+91 89530 65369`). Display only. */
    fun formatForDisplay(normalizedDigits: String?): String {
        if (normalizedDigits.isNullOrBlank()) return ""
        return if (normalizedDigits.length == 12 && normalizedDigits.startsWith("91")) {
            "+91 ${normalizedDigits.substring(2, 7)} ${normalizedDigits.substring(7)}"
        } else {
            "+$normalizedDigits"
        }
    }
}
