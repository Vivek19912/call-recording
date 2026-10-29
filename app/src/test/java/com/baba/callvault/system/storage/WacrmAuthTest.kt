/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import com.sun.net.httpserver.HttpServer
import com.baba.callvault.services.call.PhoneRecordingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/**
 * WhatsApp Max user authentication over GoTrue (Phase 2: sessions replace API keys).
 *
 * Loopback HttpServer fixtures — no network, no secrets (tokens below are shaped fakes).
 * Verifies: valid credentials connect, bad credentials are rejected without distinction,
 * tokens parse, expiry math holds, and /me identity parses.
 *
 * Pure JVM: [org.json] resolves to the reference implementation via testImplementation
 * (android.jar only ships stubs). Robolectric is deliberately NOT used — its sandbox does
 * not initialise in this environment (pre-existing failures, e.g. GitHubReleasesTest).
 */
class WacrmAuthTest {

    private fun serve(
        paths: Map<String, Pair<Int, String>>,
        seen: MutableMap<String, String?> = mutableMapOf(),
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        for ((path, response) in paths) {
            val (status, body) = response
            server.createContext(path) { exchange ->
                seen["${exchange.requestMethod} $path auth"] =
                    exchange.requestHeaders.getFirst("Authorization")
                seen["${exchange.requestMethod} $path apikey"] =
                    exchange.requestHeaders.getFirst("apikey")
                seen["${exchange.requestMethod} $path body"] =
                    exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
        }
        server.start()
        return server
    }

    private fun config(port: Int) =
        WacrmAuth.MobileConfig("http://127.0.0.1:$port", "anon-key-fixture")

    @Test
    fun `valid credentials connect with parsed session`() {
        val seen = mutableMapOf<String, String?>()
        val server = serve(
            mapOf(
                "/auth/v1/token" to (200 to
                    """{"access_token":"aaa.bbb.ccc","refresh_token":"rrr.eee.fff","expires_in":3600,"user":{"id":"u-1","email":"sagar@example.com"}}"""),
            ),
            seen,
        )
        try {
            val outcome = WacrmAuth.signIn(
                config(server.address.port),
                "sagar@example.com",
                "secret",
                openConnection = { url ->
                    java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
                },
            )
            check(outcome is WacrmAuth.AuthOutcome.Ok)
            assertEquals("aaa.bbb.ccc", outcome.session.accessToken)
            assertEquals("rrr.eee.fff", outcome.session.refreshToken)
            assertEquals("u-1", outcome.session.userId)
            assertEquals("sagar@example.com", outcome.session.userEmail)
            // Password travels in the TLS body only — never in headers or URL.
            assertTrue((seen["POST /auth/v1/token body"] ?: "").contains("sagar@example.com"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `invalid password is rejected without distinction`() {
        val server = serve(
            mapOf("/auth/v1/token" to (400 to """{"error":"invalid_grant","msg":"bad"}""")),
        )
        try {
            val outcome = WacrmAuth.signIn(config(server.address.port), "sagar@example.com", "wrong", openConnection = { url ->
                java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
            })
            check(outcome is WacrmAuth.AuthOutcome.InvalidCredentials)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `expired session refreshes with new tokens`() {
        val seen = mutableMapOf<String, String?>()
        val server = serve(
            mapOf(
                "/auth/v1/token" to (200 to
                    """{"access_token":"new.aaa.bbb","refresh_token":"new.rrr.eee","expires_in":3600,"user":{"id":"u-1"}}"""),
            ),
            seen,
        )
        try {
            val outcome = WacrmAuth.refresh(
                config(server.address.port),
                "old.refresh.token",
                openConnection = { url ->
                    java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
                },
            )
            check(outcome is WacrmAuth.AuthOutcome.Ok)
            assertEquals("new.aaa.bbb", outcome.session.accessToken)
            assertTrue((seen["POST /auth/v1/token body"] ?: "").contains("old.refresh.token"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `rejected refresh means reconnect, and malformed bodies are server errors`() {
        val server = serve(
            mapOf("/auth/v1/token" to (401 to """{"error":"invalid_grant"}""")),
        )
        try {
            val outcome = WacrmAuth.refresh(config(server.address.port), "dead.token.here", openConnection = { url ->
                java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
            })
            check(outcome is WacrmAuth.AuthOutcome.InvalidCredentials)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `expiry math and refresh skew behave`() {
        assertEquals(3_600_000L + 1_000L, WacrmAuth.expiryAtMs(3600L, 1_000L))
        // Fresh token: no refresh.
        assertEquals(false, WacrmAuth.shouldRefresh(1_000L + 3_600_000L, 1_000L))
        // Inside the skew window: refresh.
        assertEquals(true, WacrmAuth.shouldRefresh(1_000L + 30_000L, 1_000L))
        // Expired: refresh.
        assertEquals(true, WacrmAuth.shouldRefresh(500L, 1_000L))
    }

    @Test
    fun `unparseable session bodies are null, never partial secrets`() {
        assertNull(WacrmAuth.parseSession("not json", 0L))
        assertNull(WacrmAuth.parseSession("""{"access_token":"only"}""", 0L))
        assertNull(WacrmAuth.parseMobileConfig("""{"supabaseUrl":"x"}"""))
        assertNull(WacrmAuth.parseMe("""{"user":null}"""))
    }

    @Test
    fun `me endpoint identity parses for the connect screen`() {
        val me = WacrmAuth.parseMe(
            """{"user":{"id":"u-1","email":"sagar@example.com","fullName":"Sagar"},"account":{"id":"a-1","name":"Acme"},"role":"agent"}"""
        )!!
        assertEquals("u-1", me.userId)
        assertEquals("sagar@example.com", me.userEmail)
        assertEquals("Sagar", me.fullName)
        assertEquals("a-1", me.accountId)
        assertEquals("Acme", me.accountName)
        assertEquals("agent", me.role)
    }

    @Test
    fun `phone policy parses strictly, unknown stays unknown`() {
        assertEquals(
            PhoneRecordingSource.SIM2,
            WacrmAuth.parsePhoneSource("""{"phoneRecordingSource":"sim2"}"""),
        )
        assertEquals(
            PhoneRecordingSource.SIM1,
            WacrmAuth.parsePhoneSource("""{"phoneRecordingSource":" SIM1 "}"""),
        )
        assertEquals(
            PhoneRecordingSource.NONE,
            WacrmAuth.parsePhoneSource("""{"phoneRecordingSource":"none"}"""),
        )
        // Absent (older deployment) or unknown: null, so the caller
        // keeps the last-known policy instead of flipping recording.
        assertNull(WacrmAuth.parsePhoneSource("""{"whatsappRecordingSource":"whatsapp"}"""))
        assertNull(WacrmAuth.parsePhoneSource("""{"phoneRecordingSource":"slot1"}"""))
        assertNull(WacrmAuth.parsePhoneSource("""{"phoneRecordingSource":1}"""))
        assertNull(WacrmAuth.parsePhoneSource("not json"))
    }

    @Test
    fun `owner phone number parses to normalized digits`() {
        assertEquals(
            "918953065369",
            WacrmAuth.parsePhoneNumber("""{"phoneRecordingNumber":"918953065369"}"""),
        )
        assertEquals(
            "918953065369",
            WacrmAuth.parsePhoneNumber("""{"phoneRecordingNumber":"+91 89530 65369"}"""),
        )
        // Absent, null, blank, or malformed: null (caller decides
        // keep-last vs. explicit-None via hasPhoneNumber).
        assertNull(WacrmAuth.parsePhoneNumber("""{"whatsappRecordingSource":"whatsapp"}"""))
        assertNull(WacrmAuth.parsePhoneNumber("""{"phoneRecordingNumber":null}"""))
        assertNull(WacrmAuth.parsePhoneNumber("""{"phoneRecordingNumber":""}"""))
        assertNull(WacrmAuth.parsePhoneNumber("""{"phoneRecordingNumber":"abc"}"""))
        assertNull(WacrmAuth.parsePhoneNumber("""{"phoneRecordingNumber":"123"}"""))
        assertNull(WacrmAuth.parsePhoneNumber("not json"))
    }

    @Test
    fun `phone number presence distinguishes explicit none from older servers`() {
        // Explicit null = owner set None → authoritative clear.
        assertEquals(true, WacrmAuth.hasPhoneNumber("""{"phoneRecordingNumber":null}"""))
        assertEquals(true, WacrmAuth.hasPhoneNumber("""{"phoneRecordingNumber":"918953065369"}"""))
        // Absent = older server → keep last-known number.
        assertEquals(false, WacrmAuth.hasPhoneNumber("""{"phoneRecordingSource":"sim1"}"""))
        assertEquals(false, WacrmAuth.hasPhoneNumber("not json"))
    }
}
