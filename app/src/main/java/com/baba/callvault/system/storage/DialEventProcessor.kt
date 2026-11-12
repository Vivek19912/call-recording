/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import android.os.SystemClock
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.utils.AppLogger
import java.time.Instant
import java.util.Collections
import java.util.LinkedHashSet

/**
 * Handles ONE WebSocket signal. Runs off the main thread.
 *
 * Order is deliberate:
 *   1. process-lifetime dedupe (one request id opens at most once),
 *   2. tell the server the device received it (`received`),
 *   3. fast path: await the server-verified consume reply on the
 *      same socket (validated number + TTL, no extra HTTPS round
 *      trip); fall back to the one-shot authoritative GET when the
 *      reply is missing, rejected, or unusable,
 *   4. local lifecycle/expiry/number validation,
 *   5. open ACTION_DIAL (or post a notification if the OS blocks it),
 *   6. ack the outcome (`opened`) over the existing HTTP endpoint.
 *
 * Only the server decides what may be dialled: consume payloads
 * come from the authenticated socket after a server-side
 * one-time-consumption check, and the GET path re-reads the
 * authoritative row. A `received` ack alone never opens anything.
 */
object DialEventProcessor {

    private const val TAG = "CV:Dial"
    private const val MAX_HANDLED = 100

    /** Ids already acted on in this process — duplicate frames cannot redial. */
    private val handled: MutableSet<String> =
        Collections.synchronizedSet(LinkedHashSet<String>())

    fun handle(context: Context, requestId: String) {
        // T0 for the latency breakdown reported at the end (no PII).
        val tSignalMs = SystemClock.elapsedRealtime()
        val app = context.applicationContext
        val prefs = AppPreferences(app)
        // No upload-enabled gate: a valid session below is what permits
        // processing (the old toggle was removed from Settings).

        val base = WacrmApi.normalizeBaseUrl(prefs.getWacrmUrl())
        if (base == null) {
            AppLogger.w(TAG, "Event ${DialRequests.maskId(requestId)}: no HTTPS server URL")
            return
        }
        var token = DialSession.freshAccessToken(app, base)
        if (token == null) {
            AppLogger.w(TAG, "Event ${DialRequests.maskId(requestId)}: no usable session")
            return
        }
        val deviceId = DialDeviceId.get(app)

        // One `received` announces presence (idempotent server-side);
        // the consume reply below decides whether the payload is
        // usable. The process-lifetime claim happens only after a
        // path is validated, so a transient failure never consumes
        // the id permanently — a reconnect replay can still dial.
        DialSocketClient.sendReceived(requestId)
        val tReceivedMs = SystemClock.elapsedRealtime()

        // Fast path: the server answers our own `received` ack with a
        // consume reply carrying the server-verified number — one
        // WebSocket round trip instead of a fresh HTTPS GET.
        val consume = DialSocketClient.awaitConsume(requestId)
        if (consume is DialSocketProtocol.ConsumeOutcome.Consumed) {
            val tConsumeMs = SystemClock.elapsedRealtime()
            if (isUsableConsume(consume)) {
                if (!claim(requestId)) {
                    AppLogger.i(TAG, "Event ${DialRequests.maskId(requestId)}: already handled; ignored")
                    return
                }
                val request = DialRequests.DialRequest(
                    id = consume.requestId,
                    phoneNumber = consume.phoneNumber,
                    contactId = null,
                    flowId = null,
                    runId = null,
                    status = "delivered",
                    expiresAt = consume.expiresAt,
                    createdAt = "",
                )
                finish(app, base, token, request, tSignalMs, tReceivedMs, tConsumeMs, path = "consume", deviceId = deviceId)
            } else {
                AppLogger.w(TAG, "Event ${DialRequests.maskId(requestId)}: unusable consume payload; reporting failed")
                DialSocketClient.sendFailed(requestId)
            }
            return
        }
        if (consume is DialSocketProtocol.ConsumeOutcome.Rejected) {
            AppLogger.i(TAG, "Event ${DialRequests.maskId(requestId)}: consume rejected; GET fallback")
        } else {
            AppLogger.i(TAG, "Event ${DialRequests.maskId(requestId)}: no consume reply; GET fallback")
        }

        // GET fallback: the authoritative one-shot read (unchanged
        // legacy path — same validation, same ack discipline).
        var fetched = DialRequests.fetchOne(base, token, requestId, deviceId = deviceId)
        if (fetched is DialRequests.FetchOneResult.HttpFailure && fetched.statusCode == 401) {
            AppLogger.i(TAG, "Event ${DialRequests.maskId(requestId)}: 401; refreshing once")
            token = DialSession.refresh(app, base)?.accessToken
            if (token == null) {
                AppLogger.w(TAG, "Event ${DialRequests.maskId(requestId)}: refresh rejected; reconnect required")
                return
            }
            fetched = DialRequests.fetchOne(base, token, requestId, deviceId = deviceId)
        }
        if (fetched is DialRequests.FetchOneResult.NetworkError) {
            // One immediate retry, not a poll: the socket event is the
            // trigger and this is the same read after a transient drop.
            Thread.sleep(500)
            fetched = DialRequests.fetchOne(base, token, requestId, deviceId = deviceId)
        }
        val tFetchMs = SystemClock.elapsedRealtime()

        val request = when (fetched) {
            is DialRequests.FetchOneResult.Success -> fetched.request
            is DialRequests.FetchOneResult.HttpFailure -> {
                AppLogger.w(TAG, "Event ${DialRequests.maskId(requestId)}: HTTP ${fetched.statusCode}; ignored")
                return
            }
            DialRequests.FetchOneResult.NetworkError -> {
                AppLogger.w(TAG, "Event ${DialRequests.maskId(requestId)}: network unreachable")
                return
            }
            DialRequests.FetchOneResult.ParseError -> {
                AppLogger.w(TAG, "Event ${DialRequests.maskId(requestId)}: unparseable row; ignored")
                return
            }
        }

        if (!DialEventPolicy.isActionable(
                request.status,
                request.expiresAt,
                request.phoneNumber,
                System.currentTimeMillis()
            )
        ) {
            AppLogger.i(TAG, "Event ${DialRequests.maskId(request.id)}: not actionable (status/expiry/number)")
            return
        }

        // Claim only after validation: a transient read failure must
        // never consume the request permanently.
        if (!claim(request.id)) {
            AppLogger.i(TAG, "Event ${DialRequests.maskId(request.id)} already handled; ignored")
            return
        }
        DialSocketClient.sendReceived(request.id)
        val tFetchedValidatedMs = SystemClock.elapsedRealtime()
        finish(app, base, token, request, tSignalMs, tReceivedMs, maxOf(tFetchMs, tFetchedValidatedMs), path = "fetch", deviceId = deviceId)
    }

