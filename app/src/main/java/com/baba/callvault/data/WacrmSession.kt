/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.baba.callvault.utils.AppLogger

/**
 * Encrypted store for the WhatsApp Max user session (Phase 2: user login replaces API keys).
 *
 * Holds the Supabase access + refresh tokens and the identity snapshot from
 * `GET /api/auth/me` (user id/email/name, account id/name, role). Same encrypted-storage
 * discipline as [WacrmSecrets] (EncryptedSharedPreferences + Keystore MasterKey, no plaintext
 * fallback) — extended here because a session is several fields with one lifecycle, not one key.
 *
 * What is deliberately NOT here: the password (never persisted — it lives only in the login
 * call's locals), and the legacy API key (stays in [WacrmSecrets], inert since nothing reads it
 * for uploads anymore; cleared only by explicit Disconnect of the old flow, never migrated).
 */
object WacrmSession {

    private const val TAG = "CV:WacrmSession"
    private const val FILE_NAME = "wacrm_session"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT_MS = "expires_at_ms"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_ACCOUNT_ID = "account_id"
    private const val KEY_ACCOUNT_NAME = "account_name"
    private const val KEY_ROLE = "role"

    /** The persisted session, or null when disconnected (or the store cannot be opened). */
    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtMs: Long,
        val userId: String,
        val userEmail: String?,
        val userName: String?,
        val accountId: String,
        val accountName: String?,
        val role: String?,
    )

    /**
     * Reading the store has three outcomes, and callers must tell the
     * last two apart: [SignedOut] means "no session was ever stored"
     * (terminal for workers — mark FAILED + ask for reconnect), while
     * [Unavailable] means "the encrypted store itself could not be
     * opened or read" (transient — retry, never mark FAILED).
     */
    sealed interface SessionRead {
        data class Available(val session: Session) : SessionRead
        data object SignedOut : SessionRead
        data class Unavailable(val reason: String) : SessionRead
    }

    /**
     * Minimal store surface over the encrypted prefs. Exists so JVM
     * unit tests can substitute an in-memory fake without Robolectric;
     * production always uses [SharedPrefsBackingStore].
     */
    internal interface BackingStore {
        fun getString(key: String): String?
        fun getLong(key: String, def: Long): Long
        /** Persists all string fields + expiry atomically. True only if committed. */
        fun putAll(strings: Map<String, String?>, expiresAtMs: Long): Boolean
        /** Removes every stored field. True only if committed. */
        fun clearAll(): Boolean
    }

    private class SharedPrefsBackingStore(private val prefs: SharedPreferences) : BackingStore {
        override fun getString(key: String): String? = prefs.getString(key, null)
        override fun getLong(key: String, def: Long): Long = prefs.getLong(key, def)
        override fun putAll(strings: Map<String, String?>, expiresAtMs: Long): Boolean {
            val editor = prefs.edit()
            for ((key, value) in strings) editor.putString(key, value)
            editor.putLong(KEY_EXPIRES_AT_MS, expiresAtMs)
            // commit(), not apply(): login/refresh must not report success until the
            // session is durably on disk. Callers invoke this off the main thread.
            return editor.commit()
        }
        override fun clearAll(): Boolean = prefs.edit().clear().commit()
    }

    /**
     * Unit-test seam: when non-null, [read]/[save]/[clear] use this
     * instead of EncryptedSharedPreferences. A null return (or a throw)
     * is treated exactly like an unopenable encrypted store.
     */
    @Volatile
    internal var openBackingStoreForTest: ((Context) -> BackingStore?)? = null

    private fun openStore(context: Context): BackingStore? {
        val override = openBackingStoreForTest
        if (override != null) {
            return runCatching { override(context) }.onFailure {
                AppLogger.w(TAG, "Session store override failed: ${it.message}")
            }.getOrNull()
        }
        return runCatching {
            SharedPrefsBackingStore(
                requireNotNull(open(context)) { "encrypted store unavailable" }
            )
        }.onFailure {
            AppLogger.w(TAG, "Encrypted store unavailable: ${it.message}")
        }.getOrNull()
    }

    private fun open(context: Context): SharedPreferences? {
        return runCatching {
            val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.onFailure {
            AppLogger.w(TAG, "Encrypted store unavailable: ${it.message}")
        }.getOrNull()
    }

    /**
     * Reads the stored session, distinguishing "signed out" from "store
     * unreadable" (see [SessionRead]). Never logs values.
     */
    fun read(context: Context): SessionRead {
        val store = runCatching { openStore(context) }.getOrNull()
            ?: return SessionRead.Unavailable("encrypted store unavailable")
        return runCatching {
            val access = store.getString(KEY_ACCESS_TOKEN)?.takeIf { it.isNotBlank() }
            val refresh = store.getString(KEY_REFRESH_TOKEN)?.takeIf { it.isNotBlank() }
            val userId = store.getString(KEY_USER_ID)?.takeIf { it.isNotBlank() }
            val accountId = store.getString(KEY_ACCOUNT_ID)?.takeIf { it.isNotBlank() }
            if (access == null || refresh == null || userId == null || accountId == null) {
                SessionRead.SignedOut
            } else {
                SessionRead.Available(
                    Session(
                        accessToken = access,
                        refreshToken = refresh,
                        expiresAtMs = store.getLong(KEY_EXPIRES_AT_MS, 0L),
                        userId = userId,
                        userEmail = store.getString(KEY_USER_EMAIL),
                        userName = store.getString(KEY_USER_NAME),
                        accountId = accountId,
                        accountName = store.getString(KEY_ACCOUNT_NAME),
                        role = store.getString(KEY_ROLE),
                    )
                )
            }
        }.getOrElse {
            AppLogger.w(TAG, "Could not read the WhatsApp Max session: ${it.message}")
            SessionRead.Unavailable("could not read the session")
        }
    }

    /** Reads the stored session. Returns null unless access token, refresh token, user and
     *  account ids are all present and non-blank. Never logs values. */
    fun get(context: Context): Session? {
        return when (val read = read(context)) {
            is SessionRead.Available -> read.session
            is SessionRead.SignedOut,
            is SessionRead.Unavailable -> null
        }
    }

    /** True when a complete session is stored. */
    fun isConnected(context: Context): Boolean = get(context) != null

    /** Persists a freshly authenticated session (login or refresh), replacing any prior one.
     *  Returns true only if the store opened AND the write committed — callers must not
     *  report success on false. Blocking commit; call off the main thread. */
    fun save(
        context: Context,
        accessToken: String,
        refreshToken: String,
        expiresAtMs: Long,
        userId: String,
        userEmail: String?,
        userName: String?,
        accountId: String,
        accountName: String?,
        role: String?,
    ): Boolean {
        return runCatching {
            val store = openStore(context) ?: return false
            store.putAll(
                mapOf(
                    KEY_ACCESS_TOKEN to accessToken,
                    KEY_REFRESH_TOKEN to refreshToken,
                    KEY_USER_ID to userId,
                    KEY_USER_EMAIL to userEmail,
                    KEY_USER_NAME to userName,
                    KEY_ACCOUNT_ID to accountId,
                    KEY_ACCOUNT_NAME to accountName,
                    KEY_ROLE to role,
                ),
                expiresAtMs,
            )
        }.onFailure {
            AppLogger.w(TAG, "Could not save the WhatsApp Max session: ${it.message}")
        }.getOrDefault(false)
    }

    /** Removes every stored session field. Local recordings, the account, and the server
     *  session (revoked separately at disconnect time) are untouched. */
    fun clear(context: Context) {
        runCatching {
            openStore(context)?.clearAll()
        }.onFailure {
            AppLogger.w(TAG, "Could not clear the WhatsApp Max session: ${it.message}")
        }
    }
}
