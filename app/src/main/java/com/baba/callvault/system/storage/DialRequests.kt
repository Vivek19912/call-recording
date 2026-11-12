package com.baba.callvault.system.storage

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import com.baba.callvault.utils.AppLogger
import org.json.JSONObject

/**
 * Click-to-dial handoff client.
 *
 * Delivery model: one authenticated WebSocket (see [DialSocketClient]).
 * The socket carries a request id only; this object performs the
 * authoritative one-shot read of `GET {base}/api/v1/dial-requests/{id}`
 * with the WhatsApp Max user token (same bearer discipline as
 * [WacrmApi]: header only, HTTPS only, never logged). The server
 * returns only requests created by the logged-in user
 * (`requested_by`), so one employee's phone never sees another's.
 *
 * There is no list polling anywhere in this path: [fetchOne] is
 * called once per socket signal, never in a loop.
 *
 * Never uses ACTION_CALL, never places calls silently or from the
 * background. Recording / upload / call-event behavior is untouched:
 * a dial request is not proof of a call, and this client never
 * creates one.
 *
 * Failure contract: every failure is a typed [FetchOneResult], never
 * a silent empty result.
 */
object DialRequests {

    private const val TAG = "CV:Dial"

    data class DialRequest(
        val id: String,
        val phoneNumber: String,
        val contactId: String?,
        val flowId: String?,
        val runId: String?,
        val status: String,
        val expiresAt: String,
        val createdAt: String,
    )

