/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 2B call-type classification: the wire values crossing to WACRM, and the single
 * package-based resolver. Everything not positively identified stays null — a wrong
 * classification is worse than an unclassified recording.
 */
class CallTypeTest {

    @Test
    fun `wire values match the WACRM contract exactly`() {
        assertEquals("phone", CallType.PHONE.wireValue)
        assertEquals("whatsapp", CallType.WHATSAPP.wireValue)
        assertEquals("whatsapp_business", CallType.WHATSAPP_BUSINESS.wireValue)
    }

    @Test
    fun `whatsapp package resolves to WHATSAPP`() {
        assertEquals(CallType.WHATSAPP, CallTypeResolver.fromPackage("com.whatsapp"))
    }

    @Test
    fun `whatsapp business package resolves to WHATSAPP_BUSINESS`() {
        assertEquals(CallType.WHATSAPP_BUSINESS, CallTypeResolver.fromPackage("com.whatsapp.w4b"))
    }

    @Test
    fun `unsupported apps never become whatsapp`() {
        assertNull(CallTypeResolver.fromPackage("org.telegram.messenger"))
        assertNull(CallTypeResolver.fromPackage("org.thoughtcrime.securesms"))
        assertNull(CallTypeResolver.fromPackage("com.google.android.dialer"))
    }

    @Test
    fun `missing identity is null, never an exception`() {
        assertNull(CallTypeResolver.fromPackage(null))
        assertNull(CallTypeResolver.fromPackage(""))
        assertNull(CallTypeResolver.fromPackage("   "))
    }

    @Test
    fun `matching is case-insensitive and trims`() {
        assertEquals(CallType.WHATSAPP, CallTypeResolver.fromPackage("  Com.WhatsApp "))
    }

    @Test
    fun `lookalike packages do not match`() {
        // Prefix/suffix games must not classify: only the exact published IDs count.
        assertNull(CallTypeResolver.fromPackage("com.whatsapp.fake"))
        assertNull(CallTypeResolver.fromPackage("mycom.whatsapp"))
        assertNull(CallTypeResolver.fromPackage("com.whatsapp.w4b.beta"))
    }
}
