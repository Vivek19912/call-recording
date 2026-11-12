/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.WacrmSession
import com.baba.callvault.data.voip.WhatsappRecordingSource
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Owner-policy synchronization frequency + propagation.
 *
 * Covers the split between the 7-day recording-validity bound
 * ([WhatsappSourceSync.POLICY_MAX_STALE_MS]) and the 1-minute fetch
 * throttle ([WhatsappSourceSync.POLICY_SYNC_INTERVAL_MS]): a deploy-fresh
 * policy must not block refetching an Owner edit, duplicate concurrent
 * fetches collapse to one request, failures never fabricate freshness,
 * a mid-flight account switch is discarded, every success publishes the
 * full snapshot (number + enabled + WhatsApp together), and remote
 * logout still clears the policy after the session wipe.
 *
 * Plain JUnit4 + MockK + a loopback HttpServer (same fixture style as
 * [WacrmAuthTest]); real [AppPreferences] over [FakeSharedPreferences],
 * so key names, defaults and read-back semantics are the production ones.
 * No Robolectric, no network beyond loopback.
 */
class PolicySyncTest {

    private lateinit var context: Context
    private lateinit var fake: FakeSharedPreferences
    private lateinit var prefs: AppPreferences
    private var currentSession: WacrmSession.Session? = null
    private var server: HttpServer? = null

