/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import com.google.i18n.phonenumbers.PhoneNumberUtil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 2B number canonicalization: what CallVault sends as `phone_number` must be the canonical
 * E.164 form whenever the device region establishes one, so WACRM's exact digit match can hit.
 *
 * Driven with a REAL [PhoneNumberUtil] (classpath metadata, no Android runtime) through the pure
 * [WacrmPhoneNumbers.resolveUploadNumber] core — these tests pin libphonenumber's actual behavior
 * for our cases, not just our branching. Region "IN" stands in for the device region (prod uses
 * the device country via PhoneNumberManager; only the region source differs, never the logic).
 */
class WacrmPhoneNumbersTest {

    private val util = PhoneNumberUtil.getInstance()

    private fun resolve(raw: String?, region: String = "IN"): String? = runBlocking {
        WacrmPhoneNumbers.resolveUploadNumber(
            raw = raw,
            parse = { s ->
                runCatching { util.parse(s, region) }.getOrNull()
            },
            isValid = { util.isValidNumber(it) },
            formatE164 = { util.format(it, PhoneNumberUtil.PhoneNumberFormat.E164) },
        )
    }

    @Test
    fun `indian 10-digit local number becomes plus-91 E164`() {
        // The exact E2E failure: CallLog reported 8953065369, WACRM holds 918953065369.
        assertEquals("+918953065369", resolve("8953065369"))
    }

    @Test
    fun `already E164 numbers pass through unchanged`() {
        assertEquals("+91953065369", resolve("+91953065369"))
        assertEquals("+918953065369", resolve("+918953065369"))
    }

    @Test
    fun `international numbers keep their own country code`() {
        // No blind +91 prefix: a US number on an Indian device stays American.
        assertEquals("+12025550173", resolve("+12025550173"))
        assertEquals("+12025550173", resolve("+12025550173", region = "US"))
    }

    @Test
    fun `null and blank numbers stay null`() {
        assertNull(resolve(null))
        assertNull(resolve(""))
        assertNull(resolve("   "))
    }

    @Test
    fun `malformed numbers fall back to the raw string`() {
        // Existing safe behavior: evidence is never dropped, matching just won't hit.
        assertEquals("abc", resolve("abc"))
        assertEquals("12345", resolve("12345"))
    }

    @Test
    fun `surrounding whitespace is trimmed before parsing`() {
        assertEquals("+918953065369", resolve("  8953065369  "))
    }
}
