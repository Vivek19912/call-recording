/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import com.baba.callvault.data.WacrmSession
import com.baba.callvault.utils.AppLogger
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Stable per-install device id. Not a secret: it only lets the
 * server tell "this phone reconnected" from "a second phone took
 * over", so one request cannot open the dialer on two devices.
 */
object DialDeviceId {
    private const val FILE = "dial_socket"
    private const val KEY = "device_id"

    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }
        if (existing != null) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, created).apply()
        return created
    }
}

/**
 * One authenticated, persistent WebSocket to WhatsApp Max for
 * desktop click-to-dial.
 *
 * Deliberately NOT a poller: the server pushes a signal when a
 * request is created, and replays pending rows on connect. There is
 * no periodic HTTP request anywhere in this path.
 *
 * The connection lives in [DialSocketService] (foreground service),
 * so closing the UI does not drop delivery. A reconnect uses the
 * stored session, refreshing the access token once if the server
 * rejects it (close 4401); it never loops on a dead refresh token.
 *
 * Everything on the wire is an id. [DialEventProcessor] performs the
 * authoritative one-shot read before the dialer can open.
 */
object DialSocketClient {

    private const val TAG = "CV:DialWs"
    private const val NO_SESSION_RETRY_MS = 30_000L
    private const val NO_CONFIG_RETRY_MS = 60_000L

    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cv-dial-ws").apply { isDaemon = true }
    }
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "cv-dial-ws-retry").apply { isDaemon = true }
        }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile private var socket: WebSocket? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var attempt = 0
    @Volatile private var reconnectScheduled = false
    @Volatile private var forceRefresh = false

    /** Outstanding consume waits keyed by request id (one-shot). */
    private val consumeWaiters = ConcurrentHashMap<String, CompletableFuture<DialSocketProtocol.ConsumeOutcome>>()

    /** Test seam: override the consume wait timeout. */
    @Volatile
    internal var consumeWaitTimeoutMsForTest: Long? = null

    /** Test seam: deliver a consume frame as if it arrived on the socket. */
    internal fun simulateConsumeForTest(outcome: DialSocketProtocol.ConsumeOutcome): Boolean =
        routeConsume(outcome)

    /**
     * Complete the pending waiter for a consume frame. Returns true
     * when a waiter was actually waiting (stale/duplicate frames are
     * dropped silently apart from a debug log at the call site).
     */
    private fun routeConsume(outcome: DialSocketProtocol.ConsumeOutcome): Boolean {
        val id = when (outcome) {
            is DialSocketProtocol.ConsumeOutcome.Consumed -> outcome.requestId
            is DialSocketProtocol.ConsumeOutcome.Rejected -> outcome.requestId
        }
        return consumeWaiters.remove(id)?.complete(outcome) == true
    }

    /** Test seam: drop pending consume waiters between tests. */
    internal fun resetConsumeStateForTest() {
        consumeWaiters.clear()
        consumeWaitTimeoutMsForTest = null
    }

    /**
     * One-shot wait for the server's consume reply to our own
     * `received` ack. Returns the consume payload, or null on
     * rejection, timeout, or interruption — callers fall back to the
     * authenticated GET in all those cases. Blocking: call only off
     * the UI thread (the dial path already runs on a worker).
     */
    internal fun awaitConsume(
        requestId: String,
        timeoutMs: Long = consumeWaitTimeoutMsForTest ?: 4_000L,
    ): DialSocketProtocol.ConsumeOutcome? {
        val future = CompletableFuture<DialSocketProtocol.ConsumeOutcome>()
        consumeWaiters[requestId] = future
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        } finally {
            consumeWaiters.remove(requestId, future)
        }
    }

    /** Tell the server this request cannot be dialled (undialable number / bad consume payload). */
    fun sendFailed(requestId: String) {
        runCatching {
            socket?.send(
                JSONObject()
                    .put("type", "failed")
                    .put("request_id", requestId)
                    .toString()
            )
        }.onFailure { AppLogger.w(TAG, "failed ack send failed (${it.javaClass.simpleName})") }
    }

    /** Tell the server this device accepted a targeted logout; best-effort, failures ignored. */
    fun sendLogoutAck(deviceId: String, sessionId: String) {
        runCatching {
            socket?.send(DialSocketProtocol.buildLogoutAck(deviceId, sessionId))
        }.onFailure { AppLogger.w(TAG, "logout ack send failed (${it.javaClass.simpleName})") }
    }

    /** Observable for Settings; never used for delivery decisions. */
    @Volatile var isConnected: Boolean = false
        private set

    fun start(context: Context) {
        synchronized(lock) {
            if (running) return
            running = true
            appContext = context.applicationContext
            generation += 1
            attempt = 0
            reconnectScheduled = false
            val gen = generation
            AppLogger.i(TAG, "WebSocket start (no polling)")
            scheduleConnect(gen, 0L)
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            generation += 1
            appContext = null
            isConnected = false
            reconnectScheduled = false
        }
        runCatching { socket?.close(1000, "app stopped") }
        socket = null
        AppLogger.i(TAG, "WebSocket stopped")
    }

    /** Tell the server this device has the request; it marks `delivered`. */
    fun sendReceived(requestId: String) {
        runCatching {
            socket?.send(
                JSONObject()
                    .put("type", "received")
                    .put("request_id", requestId)
                    .toString()
            )
        }.onFailure { AppLogger.w(TAG, "received ack send failed (${it.javaClass.simpleName})") }
    }

    private fun isActive(gen: Int): Boolean = running && gen == generation

    private fun scheduleConnect(gen: Int, delayMs: Long) {
        if (!isActive(gen)) return
        scheduler.schedule(
            { if (isActive(gen)) connect(gen) },
            delayMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun connect(gen: Int) {
        executor.execute {
            if (!isActive(gen)) return@execute
            val ctx = appContext ?: return@execute
            // No upload-enabled gate: a signed-in session alone permits the
            // connection (the old toggle was removed from Settings).
            val prefs = com.baba.callvault.data.AppPreferences(ctx)
            if (WacrmSession.get(ctx) == null) {
                AppLogger.i(TAG, "WebSocket waiting: no WhatsApp Max session")
                scheduleRetry(gen, NO_SESSION_RETRY_MS, refresh = false)
                return@execute
            }
            val base = WacrmApi.normalizeBaseUrl(prefs.getWacrmUrl())
            if (base == null) {
                AppLogger.w(TAG, "WebSocket waiting: server URL missing or not HTTPS")
                scheduleRetry(gen, NO_CONFIG_RETRY_MS, refresh = false)
                return@execute
            }
            if (forceRefresh) {
                forceRefresh = false
                AppLogger.i(TAG, "WebSocket re-authorising after 4401")
                DialSession.refresh(ctx, base)
            }
            val token = DialSession.freshAccessToken(ctx, base)
            if (token == null) {
                AppLogger.w(TAG, "WebSocket waiting: session expired and refresh failed")
                scheduleRetry(gen, NO_SESSION_RETRY_MS, refresh = false)
                return@execute
            }
            // Device registration + revocation status, best-effort: a
            // registration failure never blocks the socket (the next
            // connect retries), but an explicit revoked answer wipes
            // locally and the socket is never opened. A status check
            // that cannot tell (network/parse) leaves everything
            // untouched — it must never sign the user out.
            WacrmDeviceAuth.ensureRegistered(ctx, base, token)
            if (WacrmDeviceAuth.checkStatusAndWipeIfRevoked(ctx, base, token)) return@execute
            val url = DialSocketProtocol.webSocketUrl(base, DialDeviceId.get(ctx))
            if (url == null) {
                AppLogger.w(TAG, "WebSocket waiting: could not build wss URL")
                scheduleRetry(gen, NO_CONFIG_RETRY_MS, refresh = false)
                return@execute
            }
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()
            val listener = listener(gen, ctx, base)
            val next = http.newWebSocket(request, listener)
            socket?.let { old -> runCatching { old.close(1000, "replaced locally") } }
            socket = next
        }
    }

    private fun listener(gen: Int, context: Context, baseUrl: String): WebSocketListener =
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isActive(gen)) {
                    runCatching { webSocket.close(1000, "stale") }
                    return
                }
                isConnected = true
                attempt = 0
                reconnectScheduled = false
                AppLogger.i(TAG, "WebSocket connected (auth accepted)")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Server-driven sign-out: wipe locally ONLY when the
                // frame targets this install (device id) and the
                // current session (session id). Anything else — a
                // bare frame, a cross-device push, a stale replay, a
                // duplicate after the wipe — is ignored. On a match
                // the order is: HTTP ack (best-effort, before the
                // wipe clears the token) → socket logout_ack frame →
                // wipe → close 1000 (deliberate — never reconnects).
                // The wipe stops the service, which invalidates this
                // listener's generation, so no retry can follow.
                if (DialSocketProtocol.isLogoutDevice(text)) {
                    handleLogoutDevice(webSocket, context, baseUrl, text)
                    return
                }
                // Consume replies complete the one-shot wait registered
                // by DialEventProcessor; anything unconsumed falls
                // through to the normal signal path below.
                when (val consume = DialSocketProtocol.parseConsume(text)) {
                    is DialSocketProtocol.ConsumeOutcome.Consumed -> {
                        if (routeConsume(consume)) {
                            AppLogger.i(TAG, "Consume payload for ${DialRequests.maskId(consume.requestId)}")
                        }
                        return
                    }
                    is DialSocketProtocol.ConsumeOutcome.Rejected -> {
                        if (routeConsume(consume)) {
                            AppLogger.i(TAG, "Consume rejected for ${DialRequests.maskId(consume.requestId)}")
                        }
                        return
                    }
                    null -> { /* not a consume frame: try the signal path */ }
                }
                val event = DialSocketProtocol.parse(text)
                if (event == null) {
                    // `ready` and malformed frames are ignored; a frame
                    // without a valid id can never reach the dialer.
                    return
                }
                AppLogger.i(TAG, "Signal ${DialRequests.maskId(event.requestId)} received")
                executor.execute { DialEventProcessor.handle(context, event.requestId) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                runCatching { webSocket.close(code, reason) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                if (!isActive(gen)) return
                if (!DialSocketProtocol.shouldReconnect(code)) {
                    AppLogger.i(TAG, "WebSocket closed ($code) — not reconnecting")
                    return
                }
                AppLogger.w(TAG, "WebSocket closed ($code); reconnecting")
                scheduleRetry(
                    gen,
                    DialSocketProtocol.reconnectDelayMs(attempt, (0..400).random().toLong()),
                    refresh = code == 4401 || code == 1008
                )
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                if (!isActive(gen)) return
                AppLogger.w(
                    TAG,
                    "WebSocket failure (${t.javaClass.simpleName}, HTTP ${response?.code ?: 0}); reconnecting"
                )
                scheduleRetry(
                    gen,
                    DialSocketProtocol.reconnectDelayMs(attempt, (0..400).random().toLong()),
                    refresh = response?.code == 401
                )
            }
        }

    /**
     * Targeted-logout gate for one `logout_device` frame. Untargeted
     * frames are ignored with a log; a targeted match runs
     * ack → wipe → close on the socket executor (the HTTP ack is
     * blocking, so it must stay off the OkHttp reader thread).
     * Everything is best-effort except the wipe itself: ack failures
     * never block it, and the close is always attempted.
     */
    private fun handleLogoutDevice(
        webSocket: WebSocket,
        context: Context,
        baseUrl: String,
        text: String,
    ) {
        val target = DialSocketProtocol.parseLogoutDevice(text)
        if (target == null) {
            AppLogger.w(TAG, "Logout frame without device/session targeting; ignoring")
            return
        }
        val (frameDeviceId, frameSessionId) = target
        executor.execute {
            val app = context.applicationContext
            // The CURRENT stored session only — a cached token could
            // match a frame for a rotated-away session.
            val currentToken = WacrmSession.get(app)?.accessToken
            val localDeviceId = runCatching { DialDeviceId.get(app) }.getOrNull()
            val localSessionId = WacrmDeviceAuth.parseSessionId(currentToken)
            if (!DialSocketProtocol.isLogoutTargeted(frameDeviceId, frameSessionId, localDeviceId, localSessionId)) {
                AppLogger.i(TAG, "Logout frame for another device/session; ignoring")
                return@execute
            }
            AppLogger.w(TAG, "WEBSOCKET_LOGOUT_RECEIVED targeted at this device; acknowledging then applying remote logout")
            // HTTP ack first: the wipe below clears the token it needs.
            runCatching {
                val token = requireNotNull(currentToken)
                val device = requireNotNull(localDeviceId)
                WacrmDeviceAuth.postLogoutAck(baseUrl, token, device)
            }.onFailure { AppLogger.w(TAG, "Logout HTTP ack failed (${it.javaClass.simpleName}); wiping anyway") }
            // Socket ack while the connection is still ours; the wipe
            // stops the service and drops the `socket` field, so this
            // must precede it (sendLogoutAck is a no-op then).
            sendLogoutAck(frameDeviceId, frameSessionId)
            WacrmRemoteLogout.applyRemoteLogout(app)
            runCatching { webSocket.close(1000, "logout") }
        }
    }

    private fun scheduleRetry(gen: Int, delayMs: Long, refresh: Boolean) {
        if (!isActive(gen)) return
        if (refresh) forceRefresh = true
        if (reconnectScheduled) return
        reconnectScheduled = true
        attempt = (attempt + 1).coerceAtMost(6)
        scheduler.schedule(
            {
                reconnectScheduled = false
                if (isActive(gen)) connect(gen)
            },
            delayMs,
            TimeUnit.MILLISECONDS
        )
    }
}
