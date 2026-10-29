/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

/**
 * What kind of call a recording came from, for the WACRM upload (Phase 2B contact association
 * follow-up: the Calls dashboard counts each value exactly).
 *
 * The wire values are centralized here and nowhere else: [wireValue] is exactly what crosses to
 * WACRM (`call_type`), matching its CHECK constraint. Null (no CallType) means unknown — old
 * recordings, unrecognized apps, or a path that cannot tell — and is sent as "absent", never as
 * a fake value. A wrong classification is worse than NULL.
 */
enum class CallType(val wireValue: String) {
    /** A normal carrier phone call. Assigned structurally, never detected. */
    PHONE("phone"),

    /** A WhatsApp call (package `com.whatsapp`). See [CallTypeResolver]. */
    WHATSAPP("whatsapp"),

    /** A WhatsApp Business call (package `com.whatsapp.w4b`). See [CallTypeResolver]. */
    WHATSAPP_BUSINESS("whatsapp_business"),
}
