package com.mdtodo.ui.model

import com.mdtodo.data.model.Todo

/**
 * 勾选来源。手动与自动必须视觉可区分，否则用户无法判断"是我点的"还是"系统帮我点的"。
 */
enum class MdCheckSource {
    /** 用户手指点的 */
    MANUAL,

    /** 子任务全部勾完，由数据层推导出来的（父任务） */
    AUTO,
}

/**
 * MD Todo 的 UI 层任务模型。
 *
 * 设计原因：
 * 1. 项目原有 [Todo] 是"扁平"的（只有 indentLevel，没有父子关系）；
 *    父子关系由 [childCount] / [doneChildCount] 显式给出，避免 UI 层重复解析 Markdown。
 * 2. [checkSource] 是本次需求的核心新增字段，UI 层据此切换复选框视觉。
 * 3. 与数据层解耦：酒狐负责把 Todo + MarkdownParser 的结果组装成 MdTodo，UI 只管渲染。
 */
data class MdTodo(
    /** 原始数据模型（保留 lineIndex / meta，供写回文件用） */
    val todo: Todo,

    /** 直属子任务数量（0 = 叶子节点） */
    val childCount: Int = 0,

    /** 已完成的直属子任务数量 */
    val doneChildCount: Int = 0,

    /** 勾选来源：手动 or 自动 */
    val checkSource: MdCheckSource = MdCheckSource.MANUAL,

    /** 相对缩进层级：0 = 根任务，1 = 子任务 */
    val depth: Int = 0,

    /** 父任务展开状态 */
    val isExpanded: Boolean = true,
) {
    val id: String get() = todo.id
    val text: String get() = todo.text
    val isDone: Boolean get() = todo.isDone
    val isParent: Boolean get() = childCount > 0
    val isAutoChecked: Boolean get() = isDone && checkSource == MdCheckSource.AUTO
    val allChildrenDone: Boolean get() = isParent && doneChildCount >= childCount

    /** 进度：叶子节点用自身完成态；父任务用子任务完成率 */
    val childProgress: Float
        get() = if (childCount == 0) {
            0f
        } else {
            (doneChildCount.toFloat() / childCount.toFloat()).coerceIn(0f, 1f)
        }
}

/** 今日页的分区模型。设计说明见交付文档 C 节。 */
enum class TodaySection {
    /** 逾期未完成（due < today） */
    OVERDUE,

    /** 今天到期（due == today） */
    TODAY,

    /** 当天日记文件 YYYY-MM-DD.md 里的任务（按需日期不限） */
    DAILY_NOTE,

    /** 没有 due 也没有在日记里的未完成任务，收在折叠区 */
    BACKLOG,
}

/** 今日页 UI 状态（纯 UI，不含文件 IO） */
data class TodayUiState(
    val isLoading: Boolean = true,
    val dateLabel: String = "",
    val dailyNoteFileName: String? = null,
    val groups: List<TodayGroup> = emptyList(),
    val backlogExpanded: Boolean = false,
    /** 每日笔记刚创建成功时的 id，用于新条目高亮动画 */
    val justCreatedNoteId: String? = null,
) {
    val totalActive: Int get() = groups.sumOf { it.items.count { !it.isDone } }
    val totalDone: Int get() = groups.sumOf { it.items.count { it.isDone } }
}

data class TodayGroup(
    val section: TodaySection,
    val title: String,
    val items: List<MdTodo>,
)
