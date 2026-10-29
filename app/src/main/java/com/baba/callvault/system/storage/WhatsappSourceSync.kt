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
import com.baba.callvault.data.voip.WhatsappRecordingSource
import com.baba.callvault.services.call.PhoneRecordingSource
import com.baba.callvault.utils.AppLogger
import java.net.HttpURLConnection

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
    )

    fun syncAfterAuth(
        context: Context,
        baseUrl: String,
        accessToken: String,
        openConnection: (String) -> HttpURLConnection = { url ->
            @Suppress("UsePropertyAccessSyntax")
            (java.net.URI(url).toURL().openConnection() as HttpURLConnection)
        },
    ): WhatsappRecordingSource? {
        val synced = fetchPolicy(baseUrl, accessToken, openConnection) ?: return null
        // Each policy applies independently; an absent/unknown one
        // keeps its last-known value (fresh installs default to
        // none via the preference defaults). A successfully parsed
        // value always wins — even an explicit 'none'.
        val prefs = AppPreferences(context.applicationContext)
        synced.whatsapp?.let { prefs.setWhatsappRecordingSource(it) }
        synced.phone?.let { prefs.setPhoneRecordingSource(it) }
        // The server phone number is authoritative after a successful
        // sync: an explicit value (even null = owner set None) wins;
        // an absent field (older server) keeps the last-known number
        // so stale deploys can never silently wipe the config.
        if (synced.phoneNumberPresent) prefs.setPhoneRecordingNumber(synced.phoneNumber)
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
    ): SyncedPolicy? {
        val text = runCatching {
            val connection = openConnection("$baseUrl/api/auth/me")
            connection.requestMethod = "GET"
            connection.doInput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
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
        )
    }
}
