/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import com.baba.callvault.BuildConfig
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Minimal WACRM upload client (Phase 1): one finalized recording → one HTTPS multipart POST.
 *
 * Deliberately [HttpURLConnection], not Retrofit/OkHttp/Ktor — the app has no HTTP client
 * dependency, and the in-app updater already proves this exact primitive (chunked streaming,
 * timeouts, retries) in [com.baba.callvault.system.updates.GitHubReleases]. One less dependency
 * is one less thing that can break recording uploads. HTTPS is guaranteed separately: uploads
 * only ever run against a base URL that passed [normalizeBaseUrl], which rejects non-HTTPS.
 *
 * Security rules, enforced by construction rather than by convention:
 * - HTTPS only: [normalizeBaseUrl] rejects anything that is not `https://`, so no code path can
 *   ever downgrade the upload to cleartext.
 * - The bearer credential (user access token, formerly an API key) travels in the
 *   `Authorization: Bearer` header only — never in the URL, never in a
 *   log line, never in an exception message. Callers must not log it; this file never does.
 * - The audio is streamed ([audio] → socket in 32 KiB chunks). A 100 MB recording never sits in
 *   RAM whole.
 *
 * Pure except for the socket itself: [writeMultipartBody], [mimeForUpload] and [normalizeBaseUrl]
 * take streams/strings and are unit tested directly.
 */
object WacrmApi {

    /** WACRM's upload ceiling. `<=` uploads, `>` is refused locally as terminally oversized. */
    const val MAX_BYTES: Long = 100L * 1024L * 1024L

    /** MIME types the WACRM Phase 1 endpoint accepts. CallVault only ever produces two of them. */
    private val WACRM_ACCEPTED_MIME_TYPES = setOf(
        "audio/ogg", "audio/opus", "audio/mpeg", "audio/mp3", "audio/mp4", "audio/aac",
        "audio/amr", "audio/3gpp", "audio/webm", "audio/x-m4a", "audio/m4a"
    )

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 120_000
    private const val COPY_BUFFER_BYTES = 32 * 1024
    private const val CRLF = "\r\n"

