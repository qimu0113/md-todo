package com.mdtodo.data

import com.mdtodo.utils.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1.5 CRLF / BOM 回归测试。
 *
 * ## 为什么这些测试必须存在
 * 修复前，`MarkdownParser.todoRegex` = `^([ \t]*)- \[(x| )\] (.*)$`。
 * Java/Kotlin 正则的 `.` 默认不匹配行终止符（含 `\r`），
 * 因此带裸 `\r` 的行整行匹配失败 → 该待办被当作普通文本忽略。
 *
 * 用户可见症状：PC 上写的 CRLF 笔记经 Syncthing 同步到手机后，
 * App 里「所有任务都看不见，也无法勾选」。
 *
 * 该问题已用真实 JVM（Liberica JDK 21）复现验证：
 * 原正则对LF 行 true、对 CRLF 行 false。
 *
 * 修复分两层：
 * 1. MarkdownParser 正则改为 `([^\r\n]*)\r?$`
 * 2. FileRepository 读取时剥离 BOM，写回显式使用 `\n`
 */
class CrlfRegressionTest {

    private val parser = MarkdownParser

    @Test
    fun `LF 文件能正常解析出待办`() {
        val lines = listOf("- [ ] 买菜", "- [x] 做饭")
        val todos = parser.parse(lines)
        assertEquals(2, todos.size)
        assertEquals("买菜", todos[0].text)
        assertEquals(true, todos[1].isDone)
    }

    @Test
    fun `CRLF 文件能正常解析出待办（修复前为0条）`() {
        val lines = listOf("- [ ] 买菜\r", "- [x] 做饭\r")
        val todos = parser.parse(lines)
        assertEquals("关键：CRLF 下必须解析出2条", 2, todos.size)
        assertEquals("买菜", todos[0].text)
        assertEquals(true, todos[1].isDone)
    }

    @Test
    fun `CRLF 下文本不含尾部 CR`() {
        val lines = listOf("- [ ] 买菜\r")
        val todos = parser.parse(lines)
        assertEquals("买菜", todos[0].text)
        assertTrue("文本不应以 \\r 结尾", !todos[0].text.endsWith("\r"))
    }

    @Test
    fun `CRLF 下toggle 能正确改勾`() {
        val lines = listOf("- [ ] 买菜\r")
        val updated = parser.toggleLine(lines, lineIndex = 0, enableTasksPlugin = false)
        assertTrue(
            "应变为已完成",
            updated[0].startsWith("- [x]")
        )
    }

    @Test
    fun `父子任务在 CRLF 下能正确识别层级`() {
        val lines = listOf(
            "- [ ] 写周报\r",
            "  - [x] 收集数据\r",
            "  - [x] 画图\r",
        )
        val todos = parser.parse(lines)
        assertEquals(3, todos.size)
        assertEquals(0, todos[0].indentLevel)
        assertEquals(1, todos[1].indentLevel)
        assertTrue("两个子任务都应解析为已完成", todos[1].isDone && todos[2].isDone)
    }

    @Test
    fun `混合CRLF 与 LF 的文件也能解析`() {
        // Syncthing 同步过程中可能出现短暂混合状态
        val lines = listOf("- [ ] 任务A\r", "- [ ] 任务B\n", "- [x] 任务C\r")
        val todos = parser.parse(lines)
        assertEquals(3, todos.size)
    }

    @Test
    fun `CRLF 文件写回后不产生重复 CR`() {
        val lines = listOf("- [ ] 买菜\r")
        val updated = parser.toggleLine(lines, lineIndex = 0, enableTasksPlugin = false)
        // 校验：结果行内不应出现 \r\r 或裸 \r 混在正文中
        assertTrue(
            "写回结果不应包含 \\r",
            updated.none { it.contains('\r') }
        )
    }

    @Test
    fun `CRLF 下 editTodoText 正常工作`() {
        val lines = listOf("- [ ] 买菜\r")
        val updated = parser.editTodoText(lines, lineIndex = 0, newText = "买水果")
        assertTrue(updated[0].contains("买水果"))
        assertTrue(updated[0].startsWith("- [ ]"))
    }

    @Test
    fun `Obsidian 元数据在 CRLF 下不丢失`() {
        val lines = listOf("- [ ] 健身 📅 2026-10-10 🔁 every day\r")
        val todos = parser.parse(lines)
        assertEquals(1, todos.size)
        assertEquals("2026-10-10", todos[0].dueDate)
        assertEquals("every day", todos[0].recurrence)
    }
}