    /** One-shot authoritative read for a WebSocket signal. */
    sealed interface FetchOneResult {
        data class Success(val request: DialRequest) : FetchOneResult
        data class HttpFailure(val statusCode: Int) : FetchOneResult
        data object NetworkError : FetchOneResult
        data object ParseError : FetchOneResult
    }

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /**
     * One-shot read of the authoritative row behind a WebSocket
     * signal. Never called in a loop: the socket delivers the id,
     * this proves the request is still live and supplies the
     * server-owned number.
     */
    fun fetchOne(
        baseUrl: String,
        bearerCredential: String,
        id: String,
        /** Stable per-install id for server-driven sign-out; header only, never logged. */
        deviceId: String? = null,
        openConnection: (String) -> HttpURLConnection = {
            (URI(it).toURL().openConnection() as HttpURLConnection)
        },
    ): FetchOneResult {
        val connection = try {
            openConnection("$baseUrl/api/v1/dial-requests/$id")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Dial read: could not open connection (${e.javaClass.simpleName})")
            return FetchOneResult.NetworkError
        }
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $bearerCredential")
            if (deviceId != null) connection.setRequestProperty(WacrmDeviceAuth.DEVICE_ID_HEADER, deviceId)
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            val body = runCatching {
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText().take(4096) }.orEmpty()
            }.getOrDefault("")
            connection.disconnect()
            when {
                status in 200..299 -> runCatching { parseOne(body) }
                    .onFailure {
                        AppLogger.w(TAG, "Dial read: HTTP $status with unparseable body (${it.javaClass.simpleName})")
                    }
                    .fold(
                        onSuccess = { row ->
                            if (row == null) FetchOneResult.ParseError
                            else FetchOneResult.Success(row)
                        },
                        onFailure = { FetchOneResult.ParseError },
                    )
                else -> {
                    AppLogger.w(TAG, "Dial read ${maskId(id)}: HTTP $status")
                    FetchOneResult.HttpFailure(status)
                }
            }
        } catch (e: IOException) {
            AppLogger.w(TAG, "Dial read: network failure (${e.javaClass.simpleName})")
            runCatching { connection.disconnect() }
            FetchOneResult.NetworkError
        } catch (e: Exception) {
            AppLogger.w(TAG, "Dial read: error (${e.javaClass.simpleName})")
            runCatching { connection.disconnect() }
            FetchOneResult.NetworkError
        }
    }

    /**
     * Parses `{ "data": { "dial_request": {…} } }`. Null when the
     * envelope is unusable, the id/number is missing, or the number
     * is not dialable — such a row must never reach the dialer.
     */
    fun parseOne(body: String): DialRequest? {
        if (body.isBlank()) return null
        val root = JSONObject(body)
        val data = root.optJSONObject("data") ?: return null
        val obj = data.optJSONObject("dial_request") ?: return null
        return parseRow(obj)
    }

    private fun parseRow(o: JSONObject): DialRequest? {
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val phone = o.optString("phone_number").takeIf { it.isNotBlank() } ?: return null
        if (!DialPhoneValidation.isDialable(phone)) {
            AppLogger.w(TAG, "Dial read: skipping row ${maskId(id)} (undialable number)")
            return null
        }
        return DialRequest(
            id = id,
            phoneNumber = DialPhoneValidation.normalize(phone),
            contactId = o.optString("contact_id").takeIf { it.isNotBlank() },
            flowId = o.optString("flow_id").takeIf { it.isNotBlank() },
            runId = o.optString("run_id").takeIf { it.isNotBlank() },
            status = o.optString("status").ifBlank { "pending" },
            expiresAt = o.optString("expires_at"),
            createdAt = o.optString("created_at"),
        )
    }

    /**
     * Acks one request's status (`delivered` | `opened` | ...).
     * Transport is POST (the server accepts POST and PATCH
     * identically): several HttpURLConnection stacks reject the
     * PATCH method itself ("Invalid HTTP method"), which would fail
     * every ack before it reaches the server. POST works everywhere.
     *
     * Returns the HTTP code (-1 on transport failure) so the caller
     * can distinguish 401 (refresh once) from 404/409/410 (terminal,
     * logged) instead of guessing from a boolean.
     */
    fun ack(
        baseUrl: String,
        bearerCredential: String,
        id: String,
        status: String,
        /** Stable per-install id for server-driven sign-out; header only, never logged. */
        deviceId: String? = null,
        openConnection: (String) -> HttpURLConnection = {
            (URI(it).toURL().openConnection() as HttpURLConnection)
        },
    ): Int {
        val connection = try {
            openConnection("$baseUrl/api/v1/dial-requests/$id")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Dial ack ${maskId(id)}: could not open connection (${e.javaClass.simpleName})")
            return -1
        }
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $bearerCredential")
            if (deviceId != null) connection.setRequestProperty(WacrmDeviceAuth.DEVICE_ID_HEADER, deviceId)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            val payload = JSONObject().put("status", status).toString().toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(payload) }
            val code = connection.responseCode
            connection.disconnect()
            if (code !in 200..299) {
                AppLogger.w(TAG, "Dial ack ${maskId(id)} -> $status: HTTP $code")
            }
            code
        } catch (e: IOException) {
            AppLogger.w(TAG, "Dial ack ${maskId(id)}: network failure (${e.javaClass.simpleName})")
            runCatching { connection.disconnect() }
            -1
        } catch (e: Exception) {
            AppLogger.w(TAG, "Dial ack ${maskId(id)}: error (${e.javaClass.simpleName})")
            runCatching { connection.disconnect() }
            -1
        }
    }

    /** True only when the ISO instant parses AND is already past. Unparseable stays server-truth (kept). */
    fun isExpired(expiresAtIso: String, nowMs: Long): Boolean {
        if (expiresAtIso.isBlank()) return false
        val instant = runCatching { Instant.parse(expiresAtIso) }.getOrNull() ?: return false
        return instant.toEpochMilli() <= nowMs
    }

    /** Log-safe request handle: id prefix only, never the phone number. */
    fun maskId(id: String): String = id.take(8)

    /** Log-safe phone shape: digit count only, never digits. */
    fun digitCount(phoneNumber: String): Int = phoneNumber.filter { it.isDigit() }.length
}
