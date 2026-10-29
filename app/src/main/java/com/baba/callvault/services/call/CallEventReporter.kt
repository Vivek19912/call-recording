/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import android.content.Context
import com.baba.callvault.data.recordings.RecordingDirection
import com.baba.callvault.data.recordings.RecordingMetadata
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.system.storage.CallEvents
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.utils.PhoneNumberManager.Companion.normalisePhoneNumber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reports one carrier call's outcome at call end, for calls with or
 * without a recording.
 *
 * Called from the `IDLE` branch of
 * [CallSessionManager.handlePhoneState] with the ended session's
 * metadata. The outcome itself always comes from the final CallLog
 * row ([CallOutcomeMapper]) — never from the session.
 *
 * Self-guarding against app calls: an app (VoIP) call leaves no
 * carrier CallLog row, so without a fresh matching row there is
 * simply no event. Two further guards pin the row to THIS call:
 * the row must be recent ([RECENCY_WINDOW_MS], same tolerance as
 * the duration lookup), and when the session carries a number it
 * must digit-match the row's number. A process-death restart (no
 * session) reports nothing — an unattributable event is worse
 * than a missing one.
 *
 * Never throws; never touches audio, the catalog, or uploads.
 */
object CallEventReporter {

    private const val TAG = "CV:CallEventReporter"

    /** A CallLog row older than this cannot be this call's end. */
    const val RECENCY_WINDOW_MS = 120_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onCallEnded(context: Context, metadata: RecordingMetadata?) {
        if (metadata == null) return
        val app = context.applicationContext
        scope.launch {
            runCatching { reportNow(app, metadata) }
                .onFailure { AppLogger.w(TAG, "Call outcome reporting failed: ${it.javaClass.simpleName}") }
        }
    }

    private suspend fun reportNow(context: Context, metadata: RecordingMetadata) {
        if (!PermissionChecks.hasCallLogPermission(context)) {
            AppLogger.d(TAG, "Call log permission missing; skipping outcome report")
            return
        }
        val selection = when (metadata.direction) {
            RecordingDirection.INCOMING -> CarrierCallLog.incomingSelection()
            RecordingDirection.OUTGOING -> CarrierCallLog.outgoingSelection()
        }
        val entry = CarrierCallLog.queryLatestWithRetry(context, selection) ?: run {
            AppLogger.d(TAG, "No final call-log row; skipping outcome report")
            return
        }
        val dateMillis = entry.dateMillis
        if (dateMillis == null || System.currentTimeMillis() - dateMillis > RECENCY_WINDOW_MS) {
            AppLogger.d(TAG, "Final call-log row is stale; skipping outcome report")
            return
        }
        val sessionDigits = metadata.rawPhoneNumber?.let(::digitsOf)
        val rowDigits = entry.number?.let(::digitsOf)
        if (!sessionDigits.isNullOrEmpty() && !rowDigits.isNullOrEmpty() && sessionDigits != rowDigits) {
            AppLogger.d(TAG, "Final call-log row number does not match the session; skipping")
            return
        }
        val mapped = CallOutcomeMapper.map(entry) ?: run {
            AppLogger.d(TAG, "Call-log type has no outcome mapping; skipping")
            return
        }
        CallEvents.reportCallEnd(
            context = context,
            direction = mapped.direction,
            outcome = mapped.outcome,
            phoneNumber = entry.number,
            durationSeconds = entry.durationSeconds,
            occurredAtMillis = dateMillis,
        )
    }

    private fun digitsOf(number: String): String = normalisePhoneNumber(number).filter { it.isDigit() }
}
