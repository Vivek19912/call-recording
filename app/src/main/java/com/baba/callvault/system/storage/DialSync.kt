package com.baba.callvault.system.storage

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.WacrmSession
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.utils.AppLogger

/**
 * Foreground sync for click-to-dial requests.
 *
 * There is no push channel in this app (no FCM — see the delivery
 * note below), so desktop → device delivery is a foreground poll:
 * when the app is opened and the WhatsApp Max session is connected,
 * pending requests created by the logged-in user are fetched,
 * validated, shown as notifications, and acked `delivered`. Tapping
 * a notification opens the native dialer (ACTION_DIAL) and the
 * request is acked `opened` (one-time consumption enforced
 * server-side).
 *
 * Delivery accounting:
 * - When the app is foregrounded and unlocked, the oldest pending
 *   request opens the native dialer DIRECTLY ([directOpen]), with
 *   no "Open dialer" prompt in between. `opened` is acked ONLY
 *   after the dialer launch is initiated; a failed launch falls
 *   back to the notification path instead of losing the request.
 * - Otherwise (locked, backgrounded poll, launch blocked) each row
 *   becomes a high-priority notification, and `delivered` is acked
 *   ONLY after the notification was actually posted
 *   ([DialRequestNotifications.show] returned true). A denied
 *   notification permission, an expired row, or a post failure
 *   leaves the row un-acked so the next open retries.
 * - One request opens the dialer at most once per process
 *   ([handledIds]) on top of the server-side one-time consumption,
 *   so polling, lifecycle callbacks, and duplicate server rows can
 *   never redial.
 * - Every poll outcome is logged (counts + HTTP codes + id
 *   prefixes; never tokens or phone digits) so a failed GET,
 *   parse drift, or missing notification is diagnosable instead
 *   of silent.
 *
 * DELIVERY LIMITATION (no FCM configured): this app has no
 * Firebase project, no `google-services.json`, and the backend has
 * no FCM server credentials — so there is no push/wake path. The
 * phone must have the app opened (or recent) for delivery. Instant
 * background delivery is NOT available and is not claimed. Adding
 * it requires a Firebase project + server key + device-token
 * registration, none of which exist today.
 */
object DialSync {

    private const val TAG = "CV:Dial"

    /**
     * Request ids already presented on screen this process lifetime
     * (dialer opened directly, or notification posted). On top of the
     * server-side one-time consumption: polling, lifecycle callbacks,
     * and duplicate server rows can never reopen the same request.
     */
    private val handledIds: MutableSet<String> =
        java.util.Collections.synchronizedSet(LinkedHashSet())

    /** How the poller will handle one request. Pure — unit tested. */
    internal enum class DeliveryKind { DIRECT, NOTIFY, SKIP }

    /** One row's delivery decision. Pure — unit tested. */
    internal data class DeliveryPlan(
        val request: DialRequests.DialRequest,
        val kind: DeliveryKind,
    )

    /**
     * Decides per-row handling for one poll, recording DIRECT rows in
     * [handled] exactly once. Pure — unit tested.
     *
     * - not actionable (wrong status / expired / undialable) → SKIP.
     * - already presented this process → SKIP (no repeats).
     * - [allowDirect] (foreground + unlocked + caller supplied) →
     *   DIRECT, else NOTIFY (notification fallback).
     */
    internal fun planDeliveries(
        requests: List<DialRequests.DialRequest>,
        nowMs: Long,
        handled: MutableSet<String>,
        allowDirect: Boolean,
    ): List<DeliveryPlan> {
        val notifiableIds = selectNotifiable(requests, nowMs).map { it.id }.toSet()
        // Only the oldest actionable row auto-opens per poll; the
        // rest notify, so one poll never fires the dialer twice.
        // Only DIRECT rows consume the repeat-suppression set: NOTIFY
        // rows stay re-notifiable so a failed delivered ack is
        // retried on the next poll.
        var directAssigned = false
        return requests.map { request ->
            when {
                request.id !in notifiableIds -> DeliveryPlan(request, DeliveryKind.SKIP)
                request.id in handled -> DeliveryPlan(request, DeliveryKind.SKIP)
                allowDirect && !directAssigned -> {
                    directAssigned = true
                    handled.add(request.id)
                    DeliveryPlan(request, DeliveryKind.DIRECT)
                }
                else -> DeliveryPlan(request, DeliveryKind.NOTIFY)
            }
        }
    }

