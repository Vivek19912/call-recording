package com.baba.callvault.system.storage

import android.content.Context
import com.baba.callvault.data.WacrmSession
import com.baba.callvault.utils.AppLogger

/**
 * Shared WhatsApp Max session helper for the dial path.
 *
 * Single place for the pre-flight refresh discipline every other
 * WACRM writer already follows: use the stored access token while
 * it lives, rotate it via the refresh token when it is expired (or
 * dies within [WacrmAuth.REFRESH_SKEW_MS]), preserving identity
 * fields and re-syncing recording policies after a rotation.
 *
 * No tokens are ever logged (see LogRedaction).
 */
object DialSession {

    private const val TAG = "CV:Dial"

    /** A usable access token, refreshing first when the stored one is stale. Null when disconnected. */
    fun freshAccessToken(appContext: Context, baseUrl: String): String? {
        val current = WacrmSession.get(appContext)
        if (current == null) {
            AppLogger.i(TAG, "Dial: no WhatsApp Max session (disconnected)")
            return null
        }
        if (!WacrmAuth.shouldRefresh(current.expiresAtMs, System.currentTimeMillis())) {
            return current.accessToken
        }
        AppLogger.i(TAG, "Dial: access token stale; refreshing before use")
        return refresh(appContext, baseUrl)?.accessToken
    }

    /**
     * Rotates the stored session's tokens via the refresh token.
     * Returns the renewed session, or null when refresh is
     * impossible (no session, no config, rejected refresh token).
     * Identity fields are preserved from storage — refresh
     * responses carry no new identity.
     */
    fun refresh(appContext: Context, baseUrl: String): WacrmSession.Session? {
        val current = WacrmSession.get(appContext) ?: return null
        val config = WacrmAuth.fetchMobileConfig(baseUrl)
        if (config == null) {
            AppLogger.w(TAG, "Dial: refresh impossible (no mobile config)")
            return null
        }
        return when (val outcome = WacrmAuth.refresh(config, current.refreshToken)) {
            is WacrmAuth.AuthOutcome.Ok -> {
                WacrmSession.save(
                    appContext,
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
                WhatsappSourceSync.syncAfterAuth(
                    appContext,
                    baseUrl,
                    outcome.session.accessToken
                )
                AppLogger.i(TAG, "Dial: session refreshed")
                WacrmSession.get(appContext)
            }
            else -> {
                AppLogger.w(TAG, "Dial: refresh rejected (${outcome.javaClass.simpleName}); reconnect required")
                null
            }
        }
    }
}
