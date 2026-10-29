/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import android.content.Context
import android.provider.CallLog
import com.baba.callvault.services.call.CallOutcomeMapper.FinalCallEntry
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.delay

/**
 * The one shared final-CallLog lookup. Both consumers — the
 * recording rename fallback (`RecordingForegroundService`) and
 * the call-outcome reporter ([CallEventReporter]) — read through
 * here, so there is exactly one retry loop and one projection.
 *
 * The rename path keeps its own narrow selection (answered calls
 * only, by historical design); the outcome path uses
 * [incomingSelection]/[outgoingSelection], which include the
 * missed/rejected rows that ARE the outcome signal. Callers own
 * their selection; this object owns the mechanics.
 */
object CarrierCallLog {

    private const val TAG = "CV:CarrierCallLog"

    /** Incoming-family types: answered, missed, rejected, blocked, voicemail, externally answered. */
    fun incomingSelection(): String = "${CallLog.Calls.TYPE} IN (" +
        "${CallLog.Calls.INCOMING_TYPE}," +
        "${CallLog.Calls.MISSED_TYPE}," +
        "${CallLog.Calls.REJECTED_TYPE}," +
        "${CallLog.Calls.BLOCKED_TYPE}," +
        "${CallLog.Calls.VOICEMAIL_TYPE}," +
        "${CallLog.Calls.ANSWERED_EXTERNALLY_TYPE})"

    /** Outgoing calls have a single type; answered-ness comes from `DURATION`, never the type. */
    fun outgoingSelection(): String =
        "${CallLog.Calls.TYPE} = ${CallLog.Calls.OUTGOING_TYPE}"

    /** One attempt: the newest row matching [selection], with number + type + duration + date. */
    fun queryLatest(context: Context, selection: String?): FinalCallEntry? {
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.DATE,
                ),
                selection, null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION)
                val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
                FinalCallEntry(
                    number = cursor.getString(0),
                    type = if (typeIndex >= 0 && !cursor.isNull(typeIndex)) cursor.getInt(typeIndex) else null,
                    durationSeconds = if (durationIndex >= 0 && !cursor.isNull(durationIndex)) cursor.getLong(durationIndex) else null,
                    dateMillis = if (dateIndex >= 0 && !cursor.isNull(dateIndex)) cursor.getLong(dateIndex) else null,
                )
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to query the call log", e)
            null
        }
    }

    /**
     * Retried lookup: the OS writes the CallLog row after the call
     * ends, so the first attempt can race it. Same 4×400ms shape the
     * rename fallback has always used.
     */
    suspend fun queryLatestWithRetry(
        context: Context,
        selection: String?,
        attempts: Int = 4,
        delayMs: Long = 400L,
    ): FinalCallEntry? {
        repeat(attempts) { i ->
            queryLatest(context, selection)?.let { return it }
            if (i < attempts - 1) delay(delayMs)
        }
        return null
    }
}
