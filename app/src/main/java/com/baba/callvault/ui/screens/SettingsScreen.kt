/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.baba.callvault.services.recording.DaemonKeepAliveService
import com.baba.callvault.services.recording.VoipCaptureController
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import android.text.format.DateUtils
import com.baba.callvault.ui.common.CvStatusPill
import com.baba.callvault.ui.common.CvTone
import androidx.compose.runtime.rememberCoroutineScope
import com.baba.callvault.R
import com.baba.callvault.ui.common.formatByteSize
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.annotation.StringRes
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.WacrmSession
import com.baba.callvault.system.storage.WacrmAuth
import com.baba.callvault.data.ModeCapability
import com.baba.callvault.data.SyncScheduleMode
import com.baba.callvault.data.TranscriptionMode
import com.baba.callvault.summary.SummaryModel
import com.baba.callvault.transcription.model.TranscriptionModel
import com.baba.callvault.ui.common.TranscriptionLabels
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.system.storage.WacrmApi
import com.baba.callvault.system.storage.DialSocketClient
import com.baba.callvault.system.storage.WacrmDeviceAuth
import com.baba.callvault.system.storage.WacrmSessionState
import com.baba.callvault.system.storage.WhatsappSourceSync
import com.baba.callvault.system.storage.PolicyRefreshLoop
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.system.openDeveloperSettings
import androidx.lifecycle.viewmodel.compose.viewModel
import com.baba.callvault.ui.common.ContactSelectionDialog
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.OemGateNotice
import com.baba.callvault.ui.common.CvScaffold
import com.baba.callvault.ui.common.CvSectionHeader
import com.baba.callvault.ui.common.M3DropdownField
import com.baba.callvault.ui.common.OptionItem
import com.baba.callvault.ui.viewmodels.ContactPickerType
import com.baba.callvault.ui.viewmodels.ContactPickerViewModel
import com.baba.callvault.ui.viewmodels.SettingsActions
import com.baba.callvault.ui.viewmodels.SettingsViewModel
import com.baba.callvault.ui.viewmodels.ContactPickerState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.RadioButton
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.WifiOff
import com.baba.callvault.ui.common.OfflineDialogMode
import com.baba.callvault.ui.common.OfflineRecordingDialog
import com.baba.callvault.data.voip.VoipAppPolicy
import com.baba.callvault.data.voip.WhatsappCallPolicy
import com.baba.callvault.data.voip.WhatsappRecordingSource
import com.baba.callvault.services.call.SimInfo
import com.baba.callvault.services.call.PhoneNumberResolver
import com.baba.callvault.services.call.SimSubscriptions

/**
 * Stateful wrapper for the Settings screen that connects [SettingsViewModel] to [SettingsContent].
 *
 * @param viewModel Handles saving whenever the user changes a setting.
 * @param onBack    Called when the user taps the top-bar back affordance; the router maps this to
 *                  [com.baba.callvault.ui.viewmodels.AppNavViewModel.navigateBack].
 * @param modifier  Optional modifier for the root scaffold.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Trigger recomposition when settings change by viewmodel.refresh()
    val updateTrigger by viewModel.updateTrigger.collectAsState()

    // ContactPickerViewModel owns the contact-loading logic and dialog state.
    val contactPickerViewModel: ContactPickerViewModel = viewModel()
    val contactPickerState by contactPickerViewModel.contactPickerState.collectAsState()

    SettingsContent(
        preferences = viewModel.preferences,
        updateTrigger = updateTrigger,
        actions = viewModel,
        contactPickerState = contactPickerState,
        onBack = onBack,
        onOpenContactsIncoming = { contactPickerViewModel.openContactPicker(ContactPickerType.INCOMING) },
        onOpenContactsOutgoing = { contactPickerViewModel.openContactPicker(ContactPickerType.OUTGOING) },
        onConfirmContacts = { numbers ->
            contactPickerViewModel.confirmContactPicker(numbers)
            // Refresh the screen so the new contact list information is shown immediately after confirming and closing the dialog.
            viewModel.refresh()
        },
        onDismissContacts = { contactPickerViewModel.dismissContactPicker() },
        modifier = modifier
    )
}

/**
 * Stateless visual layer for the Settings screen, redesigned on the "Signal" design system.
 *
 * Each section is a [CvSectionHeader] followed by a [CvCard] grouping its rows. Every existing
 * setting, action, dialog, and the hidden developer-unlock gesture is preserved; only the layout
 * is restyled.
 *
 * @param preferences            The [AppPreferences] instance to read data from.
 * @param updateTrigger          Trigger value to force/detect recomposition when settings change.
 * @param actions                Implementation of [SettingsActions] to handle user interaction.
 * @param contactPickerState     Current state of the contact picker dialog.
 * @param onBack                 Called when the user taps the top-bar back affordance.
 * @param onOpenContactsIncoming Called to open picker for incoming contacts.
 * @param onOpenContactsOutgoing Called to open picker for outgoing contacts.
 * @param onConfirmContacts      Called when contacts are confirmed from the dialog.
 * @param onDismissContacts      Called when we want to close the dialog without confirmation/saving.
 * @param modifier               Optional size/position modifier.
 */
@Composable
fun SettingsContent(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    contactPickerState: ContactPickerState?,
    onBack: () -> Unit,
    onOpenContactsIncoming: () -> Unit,
    onOpenContactsOutgoing: () -> Unit,
    onConfirmContacts: (Set<String>) -> Unit,
    onDismissContacts: () -> Unit,
    modifier: Modifier = Modifier
) {

    // Accordion: at most one section open at a time; Recording & storage is open on entry. State is
    // hoisted here (above the LazyColumn) so it is shared across all sections. Tapping the open section
    // closes it (null = none open); tapping any other section opens it and closes the previous one.
    // Everything closed on entry: the panel then opens on a short list of section names, which is
    // navigable at a glance. Auto-opening one buried the others under a screenful of its rows.
    var openSection by rememberSaveable { mutableStateOf<String?>(null) }
    val onToggleSection: (String) -> Unit = { id -> openSection = if (openSection == id) null else id }

    CvScaffold(
        modifier = modifier.fillMaxSize(),
        title = stringResource(R.string.general_settings),
        onBack = onBack
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                // The wizard cannot be re-run, so a phone whose OEM blocks the grant needs to be able to
                // find this out here too. Self-hiding when it does not apply.
                OemGateNotice(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            item {
                RecordingSection(
                    preferences = preferences,
                    updateTrigger = updateTrigger,
                    actions = actions,
                    expanded = openSection == SECTION_RECORDING,
                    onToggle = { onToggleSection(SECTION_RECORDING) },
                    onOpenContactsIncoming = onOpenContactsIncoming,
                    onOpenContactsOutgoing = onOpenContactsOutgoing
                )
            }
            item {
                WacrmUploadSection(
                    preferences = preferences,
                    updateTrigger = updateTrigger,
                    expanded = openSection == SECTION_WACRM,
                    onToggle = { onToggleSection(SECTION_WACRM) },
                )
            }
            item {
                DialerSection(
                    updateTrigger = updateTrigger,
                    expanded = openSection == SECTION_DIALER,
                    onToggle = { onToggleSection(SECTION_DIALER) },
                    onOpenLogin = { openSection = SECTION_WACRM },
                )
            }
            // End of the settings list: Recording, Login and Dialer.
        }
    }

    // The contact-picker dialog sits on top of the settings content.
    contactPickerState?.let { picker ->
        ContactSelectionDialog(
            title = when (picker.type) {
                ContactPickerType.INCOMING ->
                    if (picker.recordOnly) stringResource(R.string.settings_select_record_only_incoming)
                    else stringResource(R.string.settings_select_contacts_incoming)
                ContactPickerType.OUTGOING ->
                    if (picker.recordOnly) stringResource(R.string.settings_select_record_only_outgoing)
                    else stringResource(R.string.settings_select_contacts_outgoing)
            },
            contacts = picker.contacts,
            initialSelection = picker.selectedNumbers,
            onConfirm = onConfirmContacts,
            onDismiss = onDismissContacts
        )
    }
}

