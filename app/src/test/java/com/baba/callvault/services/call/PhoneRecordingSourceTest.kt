/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Owner phone policy → current subscription mapping. The server
 * speaks logical slots (sim1/sim2); the gate speaks subscription
 * ids. The mapping is resolved against the OS-provided active
 * list at gate time — never `slot+1`, never by phone number.
 *
 * Pure JVM: no Android APIs involved.
 */
class PhoneRecordingSourceTest {

    private fun sims() = listOf(
        SimInfo(subscriptionId = 14, slotIndex = 0, carrierName = "Airtel", number = "+911234567890"),
        SimInfo(subscriptionId = 8, slotIndex = 1, carrierName = "Jio", number = null),
    )

    @Test
    fun `server none maps to nothing`() {
        assertNull(PhoneRecordingSource.resolveSelectedSubscription(PhoneRecordingSource.NONE, sims()))
        assertNull(PhoneRecordingSource.resolveSelectedSubscription(null, sims()))
    }

    @Test
    fun `server sim1 maps to the subscription currently in slot 0`() {
        assertEquals(
            14,
            PhoneRecordingSource.resolveSelectedSubscription(PhoneRecordingSource.SIM1, sims()),
        )
    }

    @Test
    fun `server sim2 maps to the subscription currently in slot 1`() {
        assertEquals(
            8,
            PhoneRecordingSource.resolveSelectedSubscription(PhoneRecordingSource.SIM2, sims()),
        )
    }

    @Test
    fun `a configured slot with no SIM maps to nothing, never the other SIM`() {
        val single = listOf(
            SimInfo(subscriptionId = 14, slotIndex = 0, carrierName = "Airtel", number = null),
        )
        assertNull(PhoneRecordingSource.resolveSelectedSubscription(PhoneRecordingSource.SIM2, single))
        assertNull(PhoneRecordingSource.resolveSelectedSubscription(PhoneRecordingSource.SIM1, emptyList()))
    }

    @Test
    fun `mapping follows slot moves, not id arithmetic`() {
        // Subscription ids survive in odd places; only the OS slot
        // order decides. id 8 in slot 0 is SIM 1's subscription.
        val moved = listOf(
            SimInfo(subscriptionId = 8, slotIndex = 0, carrierName = "Jio", number = null),
            SimInfo(subscriptionId = 14, slotIndex = 1, carrierName = "Airtel", number = null),
        )
        assertEquals(8, PhoneRecordingSource.resolveSelectedSubscription(PhoneRecordingSource.SIM1, moved))
        assertEquals(14, PhoneRecordingSource.resolveSelectedSubscription(PhoneRecordingSource.SIM2, moved))
    }

    @Test
    fun `wire values parse strictly`() {
        assertEquals(PhoneRecordingSource.SIM1, PhoneRecordingSource.fromWire("sim1"))
        assertEquals(PhoneRecordingSource.SIM2, PhoneRecordingSource.fromWire(" SIM2 "))
        assertEquals(PhoneRecordingSource.NONE, PhoneRecordingSource.fromWire("none"))
        assertNull(PhoneRecordingSource.fromWire("slot1"))
        assertNull(PhoneRecordingSource.fromWire("1"))
        assertNull(PhoneRecordingSource.fromWire(null))
        assertNull(PhoneRecordingSource.fromWire(""))
    }

    @Test
    fun `per-user policies are independent values`() {
        // Vikas sim1 + whatsapp vs Rahul sim2 + business are just
        // distinct enum values; resolving one never touches another.
        val vikas = PhoneRecordingSource.fromWire("sim1")!!
        val rahul = PhoneRecordingSource.fromWire("sim2")!!
        assertEquals(14, PhoneRecordingSource.resolveSelectedSubscription(vikas, sims()))
        assertEquals(8, PhoneRecordingSource.resolveSelectedSubscription(rahul, sims()))
    }
}
