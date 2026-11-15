/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression pins for the recording three-dot menu contract:
 *
 * * Share and Merge never appear in [RecordingRowMenu] (their implementations elsewhere
 *   are untouched — only their menu entries are gone).
 * * The menu's Delete entry invokes the existing deletion handler.
 * * Local deletion stays local-only: no network, upload, or remote call in the delete path.
 *
 * These read the sources as text because the menu is a @Composable (not unit-renderable on
 * the JVM); they pin the exact wiring rather than re-testing Compose itself.
 */
class DeleteMenuTest {

    private fun mainSource(relative: String): String {
        // Unit tests run with the module dir as working directory.
        val direct = File("src/main/java/com/baba/callvault/$relative")
        val text = if (direct.exists()) direct.readText() else
            File("app/src/main/java/com/baba/callvault/$relative").readText()
        // Sources use CRLF; normalize so block searches are deterministic.
        return text.replace("\r\n", "\n")
    }

    /** The RecordingRowMenu composable body, from its signature to its closing brace. */
    private fun rowMenuBody(): String {
        val src = mainSource("ui/screens/HomeScreen.kt")
        val start = src.indexOf("private fun RecordingRowMenu(")
        check(start >= 0) { "RecordingRowMenu not found" }
        // The menu is a short top-level-adjacent composable ending at the first
        // line that is exactly "}" after its signature.
        val end = src.indexOf("\n}\n", start)
        check(end > start) { "RecordingRowMenu end not found" }
        return src.substring(start, end)
    }

    @Test
    fun `row menu never offers Share`() {
        val menu = rowMenuBody()
        assertFalse(menu.contains("home_share"))
        assertFalse(menu.contains("shareRecording"))
        assertFalse(menu.contains("Icons.Filled.Share"))
    }

    @Test
    fun `row menu never offers Merge or UnMerge`() {
        val menu = rowMenuBody()
        // No Merge/UnMerge menu entries (the onMerge/onUnMerge plumbing and the
        // Merge/UnMerge dialogs and services remain implemented, only unreachable
        // from this menu).
        assertFalse(menu.contains("merge_menu_action"))
        assertFalse(menu.contains("merge_menu_take_apart"))
        assertFalse(menu.contains("CallMerge"))
        assertFalse(menu.contains("CallSplit"))
    }

    @Test
    fun `row menu offers only Delete, gated on eligibility`() {
        val menu = rowMenuBody()
        assertTrue(menu.contains("R.string.home_delete"))
        // The Delete entry invokes the existing handler.
        assertTrue(menu.contains("onDelete()"))
        // The whole menu (button included) is hidden until eligible.
        assertTrue(menu.contains("canDelete"))
        assertTrue(menu.contains("if (!canDelete) return"))
    }

    @Test
    fun `call site wires eligibility and the existing delete handler`() {
        val src = mainSource("ui/screens/HomeScreen.kt")
        assertTrue(src.contains("canDelete = isDeleteEligible(item.startedAtMillis)"))
        assertTrue(src.contains("deleteTarget = DeleteTarget.Ask"))
    }

    @Test
    fun `recording card shows no transcription button`() {
        val src = mainSource("ui/screens/HomeScreen.kt")
        // The card composes only the overflow menu — TranscriptActionButton is gone
        // from the recordings list (it still exists for the playback and transcripts
        // screens, whose handlers and workers are untouched).
        assertFalse(src.contains("TranscriptActionButton("))
        assertFalse(src.contains("import com.baba.callvault.ui.common.TranscriptActionButton"))
    }

    @Test
    fun `local deletion triggers no remote deletion or upload`() {
        val repo = mainSource("data/recordings/RecordingsRepository.kt")
        for (fn in listOf("suspend fun deleteRecording(", "suspend fun deleteFile(")) {
            val start = repo.indexOf(fn)
            check(start >= 0) { "$fn not found" }
            val body = repo.substring(start, start + 3000)
            assertFalse(body.contains("Wacrm"))
            assertFalse(body.contains("http"))
            assertFalse(body.contains("enqueue"))
            assertFalse(body.contains("postRecording"))
        }
        val vm = mainSource("ui/viewmodels/HomeViewModel.kt")
        for (fn in listOf("fun deleteRecording(", "fun deleteUri(", "fun deleteUris(")) {
            val start = vm.indexOf(fn)
            check(start >= 0) { "$fn not found" }
            val body = vm.substring(start, start + 1500)
            assertFalse(body.contains("Wacrm"))
            assertFalse(body.contains("http"))
        }
    }
}