// Settings accordion: stable keys identifying each section. At most one section is open at a time;
// [SECTION_RECORDING] is the one open when Settings is first entered.
private const val SECTION_RECORDING = "recording"
private const val SECTION_WACRM = "wacrm"
private const val SECTION_DIALER = "dialer"

// Sub-section keys. Scoped to their parent section, so the same value can never collide across two.
private const val SUB_PHONE_CALLS = "sub_phone_calls"
private const val SUB_WHATSAPP_SOURCE = "sub_whatsapp_source"
private const val SUB_INCOMING = "sub_incoming"
private const val SUB_OUTGOING = "sub_outgoing"
private const val SUB_PRIVILEGES = "privileges"

// ── Settings sections ──────────────────────────────────────────────────────────────────────

/** Read-only Owner recording policy: the Owner's assigned number, phone status and
 * WhatsApp selection, synced from WhatsApp Max. No switches, pickers or dialogs —
 * the salesperson cannot change any of it here. Management happens in WhatsApp Max.
 *
 * @param preferences            The [AppPreferences] instance to read data from.
 * @param updateTrigger          Trigger value to force recomposition when settings change.
 * @param actions                Unused (kept for call-site compatibility).
 * @param expanded               Whether this accordion section is open.
 * @param onToggle               Invoked when the section header is tapped.
 * @param onOpenContactsIncoming Unused (kept for call-site compatibility).
 * @param onOpenContactsOutgoing Unused (kept for call-site compatibility).
 */
@Composable
private fun RecordingSection(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenContactsIncoming: () -> Unit,
    onOpenContactsOutgoing: () -> Unit
) {
    // Bounded foreground refresh while this section is open: fetch on expand,
    // then about once a minute while expanded and foregrounded. Collapsing the
    // section, leaving the screen, or backgrounding the app cancels the loop
    // (LaunchedEffect scope) — no polling exists outside this UI scope.
    // The fetch itself is throttled (at most one request per
    // POLICY_SYNC_INTERVAL_MS per success) and single-flight, so a resume
    // racing an expand still issues exactly one request.
    val context = LocalContext.current
    val app = context.applicationContext
    val baseUrl = WacrmApi.normalizeBaseUrl(preferences.getWacrmUrl())
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumed = true
            else if (event == Lifecycle.Event.ON_PAUSE) resumed = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val refreshLoop = remember(baseUrl) {
        PolicyRefreshLoop {
            if (baseUrl != null) {
                withContext(Dispatchers.IO) {
                    WhatsappSourceSync.syncIfDue(app, baseUrl)
                }
            }
        }
    }
    LaunchedEffect(expanded, resumed) {
        if (!expanded || !resumed || baseUrl == null) return@LaunchedEffect
        refreshLoop.runForever()
    }
    SettingsSection(
        title = stringResource(R.string.settings_recording_configuration),
        expanded = expanded,
        onToggle = onToggle,
        wrapInCard = false,
    ) {
        PhonePolicyStatus(preferences = preferences, updateTrigger = updateTrigger)
        WhatsappSourceStatus(
            prefs = preferences,
            updateTrigger = updateTrigger,
        )
    }
}

/**
 * Login as an independent top-level Settings section (previously "WACRM Upload", nested
 * inside Storage). Renders the same account sign-in rows, only relocated and reworded.
 */
@Composable
private fun WacrmUploadSection(
    preferences: AppPreferences,
    updateTrigger: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    SettingsSection(
        title = stringResource(R.string.settings_section_login),
        expanded = expanded,
        onToggle = onToggle,
    ) {
        WacrmSubSection(preferences, updateTrigger)
    }
}

/**
 * Dialer as an independent top-level Settings section (previously nested inside the WACRM
 * section). Renders the same click-to-dial rows, only relocated: the always-on auto-open
 * heading, display-over-apps permission actions and the desktop-delivery connection status.
 */
@Composable
private fun DialerSection(
    updateTrigger: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenLogin: () -> Unit,
) {
    SettingsSection(
        title = stringResource(R.string.settings_section_dialer),
        expanded = expanded,
        onToggle = onToggle,
    ) {
        DialerSubSection(updateTrigger, onOpenLogin)
    }
}

/**
 * Account connection: email + password login that stores an encrypted user session.
 * Replaces the former API-key UI — no key is created, copied, or displayed anywhere here.
 *
 * States:
 * - Disconnected: email + password + Connect (the fixed server URL from preferences is used as-is).
 * - Connecting: in-flight login, inputs disabled.
 * - Connected: account + user summary from the server-verified session + this device.
 *
 * Sign-out is server-driven only (see WacrmRemoteLogout): there is
 * deliberately NO Disconnect action here. A remote wipe clears the
 * local session without calling signOut — the session is already
 * revoked server-side, and a signOut call would risk a global
 * revocation of every phone and browser on the account.
 *
 * The password lives only in this composable's locals and the login call: it is never written
 * to prefs, never logged, and the field is cleared the moment Connect is tapped. After a
 * successful login only the session (encrypted) remains.
 */
