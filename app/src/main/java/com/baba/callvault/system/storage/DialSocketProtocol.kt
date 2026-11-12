/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Wire contract for the desktop click-to-dial WebSocket.
 *
 * The server signal carries an id, never a phone number. Every
 * message therefore leads to one authenticated `GET
 * /api/v1/dial-requests/{id}` before anything can reach the dialer
 * — a forged frame can at most make the phone ask the server about
 * an id it is not allowed to see.
 *
 * Pure and unit-tested: parsing, URL construction, reconnect policy.
 */
internal object DialSocketProtocol {

    data class Event(
        val eventId: String,
        val requestId: String,
        val expiresAt: String,
    )

    private val UUID_RE =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private const val MAX_MESSAGE_CHARS = 4096

    /**
     * `https://host[:port]` → `wss://host[:port]/ws/dial?device_id=…`.
     * Only HTTPS bases are accepted, matching [WacrmApi].
     */
    fun webSocketUrl(baseUrl: String?, deviceId: String): String? {
        val raw = baseUrl?.trim().orEmpty()
        if (raw.isEmpty() || deviceId.isBlank()) return null
        return runCatching {
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            // Debug builds may use cleartext loopback (adb reverse) to test
            // the pipeline locally; release builds are wss-only.
            val loopback = host == "127.0.0.1" || host == "localhost" || host == "10.0.2.2"
            val wsScheme = when {
                scheme == "https" -> "wss"
                com.baba.callvault.BuildConfig.DEBUG && scheme == "http" && loopback -> "ws"
                else -> return null
            }
            val defaultPort =
                (wsScheme == "wss" && uri.port == 443) ||
                    (wsScheme == "ws" && uri.port == 80)
            val port = if (uri.port == -1 || defaultPort) "" else ":${uri.port}"
            val device = URLEncoder.encode(deviceId, "UTF-8")
            "$wsScheme://$host$port/ws/dial?device_id=$device"
        }.getOrNull()
    }

    /** Parses a server frame; null for `ready`, malformed, oversized, or unsafe ids. */
    fun parse(text: String?): Event? {        if (text.isNullOrBlank() || text.length > MAX_MESSAGE_CHARS) return null
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (obj.optString("type") != "dial_request") return null
        val requestId = obj.optString("request_id").trim()
        if (!UUID_RE.matches(requestId)) return null
        return Event(
            eventId = obj.optString("event_id").trim(),
            requestId = requestId,
            expiresAt = obj.optString("expires_at").trim(),
        )
    }

    /**
     * True only for the server's remote-sign-out push
     * (`{"type":"logout_device"}`). Same size discipline as every
     * other frame: oversized or malformed input is never a logout.
     * Pure — unit tested.
     *
     * Compat only: this answers "is it a logout frame", NOT "is it
     * for this phone". The socket handler must use
     * [parseLogoutDevice] + [isLogoutTargeted] and wipe solely on a
     * targeted match — a bare or cross-device frame is ignored.
     */
    fun isLogoutDevice(text: String?): Boolean {
        if (text.isNullOrBlank() || text.length > MAX_MESSAGE_CHARS) return false
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return false
        return obj.optString("type") == "logout_device"
    }

    /**
     * Targeted logout frame: `{"type":"logout_device",
     * "device_id":"…","session_id":"…"}`. Returns the
     * `(deviceId, sessionId)` pair, or null for anything else —
     * wrong type, missing/blank ids, malformed JSON, oversized —
     * which the caller must ignore (duplicate/cross-device safe).
     * Same size discipline as every other frame. Pure — unit tested.
     */
    fun parseLogoutDevice(text: String?): Pair<String, String>? {
        if (text.isNullOrBlank() || text.length > MAX_MESSAGE_CHARS) return null
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (obj.optString("type") != "logout_device") return null
        val deviceId = obj.optString("device_id").trim()
        val sessionId = obj.optString("session_id").trim()
        if (deviceId.isEmpty() || sessionId.isEmpty()) return null
        return deviceId to sessionId
    }

    /**
     * Wipe gate for a logout frame: true if and only if the frame's
     * device id equals this install's id AND the frame's session id
     * equals the current stored session's session id. Anything else —
     * cross-device pushes, stale-session replays, duplicates after
     * the wipe cleared the session (which surfaces as a null local
     * id) — is ignored. Pure — unit tested.
     */
    fun isLogoutTargeted(
        frameDeviceId: String,
        frameSessionId: String,
        localDeviceId: String?,
        localSessionId: String?,
    ): Boolean {
        if (frameDeviceId.isBlank() || frameSessionId.isBlank()) return false
        if (localDeviceId.isNullOrBlank() || localSessionId.isNullOrBlank()) return false
        return frameDeviceId == localDeviceId && frameSessionId == localSessionId
    }

    /**
     * Our acknowledgement of a targeted logout:
     * `{"type":"logout_ack","device_id":"…","session_id":"…"}`.
     * Sent on the socket after the HTTP ack and before the wipe.
     * Pure — unit tested.
     */
    fun buildLogoutAck(deviceId: String, sessionId: String): String =
        JSONObject()
            .put("type", "logout_ack")
            .put("device_id", deviceId)
            .put("session_id", sessionId)
            .toString()

    /**
     * Outcome of the server-verified consume reply to our own
     * `received` ack. A `consume` carries the validated number and
     * TTL; a `consume_rejected` (or anything else) means "fall back
     * to the authenticated GET". Numbers in any other frame type are
     * ignored — only a consume addressed to a request we just
     * claimed may choose what is dialled.
     */
    sealed interface ConsumeOutcome {
        data class Consumed(val requestId: String, val phoneNumber: String, val expiresAt: String) : ConsumeOutcome
        data class Rejected(val requestId: String) : ConsumeOutcome
    }

    fun parseConsume(text: String?): ConsumeOutcome? {
        if (text.isNullOrBlank() || text.length > MAX_MESSAGE_CHARS) return null
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return when (obj.optString("type")) {
            "consume" -> {
                val requestId = obj.optString("request_id").trim()
                val phone = obj.optString("phone_number").trim()
                val expiresAt = obj.optString("expires_at").trim()
                if (!UUID_RE.matches(requestId)) return null
                if (phone.isEmpty() || expiresAt.isEmpty()) return null
                ConsumeOutcome.Consumed(requestId, phone, expiresAt)
            }
            "consume_rejected" -> {
                val requestId = obj.optString("request_id").trim()
                if (!UUID_RE.matches(requestId)) return null
                ConsumeOutcome.Rejected(requestId)
            }
            else -> null
        }
    }

    /**
     * Exponential backoff, capped at one minute. [jitterMs] is
     * injectable so tests are deterministic; production passes a
     * small random value to avoid reconnect herds.
     */
    fun reconnectDelayMs(attempt: Int, jitterMs: Long = 0L): Long {
        val safeAttempt = attempt.coerceIn(0, 6)
        val base = 1_000L shl safeAttempt
        return (base + jitterMs.coerceAtLeast(0L)).coerceAtMost(60_000L)
    }

    /**
     * 4001 = another device took over, 4002 = this device's newer
     * socket superseded this one: reconnecting would fight the live
     * connection. 1000 = deliberate local stop. Everything else is
     * transient (including 4401, which triggers one token refresh).
     */
    fun shouldReconnect(closeCode: Int): Boolean =
        closeCode != 1000 && closeCode != 4001 && closeCode != 4002
}
