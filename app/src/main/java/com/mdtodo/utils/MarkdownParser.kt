package com.mdtodo.utils

import com.mdtodo.data.model.TasksPriority
import com.mdtodo.data.model.Todo
import java.time.LocalDate
import java.util.UUID

object MarkdownParser {
    // M1.5 CRLF / LF 修复：
    // 原为 `(.*)$`，而 Java/Kotlin 正则的 `.` 默认不匹配行终止符，
    // 导致带裸 \r 的行（CRLF 文件）整行匹配失败 → 该待办被当作普通文本忽略，
    // 用户表现为「任务全部看不见、也无法勾选」。
    //
    // 修复要点：正文用 `[^\r\n]*` 显式排除换行符，行尾用 `[\r\n]*` 容忍任意残留。
    // ⚠️ 只写 `\r?` 是不够的 —— 已实测：`- [ ] 任务\n` 仍不匹配，
    //    因为裸 \n 也是行终止符。Syncthing 同步过程中可能出现混合换行文件，
    //    因此必须同时容忍 \r 与 \n。
    // 读取层另有 FileRepository.readLinesInternal 剥离 BOM + CR 作为治本手段。
    private val todoRegex = Regex("""^([ \t]*)- \[(x| )\] ([^\r\n]*)[\r\n]*$""")

    private val dueSuffixRegex = Regex("""\s📅\s(\d{4}-\d{2}-\d{2})\s*$""")
    private val startSuffixRegex = Regex("""\s🛫\s(\d{4}-\d{2}-\d{2})\s*$""")
    private val scheduledSuffixRegex = Regex("""\s⏳\s(\d{4}-\d{2}-\d{2})\s*$""")
    private val completionSuffixRegex = Regex("""\s✅\s(\d{4}-\d{2}-\d{2})\s*$""")
    private val createdSuffixRegex = Regex("""\s➕\s(\d{4}-\d{2}-\d{2})\s*$""")

    private val prioritySuffixRegex = Regex("""\s(🔺|⏫|🔼|🔽|⏬️?|⏬)\s*$""")
    private val recurrenceSuffixRegex = Regex("""\s🔁\s(.+?)\s*$""")

    private val completionAnywhereRegex = Regex("""\s✅\s\d{4}-\d{2}-\d{2}(?=\s|$)""")

    private fun isTodoLine(line: String): Boolean = todoRegex.matches(line)

    fun parse(lines: List<String>): List<Todo> {
        return lines.mapIndexedNotNull { index, line ->
            val match = todoRegex.matchEntire(line) ?: return@mapIndexedNotNull null
            val indentPrefix = match.groupValues[1]
            val isDone = match.groupValues[2] == "x"
            val remainder = match.groupValues[3]

            val parsed = parseRemainder(remainder)
            Todo(
                id = UUID.randomUUID().toString(),
                text = parsed.mainText,
                isDone = isDone,
                lineIndex = index,
                indentLevel = indentLevel(indentPrefix),
                dueDate = parsed.meta.dueDate,
                startDate = parsed.meta.startDate,
                scheduledDate = parsed.meta.scheduledDate,
                completionDate = parsed.meta.completionDate,
                createdDate = parsed.meta.createdDate,
                priority = parsed.meta.priority,
                recurrence = parsed.meta.recurrence,
            )
        }
    }

    fun toggleLine(
        lines: List<String>,
        lineIndex: Int,
        enableTasksPlugin: Boolean,
        today: LocalDate = LocalDate.now(),
    ): List<String> {
        if (lineIndex !in lines.indices) return lines

        val line = lines[lineIndex]
        val match = todoRegex.matchEntire(line) ?: return lines

        val indentPrefix = match.groupValues[1]
        val isDone = match.groupValues[2] == "x"
        val remainder = match.groupValues[3]
        val newMark = if (isDone) " " else "x"

        val todayStr = today.toString()

        val newRemainder = if (!enableTasksPlugin) {
            remainder
        } else {
            val withoutDoneDate = removeCompletionDate(remainder)
            if (!isDone) {
                withoutDoneDate + " ✅ $todayStr"
            } else {
                withoutDoneDate
            }
        }

        val newLine = "$indentPrefix- [$newMark] ${newRemainder.trimEnd()}"
        val updatedLines = lines.toMutableList().apply {
            this[lineIndex] = newLine
        }
        
        if (!isDone && enableTasksPlugin) {
            val parsed = parseRemainder(remainder)
            val rule = parsed.meta.recurrence
            if (!rule.isNullOrBlank()) {
                val nextLine = buildRecurredTaskLine(
                    indentPrefix = indentPrefix,
                    taskText = parsed.mainText,
                    originalMeta = parsed.meta,
                    rule = rule,
                    today = today,
                )
                if (nextLine != null) {
                    return updatedLines.toMutableList().apply {
                        add(lineIndex, nextLine)
                    }
                }
            }
        }

        return updatedLines
    }

