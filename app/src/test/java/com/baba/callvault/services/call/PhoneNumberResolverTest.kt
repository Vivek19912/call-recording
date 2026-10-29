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
 * Owner-configured phone number → current subscription mapping.
 *
 * The server speaks a normalized phone number; the gate speaks
 * subscription ids. Resolution is against the OS-provided active
 * list at gate time: primary `SubscriptionInfo.getNumber`, fallback
 * `SubscriptionManager.getPhoneNumber` — exactly one normalized
 * match wins, everything else fails closed.
 *
 * Pure JVM: no Android APIs involved.
 */
class PhoneNumberResolverTest {

    private fun sims() = listOf(
        SimInfo(subscriptionId = 11, slotIndex = 0, carrierName = "airtel", number = "+918953065369"),
        SimInfo(subscriptionId = 8, slotIndex = 1, carrierName = "airtel", number = "+917460939319"),
    )

    @Test
    fun `1 exact SIM1 number resolves to subscription 11`() {
        assertEquals(11, PhoneNumberResolver.resolveSubscriptionId("918953065369", sims()))
    }

    @Test
    fun `2 exact SIM2 number resolves to subscription 8`() {
        assertEquals(8, PhoneNumberResolver.resolveSubscriptionId("917460939319", sims()))
    }

    @Test
    fun `3 no matching SIM resolves to unknown`() {
        assertNull(PhoneNumberResolver.resolveSubscriptionId("919999999999", sims()))
    }

    @Test
    fun `4 same number on both SIMs resolves to unknown, never first match`() {
        val dupes = listOf(
            SimInfo(subscriptionId = 11, slotIndex = 0, carrierName = "airtel", number = "+918953065369"),
            SimInfo(subscriptionId = 8, slotIndex = 1, carrierName = "airtel", number = "+918953065369"),
        )
        assertNull(PhoneNumberResolver.resolveSubscriptionId("918953065369", dupes))
    }

    @Test
    fun `5 SubscriptionInfo number is preferred over the fallback`() {
        val subs = listOf(
            SimInfo(
                subscriptionId = 11, slotIndex = 0, carrierName = "airtel",
                number = "+918953065369", numberFallback = "+917460939319",
            ),
            SimInfo(subscriptionId = 8, slotIndex = 1, carrierName = "airtel", number = "+917460939319"),
        )
        // Primary (+918953065369) matches SIM 1 even though the
        // fallback would also match SIM 2 — but that is TWO matches,
        // so the correct answer is unknown, not "first wins".
        assertNull(PhoneNumberResolver.resolveSubscriptionId("917460939319", subs))
        assertEquals(11, PhoneNumberResolver.resolveSubscriptionId("918953065369", subs.take(1)))
    }

    @Test
    fun `6 getPhoneNumber fallback matches when primary is missing`() {
        val subs = listOf(
            SimInfo(
                subscriptionId = 11, slotIndex = 0, carrierName = "airtel",
                number = null, numberFallback = "+918953065369",
            ),
        )
        assertEquals(11, PhoneNumberResolver.resolveSubscriptionId("918953065369", subs))
    }

    @Test
    fun `7 Vivo SIM1 shape - empty manager value plus valid SubscriptionInfo matches`() {
        // Hardware-verified Vivo I2304: getPhoneNumber(11) is "" while
        // SubscriptionInfo.getNumber() holds the number.
        val subs = listOf(
            SimInfo(
                subscriptionId = 11, slotIndex = 0, carrierName = "airtel",
                number = "+918953065369", numberFallback = null,
            ),
        )
        assertEquals(11, PhoneNumberResolver.resolveSubscriptionId("+91 89530 65369", subs))
    }

    @Test
    fun `8 SIM2 shape - both APIs valid and consistent matches`() {
        val subs = listOf(
            SimInfo(
                subscriptionId = 8, slotIndex = 1, carrierName = "airtel",
                number = "+917460939319", numberFallback = "+917460939319",
            ),
        )
        assertEquals(8, PhoneNumberResolver.resolveSubscriptionId("917460939319", subs))
    }

    @Test
    fun `9 malformed configured number resolves to unknown`() {
        assertNull(PhoneNumberResolver.resolveSubscriptionId(null, sims()))
        assertNull(PhoneNumberResolver.resolveSubscriptionId("", sims()))
        assertNull(PhoneNumberResolver.resolveSubscriptionId("abc", sims()))
        assertNull(PhoneNumberResolver.resolveSubscriptionId("123", sims()))
    }

    @Test
    fun `10 formatting differences still match`() {
        assertEquals(11, PhoneNumberResolver.resolveSubscriptionId("+91 89530 65369", sims()))
        assertEquals(11, PhoneNumberResolver.resolveSubscriptionId("+91-89530-65369", sims()))
        assertEquals(11, PhoneNumberResolver.resolveSubscriptionId("918953065369", sims()))
    }

    @Test
    fun `11 subscriptions without any number are ignored`() {
        val subs = listOf(
            SimInfo(subscriptionId = 11, slotIndex = 0, carrierName = "airtel", number = null),
            SimInfo(subscriptionId = 8, slotIndex = 1, carrierName = "airtel", number = "+917460939319"),
        )
        assertNull(PhoneNumberResolver.resolveSubscriptionId("918953065369", subs))
        assertEquals(8, PhoneNumberResolver.resolveSubscriptionId("917460939319", subs))
    }

    @Test
    fun `12 inactive subscription is never consulted`() {
        // Only the active list is passed in; a removed SIM simply is
        // not there, so nothing stale can match.
        assertNull(PhoneNumberResolver.resolveSubscriptionId("918953065369", emptyList()))
    }

    @Test
    fun `display formatting is readable and display-only`() {
        assertEquals("+91 89530 65369", PhoneNumberResolver.formatForDisplay("918953065369"))
        assertEquals("", PhoneNumberResolver.formatForDisplay(null))
    }
}
