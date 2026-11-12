package com.baba.callvault.system.storage

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Consume fast-path tests: the server answers our own `received` ack
 * with a validated dial payload on the same socket, so no extra
 * HTTPS round trip is needed in the common case.
 *
 * Security properties pinned here:
 * - only a well-formed consume/rejected frame for a known id moves a
 *   waiter; anything else is ignored (never dialled);
 * - consumed numbers must still be dialable with a live TTL, or the
 *   phone reports `failed` instead of opening the dialer;
 * - a duplicate signal for the same id never dials twice.
 */
class DialConsumeTest {

    private val id = "11111111-1111-4111-8111-111111111111"

    @Before
    fun setUp() {
        DialSocketClient.resetConsumeStateForTest()
        DialEventProcessor.resetForTest()
    }

    @After
    fun tearDown() {
        DialSocketClient.resetConsumeStateForTest()
        DialEventProcessor.resetForTest()
    }

    // --- DialSocketProtocol.parseConsume ------------------------

    @Test
    fun `parses a valid consume reply`() {
        val parsed = DialSocketProtocol.parseConsume(
            """{"type":"consume","request_id":"$id","phone_number":"+14155552671","expires_at":"2030-01-01T00:00:00Z"}"""
        )
        val consume = parsed as? DialSocketProtocol.ConsumeOutcome.Consumed
        assertNotNull(consume)
        assertEquals(id, consume!!.requestId)
        assertEquals("+14155552671", consume.phoneNumber)
    }

    @Test
    fun `parses a consume rejection`() {
        val parsed = DialSocketProtocol.parseConsume(
            """{"type":"consume_rejected","request_id":"$id"}"""
        )
        assertTrue(parsed is DialSocketProtocol.ConsumeOutcome.Rejected)
    }

    @Test
    fun `rejects malformed or unsafe consume frames`() {
        assertNull(DialSocketProtocol.parseConsume("""{"type":"consume","request_id":"not-a-uuid","phone_number":"+14155552671","expires_at":"2030-01-01T00:00:00Z"}"""))
        // Undialable number: parsed (transport is syntax only), but
        // isUsableConsume must refuse to dial it.
        val blank = DialSocketProtocol.parseConsume(
            """{"type":"consume","request_id":"$id","phone_number":"  ","expires_at":"2030-01-01T00:00:00Z"}"""
        )
        assertNull(blank)
        assertNull(DialSocketProtocol.parseConsume("""{"type":"consume","request_id":"$id"}"""))
        assertNull(DialSocketProtocol.parseConsume("""{"type":"dial_request","request_id":"$id","expires_at":"2030-01-01T00:00:00Z"}"""))
        assertNull(DialSocketProtocol.parseConsume("not json"))
        assertNull(DialSocketProtocol.parseConsume(null))
        assertNull(DialSocketProtocol.parseConsume("x".repeat(5000)))
    }

    // --- Consume waiter rendezvous -------------------------------

    @Test
    fun `awaitConsume resolves when the reply arrives`() {
        Thread {
            Thread.sleep(50)
            DialSocketClient.simulateConsumeForTest(
                DialSocketProtocol.ConsumeOutcome.Consumed(id, "+14155552671", "2030-01-01T00:00:00Z")
            )
        }.start()
        val got = DialSocketClient.awaitConsume(id, 5_000)
        assertTrue(got is DialSocketProtocol.ConsumeOutcome.Consumed)
    }

    @Test
    fun `awaitConsume surfaces rejections instead of hanging`() {
        Thread {
            Thread.sleep(50)
            DialSocketClient.simulateConsumeForTest(DialSocketProtocol.ConsumeOutcome.Rejected(id))
        }.start()
        val got = DialSocketClient.awaitConsume(id, 5_000)
        assertTrue(got is DialSocketProtocol.ConsumeOutcome.Rejected)
    }

    @Test
    fun `awaitConsume times out instead of blocking forever`() {
        val start = System.currentTimeMillis()
        val got = DialSocketClient.awaitConsume("22222222-2222-4222-8222-222222222222", 200)
        assertNull(got)
        assertTrue(System.currentTimeMillis() - start < 5_000)
    }

    @Test
    fun `stale consume frames complete nothing`() {
        assertFalse(
            DialSocketClient.simulateConsumeForTest(
                DialSocketClient.run { DialSocketProtocol.ConsumeOutcome.Rejected(id) }
            )
        )
    }

    // --- Usability gate (what may reach the dialer) ---------------

    @Test
    fun `usable consume requires a dialable number with a live ttl`() {
        val good = DialSocketProtocol.ConsumeOutcome.Consumed(id, "+14155552671", "2030-01-01T00:00:00Z")
        assertTrue(DialEventProcessor.isUsableConsume(good))
        assertFalse(DialEventProcessor.isUsableConsume(good.copy(phoneNumber = "n/a")))
        assertFalse(DialEventProcessor.isUsableConsume(good.copy(expiresAt = "2000-01-01T00:00:00Z")))
        assertFalse(DialEventProcessor.isUsableConsume(good.copy(expiresAt = "not-a-date")))
    }

    // --- End-to-end duplicate safety ------------------------------

    @Test
    fun `a second signal for the same id is already claimed`() {
        DialEventProcessor.resetForTest()
        // First claim wins; the replay path below must not redial.
        // (Claim lives in DialEventProcessor; replicate the contract
        // via the public handle path is Android-only, so assert the
        // building blocks: the waiter fires once, then is gone.)
        var deliveries = 0
        val t = Thread {
            Thread.sleep(50)
            if (DialSocketClient.simulateConsumeForTest(
                    DialSocketProtocol.ConsumeOutcome.Consumed(id, "+14155552671", "2030-01-01T00:00:00Z")
                )
            ) deliveries++
        }
        t.start()
        val first = DialSocketClient.awaitConsume(id, 5_000)
        t.join()
        assertTrue(first is DialSocketProtocol.ConsumeOutcome.Consumed)
        // The waiter is single-shot: a duplicate frame finds no waiter.
        assertFalse(
            DialSocketClient.simulateConsumeForTest(
                DialSocketProtocol.ConsumeOutcome.Consumed(id, "+14155552671", "2030-01-01T00:00:00Z")
            )
        )
        assertEquals(1, deliveries)
    }
}