@Composable
private fun WacrmSubSection(preferences: AppPreferences, updateTrigger: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Initialized once from the stored server URL and used for login as-is. There is no
    // Settings UI for it anymore (fixed endpoint); the preference itself is preserved.
    var url by remember(updateTrigger) { mutableStateOf(preferences.getWacrmUrl()) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    // Process-wide session snapshot (see WacrmSessionState): the
    // Connected block and the sign-in form both read these derived
    // values, so a background remote-logout wipe recomposes an open
    // Settings screen instead of showing a stale session.
    val snapshot by WacrmSessionState.flow.collectAsStateWithLifecycle()
    val session: WacrmSession.Session? = snapshot.session
    // True once the encrypted store has answered at least once.
    val sessionChecked: Boolean = snapshot.checked
    // One-shot notice (success/error text), cleared by the next edit.
    var notice by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(updateTrigger) {
        withContext(Dispatchers.IO) { WacrmSessionState.refresh(context) }
    }

    // Re-read on return from background: a wipe may have landed
    // while this screen was stopped. Silent and off-main; the
    // status check below is best-effort and wipes ONLY on an
    // explicit revoked==true (guaranteed by
    // checkStatusAndWipeIfRevoked) — never on network failure.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch(Dispatchers.IO) {
                    try {
                        WacrmSessionState.refresh(context)
                        val current = WacrmSessionState.flow.value.session
                        val base = WacrmApi.normalizeBaseUrl(preferences.getWacrmUrl())
                        if (current != null && base != null) {
                            runCatching {
                                WacrmDeviceAuth.checkStatusAndWipeIfRevoked(
                                    context,
                                    base,
                                    current.accessToken
                                )
                            }
                            WacrmSessionState.refresh(context)
                        }
                        val shown = WacrmSessionState.flow.value.session != null
                        AppLogger.i("CV:Settings", "SETTINGS_CONNECTED_STATE connected=$shown")
                    } catch (_: Exception) {
                        // Silent: never throw out of the observer.
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Uploads are automatic once signed in (no separate switch): a valid session is what
    // enables them, so there is no toggle here. The stored preference is still flipped on
    // at login for the rest of the integration (dial delivery, event reporting).

    // Server URL field intentionally not shown: the stored URL is used as-is for login.
    // (The editable field was removed from Settings; the endpoint is fixed.)

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.settings_wacrm_connect_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        if (!sessionChecked) {
            // Store not answered yet — show nothing rather than flashing "not connected".
        } else if (session != null) {
            val s = session!!
            Text(
                text = stringResource(R.string.settings_wacrm_connected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Account:\n${s.accountName ?: s.accountId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "User:\n${s.userName ?: s.userEmail ?: s.userId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            OutlinedTextField(
                value = email,
                onValueChange = {
                    email = it
                    notice = null
                },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.settings_wacrm_email_hint)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !connecting
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    notice = null
                },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(
                        onClick = { showPassword = !showPassword },
                        enabled = !connecting
                    ) {
                        Icon(
                            imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password"
                        )
                    }
                },
                placeholder = { Text(stringResource(R.string.settings_wacrm_password_label)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !connecting
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        val base = WacrmApi.normalizeBaseUrl(url)
                        if (base == null) {
                            notice = context.getString(R.string.settings_wacrm_unreachable)
                            return@TextButton
                        }
                        connecting = true
                        notice = context.getString(R.string.settings_wacrm_connecting)
                        val emailValue = email.trim()
                        val passwordValue = password
                        // The password leaves this composable now: cleared from the field
                        // immediately so it cannot linger on screen or in state.
                        password = ""
                        scope.launch(Dispatchers.IO) {
                            var failureNotice: String? = null
                            val config = WacrmAuth.fetchMobileConfig(base)
                            if (config == null) {
                                failureNotice =
                                    context.getString(R.string.settings_wacrm_unreachable)
                            } else {
                                when (val login = WacrmAuth.signIn(config, emailValue, passwordValue)) {
                                    is WacrmAuth.AuthOutcome.Ok -> {
                                        val me = WacrmAuth.fetchMe(
                                            base,
                                            login.session.accessToken,
                                            deviceId = com.baba.callvault.system.storage.DialDeviceId.get(context)
                                        )
                                        if (me == null) {
                                            failureNotice =
                                                context.getString(R.string.settings_wacrm_unreachable)
                                        } else {
                                            // Durable save first: commit (not apply), and success
                                            // is reported only if it committed. A failed save
                                            // keeps the user disconnected instead of stranding a
                                            // memory-only session. Already on Dispatchers.IO.
                                            val saved = WacrmSession.save(
                                                context,
                                                login.session.accessToken,
                                                login.session.refreshToken,
                                                login.session.expiresAtMs,
                                                me.userId,
                                                me.userEmail,
                                                me.fullName,
                                                me.accountId,
                                                me.accountName,
                                                me.role
                                            )
                                            if (!saved) {
                                                failureNotice =
                                                    context.getString(R.string.settings_wacrm_unreachable)
                                            } else {
                                                // Fresh login: drop any previous user's Owner policy
                                                // BEFORE fetching this user's, so a failed first sync
                                                // can never expose the previous salesperson's settings.
                                                WhatsappSourceSync.clearRecordingPolicy(AppPreferences(context))
                                                // Pull this user's WhatsApp recording source now
                                                // that the session exists. A failed sync keeps
                                                // the last-known source — never a silent flip.
                                                WhatsappSourceSync.syncAfterAuth(
                                                    context,
                                                    base,
                                                    login.session.accessToken,
                                                    deviceId = com.baba.callvault.system.storage.DialDeviceId.get(context)
                                                )
                                                // Register this device for server-driven
                                                // sign-out. Best-effort and silent: a network
                                                // failure here must NOT fail the login — the
                                                // socket connect retries it (ensureRegistered).
                                                com.baba.callvault.system.storage.WacrmDeviceAuth.registerBestEffort(
                                                    context,
                                                    base,
                                                    login.session.accessToken
                                                )
                                            }
                                        }
                                    }
                                    is WacrmAuth.AuthOutcome.InvalidCredentials ->
                                        failureNotice =
                                            context.getString(R.string.settings_wacrm_bad_credentials)
                                    is WacrmAuth.AuthOutcome.NetworkError,
                                    is WacrmAuth.AuthOutcome.ServerError ->
                                        failureNotice =
                                            context.getString(R.string.settings_wacrm_unreachable)
                                }
                            }
                            withContext(Dispatchers.Main) {
                                connecting = false
                                if (failureNotice != null) {
                                    notice = failureNotice
                                } else {
                                    notice = null
                                    val fresh = WacrmSession.get(context)
                                    WacrmSessionState.publish(fresh)
                                    // A signed-in account is sufficient for automatic uploads (no
                                    // separate switch): flip the stored flag on so the rest of the
                                    // integration (dial delivery, event reporting) keeps working as
                                    // when the old toggle was on. Uploads themselves are gated on
                                    // the live session, so an old `false` can never block them.
                                    preferences.setWacrmEnabled(true)
                                    com.baba.callvault.system.storage.DialSocketService.ensureStarted(context)
                                }
                            }
                        }
                    },
                    enabled = !connecting && email.isNotBlank() && password.isNotEmpty()
                ) { Text(stringResource(R.string.settings_wacrm_connect)) }
            }
        }
        if (notice != null) HintText(notice!!)
    }
}

/**
 * Click-to-dial from the desktop (call delivery for the signed-in account): a readiness
 * header, the display-over-apps and background permission rows, and the live
 * desktop-delivery status. Previously nested inside the WACRM section; now its own
 * top-level section. All permission and connection behavior is unchanged.
 *
 * State is re-read on return from system Settings (same pattern as [WacrmSubSection]) and
 * the socket flag is re-polled gently while this section is visible, so neither the
 * permission rows nor the connection indicator can go stale.
 */
@Composable
private fun DialerSubSection(updateTrigger: Int, onOpenLogin: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Manual refresh, and the key that re-reads the permission states below.
    var overlayTick by remember { mutableStateOf(0) }
    var resumeTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeTick++
                scope.launch(Dispatchers.IO) { WacrmSessionState.refresh(context) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The socket itself stays the source of truth for delivery; `connected`
    // below is a one-shot read for the header badge (refreshed on resume /
    // manual check). No polling lives here anymore.

    val canOverlay = remember(updateTrigger, resumeTick, overlayTick) {
        android.provider.Settings.canDrawOverlays(context)
    }
    val powerManager = remember(updateTrigger, resumeTick, overlayTick) {
        context.getSystemService(android.os.PowerManager::class.java)
    }
    val batteryUnrestricted = remember(updateTrigger, resumeTick, overlayTick) {
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    val sessionSnapshot by WacrmSessionState.flow.collectAsStateWithLifecycle()
    val hasSession = sessionSnapshot.session != null
    val connected = remember(updateTrigger, resumeTick, overlayTick) {
        DialSocketClient.isConnected
    }
    var lastCheckedMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(updateTrigger, resumeTick, overlayTick) {
        lastCheckedMs = System.currentTimeMillis()
    }

    val (badgeTextRes, badgeTone) = when {
        !hasSession -> R.string.settings_dialer_badge_signin to CvTone.Warning
        !canOverlay -> R.string.settings_dialer_badge_setup to CvTone.Warning
        !connected -> R.string.settings_dialer_badge_connecting to CvTone.Info
        else -> R.string.settings_dialer_badge_ready to CvTone.Success
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Header: title + live readiness badge. Auto-open is always on, so there is no
        // switch — the badge is the whole state summary.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_dialer_heading),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            CvStatusPill(text = stringResource(badgeTextRes), tone = badgeTone)
        }
        SettingsDivider()

        if (!hasSession) {
            DialerRow(
                title = stringResource(R.string.settings_dialer_badge_signin),
            ) {
                TextButton(onClick = onOpenLogin) {
                    Text(stringResource(R.string.settings_dialer_go_to_login))
                }
            }
            SettingsDivider()
        }

        DialerRow(
            title = stringResource(R.string.settings_dialer_overlay_title),
        ) {
            if (canOverlay) {
                CvStatusPill(
                    text = stringResource(R.string.settings_dialer_allowed),
                    tone = CvTone.Success,
                )
            } else {
                TextButton(onClick = {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + context.packageName)
                        )
                    )
                }) { Text(stringResource(R.string.settings_dialer_allow)) }
            }
        }

        DialerRow(
            title = stringResource(R.string.settings_dialer_background_title),
        ) {
            if (batteryUnrestricted) {
                CvStatusPill(
                    text = stringResource(R.string.settings_dialer_allowed),
                    tone = CvTone.Success,
                )
            } else {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:" + context.packageName)
                            )
                        )
                    }
                }) { Text(stringResource(R.string.settings_dialer_allow)) }
            }
        }

        SettingsDivider()

        // Footer: when the states were last verified, plus manual refresh.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    R.string.settings_dialer_last_checked,
                    DateUtils.getRelativeTimeSpanString(
                        lastCheckedMs,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { overlayTick++ }) {
                Text(stringResource(R.string.settings_wacrm_auto_open_recheck))
            }
        }
    }
}

