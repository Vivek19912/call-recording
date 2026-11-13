/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.system.storage.FakeSharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setup wizard step list: the Automatic recording page sits between storage
 * setup and the reliability opt-ins, so the VoIP/app-call switch is offered during
 * first-run setup (it cannot be reached anywhere else). Navigation is plain index
 * arithmetic over this list, so adding the entry back to the list IS bringing the
 * page back — Back and Next cannot land on a step that is not listed, and
 * "Step N of M" follows automatically.
 *
 * Also pins that the wizard completion flag is independent of the step
 * list: existing installs that already finished setup are never forced
 * through onboarding again.
 */
class WizardStepsTest {

    private fun standardSteps() = wizardSteps(usesDrive = false, usesEmbeddedAdb = true)

    @Test
    fun `standard flow has exactly six steps in order`() {
        assertEquals(
            listOf(
                WizardStep.STORAGE,
                WizardStep.AUTO_RECORD,
                WizardStep.RELIABILITY,
                WizardStep.EXPERIMENTAL,
                WizardStep.AUDIO,
                WizardStep.FILE_NAME,
            ),
            standardSteps(),
        )
    }

    @Test
    fun `automatic recording page is present in every configuration`() {
        for (usesDrive in listOf(false, true)) {
            for (usesEmbeddedAdb in listOf(false, true)) {
                assertTrue(
                    "usesDrive=$usesDrive usesEmbeddedAdb=$usesEmbeddedAdb",
                    wizardSteps(usesDrive, usesEmbeddedAdb).any { it.name == "AUTO_RECORD" },
                )
            }
        }
    }

    @Test
    fun `transcription page is removed`() {
        for (usesDrive in listOf(false, true)) {
            for (usesEmbeddedAdb in listOf(false, true)) {
                assertTrue(
                    "usesDrive=$usesDrive usesEmbeddedAdb=$usesEmbeddedAdb",
                    wizardSteps(usesDrive, usesEmbeddedAdb).none { it.name == "TRANSCRIPTION" },
                )
            }
        }
    }

    @Test
    fun `updates page is removed`() {
        for (usesDrive in listOf(false, true)) {
            for (usesEmbeddedAdb in listOf(false, true)) {
                assertTrue(
                    "usesDrive=$usesDrive usesEmbeddedAdb=$usesEmbeddedAdb",
                    wizardSteps(usesDrive, usesEmbeddedAdb).none { it.name == "UPDATES" },
                )
            }
        }
    }

    @Test
    fun `drive still inserts the schedule step second`() {
        assertEquals(
            listOf(
                WizardStep.STORAGE,
                WizardStep.SCHEDULE,
                WizardStep.AUTO_RECORD,
                WizardStep.RELIABILITY,
                WizardStep.EXPERIMENTAL,
                WizardStep.AUDIO,
                WizardStep.FILE_NAME,
            ),
            wizardSteps(usesDrive = true, usesEmbeddedAdb = true),
        )
    }

    @Test
    fun `shizuku still drops only the reliability step`() {
        assertEquals(
            listOf(
                WizardStep.STORAGE,
                WizardStep.AUTO_RECORD,
                WizardStep.EXPERIMENTAL,
                WizardStep.AUDIO,
                WizardStep.FILE_NAME,
            ),
            wizardSteps(usesDrive = false, usesEmbeddedAdb = false),
        )
    }

    @Test
    fun `starts on storage ends on file name with no duplicates`() {
        for (usesDrive in listOf(false, true)) {
            for (usesEmbeddedAdb in listOf(false, true)) {
                val steps = wizardSteps(usesDrive, usesEmbeddedAdb)
                assertEquals(WizardStep.STORAGE, steps.first())
                assertEquals(WizardStep.FILE_NAME, steps.last())
                assertEquals("No step is ever listed twice", steps.size, steps.toSet().size)
            }
        }
    }

    @Test
    fun `only the storage step can withhold Next`() {
        // Stated over the remaining enum: no step may silently gain a gate.
        for (step in WizardStep.entries - WizardStep.STORAGE) {
            assertTrue(
                "$step must not gate the wizard",
                canAdvanceFrom(step, hasRecordingFolder = false, hasDriveFolder = false),
            )
        }
        assertFalse(canAdvanceFrom(WizardStep.STORAGE, hasRecordingFolder = false, hasDriveFolder = true))
        assertFalse(canAdvanceFrom(WizardStep.STORAGE, hasRecordingFolder = true, hasDriveFolder = false))
        assertTrue(canAdvanceFrom(WizardStep.STORAGE, hasRecordingFolder = true, hasDriveFolder = true))
    }

    @Test
    fun `completed onboarding stays completed regardless of the step list`() {
        // The completion flag is independent storage: shrinking the step list
        // must never force an already-onboarded install through setup again.
        val context = mockk<Context>()
        val fake = FakeSharedPreferences()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns fake
        val prefs = AppPreferences(context)
        assertFalse(prefs.isWizardCompleted())
        prefs.setWizardCompleted(true)
        assertTrue(prefs.isWizardCompleted())
    }
}
