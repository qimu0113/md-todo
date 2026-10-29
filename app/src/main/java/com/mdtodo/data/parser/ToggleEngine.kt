package com.mdtodo.data.parser

import java.time.LocalDate

/**
 * Line-level checkbox rewriting.
 *
 * Split out from `AutoCheck` so that the manual path and the automatic path provably use
 * the *same* line-rewrite primitive. If they did not, auto-check could produce a line that
 * manual toggle would never produce, and the two would fight.
 *
 * Adapted from `com.isotjs.todosian.utils.MarkdownParser.toggleLine`.
 */
object ToggleEngine {

    /**
     * Same regex as the original parser / tree parser, except the tail is `[^\r\n]*\r?`
     * rather than `.*`. A bare `.` cannot match a line terminator in Java regex and `$`
     * will not match before a trailing `\r`, so the original pattern silently rejects
     * `"  - [x] Child\r"`. [setCheckbox] and [toggle] both re-attach the CR so a
     * round-trip preserves it byte for byte.
     */
    private val todoRegex = Regex("""^([ \t]*)- \[(x| )\] ([^\r\n]*)\r?$""")

    private val completionAnywhereRegex = Regex("""\s✅\s\d{4}-\d{2}-\d{2}(?=\s|$)""")

    /**
     * Sets the checkbox to [done] **without touching any metadata**.
     *
     * This is what [AutoCheck] uses (R7). Indent prefix and the remainder — including any
     * `✅ 📅 🔁 ➕ 🔺` suffix — are carried over byte for byte.
     *
     * @return the rewritten line, or `null` if [line] is not a task line.
     */
    fun setCheckbox(line: String, done: Boolean): String? {
        val match = todoRegex.matchEntire(line) ?: return null
        val indentPrefix = match.groupValues[1]
        val remainder = match.groupValues[3]
        val mark = if (done) "x" else " "
        return "$indentPrefix- [$mark] $remainder" + trailingCarriageReturn(line)
    }

    /**
     * A CR is not part of the task text — the regex deliberately excludes it from the
     * remainder — but it IS part of the file's bytes and must survive a rewrite,
     * otherwise saving a CRLF file silently converts it to LF.
     */
    private fun trailingCarriageReturn(line: String): String =
        if (line.endsWith("\r")) "\r" else ""

    /**
     * Manual toggle of a single line, replicating the original `MarkdownParser.toggleLine`:
     * flips the checkbox and, when the Obsidian Tasks format is enabled, adds or removes
     * today's `✅` date. Recurrence rollover is intentionally NOT implemented here —
     * see [AutoCheck] R7 and the note in the report; the caller can layer it on.
     *
     * @return the rewritten lines, or the input unchanged if [lineIndex] is not a task line.
     */
    fun toggle(
        lines: List<String>,
        lineIndex: Int,
        enableTasksPlugin: Boolean,
        today: LocalDate = LocalDate.now(),
        options: AutoCheckOptions = AutoCheckOptions(),
    ): List<String> {
        if (lineIndex !in lines.indices) return lines

        val line = lines[lineIndex]
        val match = todoRegex.matchEntire(line) ?: return lines

        val indentPrefix = match.groupValues[1]
        val isDone = match.groupValues[2] == "x"
        val remainder = match.groupValues[3]
        val newMark = if (isDone) " " else "x"

        val newRemainder = if (!enableTasksPlugin) {
            remainder
        } else {
            val withoutDoneDate = removeCompletionDate(remainder)
            if (isDone) withoutDoneDate else withoutDoneDate + " ✅ $today"
        }

        val newLine = "$indentPrefix- [$newMark] ${newRemainder.trimEnd()}" + trailingCarriageReturn(line)
        return lines.toMutableList().apply { this[lineIndex] = newLine }
    }

    /**
     * Toggle addressed by **stable id** rather than line index.
     *
     * This is the entry point the write layer must use. Between the user's tap and the
     * write, a Syncthing sync may have inserted or removed lines, so the `lineIndex` the UI
     * captured can be stale or out of range. Re-resolving through the deterministic id
     * (see `StableTodoId`) makes the write land on the intended task regardless.
     *
     * @return null when no task with [todoId] exists in the current content — the caller
     *         must treat that as a conflict and NOT write.
     */
    fun toggleById(
        lines: List<String>,
        todoId: String,
        enableTasksPlugin: Boolean,
        today: LocalDate = LocalDate.now(),
        options: AutoCheckOptions = AutoCheckOptions(),
    ): List<String>? {
        val tree = MarkdownTreeParser.parseTree(lines)
        val node = MarkdownTreeParser.flatten(tree).firstOrNull { it.id == todoId }
            ?: return null
        return toggle(lines, node.lineIndex, enableTasksPlugin, today, options)
    }

    /** `null` when [line] is not a task line. Copied from the original parser. */
    fun isTodoLine(line: String): Boolean = todoRegex.matches(line)

    /** Removes a `✅ YYYY-MM-DD` wherever it appears. Copied from the original parser. */
    fun removeCompletionDate(remainder: String): String {
        val removed = remainder.replace(completionAnywhereRegex, "")
        return removed.replace(Regex("""\s{2,}"""), " ").trimEnd()
    }
}
