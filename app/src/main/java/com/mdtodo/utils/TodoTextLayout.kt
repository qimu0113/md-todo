package com.mdtodo.utils

/**
 * 任务文本的结构化拆分结果。
 *
 * 用户的笔记里任务几乎都是固定格式：
 * `- [ ] **标题**｜10-24｜描述`
 * 其中 `10-24` 是**起记日期**（不是截止日期），用来回答
 * 「这一天新记了哪些事」。
 */
data class TodoTextLayout(
    /** 标题。原文没有 `**` 包裹时，整段都算标题。 */
    val title: String,
    /** 起记日期（`MM-DD`）。没有就是 null。 */
    val date: String?,
    /** 标题和日期之后剩下的描述。可能为空。 */
    val body: String,
)

private val titleRegex = Regex("""^\*\*(.+?)\*\*""")

/** 标题后面紧跟的 `｜MM-DD｜`（全角/半角竖线、前后空格都容忍） */
private val leadingDateRegex = Regex("""^[\s｜|]*(\d{1,2}-\d{1,2})[\s｜|]*""")

/**
 * 把一条任务原文拆成「标题 / 起记日期 / 描述」三段。
 *
 * 为什么要拆：原来整段塞进一个 Text，日期夹在正文中间，
 * 两行就只够显示「标题 + 日期 + 几个字」，实际什么也没说清。
 * 拆开后标题独占一行、日期右对齐成小标签、描述单独用灰字，
 * 同样的两行高度能读到多得多的信息。
 *
 * 容错：不按这个格式写的任务（没有 `**`、没有日期）会退化成
 * 「整段就是标题」，不会丢内容。
 */
fun parseTodoLayout(raw: String): TodoTextLayout {
    val text = raw.trim()
    val titleMatch = titleRegex.find(text)
        ?: return TodoTextLayout(title = text, date = null, body = "")

    val title = titleMatch.groupValues[1]
    var rest = text.substring(titleMatch.range.last + 1)

    val dateMatch = leadingDateRegex.find(rest)
    val date = dateMatch?.groupValues?.get(1)
    rest = if (dateMatch != null) {
        rest.substring(dateMatch.range.last + 1)
    } else {
        rest.trimStart('｜', '|', ' ', '\u3000')
    }

    return TodoTextLayout(
        title = title,
        date = date,
        body = rest.trim(),
    )
}
