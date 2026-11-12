package com.baba.callvault.system.storage

import android.app.NotificationManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for the WebSocket delivery path.
 *
 * The old foreground/WorkManager poller is gone: delivery is one
 * authenticated WebSocket, and every signal must pass an
 * authoritative fetch before the dialer can open. These tests pin
 * the two pure gates around that (event policy and wire protocol)
 * plus the existing notification/auto-open/tap safeguards.
 */
class DialHandoffTest {

    private fun row(
        id: String = "req-1",
        status: String = "pending",
        expiresAt: String = "2030-01-01T00:00:00Z",
        phone: String = "+14155552671",
    ) = DialRequests.DialRequest(
        id = id,
        phoneNumber = phone,
        contactId = null,
        flowId = null,
        runId = null,
        status = status,
        expiresAt = expiresAt,
        createdAt = "2030-01-01T00:00:00Z",
    )

    // --- DialEventPolicy: what may reach the dialer -----------------

    @Test
    fun `policy accepts pending and delivered live rows`() {
        for (status in listOf("pending", "delivered")) {
            assertTrue(
                status,
                DialEventPolicy.isActionable(status, "2030-01-01T00:00:00Z", "+14155552671", 1_700_000_000_000L)
            )
        }
    }

    @Test
    fun `policy drops expired rows`() {
        assertFalse(
            DialEventPolicy.isActionable("pending", "2000-01-01T00:00:00Z", "+14155552671", 1_700_000_000_000L)
        )
    }

    @Test
    fun `policy drops finished rows`() {
        for (status in listOf("opened", "completed", "expired", "cancelled", "failed")) {
            assertFalse(
                status,
                DialEventPolicy.isActionable(status, "2030-01-01T00:00:00Z", "+14155552671", 1_700_000_000_000L)
            )
        }
    }

    @Test
    fun `policy drops undialable numbers`() {
        assertFalse(
            DialEventPolicy.isActionable("pending", "2030-01-01T00:00:00Z", "n/a", 1_700_000_000_000L)
        )
    }

    // --- DialSocketProtocol: URL + frame + reconnect ----------------

    @Test
    fun `builds a wss url from an https base and encodes the device id`() {
        assertEquals(
            "wss://whatsappmax.in/ws/dial?device_id=abc-123",
            DialSocketProtocol.webSocketUrl("https://whatsappmax.in", "abc-123")
        )
        assertEquals(
            "wss://host.test:8443/ws/dial?device_id=a+b",
            DialSocketProtocol.webSocketUrl("https://host.test:8443/", "a b")
        )
    }

    @Test
    fun `refuses cleartext or unusable bases`() {
        assertNull(DialSocketProtocol.webSocketUrl("http://whatsappmax.in", "d"))
        assertNull(DialSocketProtocol.webSocketUrl(null, "d"))
        assertNull(DialSocketProtocol.webSocketUrl("https://whatsappmax.in", " "))
        assertNull(DialSocketProtocol.webSocketUrl("not a url", "d"))
    }

    @Test
    fun `debug builds may use cleartext loopback only`() {
        // adb reverse makes 127.0.0.1 reach the dev machine; debug-only.
        assertEquals(
            "ws://127.0.0.1:3001/ws/dial?device_id=d",
            DialSocketProtocol.webSocketUrl("http://127.0.0.1:3001", "d")
        )
        assertNull(DialSocketProtocol.webSocketUrl("http://192.168.1.50:3001", "d"))
    }

