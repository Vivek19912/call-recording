/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import android.content.Context
import androidx.work.WorkerParameters
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.system.storage.FakeSharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Automatic update checking is permanently disabled.
 *
 * Four independent layers pin it OFF, and each is tested here:
 * the getter is hardcoded false, the setter coerces any write to
 * false (including the startup reset that clears a legacy stored
 * `true`), the key is not exportable/importable (a backup can never
 * restore it), and the worker early-returns before any GitHub API
 * call. The scheduler and worker both branch on the getter, so no
 * periodic work is ever enqueued and no on-open check ever runs.
 *
 * Plain JVM (MockK + [FakeSharedPreferences]); no Robolectric, no network.
 */
class UpdateCheckPolicyTest {

    private lateinit var context: Context
    private lateinit var fake: FakeSharedPreferences
    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        context = mockk()
        fake = FakeSharedPreferences()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns fake
        prefs = AppPreferences(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `update checking evaluates to false by default`() {
        assertFalse(prefs.isUpdateCheckEnabled())
    }

    @Test
    fun `legacy stored true is forced off and stays off`() {
        // A `true` left by an older version, written straight to the store.
        fake.edit().putBoolean("update_check_enabled", true).commit()
        // The startup reset (CallVaultApplication) routes through the setter.
        prefs.setUpdateCheckEnabled(true)
        assertFalse(prefs.isUpdateCheckEnabled())
        assertEquals(false, fake.getBoolean("update_check_enabled", true))
        // And enabling is impossible afterwards.
        prefs.setUpdateCheckEnabled(true)
        assertFalse(prefs.isUpdateCheckEnabled())
    }

    @Test
    fun `backup export omits the key and import cannot restore it`() {
        assertFalse(AppPreferences.EXPORTABLE_KEYS.contains("update_check_enabled"))
        val crafted = """{"app":"CallVault","format":1,"settings": {"update_check_enabled":{"type":"bool","value":true}}}"""
        assertEquals(0, prefs.importFromJson(crafted))
        assertFalse(prefs.isUpdateCheckEnabled())
        assertFalse(fake.contains("update_check_enabled"))
    }

    @Test
    fun `worker returns before any network when checks are disabled`() = runTest {
        val params = mockk<WorkerParameters>(relaxed = true)
        val worker = UpdateCheckWorker(context, params)
        val result = worker.doWork()
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        // The enabled path stamps the check time BEFORE the GitHub call;
        // an untouched stamp proves the network was never reached.
        assertEquals(0L, prefs.getLastUpdateCheckMillis())
    }
}
