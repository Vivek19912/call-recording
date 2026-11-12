/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.WacrmSession
import com.baba.callvault.data.voip.WhatsappRecordingSource
import com.baba.callvault.services.call.PhoneRecordingSource
import com.baba.callvault.utils.AppLogger
import java.net.HttpURLConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Synchronizes the authenticated user's owner-assigned recording
 * policy from WhatsApp Max (`GET {base}/api/auth/me`, same session
 * as uploads — no API keys, no service role). Covers the phone
 * number (`phone_recording_number`, mapped to the live
 * subscription on-device), the legacy phone slot (kept stored,
 * inert), and the WhatsApp source.
 *
 * Called after a successful Connect and after every successful
 * session refresh (exactly "when connectivity returns"). A
 * failed fetch or an unknown value keeps the last-known policy:
 * temporary network trouble must never silently flip recording.
 * Only successfully parsed server values are stored — even an
 * explicit 'none' (the owner turned this user off).
 */
object WhatsappSourceSync {

    private const val TAG = "CV:WhatsappSourceSync"

    /** All policies fetched together; each null when absent/unparseable. */
    data class SyncedPolicy(
        val whatsapp: WhatsappRecordingSource?,
        val phone: PhoneRecordingSource?,
        /** Owner-configured number (normalized digits); meaningful only when [phoneNumberPresent]. */
        val phoneNumber: String?,
        /** True when the server sent an explicit `phoneRecordingNumber` (even null = None). */
        val phoneNumberPresent: Boolean,
        /** Owner's explicit phone opt-in; null when the server predates the flag. */
        val phoneRecordingEnabled: Boolean?,
        /** Whether the Owner has ever saved recording settings for this user. */
        val phoneRecordingConfigured: Boolean?,
    )

    /**
     * Upper bound for trusting a synced Owner policy without a fresh
     * contact (7 days). Past this, recording stops rather than running
     * on an indefinitely stale policy — fail closed, never silent.
     * Syncs happen on login and on every token refresh, so in practice
     * this only bites after a week fully offline.
     *
     * This constant governs RECORDING VALIDITY ONLY (the
     * [CallSessionManager]/VoIP gates). It must never be reused as a
     * network-fetch throttle — see [POLICY_SYNC_INTERVAL_MS].
     */
    const val POLICY_MAX_STALE_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * How often an already-logged-in phone re-asks the server for the
     * Owner policy while the app is in the foreground (60 s). Deliberately
     * independent of [POLICY_MAX_STALE_MS]: validity is a security
     * boundary (fail closed when old), this is a chattiness bound (fetch
     * when the last SUCCESS is older than this). A failed fetch never
     * moves the success stamp, so failures retry on the next trigger
     * instead of being treated as fresh.
     */
    const val POLICY_SYNC_INTERVAL_MS = 60_000L

    /**
     * The last successfully synced Owner policy, published after every
     * successful parse+persist (and after every policy clear). The
     * read-only Settings UI collects this, so an already-open screen
     * recomposes automatically — no unrelated UI action required.
     * Null until the first publish in this process; readers fall back
     * to a direct prefs read for that first frame.
     */
    private val _policyFlow = MutableStateFlow<ObservedPolicy?>(null)
    val policyFlow: StateFlow<ObservedPolicy?> = _policyFlow.asStateFlow()

    /** Snapshot of the Owner policy as the UI displays it. */
    data class ObservedPolicy(
        val phoneNumber: String?,
        val phoneEnabled: Boolean,
        val phoneConfigured: Boolean,
        val whatsapp: WhatsappRecordingSource,
        val lastSyncMs: Long,
    )

    /** Reads the currently stored policy (no network). */
    fun currentPolicy(prefs: AppPreferences): ObservedPolicy = ObservedPolicy(
        phoneNumber = prefs.getPhoneRecordingNumber(),
        phoneEnabled = prefs.isPhoneRecordingEnabled(),
        phoneConfigured = prefs.isPhoneRecordingConfigured(),
        whatsapp = prefs.getWhatsappRecordingSource(),
        lastSyncMs = prefs.getLastPolicySyncMs(),
    )

    /** Publishes the currently stored policy (no network). */
    fun publishCurrent(context: Context) {
        _policyFlow.value = currentPolicy(AppPreferences(context.applicationContext))
    }

    private fun publishFromPrefs(prefs: AppPreferences) {
        _policyFlow.value = currentPolicy(prefs)
    }

    /** True when a fresh-enough Owner policy is stored (or was just fetched). */
    fun isPolicyFresh(
        prefs: AppPreferences,
        maxAgeMs: Long = POLICY_MAX_STALE_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val last = prefs.getLastPolicySyncMs()
        return last > 0 && nowMs - last <= maxAgeMs
    }

