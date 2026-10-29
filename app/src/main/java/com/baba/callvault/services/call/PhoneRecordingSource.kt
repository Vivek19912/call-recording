/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

/**
 * Which SIM the WhatsApp Max owner allows this device to record —
 * exactly one effective choice. This is OWNER policy synced from
 * the server, never a local user pick: the Settings UI shows it
 * read-only and there is no setter reachable from any toggle.
 *
 * The values are logical slots, deliberately NOT Android
 * subscription ids, broadcast values, or runtime slot state:
 * [resolveSelectedSubscription] maps the logical slot to the
 * device's CURRENT active subscription at gate time, so a SIM
 * swap or reboot cannot silently retarget the policy.
 */
enum class PhoneRecordingSource(val wireValue: String, val slotIndex: Int?) {
    NONE("none", null),
    SIM1("sim1", 0),
    SIM2("sim2", 1),
    ;

    companion object {
        /** Wire values accepted from WhatsApp Max (`/api/auth/me`). Unknown → null (keep last). */
        fun fromWire(value: String?): PhoneRecordingSource? =
            entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() }

        /**
         * The currently active subscription the server policy points
         * at — or null when the policy is NONE, the value is unknown,
         * or the configured slot has no SIM right now (absent SIM
         * fails closed: never fall back to the other SIM, never
         * auto-change the stored policy).
         *
         * Pure over the OS-provided [activeSubs] — unit tested. No
         * slot arithmetic (`slot+1 == subId`), no number inference.
         */
        fun resolveSelectedSubscription(
            source: PhoneRecordingSource?,
            activeSubs: List<SimInfo>,
        ): Int? {
            val slot = source?.slotIndex ?: return null
            return activeSubs.firstOrNull { it.slotIndex == slot }?.subscriptionId
        }
    }
}
