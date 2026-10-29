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
 * Encrypted storage for the WACRM API key (Phase 1 upload).
 *
 * The key (`wacrm_live_…`) must never sit in plaintext [AppPreferences]: prefs are exported,
 * included in bug-report dumps and readable to anything that can read the app's files. This store
 * uses [EncryptedSharedPreferences] with an [MasterKey] in the Android Keystore, so the secret is
 * encrypted at rest and bound to this device.
 *
 * Deliberately minimal: set / get / clear / has. No fallback to plaintext — if the encrypted store
 * cannot be opened (broken Keystore, device quirk), uploads are unavailable rather than downgrading
 * to an unencrypted secret. That failure is logged without the key and surfaced as "not configured".
 *
 * The server URL and the enabled toggle are ordinary (non-secret) settings in [AppPreferences].
 */
object WacrmSecrets {

    private const val TAG = "CV:WacrmSecrets"
    private const val FILE_NAME = "wacrm_secrets"
    private const val KEY_API_KEY = "wacrm_api_key"

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

    /** The stored API key, or null when none is saved (or the store cannot be opened). Never logged. */
    fun getApiKey(context: Context): String? {
        val key = runCatching { open(context)?.getString(KEY_API_KEY, null) }.getOrNull()
        return key?.takeIf { it.isNotBlank() }
    }

    /** True when a non-blank API key is stored. */
    fun hasApiKey(context: Context): Boolean = getApiKey(context) != null

    /** Stores (or replaces) the API key. Blank input clears it instead of storing an empty secret. */
    fun setApiKey(context: Context, apiKey: String) {
        val value = apiKey.trim()
        runCatching {
            val store = open(context) ?: return
            if (value.isEmpty()) {
                store.edit().remove(KEY_API_KEY).apply()
            } else {
                store.edit().putString(KEY_API_KEY, value).apply()
            }
        }.onFailure {
            AppLogger.w(TAG, "Could not save the WACRM API key: ${it.message}")
        }
    }

    /** Removes the stored API key, if any. */
    fun clearApiKey(context: Context) {
        runCatching { open(context)?.edit()?.remove(KEY_API_KEY)?.apply() }
            .onFailure { AppLogger.w(TAG, "Could not clear the WACRM API key: ${it.message}") }
    }
}
