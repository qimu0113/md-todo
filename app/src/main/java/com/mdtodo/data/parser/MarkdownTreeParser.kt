package com.mdtodo.data.parser

import com.mdtodo.data.model.StableTodoId
import com.mdtodo.data.model.TodoNode

/**
 * Builds a parent/child tree from Markdown lines.
 *
 * Adapted from `com.isotjs.todosian.utils.MarkdownParser`. The flat `parse()` of the
 * original is left untouched; this is a purely additive `parseTree()`.
 *
 * ## Parent resolution rule (differs from the original `hasSubtasks`)
 * The original decides "has subtasks" by looking at *only the next line*:
 * ```
 * fun hasSubtasks(lines, i) = indentLevel(lines[i + 1]) > indentLevel(lines[i])
 * ```
 * That breaks on a blank line or a paragraph between parent and child:
 * ```
 * - [ ] Parent
 *                     <- blank line
 *   - [ ] Child      <- `hasSubtasks` says FALSE. Wrong.
 * ```
 * Here we instead run the standard **stack sweep**: every task pushes onto a stack,
 * and a task's parent is the nearest preceding task with a *strictly smaller* indent.
 * Non-task lines (blank, heading, prose) are skipped without popping the stack, so
 * interleaved content does not break the hierarchy. A fence (`` ``` ``) or a heading
 * resets the stack entirely — a task under a new heading is not a child of a task above it.
 */
object MarkdownTreeParser {

    /**
     * Same regex as the original parser, anchored, requires exactly one space after `]`.
     *
     * One deliberate difference: the tail is `[^\r\n]*\r?` instead of `.*`.
     * In Java regex `.` does not match a line terminator, and `$` will not match before a
     * bare `\r`, so the original `^([ \t]*)- \[(x| )\] (.*)$` **fails to match**
     * `"  - [x] Child\r"`. Verified with a standalone probe: the whole line falls through and
     * the task disappears from the tree. That matters whenever a line reaches us with a
     * trailing CR — a CRLF file read with a naive `split("\n")`, or a mixed-ending file
     * from a Windows editor. Here the CR is matched explicitly and excluded from the
     * remainder, so it can be re-emitted untouched.
     */
    // M1.5：与 MarkdownParser 保持一致的换行容忍。
    // 行尾用 `[\r\n]*` 而非 `\r?`—— 实测裸 \n 也不匹配 `[^\r\n]*$`，
    // 而 Syncthing 同步过程中可能出现 CRLF/LF 混合文件。
    private val todoRegex = Regex("""^([ \t]*)- \[(x| )\] ([^\r\n]*)[\r\n]*$""")

    private val dueSuffixRegex = Regex("""\s📅\s(\d{4}-\d{2}-\d{2})\s*$""")
    private val startSuffixRegex = Regex("""\s🛫\s(\d{4}-\d{2}-\d{2})\s*$""")
    private val scheduledSuffixRegex = Regex("""\s⏳\s(\d{4}-\d{2}-\d{2})\s*$""")
    private val completionSuffixRegex = Regex("""\s✅\s(\d{4}-\d{2}-\d{2})\s*$""")
    private val createdSuffixRegex = Regex("""\s➕\s(\d{4}-\d{2}-\d{2})\s*$""")
    private val prioritySuffixRegex = Regex("""\s(🔺|⏫|🔼|🔽|⏬️?|⏬)\s*$""")
    private val recurrenceSuffixRegex = Regex("""\s🔁\s(.+?)\s*$""")

    /** A task line that is a structural boundary: headings open a new section. */
    private val headingRegex = Regex("""^\s{0,3}#{1,6}(\s|$)""")

    /** Opening OR closing code fence. Both reset the stack — no task nesting inside code. */
    private val fenceRegex = Regex("""^\s{0,3}(```|~~~)""")

    /** A mutable accumulator used while sweeping; frozen into an immutable [TodoNode] at the end. */
    private class Slot(
        val id: String,
        val parentId: String?,
        val text: String,
        val isDone: Boolean,
        val lineIndex: Int,
        val indentLevel: Int,
        val depth: Int,
        val dueDate: String?,
        val startDate: String?,
        val scheduledDate: String?,
        val completionDate: String?,
        val createdDate: String?,
        val priority: String?,
        val recurrence: String?,
    ) {
        val children = mutableListOf<Slot>()

        fun freeze(): TodoNode = TodoNode(
            id = id,
            parentId = parentId,
            text = text,
            isDone = isDone,
            lineIndex = lineIndex,
            indentLevel = indentLevel,
            depth = depth,
            dueDate = dueDate,
            startDate = startDate,
            scheduledDate = scheduledDate,
            completionDate = completionDate,
            createdDate = createdDate,
            priority = priority,
            recurrence = recurrence,
            children = children.map { it.freeze() },
        )
    }

