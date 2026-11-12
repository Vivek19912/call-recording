/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

import android.content.Context
import com.baba.callvault.system.storage.FakeSharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The one-time USB-debugging onboarding flag is completion state, not live state.
 *
 * It records that an offline loopback setup reached ARMED with USB debugging verified
 * on — nothing more. It must default off (including for existing installs, which have
 * no such step in their history), must never be exported to another device, must
 * survive mode switches untouched (it is not a mode capability), and must be set back
 * to off by an explicit call only.
 *
 * Plain JUnit4 + [FakeSharedPreferences]: real [AppPreferences] key names, defaults
 * and read-back semantics, no Robolectric.
 */
class UsbOnboardingCompletedTest {

    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        val context: Context = mockk()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
        prefs = AppPreferences(context)
    }

    @Test
    fun `fresh install has not completed USB onboarding`() {
        assertFalse(
            "A new install must not claim a setup step it never performed",
            prefs.isUsbOnboardingCompleted(),
        )
    }

    @Test
    fun `flag round-trips true then false`() {
        prefs.setUsbOnboardingCompleted(true)
        assertTrue(prefs.isUsbOnboardingCompleted())
        prefs.setUsbOnboardingCompleted(false)
        assertFalse(prefs.isUsbOnboardingCompleted())
    }

    @Test
    fun `flag is device state, never exported`() {
        assertFalse(
            "USB onboarding completion belongs to this phone, like pairing and wizard state",
            AppPreferences.EXPORTABLE_KEYS.contains("usb_onboarding_completed"),
        )
    }

    @Test
    fun `switching to Shizuku does not clear the flag`() {
        prefs.setUsbOnboardingCompleted(true)

        prefs.disableWhatModeCannotDo(PrivilegedMode.SHIZUKU, 36)

        assertTrue(
            "The flag is not a mode capability: entering Shizuku must leave it alone, " +
                "so returning to standalone still knows the step was done",
            prefs.isUsbOnboardingCompleted(),
        )
    }

    @Test
    fun `switching back to standalone does not invent the flag`() {
        prefs.disableWhatModeCannotDo(PrivilegedMode.SHIZUKU, 36)
        prefs.restoreWhatModeCanDoAgain(PrivilegedMode.STANDALONE, 36)

        assertFalse(
            "Restore must only undo what a mode switch took away; it must never " +
                "conjure a completed onboarding out of nothing",
            prefs.isUsbOnboardingCompleted(),
        )
    }
}
