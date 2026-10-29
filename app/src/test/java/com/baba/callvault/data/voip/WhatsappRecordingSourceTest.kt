/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.voip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mutually exclusive WhatsApp recording source. Exactly one app
 * (or neither) records — never both, never by number, never by name.
 *
 * Pure JVM: no Android APIs involved.
 */
class WhatsappRecordingSourceTest {

    private companion object {
        const val WA = "com.whatsapp"
        const val W4B = "com.whatsapp.w4b"
        const val SIGNAL = "org.thoughtcrime.securesms"
    }

    // --- Selection ---

    @Test
    fun `NONE records neither app`() {
        assertEquals(false, WhatsappRecordingSource.allowsPackage(WhatsappRecordingSource.NONE, WA))
        assertEquals(false, WhatsappRecordingSource.allowsPackage(WhatsappRecordingSource.NONE, W4B))
    }

    @Test
    fun `WHATSAPP records WhatsApp only`() {
        assertEquals(
            true,
            WhatsappRecordingSource.allowsPackage(WhatsappRecordingSource.WHATSAPP, WA)
        )
        assertEquals(
            false,
            WhatsappRecordingSource.allowsPackage(WhatsappRecordingSource.WHATSAPP, W4B)
        )
    }

    @Test
    fun `WHATSAPP_BUSINESS records Business only`() {
        assertEquals(
            false,
            WhatsappRecordingSource.allowsPackage(WhatsappRecordingSource.WHATSAPP_BUSINESS, WA)
        )
        assertEquals(
            true,
            WhatsappRecordingSource.allowsPackage(WhatsappRecordingSource.WHATSAPP_BUSINESS, W4B)
        )
    }

    @Test
    fun `other apps defer to the generic exclusion set`() {
        assertNull(
            WhatsappRecordingSource.allowsPackage(WhatsappRecordingSource.WHATSAPP, SIGNAL)
        )
        assertNull(WhatsappRecordingSource.allowsPackage(WhatsappRecordingSource.NONE, null))
        assertNull(WhatsappRecordingSource.allowsPackage(WhatsappRecordingSource.WHATSAPP, ""))
    }

    @Test
    fun `matching is case- and whitespace-tolerant but never fuzzy`() {
        assertEquals(
            true,
            WhatsappRecordingSource.allowsPackage(WhatsappRecordingSource.WHATSAPP, "  COM.WHATSAPP ")
        )
        assertNull(
            WhatsappRecordingSource.allowsPackage(WhatsappRecordingSource.WHATSAPP, "com.whatsapp.fake")
        )
    }

    // --- Global VoIP master switch ---

    @Test
    fun `VoIP off silences everything regardless of source`() {
        assertEquals(
            false,
            WhatsappRecordingSource.effectiveAllows(false, WhatsappRecordingSource.WHATSAPP, WA)
        )
        assertEquals(
            false,
            WhatsappRecordingSource.effectiveAllows(
                false, WhatsappRecordingSource.WHATSAPP_BUSINESS, W4B
            )
        )
    }

    @Test
    fun `VoIP on applies the source`() {
        assertEquals(
            true,
            WhatsappRecordingSource.effectiveAllows(true, WhatsappRecordingSource.WHATSAPP, WA)
        )
        assertEquals(
            false,
            WhatsappRecordingSource.effectiveAllows(true, WhatsappRecordingSource.WHATSAPP, W4B)
        )
        assertNull(
            WhatsappRecordingSource.effectiveAllows(true, WhatsappRecordingSource.WHATSAPP, SIGNAL)
        )
    }

    // --- Migration from the old independent toggles ---

    @Test
    fun `OFF plus OFF maps to NONE without a prompt`() {
        assertEquals(
            WhatsappRecordingSource.NONE to false,
            WhatsappRecordingSource.migrateFromExcluded(setOf(WA, W4B)),
        )
    }

    @Test
    fun `ON plus OFF maps to WHATSAPP without a prompt`() {
        assertEquals(
            WhatsappRecordingSource.WHATSAPP to false,
            WhatsappRecordingSource.migrateFromExcluded(setOf(W4B)),
        )
    }

    @Test
    fun `OFF plus ON maps to BUSINESS without a prompt`() {
        assertEquals(
            WhatsappRecordingSource.WHATSAPP_BUSINESS to false,
            WhatsappRecordingSource.migrateFromExcluded(setOf(WA)),
        )
    }

    @Test
    fun `ON plus ON maps to NONE with a required prompt, never a silent pick`() {
        val (source, required) = WhatsappRecordingSource.migrateFromExcluded(emptySet())
        assertEquals(WhatsappRecordingSource.NONE, source)
        assertTrue(required)
    }

    @Test
    fun `other apps survive migration untouched`() {
        val (source, required) = WhatsappRecordingSource.migrateFromExcluded(setOf(SIGNAL))
        assertEquals(WhatsappRecordingSource.NONE, source)
        // SIGNAL excluded + both whatsapp ON: still ambiguous, still prompts.
        assertTrue(required)
    }

    // --- Server sync mapping ---

    @Test
    fun `server values map to local values`() {
        assertEquals(
            WhatsappRecordingSource.WHATSAPP,
            WhatsappRecordingSource.fromWire("whatsapp")
        )
        assertEquals(
            WhatsappRecordingSource.WHATSAPP_BUSINESS,
            WhatsappRecordingSource.fromWire("whatsapp_business")
        )
        assertEquals(WhatsappRecordingSource.NONE, WhatsappRecordingSource.fromWire("none"))
        assertEquals(WhatsappRecordingSource.NONE, WhatsappRecordingSource.fromWire("  NONE "))
    }

    @Test
    fun `unknown or absent server values keep the last known source`() {
        assertEquals(
            WhatsappRecordingSource.WHATSAPP,
            WhatsappRecordingSource.resolveSynced("both", WhatsappRecordingSource.WHATSAPP)
        )
        assertEquals(
            WhatsappRecordingSource.WHATSAPP_BUSINESS,
            WhatsappRecordingSource.resolveSynced(null, WhatsappRecordingSource.WHATSAPP_BUSINESS)
        )
        assertEquals(
            WhatsappRecordingSource.NONE,
            WhatsappRecordingSource.resolveSynced("", WhatsappRecordingSource.NONE)
        )
    }

    @Test
    fun `an explicit server none overwrites a local app choice`() {
        assertEquals(
            WhatsappRecordingSource.NONE,
            WhatsappRecordingSource.resolveSynced("none", WhatsappRecordingSource.WHATSAPP)
        )
    }

    // --- Isolation ---

    @Test
    fun `family detection covers exactly the two packages`() {
        assertTrue(WhatsappRecordingSource.isWhatsappFamily(WA))
        assertTrue(WhatsappRecordingSource.isWhatsappFamily(W4B))
        assertFalse(WhatsappRecordingSource.isWhatsappFamily(SIGNAL))
        assertFalse(WhatsappRecordingSource.isWhatsappFamily(null))
    }
}
