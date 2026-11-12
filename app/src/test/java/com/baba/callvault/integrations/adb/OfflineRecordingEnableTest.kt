/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.system.storage.FakeSharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `OfflineRecording.enable()` must never leave a misleading enabled state.
 *
 * Arming runs over a borrowed transport and can fail after the opt-in was
 * tentatively persisted (no Wi-Fi, refused switch, port never came back). A failed
 * *fresh* setup must roll the opt-in back to off — otherwise every later connect
 * takes the loopback-only path with no Wireless-Debugging fallback, on Wi-Fi too.
 * A *previously working* setup keeps its flag so a failed re-arm can still recover.
 *
 * Plain JUnit4: [AdbShell] and [RecorderBackend] are mocked at the object level,
 * real [AppPreferences] over [FakeSharedPreferences]. No Robolectric.
 */
class OfflineRecordingEnableTest {

    private lateinit var context: Context
    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        context = mockk()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
        prefs = AppPreferences(context)
        mockkObject(AdbShell, RecorderBackend)
        every { RecorderBackend.ensureRunning(any()) } returns true
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `proven USB debugging off refuses without touching preferences`() {
        every { AdbShell.usbDebuggingState(any()) } returns UsbDebuggingState.OFF

        val result = OfflineRecording.enable(context)

        assertEquals(LoopbackArm.NEEDS_USB_DEBUGGING, result)
        assertFalse("A refused setup must not enable the opt-in", prefs.isOfflineRecordingEnabled())
        assertFalse(prefs.isUsbOnboardingCompleted())
        verify(exactly = 0) { AdbShell.armLoopbackIfNeededWithReason(any()) }
    }

    @Test
    fun `failed fresh arm rolls the opt-in back to off`() {
        every { AdbShell.usbDebuggingState(any()) } returns UsbDebuggingState.ON
        every { AdbShell.armLoopbackIfNeededWithReason(any()) } returns LoopbackArm.NO_WIFI

        val result = OfflineRecording.enable(context)

        assertEquals(LoopbackArm.NO_WIFI, result)
        assertFalse(
            "The listener never came up, so the persisted opt-in must not claim otherwise",
            prefs.isOfflineRecordingEnabled(),
        )
        assertFalse(prefs.isUsbOnboardingCompleted())
    }

    @Test
    fun `successful arm enables the opt-in and completes USB onboarding`() {
        every { AdbShell.usbDebuggingState(any()) } returns UsbDebuggingState.ON
        every { AdbShell.armLoopbackIfNeededWithReason(any()) } returns LoopbackArm.ARMED

        val result = OfflineRecording.enable(context)

        assertEquals(LoopbackArm.ARMED, result)
        assertTrue(prefs.isOfflineRecordingEnabled())
        assertTrue(
            "ARMED proves USB debugging was on and the listener answered",
            prefs.isUsbOnboardingCompleted(),
        )
    }

    @Test
    fun `failed re-arm preserves a previously working setup`() {
        every { AdbShell.usbDebuggingState(any()) } returns UsbDebuggingState.ON
        every { AdbShell.armLoopbackIfNeededWithReason(any()) } returns LoopbackArm.PORT_DID_NOT_COME_UP
        prefs.setOfflineRecordingEnabled(true)

        val result = OfflineRecording.enable(context)

        assertEquals(LoopbackArm.PORT_DID_NOT_COME_UP, result)
        assertTrue(
            "Clearing the flag on a transient failure would strand a setup that can " +
                "still recover; only a fresh opt-in rolls back",
            prefs.isOfflineRecordingEnabled(),
        )
    }
}
