/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.voip

/**
 * Which WhatsApp app CallVault may record — exactly one effective choice.
 *
 * This replaces the two independent WhatsApp / WhatsApp Business toggles, which could claim
 * both apps at once. There is deliberately no phone-number dimension here: participant-number
 * filtering is unavailable ([WhatsappCallPolicy.numberFilterAvailable]), so the allowlist is
 * app-level only — package identity from [CallTypeResolver], never numbers or names.
 */
enum class WhatsappRecordingSource(val wireValue: String) {
    NONE("none"),
    WHATSAPP("whatsapp"),
    WHATSAPP_BUSINESS("whatsapp_business"),
    ;

    companion object {
        /** Wire values accepted from WhatsApp Max (`/api/auth/me`). Unknown → null (keep last). */
        fun fromWire(value: String?): WhatsappRecordingSource? =
            entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() }

        /** Whether this package is WhatsApp-family at all (either app). */
        fun isWhatsappFamily(packageName: String?): Boolean {
            val normalized = packageName?.trim()?.lowercase().orEmpty()
            return normalized == WhatsappCallPolicy.PACKAGE_WHATSAPP ||
                normalized == WhatsappCallPolicy.PACKAGE_WHATSAPP_BUSINESS
        }

        /**
         * Whether [packageName] may record under [source]. Returns null for packages that are
         * not WhatsApp-family at all — those stay governed by the generic [VoipAppPolicy]
         * exclusion set, never by this setting.
         */
        fun allowsPackage(source: WhatsappRecordingSource, packageName: String?): Boolean? {
            val normalized = packageName?.trim()?.lowercase().orEmpty()
            if (!isWhatsappFamily(normalized)) return null
            val isWhatsapp = normalized == WhatsappCallPolicy.PACKAGE_WHATSAPP
            return when (source) {
                NONE -> false
                WHATSAPP -> isWhatsapp
                WHATSAPP_BUSINESS -> !isWhatsapp
            }
        }

        /**
         * The full effective decision, including the global VoIP master switch. Pure so the
         * matrix is unit-testable: VoIP off silences everything regardless of source.
         */
        fun effectiveAllows(
            voipEnabled: Boolean,
            source: WhatsappRecordingSource,
            packageName: String?,
        ): Boolean? {
            if (!voipEnabled) return false
            return allowsPackage(source, packageName)
        }

        /**
         * One-time migration from the old independent toggles (stored as the shared
         * `voip_excluded_packages` set). Both previously ON is ambiguous — never silently pick
         * one — so it maps to NONE with [migrationRequired] true, and the UI must ask.
         */
        fun migrateFromExcluded(excluded: Set<String>): Pair<WhatsappRecordingSource, Boolean> {
            val whatsappOff = WhatsappCallPolicy.PACKAGE_WHATSAPP in excluded
            val businessOff = WhatsappCallPolicy.PACKAGE_WHATSAPP_BUSINESS in excluded
            return when {
                whatsappOff && businessOff -> WhatsappRecordingSource.NONE to false
                !whatsappOff && businessOff -> WhatsappRecordingSource.WHATSAPP to false
                whatsappOff && !businessOff -> WhatsappRecordingSource.WHATSAPP_BUSINESS to false
                else -> WhatsappRecordingSource.NONE to true
            }
        }

        /**
         * Applies a server value to the last-known source. Unknown/absent server values keep
         * the local value (offline or stale server must never silently flip recording).
         */
        fun resolveSynced(serverValue: String?, lastKnown: WhatsappRecordingSource): WhatsappRecordingSource =
            fromWire(serverValue) ?: lastKnown
    }
}
