/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Delete menu eligibility: Delete appears only once a recording is at least 10 whole
 * calendar days old (Nov 1 -> Nov 11, device-local dates, never elapsed hours).
 *
 * The clock and zone are explicit parameters of [isDeleteEligible], so every boundary below
 * is deterministic — no dependency on when or where the test runs.
 */
class DeleteEligibilityTest {

    private val utc = ZoneOffset.UTC

    /** Noon UTC on the given 2026 date, as epoch millis. */
    private fun noonUtc(month: Int, day: Int): Long =
        ZonedDateTime.of(LocalDate.of(2026, month, day), LocalTime.NOON, utc).toInstant().toEpochMilli()

    @Test
    fun `recording created today keeps Delete hidden`() {
        val now = noonUtc(11, 1)
        assertFalse(isDeleteEligible(startedAtMillis = now, nowMillis = now, zone = utc))
    }

    @Test
    fun `recording 5 calendar days old keeps Delete hidden`() {
        assertFalse(
            isDeleteEligible(
                startedAtMillis = noonUtc(11, 1),
                nowMillis = noonUtc(11, 6),
                zone = utc
            )
        )
    }

    @Test
    fun `recording 9 calendar days apart keeps Delete hidden`() {
        // Nov 1 -> Nov 10 is only 9 days apart: eligible no earlier than Nov 11.
        assertFalse(
            isDeleteEligible(
                startedAtMillis = noonUtc(11, 1),
                nowMillis = ZonedDateTime.of(
                    LocalDate.of(2026, 11, 10), LocalTime.of(23, 59), utc
                ).toInstant().toEpochMilli(),
                zone = utc
            )
        )
    }

    @Test
    fun `recording 10 calendar days apart shows Delete`() {
        // Nov 1 -> Nov 11: the first eligible date, from the very first minute.
        assertTrue(
            isDeleteEligible(
                startedAtMillis = noonUtc(11, 1),
                nowMillis = ZonedDateTime.of(
                    LocalDate.of(2026, 11, 11), LocalTime.MIDNIGHT, utc
                ).toInstant().toEpochMilli(),
                zone = utc
            )
        )
    }

    @Test
    fun `recording older than 10 days shows Delete`() {
        assertTrue(
            isDeleteEligible(
                startedAtMillis = noonUtc(10, 1),
                nowMillis = noonUtc(11, 15),
                zone = utc
            )
        )
    }

    @Test
    fun `calendar dates count, not elapsed hours`() {
        // ~240 elapsed hours but only 9 calendar days apart: still hidden.
        val start = ZonedDateTime.of(
            LocalDate.of(2026, 11, 1), LocalTime.of(0, 1), utc
        ).toInstant().toEpochMilli()
        val now = ZonedDateTime.of(
            LocalDate.of(2026, 11, 10), LocalTime.of(23, 59), utc
        ).toInstant().toEpochMilli()
        assertFalse(isDeleteEligible(startedAtMillis = start, nowMillis = now, zone = utc))
        // Same wall-clock instants straddling midnight are only 1 day apart.
        val lateStart = ZonedDateTime.of(
            LocalDate.of(2026, 11, 1), LocalTime.of(23, 59), utc
        ).toInstant().toEpochMilli()
        val earlyNow = ZonedDateTime.of(
            LocalDate.of(2026, 11, 2), LocalTime.of(0, 1), utc
        ).toInstant().toEpochMilli()
        assertFalse(isDeleteEligible(startedAtMillis = lateStart, nowMillis = earlyNow, zone = utc))
    }

    @Test
    fun `eligibility follows the device zone, not UTC`() {
        // 2026-11-01 22:00 UTC is Nov 2 in Kolkata; 2026-11-11 00:30 UTC is still Nov 11 there:
        // 9 days apart locally (hidden), although 10 apart in UTC.
        val kolkata = ZoneId.of("Asia/Kolkata")
        val start = ZonedDateTime.of(
            LocalDate.of(2026, 11, 1), LocalTime.of(22, 0), utc
        ).toInstant().toEpochMilli()
        val now = ZonedDateTime.of(
            LocalDate.of(2026, 11, 11), LocalTime.of(0, 30), utc
        ).toInstant().toEpochMilli()
        assertFalse(isDeleteEligible(startedAtMillis = start, nowMillis = now, zone = kolkata))
        assertTrue(isDeleteEligible(startedAtMillis = start, nowMillis = now, zone = utc))
    }

    @Test
    fun `future-dated recording keeps Delete hidden`() {
        assertFalse(
            isDeleteEligible(
                startedAtMillis = noonUtc(11, 5),
                nowMillis = noonUtc(11, 1),
                zone = utc
            )
        )
    }

    @Test
    fun `missing or invalid timestamp keeps Delete hidden`() {
        val now = noonUtc(11, 20)
        assertFalse(isDeleteEligible(startedAtMillis = null, nowMillis = now, zone = utc))
        assertFalse(isDeleteEligible(startedAtMillis = 0L, nowMillis = now, zone = utc))
        assertFalse(isDeleteEligible(startedAtMillis = -1L, nowMillis = now, zone = utc))
        assertFalse(isDeleteEligible(startedAtMillis = Long.MAX_VALUE, nowMillis = now, zone = utc))
    }
}
