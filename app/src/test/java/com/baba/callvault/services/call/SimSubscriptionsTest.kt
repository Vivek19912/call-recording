/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Radio-stack fallback for the call's subscription: the single SIM that is ringing or
 * off-hook right now. Zero or several active means unknown (never guess between two live
 * calls) — an explicit SIM selection then fails closed in [SimCallFilter].
 *
 * Pure JVM: only [pickSingleActiveCall] is tested here (telephony constants are compile-time
 * ints); the binder query itself needs a device.
 */
class SimSubscriptionsTest {

    @Test
    fun `the single ringing SIM is the call`() {
        assertEquals(
            2,
            SimSubscriptions.pickSingleActiveCall(
                mapOf(
                    1 to TelephonyManager.CALL_STATE_IDLE,
                    2 to TelephonyManager.CALL_STATE_RINGING,
                ),
            ),
        )
    }

    @Test
    fun `the single off-hook SIM is the call`() {
        assertEquals(
            1,
            SimSubscriptions.pickSingleActiveCall(
                mapOf(
                    1 to TelephonyManager.CALL_STATE_OFFHOOK,
                    2 to TelephonyManager.CALL_STATE_IDLE,
                ),
            ),
        )
    }

    @Test
    fun `no active SIM means unknown, not SIM 1`() {
        assertNull(
            SimSubscriptions.pickSingleActiveCall(
                mapOf(
                    1 to TelephonyManager.CALL_STATE_IDLE,
                    2 to TelephonyManager.CALL_STATE_IDLE,
                ),
            ),
        )
        assertNull(SimSubscriptions.pickSingleActiveCall(emptyMap()))
    }

    @Test
    fun `two live SIMs are ambiguous and yield unknown`() {
        assertNull(
            SimSubscriptions.pickSingleActiveCall(
                mapOf(
                    1 to TelephonyManager.CALL_STATE_OFFHOOK,
                    2 to TelephonyManager.CALL_STATE_RINGING,
                ),
            ),
        )
    }

    @Test
    fun `comparison is on subscription ids, never slot order`() {
        assertEquals(
            11,
            SimSubscriptions.pickSingleActiveCall(
                mapOf(12 to TelephonyManager.CALL_STATE_IDLE, 11 to TelephonyManager.CALL_STATE_OFFHOOK),
            ),
        )
    }

    private fun dualSim() = listOf(
        SimInfo(subscriptionId = 1, slotIndex = 0, carrierName = "Airtel", number = null),
        SimInfo(subscriptionId = 2, slotIndex = 1, carrierName = "Jio", number = null),
    )

    @Test
    fun `broadcast subscription id takes effect when the radio agrees`() {
        assertEquals(1, SimSubscriptions.interpretCallSubscription(1, dualSim(), 1))
        assertEquals(2, SimSubscriptions.interpretCallSubscription(2, dualSim(), 2))
    }

    @Test
    fun `broadcast alone is trusted when it names a known subscription`() {
        // OEM omitted nothing here; the resolver was blind (e.g. queried between states).
        assertEquals(1, SimSubscriptions.interpretCallSubscription(1, dualSim(), null))
    }

    @Test
    fun `slot-kind broadcast values resolve through the OS slot list`() {
        // ROM puts the slot index in the "subscription" extra: with ids that cannot collide
        // with slots, slot 0/1 must resolve to the subscription the OS lists there — never to
        // an assumed slot-plus-one mapping.
        val shifted = listOf(
            SimInfo(subscriptionId = 11, slotIndex = 0, carrierName = "A", number = null),
            SimInfo(subscriptionId = 12, slotIndex = 1, carrierName = "B", number = null),
        )
        assertEquals(11, SimSubscriptions.interpretCallSubscription(0, shifted, null))
        assertEquals(12, SimSubscriptions.interpretCallSubscription(1, shifted, null))
    }

    @Test
    fun `AOSP meaning wins when a value is both a subscription id and a slot`() {
        // subIds {1,2}, slots {0,1}: broadcast 1 names subscription 1 per the documented extra
        // semantic, even though 1 is also SIM 2's slot. A disagreeing resolver still forces
        // unknown (see conflicting test) instead of a guess.
        assertEquals(1, SimSubscriptions.interpretCallSubscription(1, dualSim(), null))
    }

    @Test
    fun `garbage broadcast values are discarded, resolver can still save the call`() {
        assertEquals(2, SimSubscriptions.interpretCallSubscription(99, dualSim(), 2))
        assertEquals(2, SimSubscriptions.interpretCallSubscription(-1, dualSim(), 2))
        assertNull(SimSubscriptions.interpretCallSubscription(99, dualSim(), null))
    }

    @Test
    fun `conflicting broadcast and resolver yield unknown instead of a guess`() {
        assertNull(SimSubscriptions.interpretCallSubscription(1, dualSim(), 2))
    }

    @Test
    fun `resolver-only identification works when the broadcast is absent`() {
        assertEquals(1, SimSubscriptions.interpretCallSubscription(null, dualSim(), 1))
        assertNull(SimSubscriptions.interpretCallSubscription(null, dualSim(), null))
    }
}
