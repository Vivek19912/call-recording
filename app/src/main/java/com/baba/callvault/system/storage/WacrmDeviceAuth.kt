/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import android.os.Build
import com.baba.callvault.BuildConfig
import com.baba.callvault.utils.AppLogger
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Device identity for server-driven sign-out.
 *
 * The server must be able to revoke ONE phone (lost, replaced,
 * offboarded) without touching the user's other sessions. Two
 * pieces make that work:
 *
 * - Device id: [DialDeviceId.get] (stable per-install UUID). Sent
 *   as [DEVICE_ID_HEADER] on every device-authenticated request.
 * - Session id: the `session_id` claim of the access JWT. Read via
 *   [parseSessionId] — it creates NO new session, it only decodes
 *   the current token.
 *
 * Registration ([registerBestEffort]/[ensureRegistered]) POSTs the
 * device row with the CURRENT access token as Bearer. Status
 * ([checkStatusAndWipeIfRevoked]) GETs the row: `revoked == true`
 * wipes locally via [WacrmRemoteLogout]. Anything else — network
 * failure, unparseable body, non-2xx — leaves the session exactly
 * as it is: a transient MUST never sign the user out.
 *
 * 60-day discipline: no signIn/refresh_token calls here, ever; the
 * session is never deleted on failure (only [WacrmRemoteLogout] on
 * an explicit revoked answer). Blocking HttpURLConnection like
 * [WacrmAuth]: call only off the main thread. No tokens are logged.
 */
object WacrmDeviceAuth {

    private const val TAG = "CV:WacrmDevice"

    /** Sent on every device-authenticated request (never in the URL, never logged). */
    const val DEVICE_ID_HEADER = "X-CallVault-Device-Id"

    private const val PREFS_FILE = "wacrm_device"
    private const val KEY_REGISTERED_SESSION_ID = "registered_session_id"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Pure overload: header map for a known device id. Unit tested. */
    fun deviceHeaders(deviceId: String): Map<String, String> =
        mapOf(DEVICE_ID_HEADER to deviceId)

    /** Header map for this install. Reads the stable id; creates it on first use. */
    fun deviceHeaders(context: Context): Map<String, String> =
        deviceHeaders(DialDeviceId.get(context))

