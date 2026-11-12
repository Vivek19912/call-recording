/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import com.baba.callvault.data.WacrmSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide observable snapshot of the WACRM session store.
 *
 * The Settings screen used to read the encrypted store once
 * (`LaunchedEffect(updateTrigger)`) and keep the result in a
 * composable-local `var`, so a background
 * [WacrmRemoteLogout] wipe — or a return from background after
 * one — never recomposed the "Connected" block: the UI showed a
 * stale account/user/device until the next import/export. This
 * object is the single re-read point: the UI collects [flow],
 * the logout path publishes `null`, and the login path publishes
 * the fresh session.
 *
 * Dependency-light: kotlinx.coroutines StateFlow only.
 */
object WacrmSessionState {

    /** What the Settings UI renders: whether the store has answered, and the session if any. */
    data class Snapshot(val checked: Boolean, val session: WacrmSession.Session?)

    private val _flow = MutableStateFlow(Snapshot(false, null))

    /** Observable snapshot. Initial value is `Snapshot(false, null)` (store not read yet). */
    val flow: StateFlow<Snapshot> = _flow.asStateFlow()

    /** Replaces the snapshot and marks the store as checked. Never throws. */
    fun publish(session: WacrmSession.Session?) {
        runCatching { _flow.value = Snapshot(true, session) }
    }

    /**
     * Blocking re-read of the encrypted store, mapped onto the
     * snapshot. Safe to call off the main thread; never throws.
     *
     * Mapping: `Available` → its session (checked); `SignedOut`
     * → null (checked); `Unavailable` → keep the currently
     * displayed session but set checked=true. The last case is
     * deliberate: an unreadable store is transient (locked
     * keystore, I/O race) and must not flash a logout over a
     * previously shown connected session.
     */
    fun refresh(context: Context) {
        runCatching {
            when (val read = WacrmSession.read(context.applicationContext)) {
                is WacrmSession.SessionRead.Available ->
                    _flow.value = Snapshot(true, read.session)
                is WacrmSession.SessionRead.SignedOut ->
                    _flow.value = Snapshot(true, null)
                is WacrmSession.SessionRead.Unavailable ->
                    _flow.update { current -> current.copy(checked = true) }
            }
        }
    }
}
