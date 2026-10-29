/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

/**
 * Maps an Android application identity to a [CallType], or null when it cannot be told.
 *
 * The ONLY authoritative input is the framework-resolved package name of the uid that owns the
 * call's audio track ([VoipRecordingCoordinator.resolveCallPackage] via
 * `getPackagesForUid`) — the same source the filename badge and the per-app recording policy
 * already use. Never the filename, never a contact name, never a phone number.
 *
 * Package identifiers are Meta's published, stable application IDs:
 * - `com.whatsapp` → WhatsApp ([CallType.WHATSAPP]).
 * - `com.whatsapp.w4b` → WhatsApp Business ([CallType.WHATSAPP_BUSINESS]).
 *
 * Everything else — Telegram, Signal, an unresolvable uid, a blank — is null. In particular a
 * missing Business distinction is reported as null rather than guessed: only these two packages
 * are claimed, because only these two are verifiable.
 */
object CallTypeResolver {

    /** Meta's WhatsApp (Play Store application ID `com.whatsapp`). */
    const val PACKAGE_WHATSAPP = "com.whatsapp"

    /** Meta's WhatsApp Business (Play Store application ID `com.whatsapp.w4b`). */
    const val PACKAGE_WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    /**
     * Resolves [packageName] (an Android application ID, e.g. from the audio-owning uid) to a
     * [CallType], or null when the app is not a classified one. Case-insensitive and
     * whitespace-tolerant on input; null/blank input is null, never an exception.
     */
    fun fromPackage(packageName: String?): CallType? {
        return when (packageName?.trim()?.lowercase()) {
            PACKAGE_WHATSAPP -> CallType.WHATSAPP
            PACKAGE_WHATSAPP_BUSINESS -> CallType.WHATSAPP_BUSINESS
            else -> null
        }
    }
}
