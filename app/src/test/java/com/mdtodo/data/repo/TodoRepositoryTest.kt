package com.mdtodo.data.repo

import com.mdtodo.data.parser.AutoCheck
import com.mdtodo.data.parser.MarkdownTreeParser
import com.mdtodo.data.parser.ToggleEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the atomic-write and Syncthing-conflict behaviour.
 *
 * [FakeLineStore] mimics the real `SafFileRepository.updateLines`: Mutex-protected
 * re-read → transform → write, where returning `null` from the transform skips the write.
 */
class TodoRepositoryTest {

    /** In-memory [LineStore] with the same abort semantics as the SAF implementation. */
    private class FakeLineStore(var content: List<String>) : LineStore {
        var writeCount = 0
            private set
        var lastWritten: List<String>? = null
            private set

        override suspend fun updateLines(
            uri: String,
            transform: (List<String>) -> List<String>?,
        ): Result<Unit> {
            return runCatching {
                val next = transform(content) ?: return@runCatching
                content = next
                writeCount++
                lastWritten = next
            }
        }

        /** Simulates another device (Syncthing) rewriting the file. */
        fun externalSync(newContent: List<String>) {
            content = newContent
        }
    }

    private fun idOfFirstTaskWith(lines: List<String>, text: String): String =
        MarkdownTreeParser.flatten(MarkdownTreeParser.parseTree(lines))
            .first { it.text == text }
            .id

    // ---------- atomicity ----------

    @Test
    fun ticking_the_last_child_rewrites_both_lines_in_a_single_write() = runBlocking {
        val store = FakeLineStore(
            listOf(
                "- [ ] Parent",
                "  - [x] Child A",
                "  - [ ] Child B",
            )
        )
        val repo = TodoRepository(store)
        val childB = idOfFirstTaskWith(store.content, "Child B")

        val result = repo.toggleWithAutoCheck(
            uri = "file:///todo.md",
            todoId = childB,
            expectedFingerprint = Fingerprint.of(store.content),
            enableTasksPlugin = false,
        )

        assertTrue(result is ToggleResult.Success)
        assertEquals(1, store.writeCount)
        assertEquals(
            listOf(
                "- [x] Parent",
                "  - [x] Child A",
                "  - [x] Child B",
            ),
            store.content,
        )
    }

    @Test
    fun non_toggle_writes_are_skipped_so_no_spurious_file_writes_occur() = runBlocking {
        // Already consistent, and we toggle a task whose auto-check outcome changes nothing
        // beyond its own line — but critically, `applyAutoCheckToFile` on an already-settled
        // file must not write at all, or it would loop with the file-change observer.
        val store = FakeLineStore(
            listOf(
                "- [x] Parent",
                "  - [x] Child",
            )
        )
        val repo = TodoRepository(store)

        repo.applyAutoCheckToFile("file:///todo.md")

        assertEquals(0, store.writeCount)
    }

    @Test
    fun applyAutoCheckToFile_writes_once_when_content_actually_changes() = runBlocking {
        val store = FakeLineStore(
            listOf(
                "- [ ] Parent",
                "  - [x] Child",
            )
        )
        val repo = TodoRepository(store)

        repo.applyAutoCheckToFile("file:///todo.md")

        assertEquals(1, store.writeCount)
        assertEquals("- [x] Parent", store.content[0])
    }

    // ---------- optimistic locking / rebase ----------

    @Test
    fun clean_write_reports_no_rebase() = runBlocking {
        val store = FakeLineStore(listOf("- [ ] Alpha", "- [ ] Beta"))
        val repo = TodoRepository(store)
        val alphaId = idOfFirstTaskWith(store.content, "Alpha")

        val result = repo.toggleWithAutoCheck(
            uri = "file:///todo.md",
            todoId = alphaId,
            expectedFingerprint = Fingerprint.of(store.content),
            enableTasksPlugin = false,
        )

        assertEquals(ToggleResult.Success(rebased = false), result)
    }

    @Test
    fun write_rebases_when_lines_were_inserted_above_by_a_sync() = runBlocking {
        val original = listOf("- [ ] Alpha", "- [ ] Beta")
        val store = FakeLineStore(original)
        val repo = TodoRepository(store)
        val alphaId = idOfFirstTaskWith(original, "Alpha")
        val staleFingerprint = Fingerprint.of(original)

        // Syncthing pushes an update while the user is mid-tap.
        store.externalSync(listOf("# Heading", "", "- [ ] Alpha", "- [ ] Beta"))

        val result = repo.toggleWithAutoCheck(
            uri = "file:///todo.md",
            todoId = alphaId,
            expectedFingerprint = staleFingerprint,
            enableTasksPlugin = false,
        )

        assertEquals(ToggleResult.Success(rebased = true), result)
        // Landed on the RIGHT task despite the shifted line index.
        assertEquals(
            listOf("# Heading", "", "- [x] Alpha", "- [ ] Beta"),
            store.content,
        )
    }

    @Test
    fun write_rebases_when_the_other_device_completed_a_sibling() = runBlocking {
        val original = listOf(
            "- [ ] Parent",
            "  - [ ] Child A",
            "  - [ ] Child B",
        )
        val store = FakeLineStore(original)
        val repo = TodoRepository(store)
        val childA = idOfFirstTaskWith(original, "Child A")

        store.externalSync(
            listOf(
                "- [ ] Parent",
                "  - [ ] Child A",
                "  - [x] Child B",
            )
        )

        val result = repo.toggleWithAutoCheck(
            uri = "file:///todo.md",
            todoId = childA,
            expectedFingerprint = Fingerprint.of(original),
            enableTasksPlugin = false,
        )

        assertEquals(ToggleResult.Success(rebased = true), result)
        // Both the tap and the auto-check landed correctly.
        assertEquals("- [x] Parent", store.content[0])
        assertEquals("  - [x] Child A", store.content[1])
    }

