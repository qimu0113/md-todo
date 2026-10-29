package com.mdtodo.utils

/**
 * 预览文本的工具函数。
 *
 * 背景：用户的 Obsidian 笔记里任务几乎都写成 `**标题**｜日期｜描述` 的形式。
 * 首页分类卡片要显示「这个分类里还有什么没做」，如果直接把原文塞进去，
 * 满屏都是 `**` 星号，既难看也占地方。
 *
 * 这里只做**纯文本化**（剥掉 Markdown 内联标记），不做富文本渲染——
 * 富文本渲染是任务列表页的事（MdTreeRow / TodoRow），两处职责不同。
 */
object TextPreview {

    /** 链接 `[文字](url)` → `文字` */
    private val linkRegex = Regex("""\[([^\]]*)\]\([^)]*\)""")

    /** 行内代码 `` `代码` `` → `代码` */
    private val codeRegex = Regex("""`([^`]*)`""")

    /** 粗体 `**文字**` / `__文字__` → `文字`（递归处理嵌套，故用循环） */
    private val boldRegex = Regex("""\*\*([^*]+)\*\*|__([^_]+)__""")

    /** 斜体 `*文字*` / `_文字_` → `文字`，注意不能误吃粗体残留 */
    private val italicRegex = Regex("""(?<![\w*])\*([^*\n]+)\*(?![\w*])""")

    /** 删除线 `~~文字~~` → `文字` */
    private val strikeRegex = Regex("""~~([^~]+)~~""")

    /** 高亮 `==文字==` → `文字` */
    private val highlightRegex = Regex("""==([^=]+)==""")

    /** 多个连续空白（含全角空格）压成一个 */
    private val spacesRegex = Regex("""[ \t\u3000]+""")

    /** Obsidian Tasks 的 emoji 元数据（📅 2026-10-19 之类），预览里不要出现 */
    private val tasksMetaRegex = Regex(
        """[📅🛫⏳✅➕🔁⏫🔼🔽⏬❌]\s*\d{4}-\d{2}-\d{2}""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 把一条任务原文压成适合卡片预览的单行文本。
     *
     * @param maxChars 超过这个长度就截断并加省略号。默认 60——
     *   3 行预览各 60 字，在 2 列宫格卡片里刚好，再多就是无意义的滚动。
     */
    fun clean(raw: String, maxChars: Int = 60): String {
        var s = raw

        // 顺序有讲究：先处理成对的、结构复杂的，再处理简单的标记，
        // 否则 `**[链接](url)**` 会先被粗体正则吃成 `[链接](url)` 再走链接正则，
        // 虽然结果一样，但把链接放前面更稳。
        s = linkRegex.replace(s) { it.groupValues[1] }
        s = codeRegex.replace(s) { it.groupValues[1] }
        s = strikeRegex.replace(s) { it.groupValues[1] }
        s = highlightRegex.replace(s) { it.groupValues[1] }

        // 粗体可能嵌套多层（`****很粗****`），跑到不再变化为止，上限防死循环
        var guard = 0
        while (guard++ < 3) {
            val next = boldRegex.replace(s) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            if (next == s) break
            s = next
        }

        s = italicRegex.replace(s) { it.groupValues[1] }
        s = tasksMetaRegex.replace(s, "")

        // 剩下的裸标记（不成对的星号、下划线）直接清掉，预览里不需要
        s = s.replace("*", "").replace("~~", "")

        s = spacesRegex.replace(s, " ").trim()

        // 去掉各种前后缀分隔符，避免预览以「｜」开头
        s = s.trim(' ', '｜', '|', '，', ',', '、', '-', '—')

        return if (s.length > maxChars) {
            s.take(maxChars).trimEnd() + "…"
        } else {
            s
        }
    }
}
