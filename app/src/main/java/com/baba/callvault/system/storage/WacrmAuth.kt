/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONObject
import com.baba.callvault.data.voip.WhatsappRecordingSource
import com.baba.callvault.services.call.PhoneRecordingSource
import com.baba.callvault.utils.AppLogger

/**
 * WhatsApp Max user authentication over Supabase GoTrue (Phase 2: user sessions replace API keys).
 *
 * Deliberately [HttpURLConnection] + `org.json`, like [WacrmApi] — no new dependencies. The
 * Supabase anon key used here is publishable by design (it ships in WACRM's own web client);
 * it is fetched from `{baseUrl}/api/auth/mobile-config`, never hardcoded, never secret.
 *
 * Security rules:
 * - The password travels in exactly one TLS POST body and is never stored, logged, or retained
 *   past the login call. Callers must drop their reference (clear the char data) after calling.
 * - Access/refresh tokens are returned to the caller, which must persist them only via
 *   [com.baba.callvault.data.WacrmSession] (encrypted) and never log them (see LogRedaction).
 * - HTTPS only: every URL derives from a [WacrmApi.normalizeBaseUrl]-validated base.
 *
 * Pure except for the socket itself: [parseSession], [parseMobileConfig], [parseMe],
 * [shouldRefresh] and [expiryAtMs] take strings/numbers and are unit tested directly.
 */
object WacrmAuth {

    private const val TAG = "CV:WacrmAuth"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Refresh proactively when the token dies within this window — avoids a doomed upload. */
    const val REFRESH_SKEW_MS = 60_000L

    /** GoTrue session tokens. Never log any field. */
    data class SessionTokens(
        val accessToken: String,
        val refreshToken: String,
        /** Absolute expiry of the access token, [System.currentTimeMillis] basis. */
        val expiresAtMs: Long,
        val userId: String,
        val userEmail: String?,
    )

    /** `{supabaseUrl, supabaseAnonKey}` from `GET {base}/api/auth/mobile-config`. */
    data class MobileConfig(val supabaseUrl: String, val supabaseAnonKey: String)

    /** Identity snapshot from `GET {base}/api/auth/me`. */
    data class MeInfo(
        val userId: String,
        val userEmail: String?,
        val fullName: String?,
        val accountId: String,
        val accountName: String?,
        val role: String?,
    )

    sealed interface AuthOutcome {
        data class Ok(val session: SessionTokens) : AuthOutcome
        /** Wrong password or unknown email. Message-safe: never says which. */
        data object InvalidCredentials : AuthOutcome
        /** No route to host, TLS failure, timeout, unreachable server. */
        data object NetworkError : AuthOutcome
        /** Anything else (5xx, malformed body, rate limited, ...). */
        data class ServerError(val status: Int) : AuthOutcome
    }

    /** Absolute access-token expiry from GoTrue's `expires_in` seconds. */
    fun expiryAtMs(expiresInSec: Long, nowMs: Long): Long =
        nowMs + expiresInSec.coerceAtLeast(0L) * 1_000L

    /** True when the token is expired or dies within [REFRESH_SKEW_MS]. */
    fun shouldRefresh(expiresAtMs: Long, nowMs: Long): Boolean =
        nowMs + REFRESH_SKEW_MS >= expiresAtMs

    /** Parses a GoTrue session body (`token?grant_type=password|refresh_token`). Null when unusable. */
    fun parseSession(body: String, nowMs: Long): SessionTokens? {
        return runCatching {
            val json = JSONObject(body)
            val access = json.optString("access_token").takeIf { it.isNotEmpty() } ?: return null
            val refresh = json.optString("refresh_token").takeIf { it.isNotEmpty() } ?: return null
            val user = json.optJSONObject("user") ?: return null
            val uid = user.optString("id").takeIf { it.isNotEmpty() } ?: return null
            SessionTokens(
                accessToken = access,
                refreshToken = refresh,
                expiresAtMs = expiryAtMs(json.optLong("expires_in", 3600L), nowMs),
                userId = uid,
                userEmail = user.optString("email").takeIf { it.isNotEmpty() },
            )
        }.getOrNull()
    }

    /** Parses `GET /api/auth/mobile-config`. Null when unusable. */
    fun parseMobileConfig(body: String): MobileConfig? {
        return runCatching {
            val json = JSONObject(body)
            val url = json.optString("supabaseUrl").takeIf { it.isNotEmpty() } ?: return null
            val key = json.optString("supabaseAnonKey").takeIf { it.isNotEmpty() } ?: return null
            MobileConfig(url, key)
        }.getOrNull()
    }

