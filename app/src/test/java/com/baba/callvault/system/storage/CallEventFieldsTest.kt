/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Call-event wire fields. A missed/rejected call has no recording,
 * so these fields — not the upload worker's multipart parts — are
 * the entire payload. Absent values must be omitted, never
 * invented; the server re-validates everything.
 *
 * Pure JVM: no Android APIs involved.
 */
class CallEventFieldsTest {

    @Test
    fun `a full missed event carries every field`() {
        val fields = CallEventWorker.eventFields(
            clientEventId = "evt-1",
            direction = "in",
            outcome = "missed",
            phoneNumber = "+919876543210",
            durationSeconds = 0L,
            occurredAtMillis = 1_700_000_000_000L,
        )
        assertEquals("evt-1", fields["client_event_id"])
        assertEquals("phone", fields["call_type"])
        assertEquals("in", fields["direction"])
        assertEquals("missed", fields["outcome"])
        assertEquals("+919876543210", fields["phone_number"])
        assertEquals(0L, fields["duration_seconds"])
        assertEquals("2023-11-14T22:13:20Z", fields["occurred_at"])
    }

    @Test
    fun `duration_seconds serializes as a JSON number, everything else as strings`() {
        val fields = CallEventWorker.eventFields(
            clientEventId = "evt-1",
            direction = "in",
            outcome = "missed",
            phoneNumber = "+919876543210",
            durationSeconds = 0L,
            occurredAtMillis = 1_700_000_000_000L,
        )
        assertEquals(
            """{"client_event_id":"evt-1","call_type":"phone","direction":"in","outcome":"missed","phone_number":"+919876543210","duration_seconds":0,"occurred_at":"2023-11-14T22:13:20Z"}""",
            WacrmApi.buildJsonObject(fields),
        )
    }

    @Test
    fun `positive durations stay numeric in answered events`() {
        val fields = CallEventWorker.eventFields(
            clientEventId = "evt-9",
            direction = "out",
            outcome = "answered",
            phoneNumber = "+919876543210",
            durationSeconds = 63L,
            occurredAtMillis = 1_700_000_000_000L,
        )
        assertEquals(63L, fields["duration_seconds"])
        assertTrue(WacrmApi.buildJsonObject(fields).contains("\"duration_seconds\":63"))
    }

    @Test
    fun `non-string non-number values are omitted, never coerced`() {
        val json = WacrmApi.buildJsonObject(
            mapOf("client_event_id" to "evt-x", "flag" to true, "count" to 3, "gone" to null),
        )
        assertEquals("""{"client_event_id":"evt-x","count":3}""", json)
    }

    @Test
    fun `an event without a readable number omits it, like a recording without one`() {
        val fields = CallEventWorker.eventFields(
            clientEventId = "evt-2",
            direction = "in",
            outcome = "rejected",
            phoneNumber = null,
            durationSeconds = null,
            occurredAtMillis = 1_700_000_000_000L,
        )
        assertFalse(fields.containsKey("phone_number"))
        assertFalse(fields.containsKey("duration_seconds"))
        assertEquals("rejected", fields["outcome"])
    }

    @Test
    fun `a foreign direction value is dropped, never sent`() {
        val fields = CallEventWorker.eventFields(
            clientEventId = "evt-3",
            direction = "sideways",
            outcome = "unknown",
            phoneNumber = null,
            durationSeconds = null,
            occurredAtMillis = 1_700_000_000_000L,
        )
        assertFalse(fields.containsKey("direction"))
    }

    @Test
    fun `negative durations are dropped, never sent`() {
        val fields = CallEventWorker.eventFields(
            clientEventId = "evt-4",
            direction = "out",
            outcome = "answered",
            phoneNumber = null,
            durationSeconds = -1L,
            occurredAtMillis = 1_700_000_000_000L,
        )
        assertFalse(fields.containsKey("duration_seconds"))
    }

    @Test
    fun `occurred_at is an ISO instant of the call-log timestamp`() {
        assertEquals(
            "2023-11-14T22:13:20Z",
            CallEvents.occurredAtIso(1_700_000_000_000L),
        )
    }

    @Test
    fun `the JSON body carries fields and no credentials`() {
        val json = WacrmApi.buildJsonObject(
            mapOf("client_event_id" to "evt-1", "outcome" to "missed", "note" to "a\"b\\c"),
        )
        assertEquals("""{"client_event_id":"evt-1","outcome":"missed","note":"a\"b\\c"}""", json)
        assertFalse(json.contains("Bearer"))
        assertFalse(json.contains("password"))
        assertFalse(json.contains("token"))
    }

    @Test
    fun `unique work names are namespaced per event id`() {
        assertTrue((CallEvents.WORK_NAME_PREFIX + "evt-1").startsWith("call-event-"))
        // Distinct from recording uploads: an event can never collide with one.
        assertFalse(CallEvents.WORK_NAME_PREFIX == WacrmUploads.WORK_NAME_PREFIX)
    }

    @Test
    fun `event post statuses bucket with 503 as transient retry`() {
        // 200 (idempotent-retry answer), 201 and 409 (receipt under
        // our idempotency key) are all terminal success.
        for (code in listOf(200, 201, 409)) {
            assertEquals("HTTP $code", CallEventWorker.PostDisposition.SUCCESS, CallEventWorker.classifyPostStatus(code))
        }
        assertEquals(CallEventWorker.PostDisposition.AUTH, CallEventWorker.classifyPostStatus(401))
        // Other 4xx is terminal — a malformed event can never succeed
        // on retry. Unchanged.
        for (code in listOf(400, 403, 404, 422)) {
            assertEquals("HTTP $code", CallEventWorker.PostDisposition.TERMINAL, CallEventWorker.classifyPostStatus(code))
        }
        // 5xx — including 503 registry outage — and anything
        // unexpected is transient: retry while the budget lasts, never
        // terminal FAILED due to auth and never a wipe.
        for (code in listOf(500, 502, 503, 504)) {
            assertEquals("HTTP $code", CallEventWorker.PostDisposition.RETRY, CallEventWorker.classifyPostStatus(code))
        }
    }
}
