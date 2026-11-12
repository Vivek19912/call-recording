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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Observable session snapshot ([WacrmSessionState]).
 *
 * Pure JVM via the in-memory [WacrmSession.openBackingStoreForTest]
 * seam (same pattern as [WacrmSessionReadTest]): the Context is a
 * mock — the seam never touches it.
 *
 * Contract under test:
 * - initial flow value is `Snapshot(false, null)`;
 * - `publish` replaces the snapshot and marks it checked;
 * - `refresh` maps `Available` → session, `SignedOut` →
 *   null+checked, `Unavailable` → keep the displayed session but
 *   set checked=true (a transient store error must not flash a
 *   logout over a previously shown connected session).
 */
class WacrmSessionStateTest {

    private class MemStore(
        val strings: MutableMap<String, String?> = mutableMapOf(),
        var expiresAtMs: Long = 0L,
    ) : WacrmSession.BackingStore {
        override fun getString(key: String): String? = strings[key]
        override fun getLong(key: String, def: Long): Long = expiresAtMs
        override fun putAll(strings: Map<String, String?>, expiresAtMs: Long): Boolean {
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

    /** Restores the pristine initial value so tests are order-independent. */
    private fun resetToInitial() {
        val field = WacrmSessionState::class.java.getDeclaredField("_flow")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(WacrmSessionState) as kotlinx.coroutines.flow.MutableStateFlow<WacrmSessionState.Snapshot>
        flow.value = WacrmSessionState.Snapshot(false, null)
    }

    @Before
    fun setUp() {
        store = MemStore()
        WacrmSession.openBackingStoreForTest = { store }
        resetToInitial()
    }

    @After
    fun tearDown() {
        WacrmSession.openBackingStoreForTest = null
        resetToInitial()
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
    fun `initial value is unchecked and empty`() {
        assertEquals(WacrmSessionState.Snapshot(false, null), WacrmSessionState.flow.value)
    }

    @Test
    fun `publish null marks checked with no session`() {
        WacrmSessionState.publish(null)
        val snapshot = WacrmSessionState.flow.value
        assertTrue(snapshot.checked)
        assertNull(snapshot.session)
    }

    @Test
    fun `publish session exposes it as checked`() {
        seedFull()
        val session = WacrmSession.get(context)
        check(session != null)
        WacrmSessionState.publish(session)
        val snapshot = WacrmSessionState.flow.value
        assertTrue(snapshot.checked)
        assertEquals("acc-1", snapshot.session?.accessToken)
        assertEquals("u-1", snapshot.session?.userId)
    }

    @Test
    fun `refresh maps available store to session`() {
        seedFull()
        WacrmSessionState.refresh(context)
        val snapshot = WacrmSessionState.flow.value
        assertTrue(snapshot.checked)
        check(snapshot.session != null)
        assertEquals("acc-1", snapshot.session.accessToken)
    }

    @Test
    fun `refresh maps signed out store to null and checked`() {
        seedFull()
        WacrmSessionState.refresh(context)
        check(WacrmSessionState.flow.value.session != null)
        WacrmSession.clear(context)
        WacrmSessionState.refresh(context)
        val snapshot = WacrmSessionState.flow.value
        assertTrue(snapshot.checked)
        assertNull(snapshot.session)
    }

    @Test
    fun `refresh on unavailable store preserves displayed session but marks checked`() {
        seedFull()
        WacrmSessionState.refresh(context)
        val before = WacrmSessionState.flow.value
        check(before.session != null)
        WacrmSession.openBackingStoreForTest = { null }
        WacrmSessionState.refresh(context)
        val after = WacrmSessionState.flow.value
        assertTrue(after.checked)
        assertEquals(before.session, after.session)
    }

    @Test
    fun `refresh never throws and keeps a well-formed snapshot on a throwing store`() {
        WacrmSession.openBackingStoreForTest = { throw IllegalStateException("keystore locked") }
        // Must not throw.
        WacrmSessionState.refresh(context)
        val snapshot = WacrmSessionState.flow.value
        assertTrue(snapshot.checked)
        assertNull(snapshot.session)
    }
}
