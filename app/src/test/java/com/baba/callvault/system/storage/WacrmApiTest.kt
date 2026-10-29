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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * The WACRM upload contract, pinned without touching the network (except loopback): URL safety,
 * container→server MIME mapping, multipart shape, and the 201-only success rule.
 *
 * The API key in these tests is a fake (`wacrm_live_test…`) — never a real secret. What matters is
 * *where* it travels (header only), not its value.
 */
class WacrmApiTest {

    // -------- URL validation: HTTPS only --------

    @Test
    fun `https urls normalize to scheme plus host`() {
        assertEquals("https://whatsappmax.in", WacrmApi.normalizeBaseUrl("https://whatsappmax.in"))
        assertEquals("https://whatsappmax.in", WacrmApi.normalizeBaseUrl("  https://whatsappmax.in/  "))
        assertEquals("https://example.com:8443", WacrmApi.normalizeBaseUrl("https://example.com:8443/api"))
    }

    @Test
    fun `http and non-url input are rejected`() {
        assertNull(WacrmApi.normalizeBaseUrl("http://whatsappmax.in"))
        assertNull(WacrmApi.normalizeBaseUrl("HTTP://whatsappmax.in"))
        assertNull(WacrmApi.normalizeBaseUrl("whatsappmax.in"))
        assertNull(WacrmApi.normalizeBaseUrl(""))
        assertNull(WacrmApi.normalizeBaseUrl(null))
        assertNull(WacrmApi.normalizeBaseUrl("https://"))
        assertNull(WacrmApi.normalizeBaseUrl("ftp://example.com/x"))
    }

    // -------- MIME mapping: container truth, never the codec MIME --------

    @Test
    fun `ogg maps to audio ogg regardless of container mime`() {
        assertEquals("audio/ogg", WacrmApi.mimeForUpload("20260101_120000.000+0000_out_Mum.ogg", "audio/opus"))
        assertEquals("audio/ogg", WacrmApi.mimeForUpload("call.OGG", null))
    }

    @Test
    fun `m4a maps to audio mp4`() {
        assertEquals("audio/mp4", WacrmApi.mimeForUpload("20260101_120000.000+0000_out_Mum.m4a", "audio/mp4a-latm"))
    }

    @Test
    fun `unknown containers fall back to the container mime only when the server accepts it`() {
        assertEquals("audio/webm", WacrmApi.mimeForUpload("call.bin", "audio/webm"))
        assertEquals("audio/mpeg", WacrmApi.mimeForUpload("call.mp3", "audio/mpeg"))
        assertNull(WacrmApi.mimeForUpload("call.bin", "audio/mp4a-latm"))
        assertNull(WacrmApi.mimeForUpload("call.bin", null))
    }

    // -------- Multipart shape --------

    @Test
    fun `multipart body carries fields plus the audio bytes verbatim`() {
        val audio = byteArrayOf(0x4F, 0x67, 0x67, 0x53, 0x00, 0x01, 0x02, 0x03)
        val out = ByteArrayOutputStream()
        WacrmApi.writeMultipartBody(
            output = out,
            boundary = "testboundary",
            fields = mapOf("recorded_at" to "2026-01-01T00:00:00Z", "duration_seconds" to "187", "file_name" to "call.ogg"),
            fileName = "call.ogg",
            fileMime = "audio/ogg",
            audio = ByteArrayInputStream(audio),
        )
        val body = out.toByteArray()
        val text = String(body, StandardCharsets.UTF_8)
        assertTrue(text.contains("name=\"recorded_at\""))
        assertTrue(text.contains("2026-01-01T00:00:00Z"))
        assertTrue(text.contains("name=\"duration_seconds\""))
        assertTrue(text.contains("name=\"audio\"; filename=\"call.ogg\""))
        assertTrue(text.contains("Content-Type: audio/ogg"))
        assertTrue(text.endsWith("--testboundary--\r\n"))
        // The audio bytes survive the trip byte-for-byte.
        assertTrue(body.toList().windowed(audio.size, 1).any { it == audio.toList() })
    }

    // -------- End-to-end against loopback: headers, status mapping --------

    private fun serve(status: Int, body: String, seen: MutableMap<String, String?>): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/recordings") { exchange ->
            seen["authorization"] = exchange.requestHeaders.getFirst("Authorization")
            seen["contentType"] = exchange.requestHeaders.getFirst("Content-Type")
            seen["body"] = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        return server
    }

    @Test
    fun `upload sends bearer auth and multipart audio and reads 201`() {
        val seen = mutableMapOf<String, String?>()
        val server = serve(201, """{"data":{"id":"abc"}}""", seen)
        try {
            val result = WacrmApi.postRecording(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                bearerCredential = "wacrm_live_test",
                audio = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                fileName = "call.ogg",
                fileMime = "audio/ogg",
                fields = mapOf("file_name" to "call.ogg"),
            )
            assertEquals(201, result.statusCode)
            assertEquals("Bearer wacrm_live_test", seen["authorization"])
            assertTrue((seen["contentType"] ?: "").startsWith("multipart/form-data; boundary="))
            assertTrue((seen["body"] ?: "").contains("name=\"audio\"; filename=\"call.ogg\""))
            // The key is a header value, never part of the URL or the body.
            assertFalse((seen["body"] ?: "").contains("wacrm_live_test"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `non-201 statuses propagate for the worker to classify`() {
        for (status in listOf(400, 401, 500)) {
            val seen = mutableMapOf<String, String?>()
            val server = serve(status, """{"error":"nope"}""", seen)
            try {
                val result = WacrmApi.postRecording(
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    bearerCredential = "wacrm_live_test",
                    audio = ByteArrayInputStream(byteArrayOf(1)),
                    fileName = "call.ogg",
                    fileMime = "audio/ogg",
                    fields = emptyMap(),
                )
                assertEquals(status, result.statusCode)
            } finally {
                server.stop(0)
            }
        }
    }
}
