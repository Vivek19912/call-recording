package com.baba.callvault.system.storage

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import com.baba.callvault.utils.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Click-to-dial handoff client.
 *
 * Delivery model: authenticated foreground sync — CallVault polls
 * `GET {base}/api/v1/dial-requests` with the authenticated
 * WhatsApp Max user token (same bearer discipline as
 * [WacrmApi]: header only, HTTPS only, never logged). The server
 * returns only requests created by the logged-in user
 * (`requested_by`), so one employee's phone never sees another's.
 * Each authorized request becomes a high-priority notification;
 * the user taps to open Android's native dialer via ACTION_DIAL.
 *
 * Never uses ACTION_CALL, never places calls silently or from the
 * background. Recording / upload / call-event behavior is untouched:
 * a dial request is not proof of a call, and this client never
 * creates one.
 *
 * Failure contract: every failure is a typed [FetchResult], never
 * a silent empty list, so the poller can tell "no requests"
 * (Success) apart from "could not ask" (Auth/Network/...) and log
 * each one.
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

    /** Typed poll outcome — never a silent empty list. */
    sealed interface FetchResult {
        data class Success(val requests: List<DialRequest>) : FetchResult
        data class HttpFailure(val statusCode: Int) : FetchResult
        data object NetworkError : FetchResult
        data object ParseError : FetchResult
    }

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    fun fetchPending(
        baseUrl: String,
        bearerCredential: String,
        openConnection: (String) -> HttpURLConnection = {
            (URI(it).toURL().openConnection() as HttpURLConnection)
        },
    ): FetchResult {
        val connection = try {
            openConnection("$baseUrl/api/v1/dial-requests?status=pending&limit=10")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Dial poll: could not open connection (${e.javaClass.simpleName})")
            return FetchResult.NetworkError
        }
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $bearerCredential")
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            val body = runCatching {
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText().take(4096) }.orEmpty()
            }.getOrDefault("")
            connection.disconnect()
            when {
                status in 200..299 -> runCatching { parseList(body) }
                    .onFailure {
                        AppLogger.w(TAG, "Dial poll: HTTP $status with unparseable body (${it.javaClass.simpleName})")
                    }
                    .fold(
                        onSuccess = { FetchResult.Success(it) },
                        onFailure = { FetchResult.ParseError },
                    )
                status == 401 || status == 403 -> {
                    AppLogger.w(TAG, "Dial poll: HTTP $status (session rejected)")
                    FetchResult.HttpFailure(status)
                }
                status in 500..599 -> {
                    AppLogger.w(TAG, "Dial poll: HTTP $status (server)")
                    FetchResult.HttpFailure(status)
                }
                else -> {
                    AppLogger.w(TAG, "Dial poll: HTTP $status")
                    FetchResult.HttpFailure(status)
                }
            }
        } catch (e: IOException) {
            AppLogger.w(TAG, "Dial poll: network failure (${e.javaClass.simpleName})")
            runCatching { connection.disconnect() }
            FetchResult.NetworkError
        } catch (e: Exception) {
            AppLogger.w(TAG, "Dial poll: error (${e.javaClass.simpleName})")
            runCatching { connection.disconnect() }
            FetchResult.NetworkError
        }
    }

    /**
     * Parses the exact `GET /api/v1/dial-requests` envelope:
     * `{ "data": { "dial_requests": [ {id, phone_number,
     * contact_id, flow_id, run_id, status, expires_at, created_at,
     * ...unknowns tolerated} ] } }`. Unknown fields are ignored so
     * additive server changes never break the device. Throws on a
     * structurally unusable body (caller maps to ParseError).
     * Rows with unusable ids/numbers are skipped, never sent to
     * the dialer.
     */
    fun parseList(body: String): List<DialRequest> {
        if (body.isBlank()) return emptyList()
        val root = JSONObject(body)
        val data = root.optJSONObject("data") ?: return emptyList()
        val arr: JSONArray = data.optJSONArray("dial_requests") ?: return emptyList()
        val out = ArrayList<DialRequest>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
            val phone = o.optString("phone_number").takeIf { it.isNotBlank() } ?: continue
            // Device-side validation: malformed numbers never reach
            // the dialer even if the server ever let one through.
            if (!DialPhoneValidation.isDialable(phone)) {
                AppLogger.w(TAG, "Dial poll: skipping row ${maskId(id)} (undialable number)")
                continue
            }
            out += DialRequest(
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
        return out
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
