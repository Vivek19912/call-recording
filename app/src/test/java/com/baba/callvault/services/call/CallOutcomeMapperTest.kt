/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import android.provider.CallLog
import com.baba.callvault.services.call.CallOutcomeMapper.FinalCallEntry
import com.baba.callvault.services.call.CallOutcomeMapper.MappedOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Authoritative CallLog-type mapping. Every assertion here is a
 * promise about what WhatsApp Max will display — a wrong mapping
 * is a fabricated call outcome.
 *
 * Pure JVM: `CallLog.Calls` constants are compile-time ints.
 */
class CallOutcomeMapperTest {

    private fun entry(type: Int?, durationSeconds: Long? = 10L) =
        FinalCallEntry(
            number = "+919876543210",
            type = type,
            durationSeconds = durationSeconds,
            dateMillis = 1_700_000_000_000L,
        )

    @Test
    fun `missed type maps to missed incoming`() {
        assertEquals(
            MappedOutcome("in", "missed"),
            CallOutcomeMapper.map(entry(CallLog.Calls.MISSED_TYPE)),
        )
    }

    @Test
    fun `rejected type maps to rejected incoming`() {
        assertEquals(
            MappedOutcome("in", "rejected"),
            CallOutcomeMapper.map(entry(CallLog.Calls.REJECTED_TYPE)),
        )
    }

    @Test
    fun `rejected is never confused with missed`() {
        val rejected = CallOutcomeMapper.map(entry(CallLog.Calls.REJECTED_TYPE))!!
        val missed = CallOutcomeMapper.map(entry(CallLog.Calls.MISSED_TYPE))!!
        assertEquals("in", rejected.direction)
        assertEquals("in", missed.direction)
        assertEquals("rejected", rejected.outcome)
        assertEquals("missed", missed.outcome)
    }

    @Test
    fun `incoming type maps to answered incoming`() {
        assertEquals(
            MappedOutcome("in", "answered"),
            CallOutcomeMapper.map(entry(CallLog.Calls.INCOMING_TYPE, durationSeconds = 45L)),
        )
    }

    @Test
    fun `outgoing with talk time maps to answered, without to unknown`() {
        assertEquals(
            MappedOutcome("out", "answered"),
            CallOutcomeMapper.map(entry(CallLog.Calls.OUTGOING_TYPE, durationSeconds = 12L)),
        )
        // A zero-duration outgoing call proves nothing about pickup.
        assertEquals(
            MappedOutcome("out", "unknown"),
            CallOutcomeMapper.map(entry(CallLog.Calls.OUTGOING_TYPE, durationSeconds = 0L)),
        )
        assertEquals(
            MappedOutcome("out", "unknown"),
            CallOutcomeMapper.map(entry(CallLog.Calls.OUTGOING_TYPE, durationSeconds = null)),
        )
    }

    @Test
    fun `blocked is unknown, never rejected`() {
        assertEquals(
            MappedOutcome("in", "unknown"),
            CallOutcomeMapper.map(entry(CallLog.Calls.BLOCKED_TYPE)),
        )
    }

    @Test
    fun `voicemail and externally-answered prove connection`() {
        assertEquals(
            MappedOutcome("in", "answered"),
            CallOutcomeMapper.map(entry(CallLog.Calls.VOICEMAIL_TYPE)),
        )
        assertEquals(
            MappedOutcome("in", "answered"),
            CallOutcomeMapper.map(entry(CallLog.Calls.ANSWERED_EXTERNALLY_TYPE)),
        )
    }

    @Test
    fun `unknown or unreadable type maps to nothing, never a guess`() {
        assertNull(CallOutcomeMapper.map(entry(-1)))
        assertNull(CallOutcomeMapper.map(entry(Int.MAX_VALUE)))
        assertNull(CallOutcomeMapper.map(entry(null)))
    }
}
