package com.mdtodo.data.settings

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColorEnabled: Boolean = true,
    val showDailyFocus: Boolean = true,
    val dailyFocusMode: DailyFocusMode = DailyFocusMode.TODAY,
    val categorySort: CategorySort = CategorySort.A_Z,
    // MD Todo：首页分类的排布模式（参照 OPPO 便签的「列表 / 宫格」两种）
    val categoryLayout: CategoryLayoutMode = CategoryLayoutMode.GRID,
    val todoGrouping: TodoGrouping = TodoGrouping.GROUPED,
    val todoSort: TodoSort = TodoSort.FILE_ORDER,
    val newTodoFilePosition: NewTodoFilePosition = NewTodoFilePosition.BOTTOM,
    val enableTasksPluginSupport: Boolean = false,
    val tasksPluginUseEmojisInUi: Boolean = false,
    val enableSubtasks: Boolean = false,
    val reminderTimeHour: Int = 19,
    val reminderTimeMinute: Int = 0,
)

enum class DailyFocusMode {
    TODAY,
    OVERDUE,
    TODAY_AND_OVERDUE,
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class CategorySort {
    A_Z,
    MOST_REMAINING,
}

/**
 * 首页分类的排布模式。
 *
 * 移植自 OPPO 便签的两种浏览方式：
 * - [GRID] 宫格：2 列瀑布流卡片，卡片内带任务预览，高度随预览条数变化
 * - [LIST] 列表：一整块圆角容器 + 平铺行 + 缩进分隔线，信息密度更高
 *
 * 两者之间用纯交叉淡化切换（顶部标题区不参与动画），与便签一致。
 */
enum class CategoryLayoutMode {
    GRID,
    LIST,
}

enum class TodoGrouping {
    GROUPED,
    FILE_ORDER,
}

enum class TodoSort {
    FILE_ORDER,
    PRIORITY_HIGH_TO_LOW,
    CREATED_DATE_NEWEST_FIRST,
    DUE_DATE_EARLIEST_FIRST,
}

enum class NewTodoFilePosition {
    TOP,
    BOTTOM,
}