    @Test
    fun `parses a signal frame and rejects anything that cannot be an id`() {
        val event = DialSocketProtocol.parse(
            """{"type":"dial_request","event_id":"e1","request_id":"11111111-1111-4111-8111-111111111111","expires_at":"2030-01-01T00:00:00Z","phone_number":"+919999999999"}"""
        )
        assertNotNull(event)
        assertEquals("11111111-1111-4111-8111-111111111111", event!!.requestId)
        // The number in a forged frame is ignored: the authoritative
        // fetch decides what is dialled.
        assertEquals("2030-01-01T00:00:00Z", event.expiresAt)

        assertNull(DialSocketProtocol.parse("""{"type":"ready"}"""))
        assertNull(DialSocketProtocol.parse("""{"type":"dial_request","request_id":"not-a-uuid"}"""))
        assertNull(DialSocketProtocol.parse("not json"))
        assertNull(DialSocketProtocol.parse(""))
        assertNull(DialSocketProtocol.parse("x".repeat(5000)))
    }

    @Test
    fun `reconnects on transient closes but not on takeover or deliberate stop`() {
        assertTrue(DialSocketProtocol.shouldReconnect(1006))
        assertTrue(DialSocketProtocol.shouldReconnect(4401))
        assertFalse(DialSocketProtocol.shouldReconnect(1000))
        // Another device (4001) or a newer socket for this device
        // (4002) owns delivery now; reconnecting would fight it.
        assertFalse(DialSocketProtocol.shouldReconnect(4001))
        assertFalse(DialSocketProtocol.shouldReconnect(4002))
        // 1012 = revocation registry unreadable (server suspends
        // the socket). Retryable by design: reconnect and
        // revalidate, never wipe — a database outage is not a
        // logout.
        assertTrue(DialSocketProtocol.shouldReconnect(1012))
    }

    @Test
    fun `reconnect backoff grows and caps at one minute`() {
        assertEquals(1_000L, DialSocketProtocol.reconnectDelayMs(0))
        assertEquals(4_000L, DialSocketProtocol.reconnectDelayMs(2))
        assertEquals(60_000L, DialSocketProtocol.reconnectDelayMs(20))
        // Jitter never pushes the delay past the one-minute cap.
        assertEquals(60_000L, DialSocketProtocol.reconnectDelayMs(20, jitterMs = 500))
    }

    // --- Existing safeguards ---------------------------------------

    @Test
    fun `notification post fails closed when Android services are unavailable`() {
        // No NotificationManager service: show() must return false
        // (→ no delivered ack) rather than throw, so the request is
        // not marked delivered when nothing was shown.
        val context = mockk<Context>()
        every {
            context.getSystemService(NotificationManager::class.java)
        } returns null
        val shown = DialRequestNotifications.show(context, row(id = "n1"))
        assertFalse(shown)
    }

    @Test
    fun `background auto-open needs opt-in, overlay grant, and an unlocked app`() {
        // Everything on: background direct open is allowed.
        assertTrue(DialAutoOpen.shouldAttemptBackgroundOpen(true, canDrawOverlays = true, lockAllows = true))
        // Opt-in off: never auto-open behind the user's back.
        assertFalse(DialAutoOpen.shouldAttemptBackgroundOpen(false, canDrawOverlays = true, lockAllows = true))
        // No overlay grant: the OS would block the launch, so fall
        // back to the notification instead of a silent no-op.
        assertFalse(DialAutoOpen.shouldAttemptBackgroundOpen(true, canDrawOverlays = false, lockAllows = true))
        // App lock on with no verified unlock: the tap flow (which
        // waits for unlock) owns these rows instead.
        assertFalse(DialAutoOpen.shouldAttemptBackgroundOpen(true, canDrawOverlays = true, lockAllows = false))
        assertFalse(DialAutoOpen.shouldAttemptBackgroundOpen(false, canDrawOverlays = false, lockAllows = false))
    }

    @Test
    fun `tap extra validates the number and ignores history relaunches`() {
        assertEquals("+14155552671", OpenDialRequest.fromIntent("+1 (415) 555-2671", false))
        assertNull(OpenDialRequest.fromIntent("+14155552671", true))
        assertNull(OpenDialRequest.fromIntent("n/a", false))
        assertNull(OpenDialRequest.fromIntent(null, false))
    }
}