    fun tryToggleLine(
        lines: List<String>,
        lineIndex: Int,
        enableTasksPlugin: Boolean,
        today: LocalDate = LocalDate.now(),
    ): List<String>? {
        if (lineIndex !in lines.indices) return null
        val line = lines[lineIndex]
        if (!isTodoLine(line)) return null
        return toggleLine(lines, lineIndex, enableTasksPlugin, today)
    }

    fun addTodo(
        lines: List<String>,
        text: String,
        meta: TasksMeta? = null,
        enableTasksPlugin: Boolean = false,
        addAtStart: Boolean = false,
    ): List<String> {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return lines

        val newLine = buildTodoLine(
            text = cleaned,
            meta = meta,
            enableTasksPlugin = enableTasksPlugin,
            indentPrefix = "",
        )

        if (lines.isEmpty()) return listOf(newLine)
        return lines.toMutableList().apply {
            if (addAtStart) add(frontmatterEndIndex(this), newLine) else add(newLine)
        }
    }

    private fun frontmatterEndIndex(lines: List<String>): Int {
        if (lines.firstOrNull() != "---") return 0
        for (i in 1 until lines.size) {
            if (lines[i] == "---") return i + 1
        }
        return 0
    }

    fun addSubTodo(
        lines: List<String>,
        parentLineIndex: Int,
        text: String,
        meta: TasksMeta? = null,
        enableTasksPlugin: Boolean = false,
    ): List<String>? {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return null
        if (parentLineIndex !in lines.indices) return null

        val parentLine = lines[parentLineIndex]
        val match = todoRegex.matchEntire(parentLine) ?: return null
        val parentIndentPrefix = match.groupValues[1]
        val parentIndentLevel = indentLevel(parentIndentPrefix)

        val indentUnit = inferIndentUnit(parentIndentPrefix)
        val newIndentPrefix = parentIndentPrefix + indentUnit

        val newLine = buildTodoLine(
            text = cleaned,
            meta = meta,
            enableTasksPlugin = enableTasksPlugin,
            indentPrefix = newIndentPrefix,
        )

        var insertIndex = parentLineIndex + 1
        while (insertIndex < lines.size) {
            val nextLine = lines[insertIndex]
            val nextMatch = todoRegex.matchEntire(nextLine) ?: break
            val nextIndent = nextMatch.groupValues[1]
            if (indentLevel(nextIndent) > parentIndentLevel) {
                insertIndex++
            } else {
                break
            }
        }

        return lines.toMutableList().apply { add(insertIndex, newLine) }
    }

    /**
     * 删除一个待办及其整棵子树。
     *
     * T2 修复（2026-10-27）：原实现遇到**非任务行**就 `break`，导致
     * 「父任务 / 一句说明 / 子1 / 子2」这种结构只删掉父行，子任务变孤儿，
     * 而确认框只说「移除该待办」——用户以为删干净了。
     *
     * 现在的规则：
     * - 遇到同级或更浅的任务 → 子树结束（正常边界）
     * - 遇到 `#` 开头的标题 → 子树结束（结构边界）
     * - 遇到非任务行（说明/空行）→ 先向后看有没有更深的子任务：
     *   有 → 该行属于这个子树，一并删除；没有 → 结束（不吞掉后面的正文）
     *
     * 这样「父/说明/子1/子2」整块删除，而「父/说明/同级任务」只删父行。
     */
    fun tryDeleteTodoWithSubtasks(lines: List<String>, lineIndex: Int): List<String>? {
        if (lineIndex !in lines.indices) return null
        val line = lines[lineIndex]
        val match = todoRegex.matchEntire(line) ?: return null
        val parentIndent = indentLevel(match.groupValues[1])

        var endIndex = lineIndex + 1
        while (endIndex < lines.size) {
            val nextLine = lines[endIndex]
            if (nextLine.startsWith("#")) break

            val nextMatch = todoRegex.matchEntire(nextLine)
            if (nextMatch != null) {
                val nextIndent = indentLevel(nextMatch.groupValues[1])
                if (nextIndent > parentIndent) {
                    endIndex++
                } else {
                    break
                }
            } else {
                // 非任务行：仅当它后面还有更深的子任务时才算进本子树
                if (hasDeeperTaskAfter(lines, endIndex + 1, parentIndent)) {
                    endIndex++
                } else {
                    break
                }
            }
        }

        return lines.toMutableList().apply {
            subList(lineIndex, endIndex).clear()
        }
    }