/**
 * One dialer row: title on the left (plus an optional description underneath), and a
 * trailing status or action on the right. The trailing slot holds either a status pill
 * (nothing left to do) or the one button that fixes this row — buttons never appear
 * next to an already-healthy row.
 */
@Composable
private fun DialerRow(
    title: String,
    description: String? = null,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/** Bytes in a megabyte, for stating a model's download size. Shared with the wizard's own cost note. */
internal const val BYTES_PER_MB = 1_000_000L

/**
 * The speech-model dropdown, listing every tier in the catalogue.
 *
 * Extracted for the setup wizard, which offers the download and therefore has to let the user say
 * *which* model is being fetched — 190 MB against 874 MB is the difference between a minute and
 * several on a slow connection, and between gist Hebrew and clean Hebrew afterwards. Shared rather
 * than copied for the same reason as [TranscriptionModeField]: the row that starts the download
 * reads this setting, so two dropdowns writing it would be two chances to disagree about what is
 * being downloaded.
 *
 * @param modelId The stored id; anything unrecognised falls back to [TranscriptionModel.DEFAULT].
 */
@Composable
internal fun TranscriptionModelField(modelId: String, onModelChange: (String) -> Unit) {
    val modelOptions = TranscriptionModel.entries.map {
        OptionItem(it.id, stringResource(TranscriptionLabels.titleOf(it)))
    }
    DropdownRow {
        M3DropdownField(
            label = stringResource(R.string.transcription_model_label),
            // DEFAULT, not `modelOptions.first()`. Every other reader of this preference —
            // TranscriptionWorker, HomeScreen, the wizard, the progress estimate — resolves an
            // unrecognised id to DEFAULT, and this one resolving it to whichever tier happens to be
            // declared first meant the dropdown could name a different model from the one that would
            // actually run. Harmless while nothing writes a bad id, and a silent trap the moment
            // something does; it also stopped being merely theoretical once the enum's first entry
            // and its DEFAULT were no longer one tier apart.
            selected = modelOptions.find { it.key == modelId }
                ?: modelOptions.first { it.key == TranscriptionModel.DEFAULT.id },
            options = modelOptions,
            onOptionSelected = { onModelChange(it.key) }
        )
    }
}

/**
 * The "Transcribe: manually / after each call / automatically" dropdown, with the cost warning that
 * guards the two automatic modes.
 *
 * Shared with the setup wizard rather than copied into it. The warning **is** the control here — a
 * hand-rolled second dropdown would be one edit away from persisting an automatic mode with nothing
 * said about what it costs, which is the same drift the hand-rolled offline toggle produced.
 *
 * @param onModeChange Called only once the choice is settled — i.e. after the warning is accepted,
 *   never on merely opening it. Callers persist AND reconcile `TranscriptionScheduler` from it.
 */
@Composable
internal fun TranscriptionModeField(
    mode: TranscriptionMode,
    onModeChange: (TranscriptionMode) -> Unit,
) {
    val modeOptions = TranscriptionMode.entries.map {
        OptionItem(it.key, stringResource(TranscriptionLabels.titleOf(it)))
    }

    // Turning an automatic mode ON is confirmed; turning it off never is. The cost being warned
    // about is the cost of it running, so there is nothing to warn about when stopping.
    var pendingAutoMode by remember { mutableStateOf<TranscriptionMode?>(null) }

    DropdownRow {
        M3DropdownField(
            label = stringResource(R.string.transcription_mode_label),
            selected = modeOptions.find { it.key == mode.key } ?: modeOptions.first(),
            options = modeOptions,
            onOptionSelected = { option ->
                val chosen = TranscriptionMode.fromKey(option.key)
                if (chosen != TranscriptionMode.MANUAL && chosen != mode) {
                    pendingAutoMode = chosen
                } else {
                    onModeChange(chosen)
                }
            }
        )
    }

    pendingAutoMode?.let { chosen ->
        AlertDialog(
            onDismissRequest = { pendingAutoMode = null },
            icon = { Icon(imageVector = Icons.Filled.BatteryChargingFull, contentDescription = null) },
            title = { Text(stringResource(R.string.transcription_warning_title)) },
            text = { Text(stringResource(R.string.transcription_warning_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onModeChange(chosen)
                    pendingAutoMode = null
                }) {
                    Text(stringResource(R.string.transcription_warning_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAutoMode = null }) {
                    Text(stringResource(R.string.general_cancel))
                }
            }
        )
    }
}

/**
 * The transcription-language dropdown, and the one thing a dropdown alone cannot say: that auto-detect
 * is not a neutral option.
 *
 * The hint appears only while auto-detect is selected. It is a warning about a state, not a label for
 * the control, and a line that sat under every language would read as a scold to the majority who never
 * chose auto-detect at all.
 *
 * Shared with the setup wizard for the same reason as [TranscriptionModeField]: the warning must not be
 * the half that a second copy forgets.
 *
 * @param language The pinned language, null meaning auto-detect.
 * @param onLanguageChange Given the new language, null meaning auto-detect.
 */
@Composable
internal fun TranscriptionLanguageField(
    language: String?,
    onLanguageChange: (String?) -> Unit,
) {
    // Sorted by the translated name, so the list reads A-Z in whatever language the app is in.
    val languageOptions = TranscriptionLabels
        .sortLanguageOptions(
            TranscriptionLabels.LANGUAGE_OPTIONS.map { code ->
                (code ?: TranscriptionLabels.AUTO_DETECT_KEY) to
                    stringResource(TranscriptionLabels.languageOf(code))
            }
        )
        .map { (key, label) -> OptionItem(key, label) }

    DropdownRow {
        M3DropdownField(
            label = stringResource(R.string.transcription_language_label),
            selected = languageOptions.find {
                it.key == (language ?: TranscriptionLabels.AUTO_DETECT_KEY)
            } ?: languageOptions.first(),
            options = languageOptions,
            onOptionSelected = {
                onLanguageChange(it.key.takeIf { k -> k != TranscriptionLabels.AUTO_DETECT_KEY })
            }
        )
        if (language == null) {
            HintText(stringResource(R.string.transcription_language_auto_hint))
        }
    }
}

/** One-line status under a settings row (arming progress, or why something is unavailable). */
@Composable
private fun SettingsHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
    )
}

/** Small label separating groups of related rows inside one collapsible section. */
@Composable
private fun SettingsSubHeader(text: String, nested: Boolean = false, modifier: Modifier = Modifier) {
    // General ▸ Experimental ▸ Resilience is three levels deep. Rendering the inner grouping at the
    // same weight as its parent makes the two read as siblings, so a nested header is quieter and
    // indented instead of introducing a second component.
    Text(
        text = text,
        style = if (nested) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
        color = if (nested) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(
            start = if (nested) 20.dp else 12.dp,
            top = if (nested) 10.dp else 12.dp,
            bottom = if (nested) 2.dp else 8.dp,
        )
    )
}

/**
 * Which apps' calls get recorded — one switch per installed app that could place a call.
 *
 * Every switch is ON by default. Underneath, only the apps switched OFF are stored: an allow-list
 * kept literally would start empty, and an empty allow-list means "record nothing", so every
 * existing user would silently stop recording app calls the moment they upgraded. See
 * [com.baba.callvault.data.voip.VoipAppPolicy].
 *
 * The list is loaded off the main thread. Enumerating installed packages with their permissions is
 * tens of milliseconds on a full phone, which is enough to stutter a screen that is opening.
 */
