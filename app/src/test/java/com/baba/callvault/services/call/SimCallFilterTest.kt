/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Record calls from" gate. Comparison is on subscription ids, never slot labels.
 * Fail-CLOSED on unknown for an explicit selection (the field-reported bug was a SIM 1 call
 * recording while SIM 2 was selected, because the subscription arrived as unknown and the
 * old design failed open); All SIMs always records; a stale selection records while the
 * picker heals it.
 *
 * Pure JVM: [SimCallFilter] touches no Android APIs.
 */
class SimCallFilterTest {

    private companion object {
        const val SIM_1 = 1
        const val SIM_2 = 2
    }

    @Test
    fun `no selection records on every SIM`() {
        assertTrue(SimCallFilter.shouldRecord(SIM_1, SimSubscriptions.ALL_SUBSCRIPTIONS, true))
        assertTrue(SimCallFilter.shouldRecord(SIM_2, SimSubscriptions.ALL_SUBSCRIPTIONS, true))
        assertTrue(SimCallFilter.shouldRecord(null, SimSubscriptions.ALL_SUBSCRIPTIONS, true))
    }

    @Test
    fun `SIM 1 selected, SIM 1 call records`() {
        assertTrue(SimCallFilter.shouldRecord(SIM_1, SIM_1, true))
    }

    @Test
    fun `SIM 1 selected, SIM 2 call does not record`() {
        assertFalse(SimCallFilter.shouldRecord(SIM_2, SIM_1, true))
    }

    @Test
    fun `SIM 2 selected, SIM 2 call records`() {
        assertTrue(SimCallFilter.shouldRecord(SIM_2, SIM_2, true))
    }

    @Test
    fun `SIM 2 selected, SIM 1 call does not record`() {
        assertFalse(SimCallFilter.shouldRecord(SIM_1, SIM_2, true))
    }

    @Test
    fun `comparison is on subscription id, not slot label`() {
        // Slot labels never reach the filter: two different ids always mismatch, even if a UI
        // once called both "SIM 1". A stable id that survives a slot move still matches.
        assertFalse(SimCallFilter.shouldRecord(11, 12, true))
        assertTrue(SimCallFilter.shouldRecord(11, 11, true))
    }

    @Test
    fun `explicit selection plus unknown call SIM fails closed`() {
        // The reported bug: SIM 1 call recorded while SIM 2 was selected, because the
        // subscription arrived unknown. Unknown must not record under an explicit pick.
        assertFalse(SimCallFilter.shouldRecord(null, SIM_1, true))
        assertFalse(SimCallFilter.shouldRecord(null, SIM_2, true))
        assertFalse(
            SimCallFilter.shouldRecord(SimSubscriptions.ALL_SUBSCRIPTIONS, SIM_1, true),
        )
    }

    @Test
    fun `All SIMs plus unknown subscription preserves existing behavior`() {
        assertTrue(SimCallFilter.shouldRecord(null, SimSubscriptions.ALL_SUBSCRIPTIONS, true))
        assertTrue(
            SimCallFilter.shouldRecord(
                SimSubscriptions.ALL_SUBSCRIPTIONS,
                SimSubscriptions.ALL_SUBSCRIPTIONS,
                true,
            ),
        )
    }

    @Test
    fun `removed selection records instead of silencing the remaining SIMs`() {
        assertTrue(SimCallFilter.shouldRecord(SIM_2, SIM_1, selectedActive = false))
        assertTrue(SimCallFilter.shouldRecord(SIM_1, SIM_1, selectedActive = false))
        assertTrue(SimCallFilter.shouldRecord(null, SIM_1, selectedActive = false))
    }

    @Test
    fun `single-SIM device behaves like no selection for its only SIM`() {
        assertTrue(SimCallFilter.shouldRecord(SIM_1, SIM_1, true))
        assertTrue(SimCallFilter.shouldRecord(SIM_1, SimSubscriptions.ALL_SUBSCRIPTIONS, true))
    }
}
