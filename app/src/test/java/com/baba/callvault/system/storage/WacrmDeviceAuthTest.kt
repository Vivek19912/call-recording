/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/**
 * Device identity for server-driven sign-out ([WacrmDeviceAuth],
 * [DialSocketProtocol.isLogoutDevice]).
 *
 * Loopback HttpServer fixtures — no network, no secrets (tokens
 * below are shaped fakes). Verifies: the session id decodes from
 * the access JWT (and nothing else does), the `logout_device` push
 * parses, the registration body carries ids but never tokens, and
 * only an explicit `revoked == true` arms the wipe.
 *
 * Pure JVM: [org.json] resolves to the reference implementation via
 * testImplementation (android.jar only ships stubs). Robolectric is
 * deliberately NOT used — its sandbox does not initialise in this
 * environment (pre-existing failures, e.g. GitHubReleasesTest).
 */
class WacrmDeviceAuthTest {

    private fun b64url(raw: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.toByteArray(Charsets.UTF_8))

    private fun jwt(payload: String): String {
        val header = b64url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}")
        return header + "." + b64url(payload) + ".fixture-signature"
    }

    // --- parseSessionId -----------------------------------------------------

    @Test
    fun `session id decodes from the access jwt payload`() {
        assertEquals(
            "sess-abc-123",
            WacrmDeviceAuth.parseSessionId(jwt("""{"sub":"u-1","session_id":"sess-abc-123"}""")),
        )
    }

    @Test
    fun `session id parse is null on malformed tokens, never partial`() {
        assertNull(WacrmDeviceAuth.parseSessionId(null))
        assertNull(WacrmDeviceAuth.parseSessionId(""))
        assertNull(WacrmDeviceAuth.parseSessionId("only-two.parts"))
        assertNull(WacrmDeviceAuth.parseSessionId("a.b.c.d"))
        assertNull(WacrmDeviceAuth.parseSessionId("not-a-jwt"))
        // Valid JWT shape, no session_id claim: creates nothing.
        assertNull(WacrmDeviceAuth.parseSessionId(jwt("""{"sub":"u-1"}""")))
        // Blank claim is unusable.
        assertNull(WacrmDeviceAuth.parseSessionId(jwt("""{"session_id":"  "}""")))
        // Bad base64 payload.
        assertNull(WacrmDeviceAuth.parseSessionId("eyJhIjox.%%%.sig"))
        // Non-JSON payload.
        assertNull(WacrmDeviceAuth.parseSessionId(b64url("{}") + "." + b64url("plain") + ".sig"))
    }

    // --- logout_device frame ------------------------------------------------

    @Test
    fun `logout device frame parses, everything else does not`() {
        assertTrue(DialSocketProtocol.isLogoutDevice("""{"type":"logout_device"}"""))
        assertFalse(DialSocketProtocol.isLogoutDevice("""{"type":"ready"}"""))
        assertFalse(
            DialSocketProtocol.isLogoutDevice(
                """{"type":"dial_request","request_id":"123e4567-e89b-12d3-a456-426614174000"}"""
            )
        )
        assertFalse(DialSocketProtocol.isLogoutDevice(null))
        assertFalse(DialSocketProtocol.isLogoutDevice(""))
        assertFalse(DialSocketProtocol.isLogoutDevice("not json"))
        // Oversized input is never a logout (same discipline as other frames).
        assertFalse(DialSocketProtocol.isLogoutDevice("x".repeat(5000)))
        // A padded oversized logout body is still not a logout.
        assertFalse(
            DialSocketProtocol.isLogoutDevice(
                """{"type":"logout_device","pad":"${"y".repeat(5000)}"}"""
            )
        )
    }

    // --- device headers -----------------------------------------------------

    @Test
    fun `device header carries the install id`() {
        val headers = WacrmDeviceAuth.deviceHeaders("device-id-fixture")
        assertEquals("device-id-fixture", headers[WacrmDeviceAuth.DEVICE_ID_HEADER])
        assertEquals("X-CallVault-Device-Id", WacrmDeviceAuth.DEVICE_ID_HEADER)
    }

    // --- registration -------------------------------------------------------

    @Test
    fun `registration body carries ids and facts, never tokens`() {
        val access = "access.token.fixture"
        val refresh = "refresh.token.fixture"
        val body = WacrmDeviceAuth.buildRegisterBody(
            deviceId = "device-1",
            sessionId = "sess-1",
            deviceName = "Maker Model",
            deviceModel = "Model",
            appVersion = "2.4.5",
        )
        assertTrue(body.contains("device-1"))
        assertTrue(body.contains("sess-1"))
        assertTrue(body.contains("Maker Model"))
        assertTrue(body.contains("Model"))
        assertTrue(body.contains("2.4.5"))
        // The builder never receives tokens, so it cannot leak them.
        assertFalse(body.contains(access))
        assertFalse(body.contains(refresh))
    }

    @Test
    fun `registration posts ids with bearer auth and succeeds on 2xx`() {
        val seen = mutableMapOf<String, String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/auth/callvault-devices/register") { exchange ->
            seen["auth"] = exchange.requestHeaders.getFirst("Authorization")
            seen["device"] = exchange.requestHeaders.getFirst(WacrmDeviceAuth.DEVICE_ID_HEADER)
            seen["body"] = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            val bytes = """{"ok":true}""".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val body = WacrmDeviceAuth.buildRegisterBody(
                deviceId = "device-9",
                sessionId = "sess-9",
                deviceName = "Maker Model",
                deviceModel = "Model",
                appVersion = "2.4.5",
            )
            val ok = WacrmDeviceAuth.registerDevice(
                base,
                "access.token.fixture",
                "device-9",
                body,
                openConnection = { url ->
                    java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
                },
            )
            assertTrue(ok)
            assertEquals("Bearer access.token.fixture", seen["auth"])
            assertEquals("device-9", seen["device"])
            assertTrue((seen["body"] ?: "").contains("device-9"))
            assertTrue((seen["body"] ?: "").contains("sess-9"))
            // Tokens travel as the Bearer header only — never in the JSON body.
            assertFalse((seen["body"] ?: "").contains("access.token.fixture"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `registration failure is false, never a throw`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/auth/callvault-devices/register") { exchange ->
            val bytes = """{"error":"boom"}""".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(500, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertFalse(
                WacrmDeviceAuth.registerDevice(
                    base,
                    "access.token.fixture",
                    "device-9",
                    "{}",
                    openConnection = { url ->
                        java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
                    },
                )
            )
            // Unreachable host: also false, never a throw.
            assertFalse(
                WacrmDeviceAuth.registerDevice(
                    "http://127.0.0.1:1",
                    "access.token.fixture",
                    "device-9",
                    "{}",
                    openConnection = { url ->
                        val c = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 500
                        c.readTimeout = 500
                        c
                    },
                )
            )
        } finally {
            server.stop(0)
        }
    }

    // --- targeted logout_device frame -----------------------------------------

    @Test
    fun `targeted logout frame parses to device and session ids`() {
        val parsed = DialSocketProtocol.parseLogoutDevice(
            """{"type":"logout_device","device_id":"device-1","session_id":"sess-1"}"""
        )
        assertEquals("device-1" to "sess-1", parsed)
    }

    @Test
    fun `untargeted logout frames parse to null and are never a wipe`() {
        // Bare frame (compat shape): still a logout frame, but with no
        // targeting it can never match — the handler ignores it.
        assertTrue(DialSocketProtocol.isLogoutDevice("""{"type":"logout_device"}"""))
        assertNull(DialSocketProtocol.parseLogoutDevice("""{"type":"logout_device"}"""))
        // Missing or blank ids: ignore.
        assertNull(DialSocketProtocol.parseLogoutDevice("""{"type":"logout_device","device_id":"d"}"""))
        assertNull(DialSocketProtocol.parseLogoutDevice("""{"type":"logout_device","session_id":"s"}"""))
        assertNull(DialSocketProtocol.parseLogoutDevice("""{"type":"logout_device","device_id":"  ","session_id":"s"}"""))
        assertNull(DialSocketProtocol.parseLogoutDevice("""{"type":"logout_device","device_id":"d","session_id":""}"""))
        // Wrong type, malformed, oversized: never a logout target.
        assertNull(DialSocketProtocol.parseLogoutDevice("""{"type":"ready"}"""))
        assertNull(
            DialSocketProtocol.parseLogoutDevice(
                """{"type":"dial_request","request_id":"123e4567-e89b-12d3-a456-426614174000"}"""
            )
        )
        assertNull(DialSocketProtocol.parseLogoutDevice("not json"))
        assertNull(DialSocketProtocol.parseLogoutDevice(null))
        assertNull(DialSocketProtocol.parseLogoutDevice(""))
        assertNull(DialSocketProtocol.parseLogoutDevice("x".repeat(5000)))
        assertNull(
            DialSocketProtocol.parseLogoutDevice(
                """{"type":"logout_device","device_id":"d","session_id":"s","pad":"${"y".repeat(5000)}"}"""
            )
        )
    }

    @Test
    fun `logout wipe gate matches device and session exactly`() {
        // Exact match: the only shape that wipes.
        assertTrue(DialSocketProtocol.isLogoutTargeted("d-1", "s-1", "d-1", "s-1"))
        // Cross-device push and stale-session replay: ignored.
        assertFalse(DialSocketProtocol.isLogoutTargeted("d-2", "s-1", "d-1", "s-1"))
        assertFalse(DialSocketProtocol.isLogoutTargeted("d-1", "s-2", "d-1", "s-1"))
        assertFalse(DialSocketProtocol.isLogoutTargeted("d-2", "s-2", "d-1", "s-1"))
        // No local session (signed out, or a duplicate arriving after
        // the wipe cleared the store): never a wipe.
        assertFalse(DialSocketProtocol.isLogoutTargeted("d-1", "s-1", "d-1", null))
        assertFalse(DialSocketProtocol.isLogoutTargeted("d-1", "s-1", null, "s-1"))
        assertFalse(DialSocketProtocol.isLogoutTargeted("d-1", "s-1", null, null))
        assertFalse(DialSocketProtocol.isLogoutTargeted("d-1", "s-1", "", "s-1"))
        // Blank frame ids: never a wipe.
        assertFalse(DialSocketProtocol.isLogoutTargeted("", "s-1", "d-1", "s-1"))
        assertFalse(DialSocketProtocol.isLogoutTargeted("d-1", "", "d-1", "s-1"))
    }

    @Test
    fun `duplicate targeted frames gate identically`() {
        // Gating is a pure comparison: re-delivery of the same frame
        // decides the same way every time (post-wipe duplicates carry
        // a session the cleared store no longer holds, so they miss).
        repeat(2) {
            assertTrue(DialSocketProtocol.isLogoutTargeted("d-1", "s-1", "d-1", "s-1"))
            assertFalse(DialSocketProtocol.isLogoutTargeted("d-9", "s-1", "d-1", "s-1"))
        }
    }

    @Test
    fun `logout ack frame carries type and both ids`() {
        val ack = DialSocketProtocol.buildLogoutAck("device-1", "sess-1")
        val obj = org.json.JSONObject(ack)
        assertEquals("logout_ack", obj.optString("type"))
        assertEquals("device-1", obj.optString("device_id"))
        assertEquals("sess-1", obj.optString("session_id"))
    }

    // --- logout ack POST ------------------------------------------------------

    @Test
    fun `logout ack posts device id with bearer auth`() {
        val seen = mutableMapOf<String, String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/auth/callvault-devices/ack") { exchange ->
            seen["auth"] = exchange.requestHeaders.getFirst("Authorization")
            seen["device"] = exchange.requestHeaders.getFirst(WacrmDeviceAuth.DEVICE_ID_HEADER)
            seen["body"] = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            val bytes = """{"ok":true}""".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val ok = WacrmDeviceAuth.postLogoutAck(
                base,
                "access.token.fixture",
                "device-9",
                openConnection = { url ->
                    java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
                },
            )
            assertTrue(ok)
            assertEquals("Bearer access.token.fixture", seen["auth"])
            assertEquals("device-9", seen["device"])
            assertTrue((seen["body"] ?: "").contains("device-9"))
            // The token travels as the Bearer header only — never in the body.
            assertFalse((seen["body"] ?: "").contains("access.token.fixture"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `logout ack is best effort on failure, never a throw`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/auth/callvault-devices/ack") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val bytes = """{"error":"boom"}""".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(500, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val open: (String) -> java.net.HttpURLConnection = { url ->
                java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
            }
            // Server 500: false, no throw, and nothing is wiped here
            // (this function never touches the session store).
            assertFalse(WacrmDeviceAuth.postLogoutAck(base, "access.token.fixture", "device-9", open))
            // Unreachable host: also false, never a throw.
            assertFalse(
                WacrmDeviceAuth.postLogoutAck(
                    "http://127.0.0.1:1",
                    "access.token.fixture",
                    "device-9",
                    openConnection = { url ->
                        val c = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 500
                        c.readTimeout = 500
                        c
                    },
                )
            )
        } finally {
            server.stop(0)
        }
    }

    // --- 503 registry outage --------------------------------------------------

    @Test
    fun `status 503 is null transient, never a wipe`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/auth/callvault-devices/status") { exchange ->
            val bytes = """{"error":"registry unavailable"}""".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(503, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val revoked = WacrmDeviceAuth.fetchRevoked(
                base,
                "access.token.fixture",
                "device-9",
                openConnection = { url ->
                    java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
                },
            )
            assertNull(revoked)
            // Null (could not tell) is never a wipe.
            assertFalse(WacrmDeviceAuth.shouldRemoteWipe(revoked))
        } finally {
            server.stop(0)
        }
    }

    // --- status -------------------------------------------------------------

    @Test
    fun `only an explicit revoked true arms the wipe`() {
        assertEquals(true, WacrmDeviceAuth.parseStatusRevoked("""{"revoked":true}"""))
        assertEquals(false, WacrmDeviceAuth.parseStatusRevoked("""{"revoked":false}"""))
        // Absent or wrongly typed: null (do nothing), never a wipe.
        assertNull(WacrmDeviceAuth.parseStatusRevoked("""{"ok":true}"""))
        assertNull(WacrmDeviceAuth.parseStatusRevoked("""{"revoked":"yes"}"""))
        assertNull(WacrmDeviceAuth.parseStatusRevoked("not json"))
        assertNull(WacrmDeviceAuth.parseStatusRevoked(null))
        assertNull(WacrmDeviceAuth.parseStatusRevoked(""))

        assertTrue(WacrmDeviceAuth.shouldRemoteWipe(true))
        assertFalse(WacrmDeviceAuth.shouldRemoteWipe(false))
        // Network failure / non-2xx / unparseable: never wipe.
        assertFalse(WacrmDeviceAuth.shouldRemoteWipe(null))
    }

    @Test
    fun `status check reads revoked over loopback with device auth`() {
        val seen = mutableMapOf<String, String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var response = """{"revoked":true}"""
        server.createContext("/api/auth/callvault-devices/status") { exchange ->
            seen["auth"] = exchange.requestHeaders.getFirst("Authorization")
            seen["device"] = exchange.requestHeaders.getFirst(WacrmDeviceAuth.DEVICE_ID_HEADER)
            seen["query"] = exchange.requestURI.query
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val open: (String) -> java.net.HttpURLConnection = { url ->
                java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
            }
            assertEquals(true, WacrmDeviceAuth.fetchRevoked(base, "access.token.fixture", "device-9", open))
            assertEquals("Bearer access.token.fixture", seen["auth"])
            assertEquals("device-9", seen["device"])
            assertTrue((seen["query"] ?: "").contains("device_id=device-9"))

            response = """{"revoked":false}"""
            assertEquals(false, WacrmDeviceAuth.fetchRevoked(base, "access.token.fixture", "device-9", open))

            response = "not json"
            assertNull(WacrmDeviceAuth.fetchRevoked(base, "access.token.fixture", "device-9", open))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `status check is null on server error, never a throw`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/auth/callvault-devices/status") { exchange ->
            val bytes = """{"error":"boom"}""".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(500, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertNull(
                WacrmDeviceAuth.fetchRevoked(
                    base,
                    "access.token.fixture",
                    "device-9",
                    openConnection = { url ->
                        java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
                    },
                )
            )
        } finally {
            server.stop(0)
        }
    }
}
