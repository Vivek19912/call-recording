package com.baba.callvault.system.storage

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.WacrmSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the dialer availability fix: the obsolete `wacrm_enabled`
 * preference no longer gates the desktop click-to-dial pipeline.
 *
 * Before the fix, every dialer entry point early-returned on a stored `false`
 * while the Settings toggle that could flip it had been removed — so an
 * authenticated user with an old `false` value silently received nothing: no
 * socket, no request processing, not even the notification fallback. A valid
 * authenticated session is now sufficient everywhere.
 *
 * Plain JUnit4 + MockK (no Robolectric): the ApplicationProvider-free
 * [Context] mock serves a stubbed [SharedPreferences], and the session store
 * is faked with [mockkObject]. Network is never touched: the GET fallback is
 * stubbed, and the consume wait uses the existing zero-timeout test seam.
 */
class DialGateRegressionTest {

    private lateinit var context: Context
    private lateinit var store: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var session: WacrmSession.Session

    private val requestId = "11111111-1111-4111-8111-111111111111"

    @Before
    fun setUp() {
        context = mockk()
        store = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns store
        every { store.edit() } returns editor
        // Old saved value: the upload toggle was OFF (or never flipped on).
        every { store.getBoolean("wacrm_enabled", any()) } returns false
        every { store.getString("wacrm_url", any()) } returns "https://whatsappmax.in"
        session = WacrmSession.Session(
            accessToken = "access",
            refreshToken = "refresh",
            expiresAtMs = System.currentTimeMillis() + 3_600_000L,
            userId = "user-1",
            userEmail = "user@example.invalid",
            userName = null,
            accountId = "account-1",
            accountName = null,
            role = null,
        )
        mockkObject(WacrmSession)
        mockkObject(DialRequests)
        DialSocketClient.consumeWaitTimeoutMsForTest = 0
    }

    @After
    fun tearDown() {
        DialSocketClient.resetConsumeStateForTest()
        DialEventProcessor.resetForTest()
        unmockkAll()
    }

    // --- Automatic dialer stays permanently enabled --------------------------

    @Test
    fun `auto-open is on despite stored false and cannot be turned off`() {
        val prefs = AppPreferences(context)
        assertTrue(prefs.isDialAutoOpen())
        prefs.setDialAutoOpen(false)
        verify { editor.putBoolean("dial_auto_open", true) }
        verify { editor.apply() }
        assertTrue(prefs.isDialAutoOpen())
    }

    // --- DialSocketService.ensureStarted -------------------------------------

    @Test
    fun `service starts for a signed-in account despite old pref false`() {
        every { WacrmSession.get(any()) } returns session
        val manager = mockk<NotificationManager>(relaxed = true)
        every { context.getSystemService(NotificationManager::class.java) } returns manager
        every { manager.getNotificationChannel(any<String>()) } returns null

        DialSocketService.ensureStarted(context)

        verify { manager.createNotificationChannel(any()) }
    }

    @Test
    fun `service stays stopped without a session`() {
        every { WacrmSession.get(any()) } returns null
        val manager = mockk<NotificationManager>(relaxed = true)
        every { context.getSystemService(NotificationManager::class.java) } returns manager
        every { manager.getNotificationChannel(any<String>()) } returns null

        DialSocketService.ensureStarted(context)

        verify(exactly = 0) { manager.createNotificationChannel(any()) }
    }

    // --- DialEventProcessor.handle --------------------------------------------

    @Test
    fun `request is processed with a valid session despite old pref false`() {
        every { WacrmSession.get(any()) } returns session
        every {
            DialRequests.fetchOne(any(), any(), any(), any())
        } returns DialRequests.FetchOneResult.NetworkError

        DialEventProcessor.handle(context, requestId)

        // Reaching the authoritative fetch proves the old preference gate
        // no longer discards the request.
        verify(atLeast = 1) { DialRequests.fetchOne(any(), any(), any(), any()) }
    }

    @Test
    fun `request is not processed without a session`() {
        every { WacrmSession.get(any()) } returns null

        DialEventProcessor.handle(context, requestId)

        verify(exactly = 0) { DialRequests.fetchOne(any(), any(), any(), any()) }
    }

    @Test
    fun `expired session goes through refresh instead of bypassing auth`() {
        val expired = session.copy(expiresAtMs = 1L)
        every { WacrmSession.get(any()) } returns expired
        mockkObject(DialSession)
        every { DialSession.refresh(any(), any()) } returns null

        DialEventProcessor.handle(context, requestId)

        verify(exactly = 0) { DialRequests.fetchOne(any(), any(), any(), any()) }
    }

    // --- Dialer intent shape ---------------------------------------------------

    @Test
    fun `dialer uris use the tel scheme`() {
        assertEquals("tel:+14155552671", DialPhoneValidation.toTelUri("+1 (415) 555-2671"))
        assertNull(DialPhoneValidation.toTelUri("n/a"))
    }
}