    /**
     * A consume payload is usable only when the number is dialable and
     * the TTL parses and is still in the future. Anything else is a
     * protocol violation: report `failed` so the server (and the
     * Workspace cell) reflects it instead of silently dropping.
     */
    internal fun isUsableConsume(consume: DialSocketProtocol.ConsumeOutcome.Consumed): Boolean {
        if (!DialPhoneValidation.isDialable(consume.phoneNumber)) return false
        val expiresMs = runCatching { Instant.parse(consume.expiresAt).toEpochMilli() }.getOrNull() ?: return false
        return expiresMs > System.currentTimeMillis()
    }

    /**
     * Shared tail for both paths: open the dialer (or fall back to a
     * notification), ack the outcome, and log the latency breakdown.
     * `tWaitMs` marks the end of the consume wait or the GET read —
     * whichever ran.
     */
    private fun finish(
        app: Context,
        base: String,
        token: String,
        request: DialRequests.DialRequest,
        tSignalMs: Long,
        tReceivedMs: Long,
        tWaitMs: Long,
        path: String,
        deviceId: String,
    ) {
        val opened = if (AppPreferences(app).isDialAutoOpen()) {
            DialOpenDispatcher.open(app, request)
        } else {
            AppLogger.i(TAG, "Dial ${DialRequests.maskId(request.id)}: auto-open off; notification path")
            false
        }

        if (opened) {
            val code = DialRequests.ack(base, token, request.id, "opened", deviceId = deviceId)
            if (code !in 200..299) {
                AppLogger.w(TAG, "Dial ${DialRequests.maskId(request.id)}: opened ack HTTP $code")
            } else {
                AppLogger.i(TAG, "Dial ${DialRequests.maskId(request.id)}: opened + consumed")
            }
            val nowMs = SystemClock.elapsedRealtime()
            AppLogger.i(
                TAG,
                "Dial ${DialRequests.maskId(request.id)}: signal_to_dialer_ms=${nowMs - tSignalMs} " +
                    "(received+${tWaitMs - tReceivedMs}ms, open+${nowMs - tWaitMs}ms, path=$path, " +
                    "${DialRequests.digitCount(request.phoneNumber)} digits)",
            )
            return
        }

        if (!PermissionChecks.hasNotificationPermission(app)) {
            AppLogger.w(TAG, "Dial ${DialRequests.maskId(request.id)}: launch blocked and notifications disabled")
            return
        }
        val shown = DialRequestNotifications.show(app, request)
        AppLogger.i(
            TAG,
            "Dial ${DialRequests.maskId(request.id)}: notification fallback shown=$shown"
        )
    }

    /** Returns true the first time this process sees the id. */
    private fun claim(id: String): Boolean {
        synchronized(handled) {
            if (!handled.add(id)) return false
            while (handled.size > MAX_HANDLED) {
                val oldest = handled.iterator()
                if (!oldest.hasNext()) break
                oldest.next()
                oldest.remove()
            }
            return true
        }
    }

    /** Test seam: forget process-lifetime dedupe. */
    internal fun resetForTest() {
        synchronized(handled) { handled.clear() }
    }
}