    /**
     * @param directOpen opens the dialer for a validated row. The
     *   foreground caller (MainActivity) supplies a UI-thread
     *   launcher; background callers (DialPollWorker) supply
     *   [DialAutoOpen.openDirectly]. Null (or a locked app) keeps the
     *   notification-only path.
     * @param allowDirectOverride when non-null, replaces the
     *   foreground/unlocked check (used by background callers that
     *   apply their own gate, e.g. overlay permission + no app lock).
     */
    fun checkAndNotify(
        context: Context,
        directOpen: ((DialRequests.DialRequest) -> Boolean)? = null,
        allowDirectOverride: Boolean? = null,
    ) {
        val app = context.applicationContext
        val prefs = AppPreferences(app)
        if (!prefs.isWacrmEnabled()) {
            AppLogger.i(TAG, "Dial poll skipped: WACRM integration disabled")
            return
        }
        val base = WacrmApi.normalizeBaseUrl(prefs.getWacrmUrl())
        if (base == null) {
            AppLogger.w(TAG, "Dial poll skipped: server URL is missing or not HTTPS")
            return
        }
        if (WacrmSession.get(app) == null) {
            AppLogger.i(TAG, "Dial poll skipped: no WhatsApp Max session (disconnected)")
            return
        }
        var token = DialSession.freshAccessToken(app, base)
        if (token == null) {
            AppLogger.w(TAG, "Dial poll skipped: session expired and refresh failed; reconnect required")
            return
        }

        var fetched = DialRequests.fetchPending(base, token)
        if (fetched is DialRequests.FetchResult.HttpFailure && fetched.statusCode == 401) {
            // Possibly a token that died mid-flight: exactly one
            // refresh + one refetch. A second 401 is terminal —
            // looping cannot mint a login.
            AppLogger.i(TAG, "Dial poll got 401; refreshing the session once")
            token = DialSession.refresh(app, base)?.accessToken
            if (token == null) {
                AppLogger.w(TAG, "Dial poll aborted: refresh rejected; reconnect required")
                return
            }
            fetched = DialRequests.fetchPending(base, token)
        }

        val pending = when (fetched) {
            is DialRequests.FetchResult.Success -> fetched.requests
            is DialRequests.FetchResult.HttpFailure -> {
                AppLogger.w(TAG, "Dial poll aborted: HTTP ${fetched.statusCode}")
                return
            }
            DialRequests.FetchResult.NetworkError -> {
                AppLogger.w(TAG, "Dial poll aborted: network unreachable; next open retries")
                return
            }
            DialRequests.FetchResult.ParseError -> {
                AppLogger.w(TAG, "Dial poll aborted: server response unparseable (contract drift?)")
                return
            }
        }
        if (pending.isEmpty()) {
            AppLogger.i(TAG, "Dial poll: 0 pending requests")
            return
        }
        val nowMs = System.currentTimeMillis()
        // Direct launch only when the app is truly in front of the
        // user (foreground caller + unlocked), or when a background
        // caller explicitly vouches via allowDirectOverride (overlay
        // permission + no app lock — see DialAutoOpen). Otherwise
        // every row takes the notification fallback.
        val opener = directOpen
        val allowDirect = if (allowDirectOverride != null) {
            opener != null && allowDirectOverride
        } else {
            opener != null && com.baba.callvault.MainActivity.isUnlockedForDial(app)
        }
        val plans = planDeliveries(pending, nowMs, handledIds, allowDirect)
        trimHandled()
        val actionable = plans.count { it.kind != DeliveryKind.SKIP }
        AppLogger.i(TAG, "Dial poll: ${pending.size} received, $actionable actionable (direct=$allowDirect)")
        for (plan in plans) {
            val request = plan.request
            // Account isolation: the server scopes rows to the
            // credential's account AND requesting user; a session
            // change that clears WacrmSession stops all polling
            // above, so a signed-out or switched account never sees
            // another account's rows.
        when (plan.kind) {
                DeliveryKind.SKIP -> Unit
                DeliveryKind.DIRECT -> tryDirectOpen(app, base, token, opener!!, request)
                DeliveryKind.NOTIFY -> notifyFallback(app, base, token, request)
            }
        }
    }

