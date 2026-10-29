/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.voip

import com.baba.callvault.utils.PhoneNumberManager.Companion.normalisePhoneNumber

/**
 * WhatsApp / WhatsApp Business recording policy.
 *
 * Two halves with very different reliability, kept in one place so the asymmetry is visible:
 *
 * 1. **Per-app on/off — reliable.** The audio owner resolves to a package
 *    ([VoipRecordingCoordinator.resolveCallPackage][com.baba.callvault.services.recording.VoipRecordingCoordinator]
 *    via the daemon UID + `getPackagesForUid`), and
 *    [CallTypeResolver][com.baba.callvault.data.recordings.CallTypeResolver] classifies
 *    `com.whatsapp` → WhatsApp and `com.whatsapp.w4b` → WhatsApp Business. The toggles drive the
 *    existing [VoipAppPolicy] exclusion set — no new gate, same pre-file-creation check.
 *
 * 2. **Participant-number filtering — NOT available.** A VoIP call leaves no call-log entry, the
 *    app's own history sits in a private directory, Telecom holds nothing, and the ongoing-call
 *    notification carries only a display *name* (see `VoipCallerName`). There is no remote E.164
 *    anywhere in the flow, so [numberFilterAvailable] is false and the number fields in Settings
 *    are shown disabled with that explanation rather than pretending to filter. The normalize /
 *    validate helpers below are the plumbing a future gate would use once a real number source
 *    exists; they are unit-tested, but nothing consults them at record time today.
 */
object WhatsappCallPolicy {

    const val PACKAGE_WHATSAPP = "com.whatsapp"
    const val PACKAGE_WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    /**
     * Whether a participant-number filter can be enforced. False: Android exposes no remote
     * WhatsApp number to CallVault (documented above). Do NOT gate recording on a number while
     * this is false — there is nothing truthful to compare against.
     */
    const val numberFilterAvailable = false

    /**
     * Normalizes a user-typed WhatsApp number the same way uploads are canonicalized: strip
     * separators, keep a leading '+'. "+91 98765 43210" and "+919876543210" store identically.
     */
    fun normalizeNumber(raw: String): String = normalisePhoneNumber(raw)

    /**
     * Whether [normalized] is acceptable as a configured WhatsApp number: 7–15 digits with an
     * optional leading '+' (E.164 range). Empty is never valid when recording is enabled.
     */
    fun isValidNumber(normalized: String): Boolean {
        val digits = normalized.removePrefix("+")
        if (normalized.startsWith("+") && normalized.length == 1) return false
        if (digits.any { !it.isDigit() }) return false
        return digits.length in 7..15
    }
}
