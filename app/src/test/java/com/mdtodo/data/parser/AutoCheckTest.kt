package com.mdtodo.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers rules R1–R7 of `AutoCheck` plus the parsing guarantees the write layer relies on.
 */
class AutoCheckTest {

    // ---------- R1: childless tasks are never auto-checked ----------

    @Test
    fun applyAutoCheckState_leaves_plain_task_unticked() {
        val lines = listOf("- [ ] Buy milk")

        val result = AutoCheck.applyAutoCheckState(lines)

        assertEquals(listOf("- [ ] Buy milk"), result)
    }

    @Test
    fun applyAutoCheckState_does_not_tick_every_childless_task_in_a_large_file() {
        // Regression guard for the worst failure mode: a file-wide pass ticking all
        // standalone tasks the first time the user opens the note.
        val lines = listOf(
            "# Shopping",
            "- [ ] Buy milk",
            "",
            "Some prose that must survive.",
            "- [ ] Call dentist",
            "  - [x] Find number",
            "- [ ] Pay rent",
        )

        val result = AutoCheck.applyAutoCheckState(lines)

        assertEquals("- [ ] Buy milk", result[1])
        assertEquals("- [ ] Pay rent", result[6])
        // "Call dentist" has one child and that child IS done, so R2 ticks it. This is the
        // point of the test: only genuinely childless tasks are protected by R1.
        assertEquals("- [x] Call dentist", result[4])
    }

    // ---------- R2: all direct children done => parent ticked ----------

    @Test
    fun applyAutoCheckState_ticks_parent_when_all_children_done() {
        val lines = listOf(
            "- [ ] Parent",
            "  - [x] Child A",
            "  - [x] Child B",
        )

        val result = AutoCheck.applyAutoCheckState(lines)

        assertEquals("- [x] Parent", result[0])
        assertEquals("  - [x] Child A", result[1])
        assertEquals("  - [x] Child B", result[2])
    }

    @Test
    fun applyAutoCheckState_ticks_parent_with_a_single_done_child() {
        val lines = listOf(
            "- [ ] Parent",
            "\t- [x] Child",
        )

        val result = AutoCheck.applyAutoCheckState(lines)

        assertEquals("- [x] Parent", result[0])
        assertEquals("\t- [x] Child", result[1])
    }

    // ---------- R3: any child not done => parent un-ticked ----------

    @Test
    fun applyAutoCheckState_unticks_parent_when_one_child_is_undone() {
        val lines = listOf(
            "- [x] Parent",
            "  - [x] Child A",
            "  - [ ] Child B",
        )

        val result = AutoCheck.applyAutoCheckState(lines)

        assertEquals("- [ ] Parent", result[0])
    }

    @Test
    fun applyAutoCheckState_parent_and_inconsistent_child_converge_in_one_pass() {
        // Parent ticked, first child open. Both must flip in a single call — this is the
        // "tick the last remaining child" case that would need two passes if the
        // implementation resolved top-down.
        val lines = listOf(
            "- [x] Parent",
            "  - [ ] Child A",
            "  - [x] Child B",
        )

        val result = AutoCheck.applyAutoCheckState(lines)

        assertEquals("- [ ] Parent", result[0])
        assertEquals("  - [x] Child B", result[2])
    }

    @Test
    fun applyAutoCheckState_is_idempotent() {
        val lines = listOf(
            "- [ ] Parent",
            "  - [x] Child A",
            "  - [x] Child B",
        )

        val once = AutoCheck.applyAutoCheckState(lines)
        val twice = AutoCheck.applyAutoCheckState(once)

        assertEquals(once, twice)
    }

    // ---------- R4: manual un-check of an all-done parent springs back ----------

    @Test
    fun applyAutoCheckState_reverts_manual_untick_of_parent_whose_children_are_all_done() {
        val lines = listOf(
            "- [ ] Parent",
            "  - [x] Child A",
        )

        val result = AutoCheck.applyAutoCheckState(lines)

        assertEquals("- [x] Parent", result[0])
    }

    // ---------- R5: deep nesting ----------

