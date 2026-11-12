/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One-time recovery for call events terminally failed by the old
 * string-serialized `duration_seconds` (server HTTP 400).
 *
 * Pure JVM: the plan/execute core takes a fake marker and a
 * recording scheduler — no Room, WorkManager, or Context involved.
 * The Android shell ([CallEventRecovery.maybeRecoverOnce]) stays
 * thin by construction: query FAILED ids → [runOnce] → mark done.
 */
class CallEventRecoveryTest {

    private class FakeMarker(var done: Boolean = false) : CallEventRecovery.Marker {
        override fun isDone(): Boolean = done
        override fun markDone() {
            done = true
        }
    }

    @Test
    fun `eligible FAILED events are requeued once with their original ids`() = runBlocking {
        val marker = FakeMarker()
        val scheduled = mutableListOf<String>()
        val count = CallEventRecovery.runOnce(listOf("evt-1", "evt-2"), marker) { scheduled.add(it) }
        assertEquals(2, count)
        assertEquals(listOf("evt-1", "evt-2"), scheduled)
        assertTrue(marker.done)
    }

    @Test
    fun `a completed recovery never requeues on later startups`() = runBlocking {
        val marker = FakeMarker(done = true)
        var scheduled = 0
        val count = CallEventRecovery.runOnce(listOf("evt-1"), marker) { scheduled++ }
        assertEquals(0, count)
        assertEquals(0, scheduled)
    }

    @Test
    fun `an empty failure list still marks the pass complete`() = runBlocking {
        val marker = FakeMarker()
        var scheduled = 0
        val count = CallEventRecovery.runOnce(emptyList(), marker) { scheduled++ }
        assertEquals(0, count)
        assertEquals(0, scheduled)
        // No repeated database scans on every launch afterwards.
        assertTrue(marker.done)
    }

    @Test
    fun `duplicate ids in one pass are scheduled once`() = runBlocking {
        val marker = FakeMarker()
        val scheduled = mutableListOf<String>()
        val count = CallEventRecovery.runOnce(listOf("evt-1", "evt-1"), marker) { scheduled.add(it) }
        assertEquals(1, count)
        assertEquals(listOf("evt-1"), scheduled)
    }

    @Test
    fun `a crash mid-pass leaves the marker unset so the next startup reproduces the plan`() =
        runBlocking {
            val marker = FakeMarker()
            val firstAttempt = mutableListOf<String>()
            var crashed = false
            try {
                CallEventRecovery.runOnce(listOf("evt-1", "evt-2"), marker) {
                    firstAttempt.add(it)
                    if (it == "evt-1") throw RuntimeException("process death")
                }
            } catch (expected: RuntimeException) {
                crashed = true
            }
            assertTrue(crashed)
            assertFalse(marker.done)
            // Rerun reproduces the full original plan — nothing lost.
            val secondAttempt = mutableListOf<String>()
            val count = CallEventRecovery.runOnce(listOf("evt-1", "evt-2"), marker) {
                secondAttempt.add(it)
            }
            assertEquals(2, count)
            assertEquals(listOf("evt-1", "evt-2"), secondAttempt)
            assertTrue(marker.done)
        }

    @Test
    fun `the plan never invents ids and never expands the input`() {
        assertEquals(
            listOf("evt-1"),
            CallEventRecovery.plan(listOf("evt-1"), alreadyDone = false),
        )
        assertEquals(
            emptyList<String>(),
            CallEventRecovery.plan(listOf("evt-1"), alreadyDone = true),
        )
        assertEquals(
            emptyList<String>(),
            CallEventRecovery.plan(emptyList(), alreadyDone = false),
        )
    }
}
