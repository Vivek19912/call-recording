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
import java.util.concurrent.CountDownLatch

/**
 * The ONE place that rotates a stored WhatsApp Max session.
 *
 * Before this, [DialSession], the two WorkManager uploaders and the
 * dial socket each carried their own refresh copy: concurrent workers
 * fired parallel HTTP refreshes (Supabase rotates refresh tokens, so
 * the loser's response could overwrite the winner's), and a rejected
 * refresh in one path behaved differently from another.
 *
 * Discipline, enforced here for every caller:
 * - Process-wide single-flight: threads refreshing with the SAME
 *   refresh token join the in-flight HTTP call instead of issuing a
 *   second one. A thread whose token differs waits for the landing,
 *   then re-reads the store (it may already hold a fresh session).
 * - Stale-response guard: the response is persisted only if the
 *   stored refresh token still equals the one used. A superseded (or
 *   disconnected-mid-flight) response is discarded, never saved.
 * - A rejected/unreachable refresh NEVER clears the store: the old
 *   session stays until the user explicitly disconnects.
 *
 * Blocking (HttpURLConnection underneath): call only off the main
 * thread — workers, the socket executor, and IO-dispatched UI scopes.
 * No tokens are ever logged (see LogRedaction).
 */
object WacrmSessionRefresh {

    private const val TAG = "CV:WacrmRefresh"

    sealed interface SessionResult {
        /** A usable stored session (fresh, or newly refreshed + durably saved). */
        data class Valid(val session: WacrmSession.Session) : SessionResult
        /** No session is stored — the user must reconnect. */
        data object SignedOut : SessionResult
        /** The encrypted store could not be opened/read/saved — transient, retry. */
        data class Unavailable(val reason: String) : SessionResult
        /** The refresh HTTP call failed; the previously stored session is untouched. */
        data class RefreshFailed(val reason: String) : SessionResult
    }

    private val lock = Any()
    private var inFlight: Flight? = null

    private class Flight(val refreshToken: String) {
        val done = CountDownLatch(1)

        @Volatile
        var result: SessionResult? = null
    }

    /**
     * Returns a usable session, refreshing first when [force] or the
     * access token is stale (see [WacrmAuth.shouldRefresh]).
     *
     * [refreshCall] and [nowMs] are test seams (defaults hit the network).
     */
    fun refreshIfNeeded(
        context: Context,
        config: WacrmAuth.MobileConfig,
        force: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        refreshCall: (WacrmAuth.MobileConfig, String) -> WacrmAuth.AuthOutcome =
            { c, token -> WacrmAuth.refresh(c, token) },
    ): SessionResult {
        val current = when (val read = WacrmSession.read(context)) {
            is WacrmSession.SessionRead.Available -> read.session
            is WacrmSession.SessionRead.SignedOut -> return SessionResult.SignedOut
            is WacrmSession.SessionRead.Unavailable -> return SessionResult.Unavailable(read.reason)
        }
        if (!force && !WacrmAuth.shouldRefresh(current.expiresAtMs, nowMs)) {
            return SessionResult.Valid(current)
        }
        return singleFlightRefresh(context, config, current, refreshCall)
    }

    private fun singleFlightRefresh(
        context: Context,
        config: WacrmAuth.MobileConfig,
        firstSeen: WacrmSession.Session,
        refreshCall: (WacrmAuth.MobileConfig, String) -> WacrmAuth.AuthOutcome,
    ): SessionResult {
        var current = firstSeen
        while (true) {
            var mine = false
            var join: Flight? = null
            var other: Flight? = null
            synchronized(lock) {
                val flying = inFlight
                when {
                    flying == null -> {
                        val flight = Flight(current.refreshToken)
                        inFlight = flight
                        mine = true
                    }
                    flying.refreshToken == current.refreshToken -> join = flying
                    else -> other = flying
                }
            }
            if (mine) {
                val flight = synchronized(lock) { requireNotNull(inFlight) }
                return fly(context, config, current, flight, refreshCall)
            }
            if (join != null) {
                await(join)
                // The winner saved before releasing us, so the store now
                // holds exactly what the shared call produced.
                return when (val read = WacrmSession.read(context)) {
                    is WacrmSession.SessionRead.Available -> SessionResult.Valid(read.session)
                    is WacrmSession.SessionRead.SignedOut -> SessionResult.SignedOut
                    is WacrmSession.SessionRead.Unavailable ->
                        SessionResult.Unavailable(read.reason)
                }
            }
            // A different token is landing: wait for it, then re-read —
            // our snapshot may already be superseded (or fresh again).
            await(requireNotNull(other))
            current = when (val read = WacrmSession.read(context)) {
                is WacrmSession.SessionRead.Available -> read.session
                is WacrmSession.SessionRead.SignedOut -> return SessionResult.SignedOut
                is WacrmSession.SessionRead.Unavailable ->
                    return SessionResult.Unavailable(read.reason)
            }
            if (!WacrmAuth.shouldRefresh(current.expiresAtMs, System.currentTimeMillis())) {
                return SessionResult.Valid(current)
            }
        }
    }