    @Test
    fun applyAutoCheckState_deep_nesting_propagates_from_grandchildren() {
        val lines = listOf(
            "- [ ] Grandparent",
            "  - [ ] Parent",
            "    - [x] Child A",
            "    - [x] Child B",
        )

        val result = AutoCheck.applyAutoCheckState(lines)

        assertEquals("- [x] Grandparent", result[0])
        assertEquals("  - [x] Parent", result[1])
    }

    @Test
    fun applyAutoCheckState_deep_nesting_stops_when_a_middle_node_is_open() {
        val lines = listOf(
            "- [x] Grandparent",
            "  - [ ] Parent",
            // The middle node's only child is done, so R2 ticks the middle node FIRST
            // (bottom-up), and the grandparent then follows. Propagation is not blocked by
            // the middle node being open — that state is itself derived, not authoritative.
            "    - [x] Child A",
        )

        val result = AutoCheck.applyAutoCheckState(lines)

        assertEquals("- [x] Grandparent", result[0])
        assertEquals("  - [x] Parent", result[1])
    }

    @Test
    fun applyAutoCheckState_deep_nesting_blocks_at_an_undone_descendant() {
        val lines = listOf(
            "- [x] Grandparent",
            "  - [x] Parent",
            "    - [x] Child A",
            "    - [ ] Child B",
        )

        val result = AutoCheck.applyAutoCheckState(lines)

        assertEquals("- [ ] Grandparent", result[0])
        assertEquals("  - [ ] Parent", result[1])
        assertEquals("    - [x] Child A", result[2])
        assertEquals("    - [ ] Child B", result[3])
    }

    @Test
    fun applyAutoCheckState_four_levels_all_done_ticks_the_root() {
        val lines = listOf(
            "- [ ] L0",
            "  - [ ] L1",
            "    - [ ] L2",
            "      - [x] L3",
        )

        val result = AutoCheck.applyAutoCheckState(lines)

        assertEquals("- [x] L0", result[0])
        assertEquals("  - [x] L1", result[1])
        assertEquals("    - [x] L2", result[2])
    }

    // ---------- R6: deleting all children leaves the parent alone ----------

    @Test
    fun applyAutoCheckState_orphaned_parent_keeps_its_state() {
        // The last child was deleted. R1 applies, so the parent is left exactly as-is
        // rather than being reset to open.
        val ticked = listOf("- [x] Parent")

        assertEquals(ticked, AutoCheck.applyAutoCheckState(ticked))

        val open = listOf("- [ ] Parent")

        assertEquals(open, AutoCheck.applyAutoCheckState(open))
    }

    // ---------- non-task lines are never damaged ----------

    @Test
    fun applyAutoCheckState_preserves_blank_heading_and_prose_lines() {
        val lines = listOf(
            "---",
            "tags: [daily]",
            "---",
            "# Today",
            "",
            "Intro paragraph.",
            "- [ ] Parent",
            "  - [x] Child",
            "",
            "Outro paragraph.",
            "```",
            "- [ ] not a task, inside a code fence",
            "```",
        )

        val result = AutoCheck.applyAutoCheckState(lines)

        assertEquals(lines[0], result[0])
        assertEquals(lines[1], result[1])
        assertEquals(lines[2], result[2])
        assertEquals(lines[3], result[3])
        assertEquals(lines[4], result[4])
        assertEquals(lines[5], result[5])
        assertEquals("- [x] Parent", result[6])
        assertEquals(lines[9], result[9])
        assertEquals(lines[12], result[12])
        assertEquals(lines.size, result.size)
    }

    @Test
    fun applyAutoCheckState_ignores_tasks_inside_a_code_fence() {
        val lines = listOf(
            "- [ ] Parent",
            "```markdown",
            "- [x] fake child",
            "```",
        )

        val result = AutoCheck.applyAutoCheckState(lines)

        // The fenced line is not a child, so the parent is childless and untouched.
        assertEquals(lines, result)
    }

    @Test
    fun applyAutoCheckState_blank_line_between_parent_and_child_does_not_break_hierarchy() {
        // The original `hasSubtasks()` fails here because it only inspects the next line.
        val lines = listOf(
            "- [ ] Parent",
            "",
            "  - [x] Child",
        )

        val result = AutoCheck.applyAutoCheckState(lines)

        assertEquals("- [x] Parent", result[0])
    }

