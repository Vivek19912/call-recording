package com.baba.callvault.system.storage

import android.app.NotificationManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for the delivery-selection step: only
 * actionable, unexpired, dialable rows may produce a notification
 * (and therefore a `delivered` ack). An expired or finished row
 * must never notify — and must never be marked delivered.
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

    @Test
    fun `keeps pending and delivered future rows`() {
        val selected = DialSync.selectNotifiable(
            listOf(row(status = "pending"), row(id = "req-2", status = "delivered")),
            1_700_000_000_000L,
        )
        assertEquals(listOf("req-1", "req-2"), selected.map { it.id })
    }

    @Test
    fun `drops expired rows`() {
        val selected = DialSync.selectNotifiable(
            listOf(row(expiresAt = "2000-01-01T00:00:00Z")),
            1_700_000_000_000L,
        )
        assertTrue(selected.isEmpty())
    }

    @Test
    fun `drops finished rows`() {
        for (status in listOf("opened", "completed", "expired", "cancelled", "failed")) {
            val selected = DialSync.selectNotifiable(listOf(row(status = status)), 1_700_000_000_000L)
            assertTrue("status $status must not notify", selected.isEmpty())
        }
    }

    @Test
    fun `drops undialable numbers`() {
        val selected = DialSync.selectNotifiable(listOf(row(phone = "n/a")), 1_700_000_000_000L)
        assertTrue(selected.isEmpty())
    }

    @Test
    fun `planner directs the oldest actionable row and notifies the rest`() {
        val handled = mutableSetOf<String>()
        val plans = DialSync.planDeliveries(
            listOf(row(id = "a"), row(id = "b", status = "delivered")),
            1_700_000_000_000L, handled, allowDirect = true,
        )
        // One auto-open per poll at most; the second row still gets
        // its notification rather than a second dialer launch.
        assertEquals(
            listOf(DialSync.DeliveryKind.DIRECT, DialSync.DeliveryKind.NOTIFY),
            plans.map { it.kind }
        )
        assertEquals(setOf("a"), handled)
    }

    @Test
    fun `planner never repeats an already-handled request`() {
        val handled = mutableSetOf("a")
        val plans = DialSync.planDeliveries(
            listOf(row(id = "a"), row(id = "b")),
            1_700_000_000_000L, handled, allowDirect = true,
        )
        assertEquals(DialSync.DeliveryKind.SKIP, plans[0].kind)
        assertEquals(DialSync.DeliveryKind.DIRECT, plans[1].kind)
        assertEquals(setOf("a", "b"), handled)
    }

    @Test
    fun `planner skips dead rows without consuming them`() {
        val handled = mutableSetOf<String>()
        val plans = DialSync.planDeliveries(
            listOf(
                row(id = "e1", expiresAt = "2000-01-01T00:00:00Z"),
                row(id = "e2", phone = "n/a"),
                row(id = "e3", status = "opened"),
            ),
            1_700_000_000_000L, handled, allowDirect = true,
        )
        assertTrue(plans.all { it.kind == DialSync.DeliveryKind.SKIP })
        assertTrue(handled.isEmpty())
    }

    @Test
    fun `planner falls back to notify when direct launch is not allowed`() {
        val handled = mutableSetOf<String>()
        val plans = DialSync.planDeliveries(
            listOf(row(id = "a")),
            1_700_000_000_000L, handled, allowDirect = false,
        )
        assertEquals(DialSync.DeliveryKind.NOTIFY, plans.single().kind)
        // NOTIFY rows are not consumed by the planner: presenting them
        // is the notification path's job, with its own delivered ack.
        assertTrue(handled.isEmpty())
    }

    @Test
    fun `notification post fails closed when Android services are unavailable`() {
        // No NotificationManager service: show() must return false
        // (→ no delivered ack) rather than throw, so the poller holds
        // the request instead of marking an unseen row delivered.
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