    private fun await(flight: Flight) {
        try {
            flight.done.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun fly(
        context: Context,
        config: WacrmAuth.MobileConfig,
        current: WacrmSession.Session,
        flight: Flight,
        refreshCall: (WacrmAuth.MobileConfig, String) -> WacrmAuth.AuthOutcome,
    ): SessionResult {
        val outcome = try {
            doRefresh(context, config, current, refreshCall)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Session refresh failed (${e.javaClass.simpleName}); keeping stored session")
            SessionResult.RefreshFailed("could not refresh the session")
        }
        synchronized(lock) {
            flight.result = outcome
            if (inFlight === flight) inFlight = null
            flight.done.countDown()
        }
        return outcome
    }

    private fun doRefresh(
        context: Context,
        config: WacrmAuth.MobileConfig,
        current: WacrmSession.Session,
        refreshCall: (WacrmAuth.MobileConfig, String) -> WacrmAuth.AuthOutcome,
    ): SessionResult {
        val outcome = runCatching { refreshCall(config, current.refreshToken) }.getOrElse {
            AppLogger.w(TAG, "Session refresh errored (${it.javaClass.simpleName}); keeping stored session")
            return SessionResult.RefreshFailed("network unreachable")
        }
        if (outcome !is WacrmAuth.AuthOutcome.Ok) {
            // Rejected or unreachable: keep the stored session exactly as
            // it is — clearing here would sign the user out on a blip.
            return when (outcome) {
                is WacrmAuth.AuthOutcome.InvalidCredentials -> {
                    AppLogger.w(TAG, "Session refresh rejected; reconnect required (stored session kept)")
                    SessionResult.RefreshFailed("the session expired; reconnect required")
                }
                is WacrmAuth.AuthOutcome.NetworkError -> {
                    AppLogger.w(TAG, "Session refresh network failure; keeping stored session")
                    SessionResult.RefreshFailed("network unreachable")
                }
                is WacrmAuth.AuthOutcome.ServerError -> {
                    AppLogger.w(TAG, "Session refresh server error (HTTP ${outcome.status}); keeping stored session")
                    SessionResult.RefreshFailed("server error (HTTP ${outcome.status})")
                }
                else -> SessionResult.RefreshFailed("could not refresh the session")
            }
        }
        // Re-read BEFORE saving: another refresh may have won while our
        // HTTP call was in flight (Supabase rotates refresh tokens, so a
        // stale write would orphan the winner's tokens).
        when (val latest = WacrmSession.read(context)) {
            is WacrmSession.SessionRead.Unavailable ->
                return SessionResult.Unavailable(latest.reason)
            is WacrmSession.SessionRead.SignedOut -> {
                // Disconnected mid-flight: do not resurrect a session.
                AppLogger.i(TAG, "Session refresh landed after disconnect; discarding")
                return SessionResult.SignedOut
            }
            is WacrmSession.SessionRead.Available -> {
                if (latest.session.refreshToken != current.refreshToken) {
                    AppLogger.i(TAG, "Session refresh superseded; keeping newest stored session")
                    return SessionResult.Valid(latest.session)
                }
            }
        }
        val saved = WacrmSession.save(
            context,
            outcome.session.accessToken,
            outcome.session.refreshToken,
            outcome.session.expiresAtMs,
            current.userId,
            current.userEmail,
            current.userName,
            current.accountId,
            current.accountName,
            current.role,
        )
        if (!saved) {
            AppLogger.w(TAG, "Session refresh could not be persisted; keeping stored session")
            return SessionResult.Unavailable("could not persist refreshed session")
        }
        AppLogger.i(TAG, "Session refreshed")
        return when (val reread = WacrmSession.read(context)) {
            is WacrmSession.SessionRead.Available -> SessionResult.Valid(reread.session)
            is WacrmSession.SessionRead.SignedOut -> SessionResult.SignedOut
            is WacrmSession.SessionRead.Unavailable -> SessionResult.Unavailable(reread.reason)
        }
    }
}
