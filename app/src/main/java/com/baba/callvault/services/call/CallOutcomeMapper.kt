/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import android.provider.CallLog

/**
 * One carrier call's outcome, sourced EXCLUSIVELY from the Android
 * CallLog `Calls.TYPE` of its final row — the authoritative source
 * for phone-call outcomes. Nothing here is inferred from direction,
 * duration, filenames, contact names, or recording existence.
 *
 * A missed or rejected call usually has no recording at all, so this
 * travels to WhatsApp Max as a `call_events` row of its own — never
 * through the recording pipeline, never requiring audio.
 *
 * Pure: [CallLog.Calls] constants are compile-time ints, so this is
 * unit tested on the JVM with no device.
 */
object CallOutcomeMapper {

    /** Wire values of `POST /api/v1/call-events`. */
    const val DIRECTION_IN = "in"
    const val DIRECTION_OUT = "out"
    const val OUTCOME_ANSWERED = "answered"
    const val OUTCOME_MISSED = "missed"
    const val OUTCOME_REJECTED = "rejected"
    const val OUTCOME_UNKNOWN = "unknown"

    /**
     * A final CallLog row, as read by the shared lookup (extended
     * `tryGetFinalNumberFromLog` shape: number + type + duration +
     * timestamp in one query, never a second lookup).
     */
    data class FinalCallEntry(
        val number: String?,
        /** One of `CallLog.Calls.*_TYPE`, or null when unreadable. */
        val type: Int?,
        /** CallLog `DURATION` seconds, or null when unreadable. */
        val durationSeconds: Long?,
        /** CallLog `DATE` epoch millis, or null when unreadable. */
        val dateMillis: Long?,
    )

    /** A mappable outcome: direction + outcome wires for one event. */
    data class MappedOutcome(val direction: String, val outcome: String)

    /**
     * Maps one final CallLog row.
     *
     * - `MISSED_TYPE` → in/missed; `REJECTED_TYPE` → in/rejected.
     *   Rejected is ONLY ever this explicit type — a missed call is
     *   never upgraded, and nothing else is called a rejection.
     * - `INCOMING_TYPE` → in/answered.
     * - `OUTGOING_TYPE` → out/answered when the log proves talk time
     *   (`duration > 0`); out/unknown when it does not. An unanswered
     *   outgoing call must not be reported as answered.
     * - `BLOCKED_TYPE` → in/unknown. A blocked call is NOT a
     *   user-rejected call and is never reported as one.
     * - `VOICEMAIL_TYPE`, `ANSWERED_EXTERNALLY_TYPE` → in/answered:
     *   both prove the call connected (voicemail = answered by the
     *   mailbox; externally = answered on another device).
     * - Anything else (including an unreadable type) → null: no
     *   event rather than a guessed one.
     */
    fun map(entry: FinalCallEntry): MappedOutcome? {
        return when (entry.type) {
            CallLog.Calls.MISSED_TYPE ->
                MappedOutcome(DIRECTION_IN, OUTCOME_MISSED)
            CallLog.Calls.REJECTED_TYPE ->
                MappedOutcome(DIRECTION_IN, OUTCOME_REJECTED)
            CallLog.Calls.INCOMING_TYPE ->
                MappedOutcome(DIRECTION_IN, OUTCOME_ANSWERED)
            CallLog.Calls.OUTGOING_TYPE ->
                if ((entry.durationSeconds ?: 0L) > 0L) {
                    MappedOutcome(DIRECTION_OUT, OUTCOME_ANSWERED)
                } else {
                    MappedOutcome(DIRECTION_OUT, OUTCOME_UNKNOWN)
                }
            CallLog.Calls.BLOCKED_TYPE ->
                MappedOutcome(DIRECTION_IN, OUTCOME_UNKNOWN)
            CallLog.Calls.VOICEMAIL_TYPE,
            CallLog.Calls.ANSWERED_EXTERNALLY_TYPE,
            ->
                MappedOutcome(DIRECTION_IN, OUTCOME_ANSWERED)
            else -> null
        }
    }
}
