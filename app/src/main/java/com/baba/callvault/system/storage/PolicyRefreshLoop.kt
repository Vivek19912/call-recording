/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import kotlinx.coroutines.delay

/**
 * Bounded foreground refresh loop for the Owner recording policy.
 *
 * Fetches immediately on start, then approximately every [intervalMs]
 * until the caller cancels the [runForever] coroutine — the UI scopes it
 * with `LaunchedEffect(expanded, resumed)`, so collapsing the Recording
 * Configuration section, leaving the screen, or backgrounding the app
 * cancels the loop with no extra bookkeeping. No polling exists outside
 * this UI scope: there is no WorkManager job and no background service.
 *
 * Duplicate suppression across overlapping triggers (Activity resume vs.
 * section expand) lives in [WhatsappSourceSync.tryBeginSync], not here:
 * two loops racing still issue exactly one request.
 */
class PolicyRefreshLoop(
    private val intervalMs: Long = WhatsappSourceSync.POLICY_SYNC_INTERVAL_MS,
    private val fetch: suspend () -> Unit,
) {
    /**
     * Fetches now, then every [intervalMs]. Returns only on cancellation.
     * A throwing [fetch] is swallowed (not retried early, not fatal):
     * the next tick retries on schedule, and the loop never dies from a
     * transient failure.
     */
    suspend fun runForever() {
        while (true) {
            runCatching { fetch() }
            delay(intervalMs)
        }
    }
}
