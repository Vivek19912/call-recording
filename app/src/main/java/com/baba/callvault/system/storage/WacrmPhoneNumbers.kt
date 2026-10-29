/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import com.baba.callvault.utils.PhoneNumberManager
import com.google.i18n.phonenumbers.Phonenumber

/**
 * Canonicalizes a finalized carrier number for the WACRM upload (Phase 2B contact matching).
 *
 * Root cause it exists for: the number CallVault finalizes comes from telephony/CallLog in
 * whatever form the device reports — on Indian devices that is the 10-digit local form
 * (`8953065369`), while WACRM matches exactly against E.164-digit contacts (`918953065369`).
 * Sending the raw local form therefore never matches, even though both sides hold the same
 * subscriber. The prior E.164 formatting only covered the CallLog-*fallback* path; the common
 * path (number already known at record start) passed the raw string straight to the uploader.
 *
 * Rules:
 * - null/blank → null (omitted from the upload, Phase 1 behavior).
 * - Parse with the device region; if valid per libphonenumber, send E.164
 *   (`8953065369` on an Indian device → `+918953065369`). Region-contextual, never a blind
 *   `+91` prefix: an international number keeps its own country code.
 * - Unparseable or invalid → send the trimmed raw string (today's behavior — the server still
 *   digit-matches it, and dropping evidence would be worse than sending it uncanonicalized).
 *
 * VoIP never reaches here (it passes null). WACRM matching itself is untouched.
 */
object WacrmPhoneNumbers {

    /**
     * Canonicalizes [raw] using the device region. Suspend: libphonenumber parsing is
     * offloaded by [PhoneNumberManager], matching its own threading contract.
     */
    suspend fun canonicalize(context: Context, raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val manager = PhoneNumberManager.getInstance(context)
        return resolveUploadNumber(
            raw = trimmed,
            parse = { manager.parsePhoneNumber(it) },
            isValid = { manager.isValidNumber(it) },
            formatE164 = { manager.formatToE164(it) },
        )
    }

    /**
     * Pure core of [canonicalize]: given a raw number and libphonenumber operations, picks the
     * exact string to upload (null when there is nothing to send). Pure so JVM unit tests can
     * drive it with a real [com.google.i18n.phonenumbers.PhoneNumberUtil] and no Android runtime.
     */
    suspend fun resolveUploadNumber(
        raw: String?,
        parse: suspend (String) -> Phonenumber.PhoneNumber?,
        isValid: suspend (Phonenumber.PhoneNumber) -> Boolean,
        formatE164: suspend (Phonenumber.PhoneNumber) -> String?,
    ): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val parsed = runCatching { parse(trimmed) }.getOrNull() ?: return trimmed
        if (!runCatching { isValid(parsed) }.getOrDefault(false)) return trimmed
        return runCatching { formatE164(parsed) }.getOrNull() ?: trimmed
    }
}