@Composable
private fun VoipAppPicker(prefs: AppPreferences) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<CallingApp>?>(null) }
    var excluded by remember { mutableStateOf(prefs.getVoipExcludedPackages()) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { VoipAppList.installedCallingApps(context) }
    }

    val loaded = apps
    SettingsToggleRow(
        label = stringResource(R.string.settings_voip_apps_label),
        description = when {
            loaded == null -> stringResource(R.string.settings_voip_apps_loading)
            excluded.isEmpty() -> stringResource(R.string.settings_voip_apps_all)
            // pluralStringResource, not stringResource: the English text used to read "app(s)",
            // which four translators copied verbatim and Polish and Russian could not express at all
            // — they need three and four forms respectively for the same sentence.
            else -> pluralStringResource(
                R.plurals.settings_voip_apps_some, excluded.size, excluded.size
            )
        },
        checked = expanded,
        enabled = loaded != null,
        onCheckedChange = { expanded = it }
    )

    AnimatedVisibility(
        visible = expanded && loaded != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        NestedGroup {
            SettingsHint(stringResource(R.string.settings_voip_apps_hint))
            // WhatsApp and WhatsApp Business are governed by the single
            // WhatsApp recording source (Settings → WhatsApp Recording),
            // not by this list — showing them here would offer a second,
            // competing switch for the same call.
            loaded?.filterNot { app ->
                WhatsappRecordingSource.isWhatsappFamily(app.packageName)
            }?.forEach { app ->
                SettingsToggleRow(
                    label = app.label,
                    checked = app.packageName !in excluded,
                    onCheckedChange = { record ->
                        excluded = VoipAppPolicy.withRecording(excluded, app.packageName, record)
                        prefs.setVoipExcludedPackages(excluded)
                    }
                )
            }
        }
    }
}

/**
 * Owner phone policy, read-only. Shows the Owner-assigned number and whether the
 * Owner enabled phone recording. No controls: the salesperson cannot change this.
 * The displayed line is the CONFIGURED number, never the remote caller of any call.
 */
@Composable
private fun PhonePolicyStatus(preferences: AppPreferences, updateTrigger: Int) {
    // Observable Owner policy: every successful sync publishes, so this open
    // screen recomposes with the new number/status automatically. The direct
    // prefs reads are only the first-frame fallback before the first publish
    // lands in this process. `updateTrigger` is kept for call-site compat.
    val context = LocalContext.current
    val policy by WhatsappSourceSync.policyFlow.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { WhatsappSourceSync.publishCurrent(context) }
    }
    val fallbackNumber = remember { preferences.getPhoneRecordingNumber() }
    val configuredNumber = policy?.phoneNumber ?: fallbackNumber

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.settings_phone_recording_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_assigned_number_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (configuredNumber.isNullOrBlank()) stringResource(R.string.settings_owner_not_configured)
            else PhoneNumberResolver.formatForDisplay(configuredNumber),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private enum class PolicyState { ENABLED, DISABLED, UNAVAILABLE }

/** Status dot + label. Informational only — never a control. */
@Composable
private fun StatusLine(text: String, state: PolicyState) {
    val color = when (state) {
        PolicyState.ENABLED -> MaterialTheme.colorScheme.primary
        PolicyState.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant
        PolicyState.UNAVAILABLE -> MaterialTheme.colorScheme.error
    }
    Text(
        text = "● $text",
        style = MaterialTheme.typography.bodySmall,
        color = color
    )
}


/**
 * Owner WhatsApp policy, read-only. Shows the Owner-selected application
 * (None / WhatsApp / WhatsApp Business). No controls, no migration dialog:
 * the salesperson cannot change the Owner's selection here — a synced value
 * always wins, and this screen never writes it. Management happens in
 * WhatsApp Max.
 */
@Composable
private fun WhatsappSourceStatus(
    prefs: AppPreferences,
    updateTrigger: Int,
) {
    // Same observable policy as PhonePolicyStatus: recomposes automatically
    // after every successful sync. Direct prefs reads are first-frame
    // fallback only.
    val context = LocalContext.current
    val policy by WhatsappSourceSync.policyFlow.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { WhatsappSourceSync.publishCurrent(context) }
    }
    val source = policy?.whatsapp ?: remember { prefs.getWhatsappRecordingSource() }
    val configured = policy?.phoneConfigured ?: remember { prefs.isPhoneRecordingConfigured() }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.settings_whatsapp_recording_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_selected_app_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (!configured) stringResource(R.string.settings_owner_not_configured)
            else when (source) {
                WhatsappRecordingSource.NONE -> stringResource(R.string.settings_whatsapp_source_none)
                WhatsappRecordingSource.WHATSAPP -> stringResource(R.string.settings_whatsapp_source_whatsapp)
                WhatsappRecordingSource.WHATSAPP_BUSINESS -> stringResource(R.string.settings_whatsapp_source_business)
            },
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * Opt-in "Record VoIP calls" toggle (experimental).
 *
 * Unlike the other opt-ins this one carries a confirmation, for two reasons that are not the app's to
 * decide for the user: consent law for VoIP recording is stricter than for carrier calls in many
 * places, and support is per-app — an app that opts out of capture cannot be recorded at all, and we
 * can only tell once a call is under way.
 */
/**
 * Whether [capability] works in the mode the user has chosen, for greying a row out.
 *
 * Read on each recomposition rather than remembered: the mode can change from the privileges section
 * on this very screen, and a row that stayed enabled after the switch would offer something that
 * cannot work.
 */
@Composable
internal fun capabilityAvailable(capability: ModeCapability): Boolean {
    val context = LocalContext.current
    return capability.isAvailable(AppPreferences(context).getPrivilegedMode(), android.os.Build.VERSION.SDK_INT)
}

/**
 * The one-line reason a row is greyed out, or null when it is not. The phone's Android version is named
 * first when it is the reason (backlog #1): no mode switch can fix that, so offering Shizuku's reason
 * would send the user looking for a fix that does not exist.
 */
@Composable
internal fun unavailableReason(capability: ModeCapability): String? = when {
    capabilityAvailable(capability) -> null
    !capability.isAvailableOn(android.os.Build.VERSION.SDK_INT) -> stringResource(
        R.string.settings_needs_newer_android,
        capability.minAndroidRelease,
        android.os.Build.VERSION.RELEASE.orEmpty(),
    )
    else -> stringResource(R.string.settings_unavailable_in_shizuku)
}

/**
 * @param onEnabledChange Notified whenever the opt-in is persisted. The wizard needs it to tell whether
 *   *anything* will be recorded once the user finishes; Settings has no such question and ignores it.
 */