    /** Parses `GET /api/auth/me`. Null when unusable. */
    fun parseMe(body: String): MeInfo? {        return runCatching {
            val json = JSONObject(body)
            val user = json.optJSONObject("user") ?: return null
            val account = json.optJSONObject("account") ?: return null
            val uid = user.optString("id").takeIf { it.isNotEmpty() } ?: return null
            val accountId = account.optString("id").takeIf { it.isNotEmpty() } ?: return null
            MeInfo(
                userId = uid,
                userEmail = user.optString("email").takeIf { it.isNotEmpty() },
                fullName = user.optString("fullName").takeIf { it.isNotEmpty() },
                accountId = accountId,
                accountName = account.optString("name").takeIf { it.isNotEmpty() },
                role = json.optString("role").takeIf { it.isNotEmpty() },
            )
        }.getOrNull()
    }

    /** Parses the recording source out of `GET /api/auth/me`. Null when absent/unusable. */
    fun parseWhatsappSource(body: String): WhatsappRecordingSource? {
        return runCatching {
            val json = JSONObject(body)
            WhatsappRecordingSource.fromWire(json.optString("whatsappRecordingSource"))
        }.getOrNull()
    }

    /** Parses the phone policy out of `GET /api/auth/me`. Null when absent/unusable. */
    fun parsePhoneSource(body: String): PhoneRecordingSource? {
        return runCatching {
            val json = JSONObject(body)
            PhoneRecordingSource.fromWire(json.optString("phoneRecordingSource"))
        }.getOrNull()
    }

    /**
     * Parses the owner-configured phone number out of
     * `GET /api/auth/me` (`phoneRecordingNumber`, normalized digits
     * from the server). Null when absent, null, blank, or malformed —
     * callers must distinguish absent (keep last) from explicit null
     * (owner set None) via [hasPhoneNumber].
     */
    fun parsePhoneNumber(body: String): String? {
        return runCatching {
            val json = JSONObject(body)
            if (!json.has("phoneRecordingNumber") || json.isNull("phoneRecordingNumber")) return null
            normalizeServerPhoneNumber(json.optString("phoneRecordingNumber"))
        }.getOrNull()
    }

    /** True when `/api/auth/me` carries an explicit `phoneRecordingNumber` (even null). */
    fun hasPhoneNumber(body: String): Boolean {
        return runCatching { JSONObject(body).has("phoneRecordingNumber") }.getOrDefault(false)
    }

    /** Canonical digits for a server phone number; null when not a plausible E.164 number. */
    fun normalizeServerPhoneNumber(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val digits = raw.filter { it.isDigit() }
        if (!Regex("^[1-9][0-9]{6,14}$").matches(digits)) return null
        return digits
    }