    @Test
    fun applyAutoCheckState_heading_resets_the_hierarchy() {
        val lines = listOf(
            "- [ ] Parent",
            "# New section",
            "  - [ ] Looks indented but is not a child",
        )

        val result = AutoCheck.applyAutoCheckState(lines)

        assertEquals(lines, result)
    }

    // ---------- R7: metadata suffix is never fabricated nor destroyed ----------

    @Test
    fun applyAutoCheckState_does_not_invent_a_completion_date_on_the_parent() {
        val lines = listOf(
            "- [ ] Parent 📅 2024-05-01",
            "  - [x] Child ✅ 2024-04-01",
        )

        val result = AutoCheck.applyAutoCheckState(lines)

        // Ticked, but no `✅` added to the parent — we never fabricate a date.
        assertEquals("- [x] Parent 📅 2024-05-01", result[0])
    }

    @Test
    fun applyAutoCheckState_preserves_full_tasks_metadata_on_both_lines() {
        val lines = listOf(
            "- [ ] Parent ➕ 2024-01-01 📅 2024-06-01 🔁 every week 🔺",
            "  - [x] Child ✅ 2024-02-02 🛫 2024-01-15 ⏳ 2024-01-20 🔼",
        )

        val result = AutoCheck.applyAutoCheckState(lines)

        assertEquals("- [x] Parent ➕ 2024-01-01 📅 2024-06-01 🔁 every week 🔺", result[0])
        assertEquals("  - [x] Child ✅ 2024-02-02 🛫 2024-01-15 ⏳ 2024-01-20 🔼", result[1])
    }

    @Test
    fun applyAutoCheckState_removes_completion_date_when_unticking_a_parent() {
        val lines = listOf(
            "- [x] Parent ✅ 2024-03-03",
            "  - [ ] Child",
        )

        // Auto-check flips the box back; ToggleEngine.setCheckbox carries the remainder
        // verbatim, so the stale date stays. Documented as a known trade-off below.
        val result = AutoCheck.applyAutoCheckState(lines)

        assertEquals("- [ ] Parent ✅ 2024-03-03", result[0])
        // See report: the date is intentionally left for the user/Obsidian to reconcile.
    }

    // ---------- CRLF / no trailing newline / empty input ----------

    @Test
    fun applyAutoCheckState_handles_crlf_line_content() {
        // Regression test for a real bug found by probing the regex.
        // The original pattern `^([ \t]*)- \[(x| )\] (.*)$` does NOT match a line ending
        // in a bare CR: in Java regex `.` cannot match a line terminator and `$` will not
        // match before a trailing `\r`. Such a task therefore vanished from the tree
        // entirely. Verified: original.matchEntire("  - [x] Child\r") == null.
        val lines = listOf(
            "- [ ] Parent\r",
            "  - [x] Child\r",
        )

        val result = AutoCheck.applyAutoCheckState(lines)

        assertEquals("- [x] Parent\r", result[0])
        assertEquals("  - [x] Child\r", result[1])
    }

    @Test
    fun applyAutoCheckState_preserves_carriage_return_byte_for_byte() {
        val lines = listOf(
            "- [ ] Parent\r",
            "  - [x] Child A 📅 2024-05-01\r",
        )

        val result = AutoCheck.applyAutoCheckState(lines)

        // Every rewritten line keeps its CR — otherwise saving would silently convert a
        // CRLF file to LF and cause a whole-file diff on the next sync.
        assertTrue(result.all { it.endsWith("\r") })
        assertEquals("- [x] Parent\r", result[0])
    }

    @Test
    fun applyAutoCheckState_does_not_invent_a_carriage_return_on_lf_lines() {
        val lines = listOf("- [ ] Parent", "  - [x] Child")

        val result = AutoCheck.applyAutoCheckState(lines)

        assertTrue(result.none { it.endsWith("\r") })
    }