@Composable
internal fun VoipRecordingToggle(onEnabledChange: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPreferences(context) }
    var enabled by remember { mutableStateOf(prefs.isVoipRecordingEnabled()) }
    var autoStart by remember { mutableStateOf(prefs.isVoipAutoStartEnabled()) }
    var showWarning by remember { mutableStateOf(false) }
    var arming by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }

    // VoIP capture runs two AudioRecords against a dynamic audio policy, and neither can start in a
    // Shizuku-hosted process — so in Shizuku mode this is greyed out rather than left to fail silently.
    val supported = capabilityAvailable(ModeCapability.VOIP_RECORDING)
    val unsupportedNote = unavailableReason(ModeCapability.VOIP_RECORDING)

    // Arming touches the daemon (and may launch it), so never on the main thread. The policy has to be
    // registered before any VoIP call starts, which is why this happens on the toggle rather than when
    // a call begins.
    fun applyPreference(turnOn: Boolean) {
        enabled = turnOn
        prefs.setVoipRecordingEnabled(turnOn)
        onEnabledChange(turnOn)
        unavailable = false
        scope.launch {
            arming = turnOn
            val ok = withContext(Dispatchers.IO) { VoipCaptureController.sync(context) }
            // Restart the keep-alive service so its detector picks up the new preference (it hosts
            // VoIP detection, so the feature is only live once it has re-synced).
            runCatching { DaemonKeepAliveService.start(context) }
            arming = false
            if (turnOn && !ok) unavailable = true
        }
    }

    SettingsToggleRow(
        icon = Icons.Filled.Groups,
        label = stringResource(R.string.settings_voip_recording_label),
        description = unsupportedNote ?: stringResource(R.string.settings_voip_recording_description),
        checked = enabled && supported,
        enabled = supported,
        onCheckedChange = { turnOn ->
            if (turnOn) showWarning = true  // confirm before enabling; never before turning OFF
            else applyPreference(false)
        },
    )
    if (arming) {
        SettingsHint(stringResource(R.string.voip_recording_arming))
    } else if (unavailable) {
        SettingsHint(stringResource(R.string.voip_recording_unavailable))
    } else if (enabled) {
        // A calling app's ongoing-call notification is the only place the system holds the contact's
        // name, so an app denied notification permission produces correct but nameless recordings.
        // Worth saying out loud: it looks like a bug in CallVault and is not one.
        SettingsHint(stringResource(R.string.voip_recording_names_hint))
    }

    // Auto-start vs "ask me", nested under the feature it qualifies: with VoIP recording off there is
    // no detection running, so there would be nothing to ask about.
    AnimatedVisibility(
        visible = enabled,
        enter   = fadeIn() +  expandVertically(),
        exit    = fadeOut() + shrinkVertically()
    ) {
        NestedGroup {
            SettingsToggleRow(
                label = stringResource(R.string.settings_voip_auto_start_label),
                description = stringResource(R.string.settings_voip_auto_start_description),
                checked = autoStart,
                onCheckedChange = { on ->
                    autoStart = on
                    prefs.setVoipAutoStartEnabled(on)
                }
            )

            SettingsDivider()

            VoipAppPicker(prefs)
        }
    }

    if (showWarning) {
        AlertDialog(
            onDismissRequest = { showWarning = false },
            title = { Text(stringResource(R.string.voip_recording_warning_title)) },
            text = { Text(stringResource(R.string.voip_recording_warning_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showWarning = false
                    applyPreference(true)
                }) { Text(stringResource(R.string.voip_recording_warning_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showWarning = false }) {
                    Text(stringResource(R.string.general_cancel))
                }
            },
        )
    }
}

/**
 * Opt-in "Resilient recording" toggle (audio-capture handoff, Option B). Default OFF — the recording
 * path is byte-identical to daemon mode. When ON (and the source is handoff-compatible) the daemon hands
 * its live capture to the always-alive app, so a recording keeps going and finalises even if the
 * background helper (daemon) is killed mid-call (e.g. screen-lock restarts adbd). A plain preference with
 * no arming side effects, so — unlike the offline toggle — it writes straight from the switch.
 */
@Composable
internal fun HandoffPersistToggle() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var enabled by remember { mutableStateOf(prefs.isHandoffPersistEnabled()) }

    // The daemon hands its live AudioRecord to the app; a Shizuku-hosted process cannot start one.
    val supported = capabilityAvailable(ModeCapability.RESILIENT_RECORDING)

    SettingsToggleRow(
        icon = Icons.Filled.Shield,
        label = stringResource(R.string.settings_handoff_persist_label),
        description = unavailableReason(ModeCapability.RESILIENT_RECORDING)
            ?: stringResource(R.string.settings_handoff_persist_description),
        checked = enabled && supported,
        enabled = supported,
        onCheckedChange = { turnOn ->
            enabled = turnOn
            prefs.setHandoffPersistEnabled(turnOn)
        },
    )
}


/**
 * Turns on **USB debugging**, which is what lets CallVault switch Wireless debugging off.
 *
 * `adbd` runs only while USB debugging or Wireless debugging is enabled, and CallVault's helper lives
 * inside it — so with neither, there is no helper (measured on a OnePlus 12 and a Galaxy S24 FE; see the
 * README). USB debugging is the better of the two to leave on: no cable is needed, and unlike Wireless
 * debugging it opens **no network port**. Shizuku makes the same trade, its manager just does it
 * silently; asking is better.
 *
 * Turning it back off is allowed and honest about the consequence — Wireless debugging comes back on,
 * because otherwise the helper would have no way in at all.
 */
/**
 * Whether CallVault may switch Wireless debugging back on after the user turned it off themselves.
 *
 * Off by default (decided 2026-09-14). Measured on the OP9 before this existed: CallVault switched it back on
 * 50 ms after the user's tap, so the switch could not be turned off at all while USB debugging was off. With
 * this off, a switch the user turned off stays off and the notification says recording is paused, with a
 * button to turn it back on. Shared with onboarding, so both places say the same thing.
 */
@Composable
internal fun WirelessDebuggingEnforceToggle() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var enforced by remember { mutableStateOf(prefs.isWirelessDebuggingEnforced()) }
    // Shizuku mode does not touch the switches at all, so the choice means nothing there.
    val usesEmbeddedAdb = remember { !prefs.getPrivilegedMode().needsShizuku }
    SettingsToggleRow(
        icon = Icons.Filled.Wifi,
        label = stringResource(R.string.settings_wd_enforce_label),
        description = stringResource(R.string.settings_wd_enforce_description),
        checked = enforced,
        enabled = usesEmbeddedAdb,
        onCheckedChange = { on ->
            prefs.setWirelessDebuggingEnforced(on)
            enforced = on
        },
    )
}

/**
 * Shared USB-debugging actions for the offline-recording flows: open Developer Options,
 * re-check the live switch, and (only where the switch is unreadable) continue anyway.
 *
 * The arm itself verifies the listener, so Continue is never a blind bypass — it just hands
 * an ambiguous switch to the component that can actually test it.
 */
@Composable
private fun UsbGateActions(
    showContinue: Boolean,
    onOpenDeveloperOptions: () -> Unit,
    onCheckAgain: () -> Unit,
    onContinue: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onOpenDeveloperOptions) {
            Text(stringResource(R.string.oem_gate_open_dev_options))
        }
        TextButton(onClick = onCheckAgain) {
            Text(stringResource(R.string.settings_wacrm_auto_open_recheck))
        }
        if (showContinue) {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.general_continue))
            }
        }
    }
}

/**
 * Opt-in "Offline recording (no Wi-Fi)" toggle. Turning it ON pops a security-warning modal
 * (Cancel / Continue anyway) because it arms a local `adb tcpip` debugging port; only on "Continue"
 * do we persist the opt-in, arm the loopback listener, and re-warm the daemon. Turning it OFF clears
 * the opt-in and best-effort closes the port (reverts adbd to USB mode).
 *
 * `internal` because the wizard renders this very composable: it used to hand-roll its own offline card,
 * which drifted out of sync and offered "Enable" in Shizuku mode, where the next mode switch turns it
 * straight back off again.
 */