    private fun postJson(
        url: String,
        anonKey: String?,
        body: JSONObject,
        bearer: String? = null,
        openConnection: (String) -> HttpURLConnection,
    ): Pair<Int, String> {
        val connection = openConnection(url)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.doInput = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        if (anonKey != null) connection.setRequestProperty("apikey", anonKey)
        if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        connection.outputStream.use { it.write(bytes) }
        val status = connection.responseCode
        val text = runCatching {
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.getOrDefault("")
        connection.disconnect()
        return status to text
    }

    private fun getJson(
        url: String,
        bearer: String?,
        openConnection: (String) -> HttpURLConnection,
    ): Pair<Int, String> {
        val connection = openConnection(url)
        connection.requestMethod = "GET"
        connection.doInput = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json")
        if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
        val status = connection.responseCode
        val text = runCatching {
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.getOrDefault("")
        connection.disconnect()
        return status to text
    }

    /**
     * Sanitized failure breadcrumb for logcat: stage + HTTP status (or
     * exception class on transport failure) ONLY. Never URLs, bodies,
     * emails, passwords, or tokens — status alone classifies the failure
     * (404 = route not deployed, 401/400 = credentials, 5xx = server,
     * IOException = network/TLS/timeout). See LogRedaction.
     */
    private fun logStageFailure(stage: String, status: Int) {
        AppLogger.w(TAG, "WhatsApp Max $stage failed: HTTP $status")
    }

    private fun logStageTransportFailure(stage: String, e: IOException) {
        AppLogger.w(TAG, "WhatsApp Max $stage network failure: ${e.javaClass.simpleName}")
    }

    /** Public connection details for talking to this server's Supabase project. */
    fun fetchMobileConfig(
        baseUrl: String,
        openConnection: (String) -> HttpURLConnection = { url ->
            @Suppress("UsePropertyAccessSyntax")
            (URI(url).toURL().openConnection() as HttpURLConnection)
        },
    ): MobileConfig? {
        return try {
            val (status, body) = getJson("$baseUrl/api/auth/mobile-config", null, openConnection)
            if (status !in 200..299) {
                logStageFailure("mobile-config", status)
                null
            } else {
                parseMobileConfig(body).also {
                    if (it == null) AppLogger.w(TAG, "WhatsApp Max mobile-config response unparseable")
                }
            }
        } catch (e: IOException) {
            logStageTransportFailure("mobile-config", e)
            null
        } catch (e: Exception) {
            AppLogger.w(TAG, "WhatsApp Max mobile-config error: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Email/password sign-in against the server's Supabase Auth.
     * Returns tokens on success; the caller must persist them via
     * [com.baba.callvault.data.WacrmSession] and drop the password.
     */
    fun signIn(
        config: MobileConfig,
        email: String,
        password: String,
        openConnection: (String) -> HttpURLConnection = { url ->
            @Suppress("UsePropertyAccessSyntax")
            (URI(url).toURL().openConnection() as HttpURLConnection)
        },
    ): AuthOutcome {
        return try {
            val (status, body) = postJson(
                "${config.supabaseUrl}/auth/v1/token?grant_type=password",
                config.supabaseAnonKey,
                JSONObject().put("email", email).put("password", password),
                openConnection = openConnection,
            )
            when {
                status in 200..299 -> {
                    parseSession(body, System.currentTimeMillis())?.let { AuthOutcome.Ok(it) }
                        ?: AuthOutcome.ServerError(status).also {
                            AppLogger.w(TAG, "WhatsApp Max sign-in session unparseable")
                        }
                }
                status == 400 -> AuthOutcome.InvalidCredentials
                else -> AuthOutcome.ServerError(status).also {
                    logStageFailure("sign-in", status)
                }
            }
        } catch (e: IOException) {
            logStageTransportFailure("sign-in", e)
            AuthOutcome.NetworkError
        } catch (e: Exception) {
            AppLogger.w(TAG, "WhatsApp Max sign-in error: ${e.javaClass.simpleName}")
            AuthOutcome.ServerError(-1)
        }
    }

    /** Rotates an expired access token. Same outcome contract as [signIn] minus credentials. */
    fun refresh(
        config: MobileConfig,
        refreshToken: String,
        openConnection: (String) -> HttpURLConnection = { url ->
            @Suppress("UsePropertyAccessSyntax")
            (URI(url).toURL().openConnection() as HttpURLConnection)
        },
    ): AuthOutcome {
        return try {
            val (status, body) = postJson(
                "${config.supabaseUrl}/auth/v1/token?grant_type=refresh_token",
                config.supabaseAnonKey,
                JSONObject().put("refresh_token", refreshToken),
                openConnection = openConnection,
            )
            when {
                status in 200..299 -> {
                    parseSession(body, System.currentTimeMillis())?.let { AuthOutcome.Ok(it) }
                        ?: AuthOutcome.ServerError(status).also {
                            AppLogger.w(TAG, "WhatsApp Max refresh session unparseable")
                        }
                }
                status == 400 || status == 401 -> AuthOutcome.InvalidCredentials
                else -> AuthOutcome.ServerError(status).also {
                    logStageFailure("refresh", status)
                }
            }
        } catch (e: IOException) {
            logStageTransportFailure("refresh", e)
            AuthOutcome.NetworkError
        } catch (e: Exception) {
            AppLogger.w(TAG, "WhatsApp Max refresh error: ${e.javaClass.simpleName}")
            AuthOutcome.ServerError(-1)
        }
    }

    /** Best-effort server-side session revocation for Disconnect. Never throws. */
    fun signOut(
        config: MobileConfig,
        accessToken: String,
        openConnection: (String) -> HttpURLConnection = { url ->
            @Suppress("UsePropertyAccessSyntax")
            (URI(url).toURL().openConnection() as HttpURLConnection)
        },
    ) {
        runCatching {
            postJson(
                "${config.supabaseUrl}/auth/v1/logout",
                config.supabaseAnonKey,
                JSONObject(),
                bearer = accessToken,
                openConnection = openConnection,
            )
        }
    }

    /** Identity snapshot for the Connect confirmation screen. Null on any failure. */
    fun fetchMe(
        baseUrl: String,
        accessToken: String,
        openConnection: (String) -> HttpURLConnection = { url ->
            @Suppress("UsePropertyAccessSyntax")
            (URI(url).toURL().openConnection() as HttpURLConnection)
        },
    ): MeInfo? {
        return try {
            val (status, body) = getJson("$baseUrl/api/auth/me", accessToken, openConnection)
            if (status !in 200..299) {
                logStageFailure("me", status)
                null
            } else {
                parseMe(body).also {
                    if (it == null) AppLogger.w(TAG, "WhatsApp Max me response unparseable")
                }
            }
        } catch (e: IOException) {
            logStageTransportFailure("me", e)
            null
        } catch (e: Exception) {
            AppLogger.w(TAG, "WhatsApp Max me error: ${e.javaClass.simpleName}")
            null
        }
    }
}