    /**
     * Parses [lines] into a forest of [TodoNode]s.
     *
     * Pure function: same input always yields the same ids. Only lines matching the task
     * regex produce nodes; every other line (heading, blank, prose, frontmatter) is ignored
     * and left untouched in the source, so writing back cannot corrupt them.
     */
    fun parseTree(lines: List<String>): List<TodoNode> {
        val roots = mutableListOf<Slot>()
        // Stack of open tasks, innermost last. Invariant: strictly increasing indentLevel.
        val stack = mutableListOf<Slot>()
        // Sibling text -> count, per parent. Keyed by parent id so duplicates under
        // different parents never share an ordinal.
        val ordinals = mutableMapOf<String, MutableMap<String, Int>>()

        var inFence = false

        for ((index, line) in lines.withIndex()) {
            if (fenceRegex.matches(line)) {
                // Entering or leaving a code fence: nothing inside can be a child of
                // anything outside it.
                inFence = !inFence
                stack.clear()
                continue
            }
            if (inFence) continue

            if (headingRegex.matches(line)) {
                // A new section starts; tasks above are no longer ancestors.
                stack.clear()
                continue
            }

            val match = todoRegex.matchEntire(line) ?: continue

            val indentPrefix = match.groupValues[1]
            val isDone = match.groupValues[2] == "x"
            val remainder = match.groupValues[3]
            val level = indentLevel(indentPrefix)

            // Pop until the top is a strict ancestor. A task at the same or deeper
            // indent is a sibling, not a child.
            while (stack.isNotEmpty() && stack.last().indentLevel >= level) {
                stack.removeAt(stack.lastIndex)
            }
            val parent = stack.lastOrNull()

            val meta = parseRemainder(remainder)
            val parentKey = parent?.id ?: ROOT_KEY
            val perParent = ordinals.getOrPut(parentKey) { mutableMapOf() }
            val ordinalKey = StableTodoId.normalize(meta.text)
            val ordinal = perParent.getOrDefault(ordinalKey, 0)
            perParent[ordinalKey] = ordinal + 1

            val slot = Slot(
                id = StableTodoId.of(parent?.id, meta.text, ordinal),
                parentId = parent?.id,
                text = meta.text,
                isDone = isDone,
                lineIndex = index,
                indentLevel = level,
                depth = (parent?.depth ?: -1) + 1,
                dueDate = meta.dueDate,
                startDate = meta.startDate,
                scheduledDate = meta.scheduledDate,
                completionDate = meta.completionDate,
                createdDate = meta.createdDate,
                priority = meta.priority,
                recurrence = meta.recurrence,
            )

            if (parent == null) roots.add(slot) else parent.children.add(slot)
            stack.add(slot)
        }

        return roots.map { it.freeze() }
    }

    /** Flattened view of a forest — same order and lineIndex as the original `parse()`. */
    fun flatten(nodes: List<TodoNode>): List<TodoNode> = nodes.flatMap { it.subtree() }

    /** Every node in the forest, indexed by id. O(n). */
    fun indexById(nodes: List<TodoNode>): Map<String, TodoNode> =
        flatten(nodes).associateBy { it.id }

    // --- copied verbatim from the original MarkdownParser.indentLevel() ---
    private fun indentLevel(prefix: String): Int {
        if (prefix.isEmpty()) return 0
        var width = 0
        for (char in prefix) {
            width += if (char == '\t') 4 else 1
        }
        return (width / 2).coerceAtLeast(0)
    }

    // --- copied/adapted from the original MarkdownParser.parseRemainder() ---
    private data class Remainder(
        val text: String,
        val dueDate: String? = null,
        val startDate: String? = null,
        val scheduledDate: String? = null,
        val completionDate: String? = null,
        val createdDate: String? = null,
        val priority: String? = null,
        val recurrence: String? = null,
    )

    private fun parseRemainder(remainder: String): Remainder {
        var working = remainder.trimEnd()

        var dueDate: String? = null
        var startDate: String? = null
        var scheduledDate: String? = null
        var completionDate: String? = null
        var createdDate: String? = null
        var priority: String? = null
        var recurrence: String? = null

        while (true) {
            // Order matters and is preserved from the original: completion, due, start,
            // scheduled, created, priority, recurrence — each peeled off the tail.
            val completion = completionSuffixRegex.find(working)
            if (completion != null) {
                completionDate = completion.groupValues[1]
                working = working.substring(0, completion.range.first).trimEnd()
                continue
            }
            val due = dueSuffixRegex.find(working)
            if (due != null) {
                dueDate = due.groupValues[1]
                working = working.substring(0, due.range.first).trimEnd()
                continue
            }
            val start = startSuffixRegex.find(working)
            if (start != null) {
                startDate = start.groupValues[1]
                working = working.substring(0, start.range.first).trimEnd()
                continue
            }
            val scheduled = scheduledSuffixRegex.find(working)
            if (scheduled != null) {
                scheduledDate = scheduled.groupValues[1]
                working = working.substring(0, scheduled.range.first).trimEnd()
                continue
            }
            val created = createdSuffixRegex.find(working)
            if (created != null) {
                createdDate = created.groupValues[1]
                working = working.substring(0, created.range.first).trimEnd()
                continue
            }
            val prio = prioritySuffixRegex.find(working)
            if (prio != null) {
                priority = prio.groupValues[1]
                working = working.substring(0, prio.range.first).trimEnd()
                continue
            }
            val recur = recurrenceSuffixRegex.find(working)
            if (recur != null) {
                recurrence = recur.groupValues[1].trim()
                working = working.substring(0, recur.range.first).trimEnd()
                continue
            }
            break
        }

        return Remainder(
            text = working.trimEnd(),
            dueDate = dueDate,
            startDate = startDate,
            scheduledDate = scheduledDate,
            completionDate = completionDate,
            createdDate = createdDate,
            priority = priority,
            recurrence = recurrence,
        )
    }

    private const val ROOT_KEY = "\u0000root"
}