    /**
     * Drops the locally stored Owner recording policy (number, explicit
     * opt-in, configured flag, last-sync stamp). Used on logout and before
     * the first sync of a fresh login, so a stale or previous-user policy
     * can never authorize recording. Does not touch the session itself.
     * Publishes the cleared state so open UI drops the previous user's
     * values immediately.
     */
    fun clearRecordingPolicy(prefs: AppPreferences) {
        prefs.setPhoneRecordingNumber(null)
        prefs.setPhoneRecordingEnabled(false)
        prefs.setPhoneRecordingConfigured(false)
        prefs.setLastPolicySyncMs(0L)
        publishFromPrefs(prefs)
    }

    fun syncAfterAuth(
        context: Context,
        baseUrl: String,
        accessToken: String,
        /** Stable per-install id for server-driven sign-out; header only, never logged. */
        deviceId: String? = null,
        openConnection: (String) -> HttpURLConnection = { url ->
            @Suppress("UsePropertyAccessSyntax")
            (java.net.URI(url).toURL().openConnection() as HttpURLConnection)
        },
    ): WhatsappRecordingSource? {
        // Who we are syncing FOR. The fetch below awaits a network round
        // trip; if the account signs out or switches user mid-flight, the
        // late answer belongs to the previous identity and must never
        // overwrite the new one — discard it instead (callers treat null
        // as "no fresh policy", never as permission).
        val app = context.applicationContext
        val before = runCatching { WacrmSession.get(app) }.getOrNull()
        val synced = fetchPolicy(baseUrl, accessToken, openConnection, deviceId) ?: return null
        if (before != null) {
            val after = runCatching { WacrmSession.get(app) }.getOrNull()
            if (after == null || after.userId != before.userId || after.accountId != before.accountId) {
                AppLogger.w(TAG, "Recording policy sync landed after an identity change — discarding")
                return null
            }
        }
        // Each policy applies independently; an absent/unknown one
        // keeps its last-known value (fresh installs default to
        // none via the preference defaults). A successfully parsed
        // value always wins — even an explicit 'none'.
        val prefs = AppPreferences(app)
        synced.whatsapp?.let { prefs.setWhatsappRecordingSource(it) }
        synced.phone?.let { prefs.setPhoneRecordingSource(it) }
        // The server phone number is authoritative after a successful
        // sync: an explicit value (even null = owner set None) wins;
        // an absent field (older server) keeps the last-known number
        // so stale deploys can never silently wipe the config.
        if (synced.phoneNumberPresent) prefs.setPhoneRecordingNumber(synced.phoneNumber)
        // The explicit opt-in and configured-flag follow the same rule:
        // set when the server sends them, untouched otherwise (they stay
        // consistent with each other: enabled is only ever true with a number).
        synced.phoneRecordingEnabled?.let { prefs.setPhoneRecordingEnabled(it) }
        synced.phoneRecordingConfigured?.let { prefs.setPhoneRecordingConfigured(it) }
        prefs.setLastPolicySyncMs(System.currentTimeMillis())
        // Publish only now — after successful parse AND persistence — so
        // observers (the open Settings screen) recompose with the complete
        // new policy. Failures return above and publish nothing.
        publishFromPrefs(prefs)
        AppLogger.i(
            TAG,
            "Recording policy synced: phone=${synced.phone?.wireValue} " +
                "phoneNumber=${synced.phoneNumber?.let { "configured" } ?: if (synced.phoneNumberPresent) "none" else "unchanged"} " +
                "whatsapp=${synced.whatsapp?.wireValue}"
        )
        return synced.whatsapp
    }

