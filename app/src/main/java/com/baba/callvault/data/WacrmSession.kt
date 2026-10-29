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

    /** Reads the stored session. Returns null unless access token, refresh token, user and
     *  account ids are all present and non-blank. Never logs values. */
    fun get(context: Context): Session? {
        return runCatching {
            val store = open(context) ?: return null
            val access = store.getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
            val refresh = store.getString(KEY_REFRESH_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
            val userId = store.getString(KEY_USER_ID, null)?.takeIf { it.isNotBlank() } ?: return null
            val accountId = store.getString(KEY_ACCOUNT_ID, null)?.takeIf { it.isNotBlank() } ?: return null
            Session(
                accessToken = access,
                refreshToken = refresh,
                expiresAtMs = store.getLong(KEY_EXPIRES_AT_MS, 0L),
                userId = userId,
                userEmail = store.getString(KEY_USER_EMAIL, null),
                userName = store.getString(KEY_USER_NAME, null),
                accountId = accountId,
                accountName = store.getString(KEY_ACCOUNT_NAME, null),
                role = store.getString(KEY_ROLE, null),
            )
        }.onFailure {
            AppLogger.w(TAG, "Could not read the WhatsApp Max session: ${it.message}")
        }.getOrNull()
    }

    /** True when a complete session is stored. */
    fun isConnected(context: Context): Boolean = get(context) != null

    /** Persists a freshly authenticated session (login or refresh), replacing any prior one. */
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
    ) {
        runCatching {
            val store = open(context) ?: return
            store.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putLong(KEY_EXPIRES_AT_MS, expiresAtMs)
                .putString(KEY_USER_ID, userId)
                .putString(KEY_USER_EMAIL, userEmail)
                .putString(KEY_USER_NAME, userName)
                .putString(KEY_ACCOUNT_ID, accountId)
                .putString(KEY_ACCOUNT_NAME, accountName)
                .putString(KEY_ROLE, role)
                .apply()
        }.onFailure {
            AppLogger.w(TAG, "Could not save the WhatsApp Max session: ${it.message}")
        }
    }

    /** Removes every stored session field. Local recordings, the account, and the server
     *  session (revoked separately at disconnect time) are untouched. */
    fun clear(context: Context) {
        runCatching {
            open(context)?.edit()?.clear()?.apply()
        }.onFailure {
            AppLogger.w(TAG, "Could not clear the WhatsApp Max session: ${it.message}")
        }
    }
}
