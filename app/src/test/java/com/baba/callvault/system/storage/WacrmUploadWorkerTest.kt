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
 * Phase 2B upload metadata: carrier recordings carry the CallLog number + direction for
 * server-side contact matching; anything else uploads exactly like Phase 1.
 *
 * Pure function tests only — the worker's network/retry behavior is covered by design review
 * and device testing, not by JVM fakes of ContentResolver.
 */
class WacrmUploadWorkerTest {

    @Test
    fun `carrier metadata includes phone number and direction`() {
        val fields = WacrmUploadWorker.uploadFields(
            displayName = "20260101_120000.000+0000_out_Mum.ogg",
            recordedAtMillis = 1700000000000L,
            durationSeconds = 35L,
            phoneNumber = "+91916394642516",
            direction = WacrmUploadWorker.DIRECTION_OUT,
        )
        assertEquals("+91916394642516", fields["phone_number"])
        assertEquals("out", fields["direction"])
        assertEquals("20260101_120000.000+0000_out_Mum.ogg", fields["file_name"])
        assertEquals("35", fields["duration_seconds"])
        assertTrue(fields["recorded_at"]?.startsWith("2023-") ?: false)
    }

    @Test
    fun `incoming direction maps to in`() {
        val fields = WacrmUploadWorker.uploadFields(
            displayName = "call.ogg",
            recordedAtMillis = -1L,
            durationSeconds = null,
            phoneNumber = "+91111",
            direction = WacrmUploadWorker.DIRECTION_IN,
        )
        assertEquals("in", fields["direction"])
    }

    @Test
    fun `missing number and direction upload like phase 1`() {
        val fields = WacrmUploadWorker.uploadFields(
            displayName = "call.ogg",
            recordedAtMillis = -1L,
            durationSeconds = null,
            phoneNumber = null,
            direction = null,
        )
        assertFalse(fields.containsKey("phone_number"))
        assertFalse(fields.containsKey("direction"))
        assertFalse(fields.containsKey("recorded_at"))
        assertFalse(fields.containsKey("duration_seconds"))
        // The pre-existing fields are untouched.
        assertEquals("call.ogg", fields["file_name"])
    }

    @Test
    fun `blank number and unknown direction are dropped never sent`() {
        val fields = WacrmUploadWorker.uploadFields(
            displayName = "call.ogg",
            recordedAtMillis = 1700000000000L,
            durationSeconds = 10L,
            phoneNumber = "   ",
            direction = "missed",
        )
        assertFalse(fields.containsKey("phone_number"))
        assertFalse(fields.containsKey("direction"))
    }

    @Test
    fun `phone sends call_type phone`() {
        val fields = WacrmUploadWorker.uploadFields(
            displayName = "20260101_120000.000+0000_out_Mum.ogg",
            recordedAtMillis = 1700000000000L,
            durationSeconds = 35L,
            phoneNumber = "+91916394642516",
            direction = WacrmUploadWorker.DIRECTION_OUT,
            callType = "phone",
        )
        assertEquals("phone", fields["call_type"])
    }

    @Test
    fun `whatsapp sends call_type whatsapp`() {
        val fields = WacrmUploadWorker.uploadFields(
            displayName = "20260101_120000.000+0000_voip-WhatsApp.ogg",
            recordedAtMillis = 1700000000000L,
            durationSeconds = 35L,
            phoneNumber = null,
            direction = null,
            callType = "whatsapp",
        )
        assertEquals("whatsapp", fields["call_type"])
        // No number/direction invented for app calls.
        assertFalse(fields.containsKey("phone_number"))
        assertFalse(fields.containsKey("direction"))
    }

    @Test
    fun `whatsapp business sends call_type whatsapp_business`() {
        val fields = WacrmUploadWorker.uploadFields(
            displayName = "call.ogg",
            recordedAtMillis = 1700000000000L,
            durationSeconds = 10L,
            phoneNumber = null,
            direction = null,
            callType = "whatsapp_business",
        )
        assertEquals("whatsapp_business", fields["call_type"])
    }

    @Test
    fun `null call type sends no fake value`() {
        val fields = WacrmUploadWorker.uploadFields(
            displayName = "call.ogg",
            recordedAtMillis = 1700000000000L,
            durationSeconds = 10L,
            phoneNumber = "+91111",
            direction = WacrmUploadWorker.DIRECTION_OUT,
            callType = null,
        )
        assertFalse(fields.containsKey("call_type"))
        // Phone + direction behavior from Phase 2B is unchanged.
        assertEquals("+91111", fields["phone_number"])
        assertEquals("out", fields["direction"])
    }

    @Test
    fun `unknown call type strings are dropped never sent`() {
        for (fake in listOf("voip", "unknown", "other", "telegram", "signal", "")) {
            val fields = WacrmUploadWorker.uploadFields(
                displayName = "call.ogg",
                recordedAtMillis = 1700000000000L,
                durationSeconds = 10L,
                phoneNumber = null,
                direction = null,
                callType = fake,
            )
            assertFalse("call_type for '$fake'", fields.containsKey("call_type"))
        }
    }
}
