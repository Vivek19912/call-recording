/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.voip

import com.baba.callvault.data.recordings.CallType
import com.baba.callvault.data.recordings.CallTypeResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WhatsApp / WhatsApp Business policy.
 *
 * The honest core of this suite: participant-number filtering is NOT available
 * ([WhatsappCallPolicy.numberFilterAvailable] is false), so there is deliberately no test here
 * in which a number decides a recording — writing one would claim a detection that does not
 * exist. What is tested: the app classification the toggles rely on, and the normalize /
 * validate plumbing a future gate would use.
 *
 * Pure JVM: no Android APIs involved.
 */
class WhatsappCallPolicyTest {

    // --- App classification (the reliable half: package -> CallType) ---

    @Test
    fun `com whatsapp remains WhatsApp`() {
        assertEquals(CallType.WHATSAPP, CallTypeResolver.fromPackage(WhatsappCallPolicy.PACKAGE_WHATSAPP))
        assertEquals(CallType.WHATSAPP, CallTypeResolver.fromPackage("com.whatsapp"))
    }

    @Test
    fun `com whatsapp w4b remains WhatsApp Business`() {
        assertEquals(
            CallType.WHATSAPP_BUSINESS,
            CallTypeResolver.fromPackage(WhatsappCallPolicy.PACKAGE_WHATSAPP_BUSINESS),
        )
        assertEquals(CallType.WHATSAPP_BUSINESS, CallTypeResolver.fromPackage("com.whatsapp.w4b"))
    }

    @Test
    fun `unsupported VoIP apps remain unsupported`() {
        assertNull(CallTypeResolver.fromPackage("org.telegram.messenger"))
        assertNull(CallTypeResolver.fromPackage("org.thoughtcrime.securesms"))
        assertNull(CallTypeResolver.fromPackage(null))
        assertNull(CallTypeResolver.fromPackage(""))
        assertNull(CallTypeResolver.fromPackage("com.whatsapp.fake"))
    }

    @Test
    fun `per-app toggle maps onto the shared exclusion set`() {
        val excluded = emptySet<String>()
        // Toggling WhatsApp off excludes exactly its package; toggling back on restores it.
        val off = VoipAppPolicy.withRecording(excluded, WhatsappCallPolicy.PACKAGE_WHATSAPP, false)
        assertFalse(VoipAppPolicy.shouldRecord(WhatsappCallPolicy.PACKAGE_WHATSAPP, off))
        // WhatsApp Business is unaffected by the WhatsApp row and vice versa.
        assertTrue(VoipAppPolicy.shouldRecord(WhatsappCallPolicy.PACKAGE_WHATSAPP_BUSINESS, off))
        val on = VoipAppPolicy.withRecording(off, WhatsappCallPolicy.PACKAGE_WHATSAPP, true)
        assertTrue(VoipAppPolicy.shouldRecord(WhatsappCallPolicy.PACKAGE_WHATSAPP, on))
    }

    // --- Number plumbing (stored/validated, never a record-time gate) ---

    @Test
    fun `number filter is explicitly unavailable`() {
        assertFalse(WhatsappCallPolicy.numberFilterAvailable)
    }

    @Test
    fun `number normalization is consistent`() {
        assertEquals("+919876543210", WhatsappCallPolicy.normalizeNumber("+91 98765 43210"))
        assertEquals("+919876543210", WhatsappCallPolicy.normalizeNumber("+919876543210"))
        assertEquals("+919876543210", WhatsappCallPolicy.normalizeNumber("+91-98765-43210"))
        assertEquals("9876543210", WhatsappCallPolicy.normalizeNumber("98765 43210"))
    }

    @Test
    fun `valid numbers are accepted`() {
        assertTrue(WhatsappCallPolicy.isValidNumber("+919876543210"))
        assertTrue(WhatsappCallPolicy.isValidNumber("+12025550173"))
        assertTrue(WhatsappCallPolicy.isValidNumber("9876543210"))
    }

    @Test
    fun `invalid numbers are rejected`() {
        // Empty is never valid when recording is enabled.
        assertFalse(WhatsappCallPolicy.isValidNumber(""))
        assertFalse(WhatsappCallPolicy.isValidNumber("+"))
        // Too short, too long, non-digits.
        assertFalse(WhatsappCallPolicy.isValidNumber("+91123"))
        assertFalse(WhatsappCallPolicy.isValidNumber("+91123456789012345"))
        assertFalse(WhatsappCallPolicy.isValidNumber("+91ABCDEF3210"))
    }
}