    /** Direct-open attempt + `opened` ack on success, notification fallback otherwise. */
    private fun tryDirectOpen(
        app: Context,
        base: String,
        token: String,
        open: (DialRequests.DialRequest) -> Boolean,
        request: DialRequests.DialRequest,
    ) {
        val opened = runCatching { open(request) }.getOrDefault(false)
        if (!opened) {
            // Blocked launch (locked/closing activity, denied overlay):
            // compliant notification fallback, never a silent drop
            // and never an auto-retry loop.
            AppLogger.i(TAG, "Dial ${DialRequests.maskId(request.id)}: direct launch blocked; falling back to notification")
            notifyFallback(app, base, token, request)
            return
        }
        val code = DialRequests.ack(base, token, request.id, "opened")
        if (code in 200..299) {
            AppLogger.i(TAG, "Dial ${DialRequests.maskId(request.id)}: dialer opened directly + consumed (${DialRequests.digitCount(request.phoneNumber)} digits)")
        } else {
            // Row stays actionable server-side; this process will not
            // reopen it (handled set), a later poll re-notifies.
            AppLogger.w(TAG, "Dial ${DialRequests.maskId(request.id)}: dialer opened but opened-ack HTTP $code; row stays actionable")
        }
    }

    /** Notification path: post, then ack `delivered` only on success. */
    private fun notifyFallback(
        app: Context,
        base: String,
        token: String,
        request: DialRequests.DialRequest,
    ) {
        if (!PermissionChecks.hasNotificationPermission(app)) {
            AppLogger.w(TAG, "Dial ${DialRequests.maskId(request.id)}: notifications disabled — request held, none marked delivered")
            return
        }
        val shown = DialRequestNotifications.show(app, request)
        if (!shown) {
            AppLogger.w(TAG, "Dial ${DialRequests.maskId(request.id)}: notification not posted; not marking delivered")
            return
        }
        val code = DialRequests.ack(base, token, request.id, "delivered")
        if (code !in 200..299) {
            AppLogger.w(TAG, "Dial ${DialRequests.maskId(request.id)}: delivered ack HTTP $code; will retry next open")
        } else {
            AppLogger.i(TAG, "Dial ${DialRequests.maskId(request.id)}: notified + delivered (${DialRequests.digitCount(request.phoneNumber)} digits)")
        }
    }

    private fun trimHandled() {
        synchronized(handledIds) {
            while (handledIds.size > 100) {
                handledIds.remove(handledIds.iterator().next())
            }
        }
    }

    /**
     * Rows safe to notify right now: actionable statuses, not yet
     * expired, and holding a dialable number. Pure — unit tested.
     * Expiry is re-checked here because a row can outlive its TTL
     * between the server read and this poll.
     */
    internal fun selectNotifiable(
        requests: List<DialRequests.DialRequest>,
        nowMs: Long,
    ): List<DialRequests.DialRequest> = requests.filter { request ->
        when {
            request.status != "pending" && request.status != "delivered" -> false
            DialRequests.isExpired(request.expiresAt, nowMs) -> false
            !DialPhoneValidation.isDialable(request.phoneNumber) -> false
            else -> true
        }
    }
}
