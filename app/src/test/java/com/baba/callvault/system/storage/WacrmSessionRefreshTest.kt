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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-flight session refresh ([WacrmSessionRefresh]).
 *
 * Pure JVM: the HTTP call is the injectable [WacrmSessionRefresh.refreshIfNeeded]
 * `refreshCall` lambda (no loopback server needed), and the encrypted store is
 * the in-memory [WacrmSession.openBackingStoreForTest] seam. The Context is a
 * mock — the seam never touches it.
 */
class WacrmSessionRefreshTest {

    private class MemStore : WacrmSession.BackingStore {
        val strings = mutableMapOf<String, String?>()
        var expiresAtMs: Long = 0L
        var failWrites: Boolean = false

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
    private val config = WacrmAuth.MobileConfig("https://wacrm.example", "anon-key-fixture")

    @Before
    fun setUp() {
        store = MemStore()
        WacrmSession.openBackingStoreForTest = { store }
    }

    @After
    fun tearDown() {
        WacrmSession.openBackingStoreForTest = null
    }

    private fun seedExpired(
        access: String = "acc-old",
        refresh: String = "ref-old",
    ) {
        assertTrue(
            WacrmSession.save(
                context, access, refresh, 0L,
                "u-1", "sagar@example.com", "Sagar", "a-1", "Acme", "agent",
            )
        )
    }

    private fun storedAccess(): String? = WacrmSession.get(context)?.accessToken

    private fun okTokens(access: String, refresh: String) = WacrmAuth.SessionTokens(
        accessToken = access,
        refreshToken = refresh,
        expiresAtMs = System.currentTimeMillis() + 3_600_000L,
        userId = "u-1",
        userEmail = "sagar@example.com",
    )

    @Test
    fun `fresh session returns without any HTTP call`() {
        assertTrue(
            WacrmSession.save(
                context, "acc-fresh", "ref-fresh",
                System.currentTimeMillis() + 3_600_000L,
                "u-1", null, null, "a-1", null, null,
            )
        )
        var calls = 0
        val result = WacrmSessionRefresh.refreshIfNeeded(context, config) { _, _ ->
            calls += 1
            WacrmAuth.AuthOutcome.NetworkError
        }
        check(result is WacrmSessionRefresh.SessionResult.Valid)
        assertEquals("acc-fresh", result.session.accessToken)
        assertEquals(0, calls)
    }

    @Test
    fun `concurrent refresh issues exactly one HTTP call`() {
        seedExpired()
        val calls = AtomicInteger(0)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..8).map {
                pool.submit<WacrmSessionRefresh.SessionResult> {
                    WacrmSessionRefresh.refreshIfNeeded(context, config, force = false) { _, _ ->
                        calls.incrementAndGet()
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "refresh was never released" }
                        WacrmAuth.AuthOutcome.Ok(okTokens("acc-new", "ref-new"))
                    }
                }
            }
            check(entered.await(10, TimeUnit.SECONDS)) { "no refresh started" }
            // All 8 callers are now inside (one flying, seven joining); let it land.
            release.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, calls.get())
            for (result in results) {
                check(result is WacrmSessionRefresh.SessionResult.Valid)
                assertEquals("acc-new", result.session.accessToken)
            }
            assertEquals("acc-new", storedAccess())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `stale response does not overwrite a newer winner`() {
        seedExpired(access = "acc-t1", refresh = "ref-t1")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<WacrmSessionRefresh.SessionResult> {
                WacrmSessionRefresh.refreshIfNeeded(context, config, force = true) { _, _ ->
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "refresh was never released" }
                    // Stale: minted from ref-t1, lands after the winner below.
                    WacrmAuth.AuthOutcome.Ok(okTokens("acc-stale", "ref-stale"))
                }
            }
            check(entered.await(10, TimeUnit.SECONDS)) { "no refresh started" }
            // A second winner persists while the first call is in flight.
            assertTrue(
                WacrmSession.save(
                    context, "acc-t2", "ref-t2",
                    System.currentTimeMillis() + 3_600_000L,
                    "u-1", null, null, "a-1", null, null,
                )
            )
            release.countDown()
            val result = future.get(10, TimeUnit.SECONDS)
            check(result is WacrmSessionRefresh.SessionResult.Valid)
            assertEquals("acc-t2", result.session.accessToken)
            assertEquals("acc-t2", storedAccess())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `rejected refresh keeps the stored session`() {
        seedExpired()
        val result = WacrmSessionRefresh.refreshIfNeeded(context, config, force = true) { _, _ ->
            WacrmAuth.AuthOutcome.InvalidCredentials
        }
        check(result is WacrmSessionRefresh.SessionResult.RefreshFailed)
        // Never cleared: the old tokens are still stored for inspection/retry.
        assertEquals("acc-old", storedAccess())
        assertEquals("ref-old", WacrmSession.get(context)?.refreshToken)
    }

    @Test
    fun `network error keeps the stored session`() {
        seedExpired()
        val result = WacrmSessionRefresh.refreshIfNeeded(context, config, force = true) { _, _ ->
            WacrmAuth.AuthOutcome.NetworkError
        }
        check(result is WacrmSessionRefresh.SessionResult.RefreshFailed)
        assertEquals("acc-old", storedAccess())
        assertEquals("ref-old", WacrmSession.get(context)?.refreshToken)
    }

    @Test
    fun `signed out short-circuits without any HTTP call`() {
        var calls = 0
        val result = WacrmSessionRefresh.refreshIfNeeded(context, config, force = true) { _, _ ->
            calls += 1
            WacrmAuth.AuthOutcome.NetworkError
        }
        check(result is WacrmSessionRefresh.SessionResult.SignedOut)
        assertEquals(0, calls)
    }

    @Test
    fun `unreadable store surfaces unavailable without any HTTP call`() {
        WacrmSession.openBackingStoreForTest = { null }
        var calls = 0
        val result = WacrmSessionRefresh.refreshIfNeeded(context, config, force = true) { _, _ ->
            calls += 1
            WacrmAuth.AuthOutcome.NetworkError
        }
        check(result is WacrmSessionRefresh.SessionResult.Unavailable)
        assertEquals(0, calls)
        assertNull(WacrmSession.get(context))
    }
}