@Composable
internal fun OfflineRecordingToggle() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var enabled by remember { mutableStateOf(prefs.isOfflineRecordingEnabled()) }
    // Non-null while the enable/disable dialog is walking the user through the ADB work with live feedback.
    var dialogMode by remember { mutableStateOf<OfflineDialogMode?>(null) }
    // One-time USB onboarding gate: turning offline recording on with USB debugging off can
    // never arm the listener, so the toggle explains the requirement first instead of failing
    // in the arming dialog. Unknown (unreadable) states get their own copy — never a claim
    // that the switch is off — and an explicit Continue that lets the verified arm decide.
    var usbGateOpen by remember { mutableStateOf(false) }
    var usbRecheckTick by remember { mutableIntStateOf(0) }
    // Live state, re-read on every gate open and every explicit Check again — never cached
    // across sessions, and never a substitute for the persisted onboarding flag below.
    val usbGateState = remember(usbRecheckTick) { AdbShell.usbDebuggingState(context) }

    // Pure embedded-ADB machinery, and Shizuku mode has no embedded ADB connection at all. The switch
    // was already turned off on entering that mode, but it stayed tappable — so it could be turned back
    // on, look on, and do nothing. Greying it says why instead.
    val supported = capabilityAvailable(ModeCapability.OFFLINE_RECORDING)

    SettingsToggleRow(
        icon = Icons.Filled.WifiOff,
        label = stringResource(R.string.settings_offline_recording_label),
        description = unavailableReason(ModeCapability.OFFLINE_RECORDING)
            ?: stringResource(R.string.settings_offline_recording_desc),
        checked = enabled && supported,
        enabled = supported && dialogMode == null && !usbGateOpen,
        onCheckedChange = { turnOn ->
            if (!turnOn) {
                usbGateOpen = false
                dialogMode = OfflineDialogMode.DISABLE
            } else if (AdbShell.usbDebuggingState(context).isOn) {
                dialogMode = OfflineDialogMode.ENABLE
            } else {
                // Proven off or unreadable: explain first. The actual USB state was just read;
                // usbGateState (keyed on usbRecheckTick) re-reads on every explicit Check again.
                usbRecheckTick++
                usbGateOpen = true
            }
        },
    )

    // Standalone-only USB gate. Shizuku mode never reaches it (the row above is disabled there).
    if (usbGateOpen && supported) {
        val firstTime = !prefs.isUsbOnboardingCompleted()
        CvCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.usb_onboarding_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    when {
                        usbGateState.isOff && !firstTime -> R.string.usb_onboarding_reenable_desc
                        usbGateState.isOff -> R.string.usb_onboarding_desc
                        else -> R.string.usb_onboarding_unverified_desc
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            UsbGateActions(
                showContinue = !usbGateState.isOn && !usbGateState.isOff,
                onOpenDeveloperOptions = { context.openDeveloperSettings() },
                onCheckAgain = {
                    // Re-read the live switch: returning from Developer Options is the moment it
                    // may have flipped. On means the requirement is met — continue straight into
                    // the normal verified setup.
                    if (AdbShell.usbDebuggingState(context).isOn) {
                        usbGateOpen = false
                        dialogMode = OfflineDialogMode.ENABLE
                    } else {
                        usbRecheckTick++
                    }
                },
                onContinue = {
                    usbGateOpen = false
                    dialogMode = OfflineDialogMode.ENABLE
                },
            )
        }
    }

    // Runtime recovery, separate from the one-time gate above: USB debugging was on when offline
    // recording was set up and is now proven off. Recording will resume by itself when it comes
    // back, but the row says so and offers the one-tap route — the keepalive notice alone is
    // easy to miss. Check again re-reads rather than trusting a stale composition.
    if (enabled && supported && usbGateState.isOff) {
        SettingsHint(stringResource(R.string.usb_onboarding_reenable_desc))
        UsbGateActions(
            showContinue = false,
            onOpenDeveloperOptions = { context.openDeveloperSettings() },
            onCheckAgain = { usbRecheckTick++ },
            onContinue = {},
        )
    }

    // Stated up front, and only to the people it applies to. The listener does not survive a reboot
    // and cannot be re-armed until the phone associates with Wi-Fi once — four escapes were tested
    // and every one is shut in AOSP, so this is a platform limit and not a gap we are going to close.
    // Left unsaid it looks like an oversight the first time a call after a reboot is missed; said
    // here it is a known condition with a ten-second fix.
    if (enabled && supported) {
        SettingsHint(stringResource(R.string.settings_offline_recording_reboot_note))
    }

    dialogMode?.let { mode ->
        OfflineRecordingDialog(
            mode = mode,
            onResult = { nowEnabled -> enabled = nowEnabled },
            onClose = { dialogMode = null },
        )
    }
}

// ── Internal helper composables ────────────────────────────────────────────────────────────

/** A branded, collapsible section: a tappable [CvSectionHeader] (with a rotating chevron) above a
 * [CvCard] grouping its rows. Tapping the header invokes [onToggle]; the body animates in and out.
 * The expand/collapse state is HOISTED to the parent so the Settings screen can run an accordion
 * (at most one section open at a time); this composable is stateless about expansion.
 *
 * @param title    Section heading shown above the card; the whole header row toggles the section.
 * @param expanded Whether this section's body is currently shown.
 * @param onToggle Invoked when the header is tapped (the parent decides the new open-section).
 * @param content  The slot for child rows rendered inside the [CvCard] when expanded.
 */

@Composable
internal fun SettingsSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    // A section made of sub-sections passes false: the card then belongs around each OPEN
    // sub-section's rows, not around a list of labels that do nothing until tapped. Wrapping the
    // labels made a closed section read as a floating menu of empty items.
    wrapInCard: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "settingsSectionChevron"
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            CvSectionHeader(text = title, modifier = Modifier.weight(1f))
            // A section takes the accent, matching its teal dash; a sub-section gets a small muted
            // triangle instead (see [SettingsSubSection]). Differing in BOTH shape and colour is what
            // makes the two levels tellable apart at a glance — size alone did not.
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(22.dp)
                    .rotate(chevronRotation)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            if (wrapInCard) {
                CvCard(contentPadding = PaddingValues(vertical = 8.dp)) { content() }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
            }
        }
    }
}

/**
 * A collapsible group INSIDE a section — Storage ▸ Retention, General ▸ Updates, and so on.
 *
 * Same interaction as [SettingsSection] so the two levels behave alike, and the same shape: a bare
 * header row, with the card around the CONTENT once it opens. The header is quieter than a section
 * title — ordinary text rather than the accent colour — so it reads as a level down rather than as a
 * competing heading.
 *
 * Callers hold the open key themselves, one per parent section, which makes the sub-sections an
 * accordion within their own section only — opening Retention cannot close something in General.
 */
@Composable
private fun SettingsSubSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "settingsSubSectionChevron"
    )
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsSubHeader(title, modifier = Modifier.weight(1f))
            // Triangle, not a chevron, and muted rather than accented — the opposite of the section
            // marker above it on both counts.
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp).size(24.dp).rotate(chevronRotation)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            CvCard(contentPadding = PaddingValues(vertical = 8.dp)) { content() }
        }
    }
}

/** A thin inset divider used to separate row clusters inside a [CvCard]. */
@Composable
private fun SettingsDivider() {
    // outlineVariant is Material's subtlest rule, and against this app's very dark surface it all but
    // disappears — reported as "barely visible in dark theme". Deriving the colour from
    // onSurfaceVariant instead keeps it theme-aware (it inverts with the scheme) while guaranteeing
    // contrast against whatever surface it lands on, in light and dark alike.
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
    )
}

/** Wraps a [M3DropdownField] (and optional hint) so it slots cleanly inside a [CvCard]. */
@Composable
internal fun DropdownRow(content: @Composable ColumnScope.() -> Unit) {
    Column(content = content)
}

/** Indents and tints a nested option cluster revealed under an auto-record toggle. */
@Composable
private fun NestedGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
            .padding(vertical = 4.dp),
        content = content
    )
}

/** A small muted helper line shown beneath a dropdown/field. */
@Composable
private fun HintText(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelSmall,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
    )
}

/** Circular tinted leading-icon badge used by settings rows. */
@Composable
private fun RowIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * A tappable navigation/dialog row: leading icon, label + value (+ optional supporting hint), and a
 * trailing chevron. Used for folder pickers, the filename template, and the About rows.
 */
@Composable
internal fun NavigationRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    supporting: String? = null,
    showChevron: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (showChevron) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * A switch row styled for the Signal cards: optional leading icon, label + supporting text, and a
 * teal [Switch]. Tapping anywhere on the row toggles it. Mirrors the behavior of the shared
 * ToggleListItem while matching the redesigned row anatomy.
 */