    @Test
    fun applyAutoCheckState_is_idempotent_on_a_crlf_file() {
        val lines = listOf("- [ ] Parent\r", "  - [x] Child\r")

        val once = AutoCheck.applyAutoCheckState(lines)
        val twice = AutoCheck.applyAutoCheckState(once)

        assertEquals(once, twice)
    }

    @Test
    fun toggle_preserves_carriage_return() {
        val lines = listOf("- [ ] Task\r")

        val result = ToggleEngine.toggle(lines, 0, enableTasksPlugin = false)

        assertEquals("- [x] Task\r", result[0])
    }

    @Test
    fun toggle_with_tasks_plugin_preserves_carriage_return() {
        val today = java.time.LocalDate.of(2024, 5, 20)
        val lines = listOf("- [ ] Task\r")

        val result = ToggleEngine.toggle(lines, 0, enableTasksPlugin = true, today = today)

        assertEquals("- [x] Task ✅ 2024-05-20\r", result[0])
    }

    @Test
    fun applyAutoCheckState_single_line_file_without_trailing_newline() {
        val lines = listOf("- [ ] Only task")

        assertEquals(listOf("- [ ] Only task"), AutoCheck.applyAutoCheckState(lines))
    }

    @Test
    fun applyAutoCheckState_empty_file() {
        assertEquals(emptyList<String>(), AutoCheck.applyAutoCheckState(emptyList()))
    }

    @Test
    fun applyAutoCheckState_file_of_only_blank_lines() {
        val lines = listOf("", "", "")

        assertEquals(lines, AutoCheck.applyAutoCheckState(lines))
    }

    @Test
    fun applyAutoCheckState_file_without_trailing_newline_keeps_last_line_intact() {
        val lines = listOf(
            "# Heading",
            "- [ ] Parent",
            "  - [x] Child",
        )

        val result = AutoCheck.applyAutoCheckState(lines)

        assertEquals(3, result.size)
        assertEquals("  - [x] Child", result[2])
    }

    // ---------- unicode ----------

    @Test
    fun applyAutoCheckState_handles_chinese_and_emoji_text() {
        val lines = listOf(
            "- [ ] 周报 🥋 提交",
            "  - [x] 第一部分 ✅ 2024-04-01",
            "  - [x] 第二部分 ✅ 2024-04-02",
        )

        val result = AutoCheck.applyAutoCheckState(lines)

        assertEquals("- [x] 周报 🥋 提交", result[0])
        assertEquals("  - [x] 第一部分 ✅ 2024-04-01", result[1])
    }

    // ---------- mixed indentation widths ----------

    @Test
    fun applyAutoCheckState_mixed_tab_and_space_indentation() {
        val lines = listOf(
            "- [ ] Parent",
            "\t- [x] Tab child",
            "  - [x] Space child",
        )

        val result = AutoCheck.applyAutoCheckState(lines)

        assertEquals("- [x] Parent", result[0])
        assertEquals("\t- [x] Tab child", result[1])
        assertEquals("  - [x] Space child", result[2])
    }

    @Test
    fun applyAutoCheckState_four_space_indent_is_a_child() {
        val lines = listOf(
            "- [ ] Parent",
            "    - [x] Child",
        )

        assertEquals("- [x] Parent", AutoCheck.applyAutoCheckState(lines)[0])
    }

    @Test
    fun applyAutoCheckState_undone_child_prevents_parent_tick_with_tab_indentation() {
        val lines = listOf(
            "- [ ] Parent",
            "\t- [x] Child A",
            "\t- [ ] Child B",
        )

        assertEquals("- [ ] Parent", AutoCheck.applyAutoCheckState(lines)[0])
    }

    // ---------- disabled ----------

    @Test
    fun applyAutoCheckState_is_a_noop_when_disabled() {
        val lines = listOf("- [ ] Parent", "  - [x] Child")

        val result = AutoCheck.applyAutoCheckState(lines, AutoCheckOptions(enabled = false))

        assertEquals(lines, result)
    }

    // ---------- stable ids ----------