    @Before
    fun setUp() {
        context = mockk()
        fake = FakeSharedPreferences()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns fake
        prefs = AppPreferences(context)
        mockkObject(WacrmSession)
        currentSession = WacrmSession.Session(
            accessToken = "access-token-fixture",
            refreshToken = "refresh-token-fixture",
            expiresAtMs = System.currentTimeMillis() + 3_600_000L,
            userId = "user-1",
            userEmail = "user@example.invalid",
            userName = null,
            accountId = "acct-1",
            accountName = null,
            role = "agent",
        )
        every { WacrmSession.get(any()) } answers { currentSession }
    }

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
        unmockkAll()
    }

    private fun meBody(
        number: String?,
        enabled: Boolean,
        configured: Boolean,
        whatsapp: String,
    ): String {
        val num = if (number == null) "null" else "\"$number\""
        return "{\"user\":{\"id\":\"user-1\"},\"account\":{\"id\":\"acct-1\"},\"role\":\"agent\"," +
            "\"whatsappRecordingSource\":\"$whatsapp\"," +
            "\"phoneRecordingSource\":\"none\"," +
            "\"phoneRecordingNumber\":$num," +
            "\"phoneRecordingEnabled\":$enabled," +
            "\"phoneRecordingConfigured\":$configured}"
    }

    /** Serves one canned `/api/auth/me`; returns the base URL. Counts hits. */
    private fun startServer(status: Int, body: String, hits: AtomicInteger): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/api/auth/me") { exchange ->
            hits.incrementAndGet()
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun seedOldPolicy(lastSyncMs: Long) {
        prefs.setPhoneRecordingNumber("91111")
        prefs.setPhoneRecordingEnabled(false)
        prefs.setPhoneRecordingConfigured(true)
        prefs.setWhatsappRecordingSource(WhatsappRecordingSource.NONE)
        prefs.setLastPolicySyncMs(lastSyncMs)
    }

    @Test
    fun `fresh seven-day policy does not block the one-minute fetch`() {
        // Validity (7d) and fetch throttle (60s) are independent: a policy
        // synced 61s ago is VALID for recording yet DUE for refetch.
        seedOldPolicy(System.currentTimeMillis() - 61_000L)
        assertTrue(WhatsappSourceSync.isPolicyFresh(prefs))
        val hits = AtomicInteger()
        val base = startServer(
            200,
            meBody("917460939319", enabled = true, configured = true, whatsapp = "whatsapp_business"),
            hits,
        )
        assertTrue(WhatsappSourceSync.syncIfDue(context, base))
        assertEquals(1, hits.get())
        // Number, enabled state and WhatsApp selection persist together.
        assertEquals("917460939319", prefs.getPhoneRecordingNumber())
        assertTrue(prefs.isPhoneRecordingEnabled())
        assertTrue(prefs.isPhoneRecordingConfigured())
        assertEquals(WhatsappRecordingSource.WHATSAPP_BUSINESS, prefs.getWhatsappRecordingSource())
        // The success stamp moved, so the policy is valid afterwards.
        assertTrue(WhatsappSourceSync.isPolicyFresh(prefs))
        // And the full snapshot was published for the open UI.
        val flow = WhatsappSourceSync.policyFlow.value
        assertEquals("917460939319", flow?.phoneNumber)
        assertEquals(true, flow?.phoneEnabled)
        assertEquals(true, flow?.phoneConfigured)
        assertEquals(WhatsappRecordingSource.WHATSAPP_BUSINESS, flow?.whatsapp)
    }

    @Test
    fun `recent success throttles a second fetch`() {
        seedOldPolicy(System.currentTimeMillis() - 1_000L)
        val hits = AtomicInteger()
        val base = startServer(
            200,
            meBody("917460939319", enabled = true, configured = true, whatsapp = "whatsapp"),
            hits,
        )
        assertTrue(WhatsappSourceSync.syncIfDue(context, base))
        assertTrue(WhatsappSourceSync.syncIfDue(context, base))
        assertEquals(0, hits.get())
        // Throttled: the cached values are untouched.
        assertEquals("91111", prefs.getPhoneRecordingNumber())
    }

    @Test
    fun `never-synced device always fetches`() {
        assertEquals(0L, prefs.getLastPolicySyncMs())
        val hits = AtomicInteger()
        val base = startServer(
            200,
            meBody("917460939319", enabled = true, configured = true, whatsapp = "whatsapp"),
            hits,
        )
        assertTrue(WhatsappSourceSync.syncIfDue(context, base))
        assertEquals(1, hits.get())
        assertEquals("917460939319", prefs.getPhoneRecordingNumber())
    }

    @Test
    fun `shouldFetchPolicy separates the one-minute throttle from seven-day validity`() {
        val now = 1_000_000_000_000L
        val week = WhatsappSourceSync.POLICY_MAX_STALE_MS
        // Never synced: always due.
        assertTrue(WhatsappSourceSync.shouldFetchPolicy(0L, now))
        // Fresh minute: not due.
        assertFalse(WhatsappSourceSync.shouldFetchPolicy(now - 1_000L, now))
        // 61s ago: due, even though still valid for days.
        assertTrue(WhatsappSourceSync.shouldFetchPolicy(now - 61_000L, now))
        // 8 days ago: due (and no longer valid — but that is the OTHER bound's job).
        assertTrue(WhatsappSourceSync.shouldFetchPolicy(now - week - 1L, now))
        // Forced: due regardless.
        assertTrue(WhatsappSourceSync.shouldFetchPolicy(now - 1_000L, now, force = true))
    }

    @Test
    fun `concurrent fetches are single-flight`() {
        val hits = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val t = thread {
            WhatsappSourceSync.syncIfDue(context, "http://127.0.0.1:9") { _ ->
                hits.incrementAndGet()
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                throw java.io.IOException("released")
            }
        }
        assertTrue("first fetch never started", entered.await(10, TimeUnit.SECONDS))
        // While the first fetch is in flight, a second trigger must not
        // issue another request.
        WhatsappSourceSync.syncIfDue(context, "http://127.0.0.1:9") { _ ->
            throw AssertionError("duplicate request must never be issued")
        }
        assertEquals(1, hits.get())
        release.countDown()
        t.join(10_000)
        assertEquals(1, hits.get())
    }

    @Test
    fun `failed sync preserves same-user cache without marking it fresh`() {
        // Stale seed: the failure must neither refresh the stamp nor touch values.
        val stamp = System.currentTimeMillis() - WhatsappSourceSync.POLICY_MAX_STALE_MS - 1_000L
        seedOldPolicy(stamp)
        WhatsappSourceSync.publishCurrent(context)
        val before = WhatsappSourceSync.policyFlow.value
        val hits = AtomicInteger()
        val base = startServer(500, "boom", hits)
        assertFalse(WhatsappSourceSync.syncIfDue(context, base))
        assertEquals(1, hits.get())
        // Cached values untouched, success stamp untouched, nothing fabricated.
        assertEquals("91111", prefs.getPhoneRecordingNumber())
        assertFalse(prefs.isPhoneRecordingEnabled())
        assertTrue(prefs.isPhoneRecordingConfigured())
        assertEquals(WhatsappRecordingSource.NONE, prefs.getWhatsappRecordingSource())
        assertEquals(stamp, prefs.getLastPolicySyncMs())
        assertFalse(WhatsappSourceSync.isPolicyFresh(prefs))
        assertEquals(before, WhatsappSourceSync.policyFlow.value)
    }

    @Test
    fun `identity switch mid-fetch discards the late response`() {
        val stamp = System.currentTimeMillis() - WhatsappSourceSync.POLICY_MAX_STALE_MS - 1_000L
        seedOldPolicy(stamp)
        WhatsappSourceSync.publishCurrent(context)
        val before = WhatsappSourceSync.policyFlow.value
        val hits = AtomicInteger()
        val base = startServer(
            200,
            meBody("917460939319", enabled = true, configured = true, whatsapp = "whatsapp_business"),
            hits,
        )
        val switched = WacrmSession.Session(
            accessToken = "other",
            refreshToken = "other-ref",
            expiresAtMs = System.currentTimeMillis() + 3_600_000L,
            userId = "user-2",
            userEmail = null,
            userName = null,
            accountId = "acct-1",
            accountName = null,
            role = "agent",
        )
        val result = WhatsappSourceSync.syncIfDue(context, base) { url ->
            // The account switches user while the first identity's answer
            // is still on the wire.
            currentSession = switched
            @Suppress("UsePropertyAccessSyntax")
            (java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection)
        }
        assertFalse(result)
        assertEquals(1, hits.get())
        // Nothing from the old identity overwrote the current user.
        assertEquals("91111", prefs.getPhoneRecordingNumber())
        assertFalse(prefs.isPhoneRecordingEnabled())
        assertEquals(WhatsappRecordingSource.NONE, prefs.getWhatsappRecordingSource())
        assertEquals(stamp, prefs.getLastPolicySyncMs())
        assertEquals(before, WhatsappSourceSync.policyFlow.value)
    }

    @Test
    fun `login sync persists the full snapshot and publishes it`() {
        val hits = AtomicInteger()
        val base = startServer(
            200,
            meBody("917460939319", enabled = true, configured = true, whatsapp = "whatsapp_business"),
            hits,
        )
        val ret = WhatsappSourceSync.syncAfterAuth(context, base, "access-token-fixture")
        assertEquals(WhatsappRecordingSource.WHATSAPP_BUSINESS, ret)
        assertEquals(1, hits.get())
        assertEquals("917460939319", prefs.getPhoneRecordingNumber())
        assertTrue(prefs.isPhoneRecordingEnabled())
        val flow = WhatsappSourceSync.policyFlow.value
        assertEquals("917460939319", flow?.phoneNumber)
        assertEquals(WhatsappRecordingSource.WHATSAPP_BUSINESS, flow?.whatsapp)
    }

    @Test
    fun `remote logout clears owner policy after the session wipe`() {
        prefs.setPhoneRecordingNumber("917460939319")
        prefs.setPhoneRecordingEnabled(true)
        prefs.setPhoneRecordingConfigured(true)
        prefs.setWhatsappRecordingSource(WhatsappRecordingSource.WHATSAPP_BUSINESS)
        prefs.setLastPolicySyncMs(System.currentTimeMillis())
        WhatsappSourceSync.publishCurrent(context)
        every { WacrmSession.clear(any()) } answers { currentSession = null }
        WacrmRemoteLogout.applyRemoteLogout(context)
        // Session wiped (simulated by the stub above).
        assertNull(currentSession)
        // Owner policy cleared with it — never leaked to the next user —
        // and the cleared snapshot published for any open UI.
        assertNull(prefs.getPhoneRecordingNumber())
        assertFalse(prefs.isPhoneRecordingEnabled())
        assertFalse(prefs.isPhoneRecordingConfigured())
        assertEquals(0L, prefs.getLastPolicySyncMs())
        val flow = WhatsappSourceSync.policyFlow.value
        assertNull(flow?.phoneNumber)
        assertEquals(false, flow?.phoneEnabled)
        assertEquals(false, flow?.phoneConfigured)
    }

    @Test
    fun `refresh loop fetches immediately then per interval until cancelled`() = runTest {
        var n = 0
        val loop = PolicyRefreshLoop(intervalMs = 60_000) { n++ }
        val job = launch { loop.runForever() }
        runCurrent()
        assertEquals(1, n)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, n)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(4, n)
        // Stopped: no further fetches (covers collapse / screen-leave).
        job.cancel()
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(4, n)
    }

    @Test
    fun `refresh loop survives a throwing fetch`() = runTest {
        var n = 0
        var fail = true
        val loop = PolicyRefreshLoop(intervalMs = 30) {
            if (fail) {
                fail = false
                throw RuntimeException("transient")
            }
            n++
        }
        val job = launch { loop.runForever() }
        advanceTimeBy(100)
        runCurrent()
        job.cancel()
        assertTrue("expected retries after a throw, got $n", n >= 2)
    }
}
