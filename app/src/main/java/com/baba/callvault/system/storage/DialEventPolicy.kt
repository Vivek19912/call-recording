/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

/**
 * Pure gate between an authoritative row and the dialer.
 *
 * A request may be opened only while it is pending/delivered, still
 * inside its TTL, and holds a dialable number. Everything else is
 * skipped without a notification and without an ack.
 */
object DialEventPolicy {

    fun isActionable(
        status: String,
        expiresAtIso: String,
        phoneNumber: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (status != "pending" && status != "delivered") return false
        if (DialRequests.isExpired(expiresAtIso, nowMs)) return false
        return DialPhoneValidation.isDialable(phoneNumber)
    }
}
