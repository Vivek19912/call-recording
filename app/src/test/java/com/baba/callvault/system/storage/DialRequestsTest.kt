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
 * Regression tests for the authoritative one-shot read.
 *
 * The socket carries only an id, so this read is what decides
 * whether a request is live and which number may be dialled. These
 * tests pin the exact server envelope, typed failure mapping, the
 * single-request URL, and ack status codes.
 */
class DialRequestsTest {

    // Exact wire shape of GET /api/v1/dial-requests/{id} (see the
    // WACRM v1 route, which wraps the row in `data.dial_request`).
    private val serverEnvelope = """
        {"data":{"dial_request":{
          "id":"d-1","phone_number":"+44 20 7946 0958","contact_id":"c-1","flow_id":"f-1","run_id":"r-1",
          "status":"pending","expires_at":"2030-01-01T00:00:00Z","created_at":"2030-01-01T00:00:00Z",
          "extra_future_field":"tolerated"
        }}}
    """.trimIndent()

    private class FakeConnection(
        url: URL,
        private val code: Int,
        private val body: String = "",
        private val failOnCode: Boolean = false,
    ) : HttpURLConnection(url) {
        val requestedUrl = url.toString()
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
    fun `parseOne reads the row, normalizes the number, tolerates unknown fields`() {
        val parsed = DialRequests.parseOne(serverEnvelope)
        assertNotNull(parsed)
        assertEquals("d-1", parsed!!.id)
        assertEquals("+442079460958", parsed.phoneNumber)
        assertEquals("c-1", parsed.contactId)
        assertEquals("pending", parsed.status)
    }

    @Test
    fun `parseOne rejects unusable payloads instead of fabricating a row`() {
        assertNull(DialRequests.parseOne(""))
        assertNull(DialRequests.parseOne("""{"data":{}}"""))
        assertNull(DialRequests.parseOne("""{"data":{"dial_request":{"phone_number":"+14155552671"}}}"""))
        assertNull(
            DialRequests.parseOne(
                """{"data":{"dial_request":{"id":"d-1","phone_number":"n/a","status":"pending","expires_at":"2030-01-01T00:00:00Z","created_at":"2030-01-01T00:00:00Z"}}}"""
            )
        )
    }

    @Test
    fun `fetchOne reads exactly the signal's id and maps 200 to Success`() {
        val conn = FakeConnection(URL("https://x.test/"), 200, serverEnvelope)
        var requestedUrl: String? = null
        val result = DialRequests.fetchOne("https://x.test", "tok", "d-1") { url ->
            requestedUrl = url
            conn
        }
        assertTrue(result is DialRequests.FetchOneResult.Success)
        assertEquals("d-1", (result as DialRequests.FetchOneResult.Success).request.id)
        assertEquals("https://x.test/api/v1/dial-requests/d-1", requestedUrl)
        assertTrue(conn.disconnected)
    }

    @Test
    fun `fetchOne maps 401 to HttpFailure not silent empty`() {
        val result = DialRequests.fetchOne("https://x.test", "stale-token", "d-1") { _ ->
            FakeConnection(URL("https://x.test/"), 401, """{"error":{"code":"unauthorized"}}""")
        }
        assertEquals(DialRequests.FetchOneResult.HttpFailure(401), result)
    }

    @Test
    fun `fetchOne surfaces 409 replay and 410 expiry distinctly`() {
        val conflict = DialRequests.fetchOne("https://x.test", "tok", "d-1") { _ ->
            FakeConnection(URL("https://x.test/"), 409, """{"error":{"code":"conflict"}}""")
        }
        assertEquals(DialRequests.FetchOneResult.HttpFailure(409), conflict)
        val gone = DialRequests.fetchOne("https://x.test", "tok", "d-1") { _ ->
            FakeConnection(URL("https://x.test/"), 410, """{"error":{"code":"gone"}}""")
        }
        assertEquals(DialRequests.FetchOneResult.HttpFailure(410), gone)
    }

    @Test
    fun `fetchOne maps transport failure to NetworkError`() {
        val result = DialRequests.fetchOne("https://x.test", "tok", "d-1") { _ ->
            FakeConnection(URL("https://x.test/"), 200, failOnCode = true)
        }
        assertEquals(DialRequests.FetchOneResult.NetworkError, result)
    }

    @Test
    fun `fetchOne maps garbage body to ParseError`() {
        val result = DialRequests.fetchOne("https://x.test", "tok", "d-1") { _ ->
            FakeConnection(URL("https://x.test/"), 200, "not json {{{")
        }
        assertEquals(DialRequests.FetchOneResult.ParseError, result)
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
