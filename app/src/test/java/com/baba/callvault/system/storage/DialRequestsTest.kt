package com.baba.callvault.system.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Regression tests for the stuck-`pending` delivery failure.
 *
 * The poller used to collapse every failure into a silent empty
 * list (expired token → 401 → nothing logged, nothing acked), the
 * tap never acked `opened`, and `delivered` was acked even when no
 * notification was shown. These tests pin the fixed contracts:
 * typed fetch results, exact server-envelope parsing, expiry
 * filtering, and ack status codes.
 */
class DialRequestsTest {

    // Exact wire shape of GET /api/v1/dial-requests (see the WACRM
    // v1 route test asserting the same envelope server-side).
    private val serverEnvelope = """
        {"data":{"dial_requests":[
          {"id":"d-1","phone_number":"+14155552671","contact_id":"c-1","flow_id":"f-1","run_id":"r-1","status":"pending","expires_at":"2030-01-01T00:00:00Z","created_at":"2030-01-01T00:00:00Z"},
          {"id":"d-2","phone_number":"+44 20 7946 0958","contact_id":null,"flow_id":null,"run_id":null,"status":"delivered","expires_at":"2030-01-01T00:00:00Z","created_at":"2030-01-01T00:00:00Z","extra_future_field":"tolerated"},
          {"id":"d-3","phone_number":"n/a","contact_id":null,"flow_id":null,"run_id":null,"status":"pending","expires_at":"2030-01-01T00:00:00Z","created_at":"2030-01-01T00:00:00Z"},
          {"id":"","phone_number":"+14155552671","status":"pending","expires_at":"","created_at":""}
        ]}}
    """.trimIndent()

    private class FakeConnection(
        url: URL,
        private val code: Int,
        private val body: String = "",
        private val failOnCode: Boolean = false,
    ) : HttpURLConnection(url) {
        val written = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode(): Int {
            if (failOnCode) throw IOException("boom")
            return code
        }
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun getOutputStream(): OutputStream = written
    }

    @Test
    fun `parses pending list and skips invalid numbers`() {
        val parsed = DialRequests.parseList(serverEnvelope)
        assertEquals(2, parsed.size)
        assertEquals("d-1", parsed[0].id)
        assertEquals("+14155552671", parsed[0].phoneNumber)
        assertEquals("c-1", parsed[0].contactId)
        assertEquals("delivered", parsed[1].status)
        assertEquals("+442079460958", parsed[1].phoneNumber)
    }

    @Test
    fun `empty body parses to empty list`() {
        assertTrue(DialRequests.parseList("").isEmpty())
        assertTrue(DialRequests.parseList("""{"data":{}}""").isEmpty())
    }

    @Test
    fun `fetchPending maps 200 to Success`() {
        val conn = FakeConnection(URL("https://x.test/"), 200, serverEnvelope)
        val result = DialRequests.fetchPending("https://x.test", "tok") { conn }
        assertTrue(result is DialRequests.FetchResult.Success)
        assertEquals(2, (result as DialRequests.FetchResult.Success).requests.size)
        assertTrue(conn.disconnected)
    }

    @Test
    fun `fetchPending maps 401 to HttpFailure not silent empty`() {
        val result = DialRequests.fetchPending("https://x.test", "stale-token") { _ ->
            FakeConnection(URL("https://x.test/"), 401, """{"error":{"code":"unauthorized"}}""")
        }
        assertEquals(DialRequests.FetchResult.HttpFailure(401), result)
    }

    @Test
    fun `fetchPending maps 500 to HttpFailure`() {
        val result = DialRequests.fetchPending("https://x.test", "tok") { _ ->
            FakeConnection(URL("https://x.test/"), 500, "boom")
        }
        assertEquals(DialRequests.FetchResult.HttpFailure(500), result)
    }

    @Test
    fun `fetchPending maps transport failure to NetworkError`() {
        val result = DialRequests.fetchPending("https://x.test", "tok") { _ ->
            FakeConnection(URL("https://x.test/"), 200, failOnCode = true)
        }
        assertEquals(DialRequests.FetchResult.NetworkError, result)
    }

    @Test
    fun `fetchPending maps garbage body to ParseError`() {
        val result = DialRequests.fetchPending("https://x.test", "tok") { _ ->
            FakeConnection(URL("https://x.test/"), 200, "not json {{{")
        }
        assertEquals(DialRequests.FetchResult.ParseError, result)
    }

    @Test
    fun `ack returns the HTTP code and sends the status payload`() {
        val conn = FakeConnection(URL("https://x.test/"), 200, """{"data":{}}""")
        val code = DialRequests.ack("https://x.test", "tok", "d-1", "delivered") { conn }
        assertEquals(200, code)
        assertTrue(conn.written.toString().contains("\"delivered\""))
    }

    @Test
    fun `ack surfaces 409 replay and 410 expiry distinctly`() {
        val conflict = DialRequests.ack("https://x.test", "tok", "d-1", "opened") { _ ->
            FakeConnection(URL("https://x.test/"), 409, """{"error":{"code":"conflict"}}""")
        }
        assertEquals(409, conflict)
        val gone = DialRequests.ack("https://x.test", "tok", "d-1", "opened") { _ ->
            FakeConnection(URL("https://x.test/"), 410, """{"error":{"code":"gone"}}""")
        }
        assertEquals(410, gone)
    }

    @Test
    fun `ack maps transport failure to minus one`() {
        val code = DialRequests.ack("https://x.test", "tok", "d-1", "delivered") { _ ->
            FakeConnection(URL("https://x.test/"), 200, failOnCode = true)
        }
        assertEquals(-1, code)
    }

    @Test
    fun `isExpired only fires on parseable past instants`() {
        assertTrue(DialRequests.isExpired("2000-01-01T00:00:00Z", 1_700_000_000_000L))
        assertFalse(DialRequests.isExpired("2030-01-01T00:00:00Z", 1_700_000_000_000L))
        assertFalse(DialRequests.isExpired("", 1_700_000_000_000L))
        assertFalse(DialRequests.isExpired("garbage", 1_700_000_000_000L))
    }
}
