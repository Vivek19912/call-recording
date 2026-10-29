/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.voip

import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.AppLogger

/**
 * One-time migration from the two independent WhatsApp toggles
 * (stored as the shared `voip_excluded_packages` set) to the
 * single [WhatsappRecordingSource].
 *
 * Runs once at app start, before any recording gate can evaluate.
 * Guarded by [AppPreferences.hasStoredWhatsappSource]: after the
 * source exists (migration, user pick, or server sync) this is a
 * no-op, so it can never clobber a synced value on later launches.
 * The WhatsApp packages are removed from the exclusion set so the
 * generic per-app picker no longer competes with the source —
 * other apps' exclusions are preserved byte-for-byte.
 */
object WhatsappSourceMigration {

    private const val TAG = "CV:WhatsappSourceMigration"

    fun runOnce(prefs: AppPreferences) {
        if (prefs.hasStoredWhatsappSource() || prefs.isWhatsappSourceMigrationRequired()) return
        // Fresh installs (untouched defaults, both ON) and both-ON
        // upgrades share the same empty exclusion set — and both map
        // through migrateFromExcluded. The prompt is raised only when
        // it matters: VoIP recording currently ON, where silently
        // landing on NONE would change what tonight's calls do. With
        // VoIP off nothing records either way, so no prompt — the
        // radio defaults to None and the user picks when enabling.
        val excluded = prefs.getVoipExcludedPackages()
        val (source, migrationRequired) = WhatsappRecordingSource.migrateFromExcluded(excluded)
        prefs.setWhatsappRecordingSource(source)
        prefs.setVoipExcludedPackages(
            excluded - WhatsappCallPolicy.PACKAGE_WHATSAPP - WhatsappCallPolicy.PACKAGE_WHATSAPP_BUSINESS
        )
        prefs.setWhatsappSourceMigrationRequired(
            migrationRequired && prefs.isVoipRecordingEnabled()
        )
        AppLogger.i(TAG, "Migrated legacy WhatsApp toggles to $source")
    }
}