    /** Fetches and parses both policies; null on transport failure. Pure except the socket. */
    fun fetchPolicy(
        baseUrl: String,
        accessToken: String,
        openConnection: (String) -> HttpURLConnection,
        deviceId: String? = null,
    ): SyncedPolicy? {        val text = runCatching {
            val connection = openConnection("$baseUrl/api/auth/me")
            connection.requestMethod = "GET"
            connection.doInput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            if (deviceId != null) connection.setRequestProperty(WacrmDeviceAuth.DEVICE_ID_HEADER, deviceId)
            val status = connection.responseCode
            val body = runCatching {
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            connection.disconnect()
            if (status !in 200..299) {
                AppLogger.w(TAG, "Recording policy sync failed: HTTP $status")
                null
            } else {
                body
            }
        }.getOrNull() ?: return null
        return SyncedPolicy(
            whatsapp = WacrmAuth.parseWhatsappSource(text),
            phone = WacrmAuth.parsePhoneSource(text),
            phoneNumber = WacrmAuth.parsePhoneNumber(text),
            phoneNumberPresent = WacrmAuth.hasPhoneNumber(text),
            phoneRecordingEnabled = WacrmAuth.parsePhoneRecordingEnabled(text),
            phoneRecordingConfigured = WacrmAuth.parsePhoneRecordingConfigured(text),
        )
    }

    /**
     * Best-effort foreground refresh of the Owner policy (call off the
     * main thread). Skips when signed out or when the stored policy is
     * still fresh — connecting on every resume would be chatty. Returns
     * true when the policy is fresh afterwards.
     *
     * Legacy entry point kept for compatibility; new UI triggers use
     * [syncIfDue], which throttles on [POLICY_SYNC_INTERVAL_MS] instead
     * of the 7-day validity bound.
     */
    fun syncIfStale(
        context: Context,
        baseUrl: String,
        maxAgeMs: Long = POLICY_MAX_STALE_MS,
        openConnection: (String) -> HttpURLConnection = { url ->
            java.net.URI(url).toURL().openConnection() as HttpURLConnection
        },
    ): Boolean {
        val app = context.applicationContext
        val prefs = AppPreferences(app)
        if (WacrmSession.get(app) == null) return false
        if (isPolicyFresh(prefs, maxAgeMs)) return true
        val token = DialSession.freshAccessToken(app, baseUrl) ?: return false
        syncAfterAuth(app, baseUrl, token, null, openConnection)
        return isPolicyFresh(prefs, maxAgeMs)
    }

    /**
     * Whether a foreground fetch is due: the last SUCCESS is older than
     * [intervalMs] (or there never was one), or the caller forces it.
     * Pure, so the throttle matrix is unit-testable. Note the deliberate
     * asymmetry with [isPolicyFresh]: a 5-minute-old policy is VALID for
     * recording but STALE for display — this function answers the display
     * question only.
     */
    fun shouldFetchPolicy(
        lastSuccessMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        intervalMs: Long = POLICY_SYNC_INTERVAL_MS,
        force: Boolean = false,
    ): Boolean {
        if (force) return true
        if (lastSuccessMs <= 0L) return true
        return nowMs - lastSuccessMs >= intervalMs
    }

    private val syncLock = Any()

    @Volatile
    private var syncInFlight = false

    /**
     * Single-flight guard: at most one policy fetch runs at a time, so a
     * foreground resume racing an open Settings section (or two rapid
     * section expands) issues exactly one request. Test seam.
     */
    internal fun tryBeginSync(): Boolean = synchronized(syncLock) {
        if (syncInFlight) false else {
            syncInFlight = true
            true
        }
    }

    /** Releases [tryBeginSync]. Test seam. */
    internal fun endSync() {
        synchronized(syncLock) { syncInFlight = false }
    }

    /**
     * Best-effort foreground fetch of the Owner policy (call off the main
     * thread). Fetches when the last SUCCESS is older than
     * [POLICY_SYNC_INTERVAL_MS] (or never happened), independently of the
     * 7-day validity bound — so an Owner edit reaches an already-logged-in
     * phone within about a minute of foreground activity instead of up to
     * a week. No-ops (without network) when signed out, when a fetch just
     * succeeded, or when another fetch is in flight.
     *
     * Failures change nothing: cached values, the success stamp and the
     * published snapshot are left untouched, so a failed request is never
     * presented as an update. Returns true when the policy is valid
     * ([isPolicyFresh]) afterwards.
     */
    fun syncIfDue(
        context: Context,
        baseUrl: String,
        maxIntervalMs: Long = POLICY_SYNC_INTERVAL_MS,
        nowMs: Long = System.currentTimeMillis(),
        force: Boolean = false,
        openConnection: (String) -> HttpURLConnection = { url ->
            java.net.URI(url).toURL().openConnection() as HttpURLConnection
        },
    ): Boolean {
        val app = context.applicationContext
        val prefs = AppPreferences(app)
        if (WacrmSession.get(app) == null) return false
        if (!force && !shouldFetchPolicy(prefs.getLastPolicySyncMs(), nowMs, maxIntervalMs)) {
            return isPolicyFresh(prefs, nowMs)
        }
        if (!tryBeginSync()) return isPolicyFresh(prefs, nowMs)
        try {
            val token = DialSession.freshAccessToken(app, baseUrl) ?: return false
            syncAfterAuth(app, baseUrl, token, null, openConnection)
            return isPolicyFresh(prefs)
        } finally {
            endSync()
        }
    }
}