    /** 从 [from] 开始找下一个任务行，判断它的缩进是否比 [parentIndent] 更深。 */
    private fun hasDeeperTaskAfter(lines: List<String>, from: Int, parentIndent: Int): Boolean {
        var i = from
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("#")) return false
            val m = todoRegex.matchEntire(l)
            if (m != null) return indentLevel(m.groupValues[1]) > parentIndent
            i++
        }
        return false
    }

    /** 统计某个任务行的直属及所有后代子任务数量（用于删除确认文案）。 */
    fun countSubtasks(lines: List<String>, lineIndex: Int): Int {
        if (lineIndex !in lines.indices) return 0
        val match = todoRegex.matchEntire(lines[lineIndex]) ?: return 0
        val parentIndent = indentLevel(match.groupValues[1])

        var count = 0
        var i = lineIndex + 1
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("#")) break
            val m = todoRegex.matchEntire(l)
            if (m != null) {
                val ind = indentLevel(m.groupValues[1])
                if (ind <= parentIndent) break
                count++
            } else if (!hasDeeperTaskAfter(lines, i + 1, parentIndent)) {
                break
            }
            i++
        }
        return count
    }

    fun hasSubtasks(lines: List<String>, lineIndex: Int): Boolean {
        if (lineIndex !in lines.indices) return false
        val line = lines[lineIndex]
        val match = todoRegex.matchEntire(line) ?: return false
        val parentIndent = indentLevel(match.groupValues[1])

        val nextIndex = lineIndex + 1
        if (nextIndex !in lines.indices) return false
        val nextLine = lines[nextIndex]
        val nextMatch = todoRegex.matchEntire(nextLine) ?: return false
        val nextIndent = indentLevel(nextMatch.groupValues[1])
        return nextIndent > parentIndent
    }

    fun editTodoText(
        lines: List<String>,
        lineIndex: Int,
        newText: String,
    ): List<String> {
        val cleaned = newText.trim()
        if (cleaned.isEmpty()) return lines
        if (lineIndex !in lines.indices) return lines

        val line = lines[lineIndex]
        val match = todoRegex.matchEntire(line) ?: return lines
        val indentPrefix = match.groupValues[1]
        val mark = match.groupValues[2]
        val remainder = match.groupValues[3]
        val parsed = parseRemainder(remainder)

        val newRemainder = buildString {
            append(cleaned)
            if (parsed.suffixRaw.isNotEmpty()) append(parsed.suffixRaw)
        }

        val newLine = "$indentPrefix- [$mark] ${newRemainder.trimEnd()}"
        return lines.toMutableList().apply { this[lineIndex] = newLine }
    }

    fun tryEditTodoText(
        lines: List<String>,
        lineIndex: Int,
        newText: String,
    ): List<String>? {
        val cleaned = newText.trim()
        if (cleaned.isEmpty()) return null
        if (lineIndex !in lines.indices) return null

        val line = lines[lineIndex]
        if (!isTodoLine(line)) return null
        return editTodoText(lines, lineIndex, cleaned)
    }

    fun tryCopyTodoLine(
        sourceLines: List<String>,
        lineIndex: Int,
        targetLines: List<String>,
    ): Pair<List<String>, List<String>>? {
        if (lineIndex !in sourceLines.indices) return null
        val line = sourceLines[lineIndex]
        if (!isTodoLine(line)) return null

        val newTargetLines = targetLines.toMutableList().apply { add(line) }
        return sourceLines to newTargetLines
    }

    fun tryMoveTodoLine(
        sourceLines: List<String>,
        lineIndex: Int,
        targetLines: List<String>,
    ): Pair<List<String>, List<String>>? {
        if (lineIndex !in sourceLines.indices) return null
        val line = sourceLines[lineIndex]
        val match = todoRegex.matchEntire(line) ?: return null
        val parentIndent = indentLevel(match.groupValues[1])

        var endIndex = lineIndex + 1
        while (endIndex < sourceLines.size) {
            val nextLine = sourceLines[endIndex]
            val nextMatch = todoRegex.matchEntire(nextLine) ?: break
            val nextIndent = indentLevel(nextMatch.groupValues[1])
            if (nextIndent > parentIndent) {
                endIndex++
            } else {
                break
            }
        }

        val block = sourceLines.subList(lineIndex, endIndex)
        val newSourceLines = sourceLines.toMutableList().apply {
            subList(lineIndex, endIndex).clear()
        }
        val newTargetLines = targetLines.toMutableList().apply { addAll(block) }
        return newSourceLines to newTargetLines
    }

    fun editTodo(
        lines: List<String>,
        lineIndex: Int,
        newText: String,
        meta: TasksMeta?,
        enableTasksPlugin: Boolean,
    ): List<String> {
        val cleaned = newText.trim()
        if (cleaned.isEmpty()) return lines
        if (lineIndex !in lines.indices) return lines

        val line = lines[lineIndex]
        val match = todoRegex.matchEntire(line) ?: return lines
        val indentPrefix = match.groupValues[1]
        val mark = match.groupValues[2]

        val resolvedMeta = if (enableTasksPlugin) meta else null
        val newLine = buildString {
            append(indentPrefix)
            append("- [")
            append(mark)
            append("] ")
            append(cleaned)
            val suffix = resolvedMeta?.toSuffixString().orEmpty()
            if (suffix.isNotEmpty()) append(suffix)
        }

        return lines.toMutableList().apply { this[lineIndex] = newLine }
    }

    private fun todayString(today: LocalDate = LocalDate.now()): String = today.toString()

    private fun buildRecurredTaskLine(
        indentPrefix: String,
        taskText: String,
        originalMeta: TasksMeta,
        rule: String,
        today: LocalDate,
    ): String? {
        val next = RecurrenceEngine.nextOccurrence(
            ruleText = rule,
            dueDate = originalMeta.dueDate,
            scheduledDate = originalMeta.scheduledDate,
            startDate = originalMeta.startDate,
            today = today,
        ) ?: return null

        val newMeta = TasksMeta(
            dueDate = next.dueDate?.toString(),
            scheduledDate = next.scheduledDate?.toString(),
            startDate = next.startDate?.toString(),
            completionDate = null,
            createdDate = if (originalMeta.createdDate != null) today.toString() else null,
            priority = originalMeta.priority,
            recurrence = originalMeta.recurrence,
        )

        return buildString {
            append(indentPrefix)
            append("- [ ] ")
            append(taskText)
            val suffix = newMeta.toSuffixString()
            if (suffix.isNotEmpty()) append(suffix)
        }
    }

    private fun buildTodoLine(
        text: String,
        meta: TasksMeta?,
        enableTasksPlugin: Boolean,
        indentPrefix: String,
    ): String {
        val resolvedMeta = if (enableTasksPlugin) {
            (meta ?: TasksMeta()).let { current ->
                if (current.createdDate == null) current.copy(createdDate = todayString()) else current
            }
        } else {
            null
        }

        return buildString {
            append(indentPrefix)
            append("- [ ] ")
            append(text)
            val suffix = resolvedMeta?.toSuffixString().orEmpty()
            if (suffix.isNotEmpty()) append(suffix)
        }
    }

    private fun indentLevel(prefix: String): Int {
        if (prefix.isEmpty()) return 0
        var width = 0
        for (char in prefix) {
            width += if (char == '\t') 4 else 1
        }
        return (width / 2).coerceAtLeast(0)
    }

    private fun inferIndentUnit(prefix: String): String {
        return if (prefix.contains('\t')) "\t" else "  "
    }

    private fun removeCompletionDate(remainder: String): String {
        val removed = remainder.replace(completionAnywhereRegex, "")
        return removed.replace(Regex("""\s{2,}"""), " ").trimEnd()
    }

    private data class ParsedRemainder(
        val mainText: String,
        val suffixRaw: String,
        val meta: TasksMeta,
    )

    data class TasksMeta(
        val dueDate: String? = null,
        val startDate: String? = null,
        val scheduledDate: String? = null,
        val completionDate: String? = null,
        val createdDate: String? = null,
        val priority: TasksPriority? = null,
        val recurrence: String? = null,
    ) {
        fun toSuffixString(): String {
            val parts = ArrayList<String>(8)
            if (createdDate != null) parts.add("➕ $createdDate")
            if (startDate != null) parts.add("🛫 $startDate")
            if (scheduledDate != null) parts.add("⏳ $scheduledDate")
            if (dueDate != null) parts.add("📅 $dueDate")
            if (completionDate != null) parts.add("✅ $completionDate")
            if (!recurrence.isNullOrBlank()) parts.add("🔁 ${recurrence.trim()}")
            val prio = priority
            if (prio != null && prio != TasksPriority.NONE) {
                parts.add(priorityToEmoji(prio))
            }

            return if (parts.isEmpty()) {
                ""
            } else {
                " " + parts.joinToString(separator = " ")
            }
        }
    }

    private fun parseRemainder(remainder: String): ParsedRemainder {
        var working = remainder.trimEnd()

        val suffixParts = mutableListOf<String>()

        var dueDate: String? = null
        var startDate: String? = null
        var scheduledDate: String? = null
        var completionDate: String? = null
        var createdDate: String? = null
        var priority: TasksPriority? = null
        var recurrence: String? = null

        while (true) {
            val completion = completionSuffixRegex.find(working)
            if (completion != null) {
                completionDate = completion.groupValues[1]
                suffixParts.add(0, working.substring(completion.range.first))
                working = working.substring(0, completion.range.first).trimEnd()
                continue
            }

            val due = dueSuffixRegex.find(working)
            if (due != null) {
                dueDate = due.groupValues[1]
                suffixParts.add(0, working.substring(due.range.first))
                working = working.substring(0, due.range.first).trimEnd()
                continue
            }

            val start = startSuffixRegex.find(working)
            if (start != null) {
                startDate = start.groupValues[1]
                suffixParts.add(0, working.substring(start.range.first))
                working = working.substring(0, start.range.first).trimEnd()
                continue
            }

            val scheduled = scheduledSuffixRegex.find(working)
            if (scheduled != null) {
                scheduledDate = scheduled.groupValues[1]
                suffixParts.add(0, working.substring(scheduled.range.first))
                working = working.substring(0, scheduled.range.first).trimEnd()
                continue
            }

            val created = createdSuffixRegex.find(working)
            if (created != null) {
                createdDate = created.groupValues[1]
                suffixParts.add(0, working.substring(created.range.first))
                working = working.substring(0, created.range.first).trimEnd()
                continue
            }

            val prio = prioritySuffixRegex.find(working)
            if (prio != null) {
                priority = emojiToPriority(prio.groupValues[1])
                suffixParts.add(0, working.substring(prio.range.first))
                working = working.substring(0, prio.range.first).trimEnd()
                continue
            }

            val recur = recurrenceSuffixRegex.find(working)
            if (recur != null) {
                recurrence = recur.groupValues[1].trim()
                suffixParts.add(0, working.substring(recur.range.first))
                working = working.substring(0, recur.range.first).trimEnd()
                continue
            }

            break
        }

        return ParsedRemainder(
            mainText = working.trimEnd(),
            suffixRaw = suffixParts.joinToString(separator = ""),
            meta = TasksMeta(
                dueDate = dueDate,
                startDate = startDate,
                scheduledDate = scheduledDate,
                completionDate = completionDate,
                createdDate = createdDate,
                priority = priority,
                recurrence = recurrence,
            ),
        )
    }

    private fun priorityToEmoji(priority: TasksPriority): String {
        return when (priority) {
            TasksPriority.HIGHEST -> "🔺"
            TasksPriority.HIGH -> "⏫"
            TasksPriority.MEDIUM -> "🔼"
            TasksPriority.LOW -> "🔽"
            TasksPriority.LOWEST -> "⏬"
            TasksPriority.NONE -> ""
        }
    }

    private fun emojiToPriority(emoji: String): TasksPriority? {
        return when (emoji) {
            "🔺" -> TasksPriority.HIGHEST
            "⏫" -> TasksPriority.HIGH
            "🔼" -> TasksPriority.MEDIUM
            "🔽" -> TasksPriority.LOW
            "⏬", "⏬️" -> TasksPriority.LOWEST
            else -> null
        }
    }
}
