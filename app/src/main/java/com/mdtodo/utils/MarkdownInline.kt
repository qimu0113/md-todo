package com.mdtodo.utils

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * 任务文本的 Markdown 内联渲染。
 *
 * 背景：用户的 Obsidian 笔记里几乎每条任务都写成
 * `**标题**｜10-24｜描述`，原来界面上是原样显示，满屏星号。
 * 这里把内联标记转成真正的样式，标记本身不显示。
 *
 * 只处理**行内**标记，不处理块级元素（标题、列表、代码块）——
 * 任务是单行文本，块级语法没有意义。
 *
 * 支持：
 * - `**粗体**` → 加粗
 * - `*斜体*` → 斜体
 * - `` `代码` `` → 等宽 + 底色
 * - `~~删除~~` → 删除线
 * - `==高亮==` → 底色高亮
 * - `#标签` → 主色（标签是给搜索用的，先在视觉上区分出来）
 */
private val inlineRegex = Regex(
    """\*\*(.+?)\*\*""" +
        """|`(.+?)`""" +
        """|~~(.+?)~~""" +
        """|==(.+?)==""" +
        """|(?<![\w*])\*(?!\*)(.+?)\*(?![\w*])""" +
        """|#([^\s#*]+)""",
)

/**
 * 把任务原文变成带样式的 [AnnotatedString]。
 *
 * 必须在 Composable 里调用（要读主题色）。结果按 [raw] 缓存，
 * 列表滚动时不会反复解析。
 */
@Composable
fun rememberMarkdownText(raw: String): AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val highlightBackground = MaterialTheme.colorScheme.primaryContainer
    val tagColor = MaterialTheme.colorScheme.primary
    return remember(raw, codeBackground, highlightBackground, tagColor) {
        buildMarkdownText(
            raw = raw,
            codeBackground = codeBackground,
            highlightBackground = highlightBackground,
            tagColor = tagColor,
        )
    }
}

private fun buildMarkdownText(
    raw: String,
    codeBackground: Color,
    highlightBackground: Color,
    tagColor: Color,
): AnnotatedString = buildAnnotatedString {
    var cursor = 0

    inlineRegex.findAll(raw).forEach { match ->
        // 标记之前的部分原样输出
        if (match.range.first > cursor) {
            append(raw.substring(cursor, match.range.first))
        }

        val groups = match.groupValues
        val content: String
        val style: SpanStyle

        when {
            groups[1].isNotEmpty() -> {
                content = groups[1]
                style = SpanStyle(fontWeight = FontWeight.SemiBold)
            }

            groups[2].isNotEmpty() -> {
                content = groups[2]
                style = SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBackground,
                )
            }

            groups[3].isNotEmpty() -> {
                content = groups[3]
                style = SpanStyle(textDecoration = TextDecoration.LineThrough)
            }

            groups[4].isNotEmpty() -> {
                content = groups[4]
                style = SpanStyle(background = highlightBackground)
            }

            groups[5].isNotEmpty() -> {
                content = groups[5]
                style = SpanStyle(fontStyle = FontStyle.Italic)
            }

            else -> {
                // 标签：保留 `#`，只是染色——去掉井号会让人认不出这是标签
                content = "#" + groups[6]
                style = SpanStyle(color = tagColor, fontWeight = FontWeight.Medium)
            }
        }

        withStyle(style) { append(content) }
        cursor = match.range.last + 1
    }

    if (cursor < raw.length) {
        append(raw.substring(cursor))
    }
}