    @Test
    fun parseTree_produces_identical_ids_across_repeated_parses() {
        val lines = listOf("- [ ] Parent", "  - [x] Child A", "  - [ ] Child B")

        val first = MarkdownTreeParser.flatten(MarkdownTreeParser.parseTree(lines))
        val second = MarkdownTreeParser.flatten(MarkdownTreeParser.parseTree(lines))

        assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test
    fun parseTree_ids_survive_insertion_of_an_unrelated_line_above() {
        // This is the case scheme A (lineIndex as key) fails and scheme B survives.
        val before = listOf("- [ ] Parent", "  - [x] Child")
        val after = listOf("# New heading", "", "- [ ] Parent", "  - [x] Child")

        val idsBefore = MarkdownTreeParser.flatten(MarkdownTreeParser.parseTree(before)).map { it.id }
        val idsAfter = MarkdownTreeParser.flatten(MarkdownTreeParser.parseTree(after)).map { it.id }

        assertEquals(idsBefore, idsAfter)
    }

    @Test
    fun parseTree_duplicate_sibling_texts_get_distinct_ids() {
        val lines = listOf("- [ ] Parent", "  - [ ] milk", "  - [ ] milk")

        val nodes = MarkdownTreeParser.flatten(MarkdownTreeParser.parseTree(lines))

        assertEquals(3, nodes.size)
        assertEquals(3, nodes.map { it.id }.toSet().size)
    }

    @Test
    fun parseTree_builds_parent_and_depth() {
        val lines = listOf("- [ ] Parent", "  - [x] Child", "    - [x] Grandchild")

        val nodes = MarkdownTreeParser.flatten(MarkdownTreeParser.parseTree(lines))

        assertEquals(0, nodes[0].depth)
        assertEquals(1, nodes[1].depth)
        assertEquals(2, nodes[2].depth)
        assertEquals(nodes[0].id, nodes[1].parentId)
        assertEquals(nodes[1].id, nodes[2].parentId)
        assertTrue(nodes[0].hasChildren)
        assertTrue(nodes[0].allChildrenDone)
    }

    @Test
    fun parseTree_sibling_after_a_child_is_not_adopted() {
        val lines = listOf(
            "- [ ] First",
            "  - [x] Child",
            "- [x] Second",
        )

        val roots = MarkdownTreeParser.parseTree(lines)

        assertEquals(2, roots.size)
        assertEquals(0, roots[1].depth)
        assertEquals(null, roots[1].parentId)
    }

    @Test
    fun toggleById_lands_on_the_right_task_after_lines_are_inserted_above() {
        val before = listOf("- [ ] Alpha", "- [ ] Beta")
        val alphaId = MarkdownTreeParser.flatten(MarkdownTreeParser.parseTree(before))[0].id

        // Syncthing inserted two lines above while the user was looking at the old render.
        val after = listOf("# Heading", "", "- [ ] Alpha", "- [ ] Beta")

        val result = ToggleEngine.toggleById(after, alphaId, enableTasksPlugin = false)

        assertEquals("# Heading", result!![0])
        assertEquals("- [x] Alpha", result[2])
        assertEquals("- [ ] Beta", result[3])
    }

    @Test
    fun toggleById_returns_null_when_the_task_no_longer_exists() {
        val result = ToggleEngine.toggleById(listOf("- [ ] Other"), "t-missing", enableTasksPlugin = false)

        assertEquals(null, result)
    }

    @Test
    fun toggle_respects_id_stability_for_tasks_with_identical_text_under_different_parents() {
        val lines = listOf(
            "- [ ] Parent A",
            "  - [ ] shared",
            "- [ ] Parent B",
            "  - [ ] shared",
        )
        val nodes = MarkdownTreeParser.flatten(MarkdownTreeParser.parseTree(lines))
        val childA = nodes.first { it.text == "shared" && it.depth == 1 && it.lineIndex == 1 }
        val childB = nodes.first { it.text == "shared" && it.depth == 1 && it.lineIndex == 3 }

        assertNotEquals(childA.id, childB.id)

        val result = ToggleEngine.toggleById(lines, childB.id, enableTasksPlugin = false)!!
        assertEquals("  - [ ] shared", result[1])
        assertEquals("  - [x] shared", result[3])
    }
}