    /**
     * Normalizes a user-entered server base URL, or null when it must not be used.
     *
     * Accepts only `https://` with a non-empty host; trims whitespace and trailing slashes. Returns
     * the canonical base (scheme + authority, no path), so endpoint construction below cannot smuggle
     * in a path or credentials. Null means "not configured or unsafe" — callers skip quietly.
     */
    fun normalizeBaseUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
            // Production is HTTPS-only. Debug builds additionally accept
            // cleartext to loopback so `adb reverse` can exercise the full
            // socket pipeline against a local backend; no other host and no
            // release build ever permits it.
            val loopback = host == "127.0.0.1" || host == "localhost" || host == "10.0.2.2"
            val allowed = scheme == "https" || (BuildConfig.DEBUG && scheme == "http" && loopback)
            if (!allowed) return null
            val defaultPort =
                (scheme == "https" && uri.port == 443) ||
                    (scheme == "http" && uri.port == 80)
            val port = if (uri.port == -1 || defaultPort) "" else ":${uri.port}"
            "$scheme://$host$port"
        }.getOrNull()
    }

    /**
     * The single MIME mapping for uploads.
     *
     * Android reports the *codec* MIME (`audio/opus`, `audio/mp4a-latm`), but WACRM validates the
     * *container* MIME — so the extension (the container truth, written by our own muxer) decides,
     * with the container MIME as fallback. Returns null when neither maps to something WACRM
     * accepts, which the worker treats as terminally unsupported rather than uploading bytes the
     * server will reject.
     */
    fun mimeForUpload(displayName: String, containerMime: String?): String? {
        val byExtension = when {
            displayName.endsWith(".ogg", ignoreCase = true) -> "audio/ogg"
            displayName.endsWith(".m4a", ignoreCase = true) ||
                displayName.endsWith(".mp4", ignoreCase = true) -> "audio/mp4"
            else -> null
        }
        if (byExtension != null) return byExtension
        val normalized = containerMime?.lowercase()
        return normalized?.takeIf { it in WACRM_ACCEPTED_MIME_TYPES }
    }

    /** Result of [postRecording]: the status plus a short safe body excerpt, for diagnostics. */
    data class UploadResult(val statusCode: Int, val bodyExcerpt: String)

    /**
     * POSTs one call-outcome event to `$baseUrl/api/v1/call-events` as JSON.
     *
     * No audio, no Storage, no recording required — a missed/rejected call has none. Same
     * credential discipline as [postRecording]: bearer header only, never logged. The body is
     * small; [UploadResult.bodyExcerpt] stays capped like everywhere else.
     *
     * @param fields the event fields (`client_event_id`, `call_type`, `direction`, `outcome`,
     *   plus `phone_number`, `duration_seconds`, `occurred_at` as applicable). Already validated
     *   by the caller; the server re-validates and 400s anything malformed.
     */
    fun postCallEvent(
        baseUrl: String,
        bearerCredential: String,
        fields: Map<String, Any>,
        /** Stable per-install id for server-driven sign-out; header only, never logged. */
        deviceId: String? = null,
        openConnection: (String) -> HttpURLConnection = { url ->
            @Suppress("UsePropertyAccessSyntax")
            (URI(url).toURL().openConnection() as HttpURLConnection)
        },
    ): UploadResult {
        val body = buildJsonObject(fields).toByteArray(Charsets.UTF_8)
        val connection = openConnection("$baseUrl/api/v1/call-events")
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.doInput = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Authorization", "Bearer $bearerCredential")
        if (deviceId != null) connection.setRequestProperty(WacrmDeviceAuth.DEVICE_ID_HEADER, deviceId)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        connection.outputStream.use { it.write(body) }
        val status = connection.responseCode
        val excerpt = runCatching {
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText().take(512) }.orEmpty()
        }.getOrDefault("")
        connection.disconnect()
        return UploadResult(status, excerpt)
    }

    /**
     * Encodes flat fields as a JSON object. Pure — unit tested. Keys and values are
     * device data (phone digits, wire enums, timestamps); credentials never pass through here.
     *
     * Numbers ([Long]/[Int]/[Short]/[Byte]) are emitted as raw JSON
     * numbers — `duration_seconds` must reach the server unquoted.
     * Strings are quoted with escaping. Anything else is omitted,
     * never coerced: a mis-typed value must not silently become a
     * string the server rejects (or a number it misreads).
     */
    fun buildJsonObject(fields: Map<String, Any?>): String = buildString {
        append('{')
        var first = true
        for ((key, value) in fields) {
            val rendered = when (value) {
                is String -> "\"" + buildString { appendJsonEscaped(value) } + "\""
                is Long, is Int, is Short, is Byte -> value.toString()
                else -> null
            } ?: continue
            if (!first) append(',')
            first = false
            append('"')
            appendJsonEscaped(key)
            append("\":")
            append(rendered)
        }
        append('}')
    }

    private fun StringBuilder.appendJsonEscaped(value: String) {
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }

    /**
     * POSTs one recording to `$baseUrl/api/v1/recordings`.
     *
     * @param baseUrl canonical base from [normalizeBaseUrl] (never user input directly).
     * @param bearerCredential the `Authorization: Bearer` value — a user access token
     *   (legacy callers passed the API key; same header, same position). Goes into the
     *   header and nowhere else; it is never logged here, and [UploadResult.bodyExcerpt]
     *   is capped so a misbehaving server cannot make us persist anything large.
     * @param audio the recording bytes, streamed — never fully buffered by this function.
     * @param fields text parts (`recorded_at`, `duration_seconds`, `file_name` as applicable).
     */
    fun postRecording(
        baseUrl: String,
        bearerCredential: String,
        audio: InputStream,
        fileName: String,
        fileMime: String,
        fields: Map<String, String>,
        /** Stable per-install id for server-driven sign-out; header only, never logged. */
        deviceId: String? = null,
        openConnection: (String) -> HttpURLConnection = { url ->
            @Suppress("UsePropertyAccessSyntax")
            (URI(url).toURL().openConnection() as HttpURLConnection)
        },
    ): UploadResult {
        val boundary = "cv${System.currentTimeMillis()}${java.util.UUID.randomUUID().toString().take(8)}"
        val connection = openConnection("$baseUrl/api/v1/recordings")
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.doInput = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Authorization", "Bearer $bearerCredential")
        if (deviceId != null) connection.setRequestProperty(WacrmDeviceAuth.DEVICE_ID_HEADER, deviceId)
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.setRequestProperty("Accept", "application/json")
        // Unknown total up front (streamed body): chunked mode avoids buffering the entity.
        connection.setChunkedStreamingMode(COPY_BUFFER_BYTES)
        connection.connect()
        connection.outputStream.use { out ->
            writeMultipartBody(out, boundary, fields, fileName, fileMime, audio)
        }
        val status = connection.responseCode
        val excerpt = runCatching {
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText().take(512) }.orEmpty()
        }.getOrDefault("")
        connection.disconnect()
        return UploadResult(status, excerpt)
    }

    /**
     * Writes one `multipart/form-data` body: text [fields] first, then the `audio` file part with
     * [audio] streamed in [COPY_BUFFER_BYTES] chunks. Pure I/O on the given streams — unit tested
     * with in-memory streams, no socket required.
     */
    fun writeMultipartBody(
        output: OutputStream,
        boundary: String,
        fields: Map<String, String>,
        fileName: String,
        fileMime: String,
        audio: InputStream,
    ) {
        val writer = output.bufferedWriter(Charsets.UTF_8)
        for ((key, value) in fields) {
            writer.write("--$boundary$CRLF")
            writer.write("Content-Disposition: form-data; name=\"$key\"$CRLF$CRLF")
            writer.write(value)
            writer.write(CRLF)
        }
        writer.write("--$boundary$CRLF")
        writer.write("Content-Disposition: form-data; name=\"audio\"; filename=\"$fileName\"$CRLF")
        writer.write("Content-Type: $fileMime$CRLF$CRLF")
        writer.flush()
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            val read = audio.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        output.flush()
        writer.write("$CRLF--$boundary--$CRLF")
        writer.flush()
    }
}
