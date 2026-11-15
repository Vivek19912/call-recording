/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Minimum whole-calendar-day age before a recording's Delete menu item appears.
 *
 * A recording made on November 1 becomes eligible on November 11: the day difference must be
 * at least 10 (Nov 1 -> Nov 10 is 9 days apart, still hidden; Nov 1 -> Nov 11 is 10, visible).
 */
const val DELETE_ELIGIBLE_AGE_DAYS = 10L

/**
 * Whether the recording menu may offer Delete for a recording created at [startedAtMillis].
 *
 * Compares whole calendar dates in [zone] (device local time by default), never elapsed hours:
 * both instants are truncated to their local date before differencing, so a recording made at
 * 23:59 is the same age as one made at 00:01 the same day.
 *
 * The source timestamp is the filename-embedded call-start time ([RecordingItem.startedAtMillis],
 * parsed at record time), never the file's modification time — playing, viewing, or uploading a
 * recording cannot move it. Missing, non-positive, or unparsable timestamps, and any
 * future-dated recording, are ineligible (Delete stays hidden).
 *
 * [nowMillis] and [zone] default to the live clock and device zone; tests pass explicit values.
 */
fun isDeleteEligible(
    startedAtMillis: Long?,
    nowMillis: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean {
    if (startedAtMillis == null || startedAtMillis <= 0L) return false
    if (nowMillis < startedAtMillis) return false
    return try {
        val startDate = Instant.ofEpochMilli(startedAtMillis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        !startDate.isAfter(today) &&
            ChronoUnit.DAYS.between(startDate, today) >= DELETE_ELIGIBLE_AGE_DAYS
    } catch (e: Exception) {
        false
    }
}