    /**
     * Decodes the `session_id` claim from an access-JWT payload.
     * Pure: base64url middle segment → JSONObject. Null for
     * anything malformed, missing, or blank — never throws.
     */
    fun parseSessionId(accessToken: String?): String? {
        if (accessToken.isNullOrBlank()) return null
        return runCatching {
            val parts = accessToken.split(".")
            if (parts.size != 3) return null
            val padded = parts[1].padEnd((parts[1].length + 3) / 4 * 4, '=')
            val payload = java.util.Base64.getUrlDecoder().decode(padded).toString(Charsets.UTF_8)
            JSONObject(payload).optString("session_id").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * Registration JSON. Pure — unit tested. Carries ids and device
     * facts only; tokens travel exclusively as the Bearer header and
     * can never appear here (this function never receives one).
     */
    fun buildRegisterBody(
        deviceId: String,
        sessionId: String,
        deviceName: String,
        deviceModel: String,
        appVersion: String,
    ): String = JSONObject()
        .put("device_id", deviceId)
        .put("session_id", sessionId)
        .put("device_name", deviceName)
        .put("device_model", deviceModel)
        .put("app_version", appVersion)
        .toString()

    /** This install's human-readable name (`"<manufacturer> <model>"`). */
    fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    /**
     * POSTs `{base}/api/auth/callvault-devices/register` (Bearer =
     * the CURRENT access token). True only on HTTP 2xx. Never
     * throws; a false return means "retry later", never "sign out".
     */
    fun registerDevice(
        baseUrl: String,
        accessToken: String,
        deviceId: String,
        bodyJson: String,
        openConnection: (String) -> HttpURLConnection = { url ->
            @Suppress("UsePropertyAccessSyntax")
            (URI(url).toURL().openConnection() as HttpURLConnection)
        },
    ): Boolean {
        return try {
            val connection = openConnection("$baseUrl/api/auth/callvault-devices/register")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.doInput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty(DEVICE_ID_HEADER, deviceId)
            connection.outputStream.use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            runCatching { connection.disconnect() }
            if (status !in 200..299) {
                AppLogger.w(TAG, "Device registration failed: HTTP $status")
                false
            } else {
                true
            }
        } catch (e: IOException) {
            AppLogger.w(TAG, "Device registration network failure: ${e.javaClass.simpleName}")
            false
        } catch (e: Exception) {
            AppLogger.w(TAG, "Device registration error: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * POSTs `{base}/api/auth/callvault-devices/ack` (`{device_id}`,
     * Bearer = the CURRENT access token) to acknowledge a targeted
     * `logout_device` push. Best-effort: true only on HTTP 2xx, false
     * (never a throw, never a clear) on any failure — the caller
     * wipes regardless. Tokens travel as the Bearer header only and
     * can never appear in the JSON body (this function builds the
     * body from the device id alone).
     */
    fun postLogoutAck(
        baseUrl: String,
        accessToken: String,
        deviceId: String,
        openConnection: (String) -> HttpURLConnection = { url ->
            @Suppress("UsePropertyAccessSyntax")
            (URI(url).toURL().openConnection() as HttpURLConnection)
        },
    ): Boolean {
        return try {
            val body = JSONObject().put("device_id", deviceId).toString()
            val connection = openConnection("$baseUrl/api/auth/callvault-devices/ack")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.doInput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty(DEVICE_ID_HEADER, deviceId)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            runCatching { connection.disconnect() }
            if (status !in 200..299) {
                AppLogger.w(TAG, "Device logout ack failed: HTTP $status")
                false
            } else {
                true
            }
        } catch (e: IOException) {
            AppLogger.w(TAG, "Device logout ack network failure: ${e.javaClass.simpleName}")
            false
        } catch (e: Exception) {
            AppLogger.w(TAG, "Device logout ack error: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun storedSessionId(context: Context): String? =
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .getString(KEY_REGISTERED_SESSION_ID, null)?.takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun storeSessionId(context: Context, sessionId: String) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .edit().putString(KEY_REGISTERED_SESSION_ID, sessionId).apply()
        }
    }

    /**
     * Registers this device, best-effort. Call after a successful
     * login. Failure (network, unparseable token) never fails the
     * login and never touches the session — the socket connect
     * retries it via [ensureRegistered].
     */
    fun registerBestEffort(
        context: Context,
        baseUrl: String,
        accessToken: String,
        openConnection: (String) -> HttpURLConnection = { url ->
            @Suppress("UsePropertyAccessSyntax")
            (URI(url).toURL().openConnection() as HttpURLConnection)
        },
    ): Boolean {
        val app = context.applicationContext
        val sessionId = parseSessionId(accessToken) ?: return false
        val deviceId = DialDeviceId.get(app)
        val body = buildRegisterBody(
            deviceId = deviceId,
            sessionId = sessionId,
            deviceName = deviceName(),
            deviceModel = Build.MODEL,
            appVersion = BuildConfig.VERSION_NAME,
        )
        val ok = registerDevice(baseUrl, accessToken, deviceId, body, openConnection)
        if (ok) storeSessionId(app, sessionId)
        return ok
    }

    /**
     * Registers only when the session id differs from the last
     * successfully registered one. Called on every socket connect
     * (and therefore on every app resume via
     * [DialSocketService.ensureStarted]): a silent retry for a
     * registration the login could not complete, and the re-register
     * after any token rotation. Never throws, never clears anything.
     */
    fun ensureRegistered(
        context: Context,
        baseUrl: String,
        accessToken: String,
        openConnection: (String) -> HttpURLConnection = { url ->
            @Suppress("UsePropertyAccessSyntax")
            (URI(url).toURL().openConnection() as HttpURLConnection)
        },
    ): Boolean {
        val sessionId = parseSessionId(accessToken) ?: return false
        if (storedSessionId(context.applicationContext) == sessionId) return true
        return registerBestEffort(context, baseUrl, accessToken, openConnection)
    }

    /**
     * Parses a status body (`{revoked: Boolean}`). True/false on a
     * well-formed answer, null when absent or unparseable — null
     * MUST mean "do nothing" to the caller, never "wipe".
     */
    fun parseStatusRevoked(body: String?): Boolean? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            when (val value = JSONObject(body).opt("revoked")) {
                is Boolean -> value
                else -> null
            }
        }.getOrNull()
    }

    /**
     * The wipe decision, isolated for tests: wipe if and only if
     * the server explicitly answered `revoked == true`. A null
     * (network failure, non-2xx, unparseable) is never a wipe.
     */
    fun shouldRemoteWipe(revoked: Boolean?): Boolean = revoked == true

    /**
     * GETs `{base}/api/auth/callvault-devices/status?device_id=…`
     * (Bearer = the CURRENT access token — never a refresh). Returns
     * the server's revoked flag, or null on any transport/parse
     * failure. Never throws.
     */
    fun fetchRevoked(
        baseUrl: String,
        accessToken: String,
        deviceId: String,
        openConnection: (String) -> HttpURLConnection = { url ->
            @Suppress("UsePropertyAccessSyntax")
            (URI(url).toURL().openConnection() as HttpURLConnection)
        },
    ): Boolean? {
        return try {
            val encoded = URLEncoder.encode(deviceId, "UTF-8")
            val connection = openConnection("$baseUrl/api/auth/callvault-devices/status?device_id=$encoded")
            connection.requestMethod = "GET"
            connection.doInput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty(DEVICE_ID_HEADER, deviceId)
            val status = connection.responseCode
            val body = runCatching {
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            runCatching { connection.disconnect() }
            if (status !in 200..299) {
                AppLogger.w(TAG, "DEVICE_REVOCATION_CHECK_RESULT http=$status revoked=null (non-2xx, no action)")
                null
            } else {
                parseStatusRevoked(body).also {
                    AppLogger.i(
                        TAG,
                        "DEVICE_REVOCATION_CHECK_RESULT http=$status revoked=$it device=...${deviceId.takeLast(4)}",
                    )
                    if (it == null) AppLogger.w(TAG, "Device status response unparseable")
                }
            }
        } catch (e: IOException) {
            AppLogger.w(TAG, "Device status network failure: ${e.javaClass.simpleName}")
            null
        } catch (e: Exception) {
            AppLogger.w(TAG, "Device status error: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Status check with the wipe attached. True when a remote wipe
     * was applied (caller must stop what it was doing — e.g. never
     * open the socket). False covers both "not revoked" and "could
     * not tell": only an explicit revoked answer wipes. Never
     * throws, never refreshes, never clears on failure.
     */
    fun checkStatusAndWipeIfRevoked(
        context: Context,
        baseUrl: String,
        accessToken: String,
        deviceId: String? = null,
        openConnection: (String) -> HttpURLConnection = { url ->
            @Suppress("UsePropertyAccessSyntax")
            (URI(url).toURL().openConnection() as HttpURLConnection)
        },
    ): Boolean {
        return try {
            AppLogger.i(TAG, "DEVICE_REVOCATION_CHECK_STARTED device=...${(deviceId ?: "?").takeLast(4)}")
            val id = deviceId ?: DialDeviceId.get(context.applicationContext)
            val revoked = fetchRevoked(baseUrl, accessToken, id, openConnection)
            if (!shouldRemoteWipe(revoked)) return false
            AppLogger.w(TAG, "Server revoked this device; acknowledging then applying remote logout")
            // Acknowledge BEFORE wiping: the tokens are still in
            // memory here, and the ack endpoint accepts exactly this
            // revoked session. Best-effort — a failed ack never blocks
            // the wipe, and the socket path acks independently.
            // Without this, status-detected wipes would leave the
            // dashboard stuck at Logout Pending forever.
            runCatching { postLogoutAck(baseUrl, accessToken, id, openConnection) }
            WacrmRemoteLogout.applyRemoteLogout(context)
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "Device status check error: ${e.javaClass.simpleName}")
            false
        }
    }
}