    @Test
    fun write_is_aborted_when_the_task_was_deleted_on_another_device() = runBlocking {
        val original = listOf("- [ ] Alpha", "- [ ] Beta")
        val store = FakeLineStore(original)
        val repo = TodoRepository(store)
        val alphaId = idOfFirstTaskWith(original, "Alpha")

        store.externalSync(listOf("- [ ] Beta"))

        val result = repo.toggleWithAutoCheck(
            uri = "file:///todo.md",
            todoId = alphaId,
            expectedFingerprint = Fingerprint.of(original),
            enableTasksPlugin = false,
        )

        assertEquals(ToggleResult.Missing(alphaId), result)
        // Nothing written — we must not guess which line the user meant.
        assertEquals(0, store.writeCount)
        assertEquals(listOf("- [ ] Beta"), store.content)
    }

    @Test
    fun write_is_aborted_when_the_task_text_changed_on_another_device_changing_its_id() = runBlocking {
        val original = listOf("- [ ] Alpha", "- [ ] Beta")
        val store = FakeLineStore(original)
        val repo = TodoRepository(store)
        val alphaId = idOfFirstTaskWith(original, "Alpha")

        // The other device renamed the task, so the content-derived id no longer resolves.
        store.externalSync(listOf("- [ ] Alpha renamed", "- [ ] Beta"))

        val result = repo.toggleWithAutoCheck(
            uri = "file:///todo.md",
            todoId = alphaId,
            expectedFingerprint = Fingerprint.of(original),
            enableTasksPlugin = false,
        )

        assertTrue(result is ToggleResult.Missing)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun null_expected_fingerprint_skips_the_conflict_check() = runBlocking {
        val original = listOf("- [ ] Alpha")
        val store = FakeLineStore(original)
        val repo = TodoRepository(store)
        val alphaId = idOfFirstTaskWith(original, "Alpha")

        store.externalSync(listOf("# H", "- [ ] Alpha"))

        val result = repo.toggleWithAutoCheck(
            uri = "file:///todo.md",
            todoId = alphaId,
            expectedFingerprint = null,
            enableTasksPlugin = false,
        )

        assertEquals(ToggleResult.Success(rebased = false), result)
        assertEquals("- [x] Alpha", store.content[1])
    }

    // ---------- fingerprint ----------

    @Test
    fun fingerprint_is_stable_for_identical_content() {
        val lines = listOf("- [ ] a", "  - [x] b", "", "prose")

        assertEquals(Fingerprint.of(lines), Fingerprint.of(lines.toList()))
    }

    @Test
    fun fingerprint_distinguishes_different_line_splits() {
        // Guards the concatenation-ambiguity case: these two must NOT collide.
        val a = Fingerprint.of(listOf("ab", "c"))
        val b = Fingerprint.of(listOf("a", "bc"))

        assertTrue(a != b)
    }

    @Test
    fun fingerprint_changes_when_any_line_changes() {
        val base = Fingerprint.of(listOf("- [ ] a", "- [ ] b"))

        assertTrue(base != Fingerprint.of(listOf("- [x] a", "- [ ] b")))
        assertTrue(base != Fingerprint.of(listOf("- [ ] a")))
        assertTrue(base != Fingerprint.of(listOf("- [ ] a", "- [ ] b", "")))
    }

    @Test
    fun fingerprint_of_empty_content_is_stable() {
        assertEquals(Fingerprint.of(emptyList()), Fingerprint.of(emptyList()))
    }

    // ---------- tasks-plugin interaction ----------

    @Test
    fun tasks_plugin_adds_a_completion_date_to_the_manually_toggled_child_only() = runBlocking {
        val today = java.time.LocalDate.of(2024, 5, 20)
        val store = FakeLineStore(
            listOf(
                "- [ ] Parent",
                "  - [ ] Child A",
                "  - [x] Child B",
            )
        )
        val repo = TodoRepository(store)
        val childA = idOfFirstTaskWith(store.content, "Child A")

        repo.toggleWithAutoCheck(
            uri = "file:///todo.md",
            todoId = childA,
            expectedFingerprint = Fingerprint.of(store.content),
            enableTasksPlugin = true,
            today = today,
        )

        // The tapped child gets a date...
        assertEquals("  - [x] Child A ✅ 2024-05-20", store.content[1])
        // ...the auto-checked parent does NOT, because we never fabricate dates.
        assertEquals("- [x] Parent", store.content[0])
    }

    @Test
    fun applyAutoCheckAfterToggle_matches_the_repository_path() = runBlocking {
        val lines = listOf(
            "- [ ] Parent",
            "  - [x] Child A",
            "  - [ ] Child B",
        )
        val viaRepository = AutoCheck.applyAutoCheckAfterToggle(
            lines = lines,
            lineIndex = 2,
            enableTasksPlugin = false,
        )
        val viaToggle = ToggleEngine.toggle(lines, 2, enableTasksPlugin = false)
        val viaAutoCheck = AutoCheck.applyAutoCheckState(viaToggle)

        assertEquals(viaAutoCheck, viaRepository)
    }
}