@Composable
internal fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    description: String? = null,
    enabled: Boolean = true,
    descriptionBelow: Boolean = false,
) {
    val contentAlpha = if (enabled) 1f else 0.38f

    @Composable
    fun descriptionText(modifier: Modifier = Modifier) {
        if (description == null) return
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            modifier = modifier,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                RowIcon(icon)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
                )
                if (!descriptionBelow) {
                    Spacer(Modifier.height(2.dp))
                    descriptionText()
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
        if (descriptionBelow && description != null) {
            // Full width, under the switch rather than in the narrow column beside it.
            //
            // Sharing the column with the label means wrapping against roughly two thirds of the
            // screen, so a status line that runs to two lines at full width takes four — and the row
            // grows tallest exactly when it has the most to say, which is when something is wrong.
            Spacer(Modifier.height(4.dp))
            descriptionText(Modifier.fillMaxWidth())
        }
    }
}

/**
 * A radio-button group for choosing which contacts to ignore.
 * When "selected" is active, shows a text field and a "Pick Contacts" button.
 *
 * @param label           Label shown above the radio buttons.
 * @param selectedEnum     The currently active mode ("none", "all", or "selected").
 * @param selectedCount    The number of contacts currently selected
 * @param onSelected      Called with the new active mode when the user taps a radio button.
 * @param onSelectContacts Called when the user taps the "Select Contacts" button; opens the
 *                        [ContactSelectionDialog] via [ContactPickerViewModel].
 */
@Composable
private fun IgnoreContactsOptions(
    label: String,
    selectedEnum: AppPreferences.IgnoreContactsMode,
    selectedCount: Int,
    onSelected: (AppPreferences.IgnoreContactsMode) -> Unit,
    onSelectContacts: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text  = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(8.dp))

        val enumEntries = AppPreferences.IgnoreContactsMode.entries
        enumEntries.forEach { ignoreContactMode ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    // This make the box/text next to the radio button clickable, not just the button itself, which is more user-friendly.
                    .clickable { onSelected(ignoreContactMode) }
                    .padding(vertical = 4.dp)
            ) {
                // Make the actual radio button (circle) clickable (it's quite small)
                RadioButton(selected = selectedEnum == ignoreContactMode, onClick = { onSelected(ignoreContactMode) })
                Text(
                    text = when (ignoreContactMode) {
                        AppPreferences.IgnoreContactsMode.NONE -> stringResource(R.string.settings_ignore_contacts_none)
                        AppPreferences.IgnoreContactsMode.ALL -> stringResource(R.string.settings_ignore_contacts_all)
                        AppPreferences.IgnoreContactsMode.SELECTED   -> stringResource(R.string.settings_ignore_contacts_selected)
                        AppPreferences.IgnoreContactsMode.ONLY_SELECTED -> stringResource(R.string.settings_record_only_selected_contacts)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Both list modes pick contacts; each mode keeps its own list (see IgnoreContactsMode.ONLY_SELECTED).
        val usesList = selectedEnum == AppPreferences.IgnoreContactsMode.SELECTED ||
            selectedEnum == AppPreferences.IgnoreContactsMode.ONLY_SELECTED
        if (selectedEnum == AppPreferences.IgnoreContactsMode.ONLY_SELECTED) {
            // Said plainly, because it is the opposite of every other choice here: a call that cannot be
            // matched — a withheld number — is not recorded automatically.
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_record_only_selected_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (usesList) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick  = onSelectContacts,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(),
                shape = MaterialTheme.shapes.small
            ) { Text(stringResource(R.string.settings_select_contacts, selectedCount)) }
        }
    }
}

/**
 * Safe Compose Preview for Settings.
 */
@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    MaterialTheme {
        val mockContext = LocalContext.current
        val dummyPreferences = AppPreferences(mockContext)
        val dummyActions = object : SettingsActions {
            override fun refreshSettings() {}
            override fun setCarrierRecording(enabled: Boolean) {}
            override fun setAutoRecordIncoming(enabled: Boolean) {}
            override fun setAutoRecordOutgoing(enabled: Boolean) {}
            override fun setRecordFromAnswer(enabled: Boolean) {}
            override fun setVibrationEnabled(enabled: Boolean) {}
            override fun setIgnoreAnonymousIncoming(enabled: Boolean) {}
            override fun setIgnoreCrossCountryIncoming(enabled: Boolean) {}
            override fun setIgnoreCrossCountryOutgoing(enabled: Boolean) {}
            override fun setIgnoreContactsModeIncoming(modeEnum: AppPreferences.IgnoreContactsMode) {}
            override fun setIgnoreContactsModeOutgoing(modeEnum: AppPreferences.IgnoreContactsMode) {}
            override fun setAudioSource(source: String) {}
            override fun setAudioCodec(codec: String) {}
            override fun setAudioBitRate(bitRate: Int) {}
            override fun setThemeMode(mode: AppPreferences.ThemeMode) {}
            override fun setDynamicColorEnabled(enabled: Boolean) {}
            override fun setShowToastsEnabled(enabled: Boolean) {}
            override fun setAppLanguage(languageCode: String) {}
            override fun setLoggingEnabled(enabled: Boolean) {}
            override fun getAppVersion(): String = "Version 1.0.0 (Mock)"
            override fun setFileNameTemplate(template: String) {}
            override fun setStorageTarget(target: StorageTarget) {}
            override fun setDriveFolderUri(uri: android.net.Uri?) {}
            override fun setRetentionLinked(linked: Boolean) {}
            override fun setMinDurationSeconds(seconds: Int) {}
            override fun setWriteMetadataFileEnabled(enabled: Boolean) {}
            override fun setWriteTranscriptSidecarEnabled(enabled: Boolean) {}
            override fun exportSettingsJson(): String = "{}"
            override fun importSettings(json: String): Int = 0
            override fun setStorageCapBytes(bytes: Long) {}
            override fun setRetentionLocalDays(days: Int) {}
            override fun setRetentionDriveDays(days: Int) {}
            override fun setRetentionTimeHour(hour: Int) {}
            override fun setRetentionTimeMinute(minute: Int) {}
            override fun setSyncScheduleMode(mode: SyncScheduleMode) {}
            override fun setTranscriptionMode(mode: TranscriptionMode) {}
            override fun setTranscriptionHour(hour: Int) {}
            override fun setTranscriptionMinute(minute: Int) {}
            override fun setTranscriptionRequiresCharging(required: Boolean) {}
        override fun setTranscriptionConfirmBeforeRun(confirm: Boolean) {}
            override fun setTranscriptionBatchLimit(limit: Int) {}
            override fun setTranscriptionModelId(id: String) {}
            override fun setTranscriptionLanguage(language: String?) {}
            override fun setTranscriptionAskLanguage(ask: Boolean) {}
            override fun downloadTranscriptionModel(model: TranscriptionModel) {}
            override fun cancelTranscriptionModelDownload(model: TranscriptionModel) {}
            override fun deleteTranscriptionModel(model: TranscriptionModel) {}
            override fun downloadSummaryModel(model: SummaryModel) {}
            override fun cancelSummaryModelDownload(model: SummaryModel) {}
            override fun deleteSummaryModel(model: SummaryModel) {}
            override fun setSyncTimeHour(hour: Int) {}
            override fun setSyncTimeMinute(minute: Int) {}
            override fun setSyncDayOfWeek(day: Int) {}
            override fun setUpdateCheckEnabled(enabled: Boolean) {}
        }

        SettingsContent(
            preferences = dummyPreferences,
            updateTrigger = 0,
            actions = dummyActions,
            contactPickerState = null,
            onBack = {},
            onOpenContactsIncoming = {},
            onOpenContactsOutgoing = {},
            onConfirmContacts = {},
            onDismissContacts = {}
        )
    }
}
