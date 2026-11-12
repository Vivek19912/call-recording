package com.baba.callvault.system.storage

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.voip.WhatsappRecordingSource
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner-controlled recording policy: the Owner's WhatsApp selection is the only
 * gate for WhatsApp-family calls, and the synced policy carries a bounded
 * freshness stamp. Local switches, pickers, migration dialogs and backup
 * import can never override it.
 *
 * Pure JVM (no Robolectric): [WhatsappRecordingSource.allowsPackage] and the
 * [WacrmAuth] parse helpers take strings, and [WhatsappSourceSync.isPolicyFresh]
 * / [clearRecordingPolicy] run against a mocked [AppPreferences].
 */
class OwnerRecordingPolicyTest {

    private companion object {
        const val WA = "com.whatsapp"
        const val W4B = "com.whatsapp.w4b"
        const val OTHER = "org.telegram.messenger"
    }

    @Test
    fun `none blocks both whatsapp apps`() {
        assertEquals(false, WhatsappRecordingSource.allowsPackage(WhatsappRecordingSource.NONE, WA))
        assertEquals(false, WhatsappRecordingSource.allowsPackage(WhatsappRecordingSource.NONE, W4B))
    }

    @Test
    fun `whatsapp allows only standard whatsapp`() {
        assertEquals(true, WhatsappRecordingSource.allowsPackage(WhatsappRecordingSource.WHATSAPP, WA))
        assertEquals(false, WhatsappRecordingSource.allowsPackage(WhatsappRecordingSource.WHATSAPP, W4B))
    }

    @Test
    fun `whatsapp business allows only whatsapp business`() {
        assertEquals(false, WhatsappRecordingSource.allowsPackage(WhatsappRecordingSource.WHATSAPP_BUSINESS, WA))
        assertEquals(true, WhatsappRecordingSource.allowsPackage(WhatsappRecordingSource.WHATSAPP_BUSINESS, W4B))
    }

    @Test
    fun `non-whatsapp packages stay on the generic path`() {
        // Null = not WhatsApp-family: the owner selection does not apply, and the
        // generic local behavior underneath is preserved (never broadened here).
        assertNull(WhatsappRecordingSource.allowsPackage(WhatsappRecordingSource.WHATSAPP, OTHER))
        assertNull(WhatsappRecordingSource.allowsPackage(WhatsappRecordingSource.NONE, OTHER))
        assertNull(WhatsappRecordingSource.allowsPackage(WhatsappRecordingSource.WHATSAPP_BUSINESS, null))
    }

    @Test
    fun `owner selections survive case and whitespace`() {
        assertEquals(true, WhatsappRecordingSource.allowsPackage(WhatsappRecordingSource.WHATSAPP, " COM.WHATSAPP "))
        assertEquals(false, WhatsappRecordingSource.allowsPackage(WhatsappRecordingSource.WHATSAPP_BUSINESS, "com.whatsapp"))
    }

    @Test
    fun `fresh policy passes, missing or week-old policy fails`() {
        val now = 1_000_000_000_000L
        val fresh = mockk<AppPreferences>()
        every { fresh.getLastPolicySyncMs() } returns now - 60_000L
        assertTrue(WhatsappSourceSync.isPolicyFresh(fresh, nowMs = now))

        val stale = mockk<AppPreferences>()
        every { stale.getLastPolicySyncMs() } returns now - WhatsappSourceSync.POLICY_MAX_STALE_MS - 1L
        assertFalse(WhatsappSourceSync.isPolicyFresh(stale, nowMs = now))

        val never = mockk<AppPreferences>()
        every { never.getLastPolicySyncMs() } returns 0L
        assertFalse(WhatsappSourceSync.isPolicyFresh(never, nowMs = now))
    }

    @Test
    fun `clearing drops number, opt-in, configured flag and sync stamp`() {
        val fake = FakeSharedPreferences()
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns fake
        val prefs = AppPreferences(context)
        prefs.setPhoneRecordingNumber("917460939319")
        prefs.setPhoneRecordingEnabled(true)
        prefs.setPhoneRecordingConfigured(true)
        prefs.setLastPolicySyncMs(1_000L)
        prefs.setWhatsappRecordingSource(WhatsappRecordingSource.WHATSAPP)
        WhatsappSourceSync.clearRecordingPolicy(prefs)
        assertNull(prefs.getPhoneRecordingNumber())
        assertFalse(prefs.isPhoneRecordingEnabled())
        assertFalse(prefs.isPhoneRecordingConfigured())
        assertEquals(0L, prefs.getLastPolicySyncMs())
        // The cleared snapshot is published for any open UI.
        val flow = WhatsappSourceSync.policyFlow.value
        assertNull(flow?.phoneNumber)
        assertEquals(false, flow?.phoneEnabled)
        assertEquals(false, flow?.phoneConfigured)
    }

    @Test
    fun `owner-managed keys are never exported or imported`() {
        // Importing another phone's selection could silently enable or disable
        // recording for this device's owner policy.
        assertFalse(AppPreferences.EXPORTABLE_KEYS.contains("whatsapp_recording_source"))
        assertFalse(AppPreferences.EXPORTABLE_KEYS.contains("phone_recording_number"))
        assertFalse(AppPreferences.EXPORTABLE_KEYS.contains("phone_recording_enabled"))
        assertFalse(AppPreferences.EXPORTABLE_KEYS.contains("phone_recording_configured"))
        assertFalse(AppPreferences.EXPORTABLE_KEYS.contains("last_policy_sync_ms"))
    }
}
