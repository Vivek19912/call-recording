/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import com.baba.callvault.data.WacrmSession
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Storage read outcomes ([WacrmSession.read]).
 *
 * Pure JVM via the in-memory [WacrmSession.openBackingStoreForTest]
 * seam: an empty store must read as [WacrmSession.SessionRead.SignedOut]
 * (terminal for workers), while an unopenable/throwing store must read
 * as [WacrmSession.SessionRead.Unavailable] (transient — retry, never
 * FAILED). The Context is a mock — the seam never touches it.
 */
class WacrmSessionReadTest {

    private class MemStore(
        val strings: MutableMap<String, String?> = mutableMapOf(),
        var expiresAtMs: Long = 0L,
        var failWrites: Boolean = false,
    ) : WacrmSession.BackingStore {
        override fun getString(key: String): String? = strings[key]
        override fun getLong(key: String, def: Long): Long = expiresAtMs
        override fun putAll(strings: Map<String, String?>, expiresAtMs: Long): Boolean {
            if (failWrites) return false
            this.strings.putAll(strings)
            this.expiresAtMs = expiresAtMs
            return true
        }

        override fun clearAll(): Boolean {
            strings.clear()
            return true
        }
    }

    private val context: Context = mockk(relaxed = true)
    private lateinit var store: MemStore

    @Before
    fun setUp() {
        store = MemStore()
        WacrmSession.openBackingStoreForTest = { store }
    }

    @After
    fun tearDown() {
        WacrmSession.openBackingStoreForTest = null
    }

    private fun seedFull() {
        assertTrue(
            WacrmSession.save(
                context, "acc-1", "ref-1", 12345L,
                "u-1", "sagar@example.com", "Sagar", "a-1", "Acme", "agent",
            )
        )
    }

    @Test
    fun `empty store reads as signed out`() {
        val read = WacrmSession.read(context)
        check(read is WacrmSession.SessionRead.SignedOut)
        assertNull(WacrmSession.get(context))
        assertFalse(WacrmSession.isConnected(context))
    }

    @Test
    fun `partial store reads as signed out`() {
        store.strings["access_token"] = "acc-1"
        val read = WacrmSession.read(context)
        check(read is WacrmSession.SessionRead.SignedOut)
        assertNull(WacrmSession.get(context))
    }

    @Test
    fun `unopenable store reads as unavailable`() {
        WacrmSession.openBackingStoreForTest = { null }
        val read = WacrmSession.read(context)
        check(read is WacrmSession.SessionRead.Unavailable)
        assertNull(WacrmSession.get(context))
        assertFalse(WacrmSession.isConnected(context))
    }

    @Test
    fun `throwing store reads as unavailable`() {
        WacrmSession.openBackingStoreForTest = { throw IllegalStateException("keystore locked") }
        val read = WacrmSession.read(context)
        check(read is WacrmSession.SessionRead.Unavailable)
        assertNull(WacrmSession.get(context))
    }

    @Test
    fun `full store reads as available with all fields`() {
        seedFull()
        val read = WacrmSession.read(context)
        check(read is WacrmSession.SessionRead.Available)
        assertEquals("acc-1", read.session.accessToken)
        assertEquals("ref-1", read.session.refreshToken)
        assertEquals(12345L, read.session.expiresAtMs)
        assertEquals("u-1", read.session.userId)
        assertEquals("sagar@example.com", read.session.userEmail)
        assertEquals("Sagar", read.session.userName)
        assertEquals("a-1", read.session.accountId)
        assertEquals("Acme", read.session.accountName)
        assertEquals("agent", read.session.role)
        assertTrue(WacrmSession.isConnected(context))
    }

    @Test
    fun `save reports false when the store is unavailable`() {
        WacrmSession.openBackingStoreForTest = { null }
        assertFalse(
            WacrmSession.save(
                context, "acc-1", "ref-1", 0L,
                "u-1", null, null, "a-1", null, null,
            )
        )
    }

    @Test
    fun `save reports false when the write is not committed`() {
        store.failWrites = true
        assertFalse(
            WacrmSession.save(
                context, "acc-1", "ref-1", 0L,
                "u-1", null, null, "a-1", null, null,
            )
        )
        check(WacrmSession.read(context) is WacrmSession.SessionRead.SignedOut)
    }

    @Test
    fun `clear wipes a stored session`() {
        seedFull()
        assertTrue(WacrmSession.isConnected(context))
        WacrmSession.clear(context)
        check(WacrmSession.read(context) is WacrmSession.SessionRead.SignedOut)
    }
}
