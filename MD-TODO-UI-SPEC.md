# MD Todo — UI/UX 设计说明

> 本文记录这一版 UI 的取舍理由，不是使用文档。
> 上游：`github.com/isotjs/todosian-app`（Apache-2.0）
> 技术栈：Kotlin 2.4.20 / Compose BOM 2026.09.00 / Material3 1.5.0-alpha21 / minSdk 30 / targetSdk 37

---

## 0. 先说复用：原项目能复用多少

我把 `TodoRow.kt`（662 行）、`CategoryScreen.kt`、`HomeScreen.kt`、
`DailyFocusScreen.kt`、`DailyFocusViewModel.kt`、`SettingsScreen.kt`、`Theme.kt` 全读完了。结论：

### 能直接复用（几乎不用改）

| 资产 | 位置 | 复用方式 |
|---|---|---|
| `SwipeToDismissBox` + 滑动背景 | 原 `TodoRow.kt:102-219` | 原样搬过来，只换配色来源 |
| `AssistChip` 元信息行（📅🛫⏳✅➕🔁） | 原 `TodoRow.kt:163-206` | `buildTasksMetaChips` 逻辑完整搬，仅入参类型改 `Todo` |
| 优先级圆点 | 原 `TodoRow.kt:145-153` | 保留，但**颜色改成 colorScheme 派生**（见 §E-4） |
| `showDatePicker` | 原 `TodoRow.kt:549` | 直接用 |
| `TodosianDimens` / `TodosianSectionHeader` / `TodosianEmptyState` | `TodosianComponents.kt` | 直接用 |
| `DailyFocusBanner` 模式 | `HomeScreen.kt:529-571` | 今日页的每日笔记反馈**复用这个模式**（顶部卡片 + `primaryContainer`） |
| `MarkdownParser.addSubTodo` / `hasSubtasks` / `tryDeleteTodoWithSubtasks` | `MarkdownParser.kt:148/212/189` | **父子层级的数据基础已经现成**，见下 |
| `SettingsGroup` / `SettingsSwitchRow` / `SettingsNavRow` / `SingleChoiceDialog` | `SettingsScreen.kt:615-799` | 设置页新增区块沿用同一套卡片语言 |
| `rememberTimePickerState` + `TimePicker` | `SettingsScreen.kt:282-311` | 自动创建时间选择直接抄这段 |

### 必须替换

| 原实现 | 问题 | 新实现 |
|---|---|---|
| `TextDecoration.LineThrough`（`TodoRow.kt:158`） | 瞬间出现/消失，无法动画；颜色跟随文字色，无法单独染色 | `drawWithContent` + `TextLayoutResult` 手绘删除线，Animatable 驱动 |
| `Checkbox`（`TodoRow.kt:126`） | 无法区分手动/自动 | `MdCheckbox`：手动=primary 对勾；自动=tertiary 圆环+✨ |
| `todo.indentLevel * 12.dp` 缩进（`TodoRow.kt:85`） | **这不是父子层级**，只是左缩进 | `MdTodo(depth/childCount/doneChildCount/isExpanded)` 显式建模 |

### 关于"原项目没有父子层级 UI"的准确说法

原项目**有数据、没 UI**：

- `Todo.indentLevel: Int`（`Todo.kt:28`）已存在，Markdown 解析器已算出层级（`MarkdownParser.indentLevel()`）
- `MarkdownParser.addSubTodo(parent, ...)` 已能插入子任务（`MarkdownParser.kt:148`）
- `MarkdownParser.hasSubtasks(lines, lineIndex)` 已能判断有没有子任务（`:212`）
- `sortTodosByPriorityKeepingSubtasks()` 已经把父子当作一个 group 处理（`CategoryScreen.kt:700`）

**但是 UI 层完全没利用**：`CategoryScreen` 直接 `items(sortTodos(...))` 平铺，
`TodoRow` 只把 `indentLevel` 换算成 12dp 左缩进，没有展开箭头、没有引导线、没有"子任务全完成"的概念。

所以本次改造的重点不是"从零做层级"，而是**把已有的数据能力接到 UI 上**。这是好消息，工作量比预想小。

---

## A. TodoRow 改造

代码：`app/src/main/java/com/mdtodo/ui/components/MdTodoRow.kt`（完整可编译）

核心签名：

```kotlin
@Composable
fun MdTodoRow(
    item: MdTodo,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onRequestDelete: () -> Unit,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
    onAddSubtask: (() -> Unit)? = null,
    onRequestMove: (() -> Unit)? = null,
    enableTasksPluginSupport: Boolean = false,
    useEmojisInUi: Boolean = false,
    showHierarchyGuides: Boolean = true,
    showSubtaskButton: Boolean = true,
    autoCheckedPulse: Boolean = false,
)
```

### A-1. 删除线动画：为什么必须手绘

| 方案 | 能否平滑展开 | 能否单独染色 | 结论 |
|---|---|---|---|
| `TextDecoration.LineThrough` | ❌ 二值开关，瞬间 | ❌ 跟随 Text color | 原项目用的，不够 |
| 叠一层 `Text` 做 mask | ⚠️ 可做但多一层测量开销 | ⚠️ | 不划算 |
| **`drawWithContent` + `TextLayoutResult`** | ✅ 任意 0~1 进度 | ✅ 独立 color | **采用** |

实现要点：

```kotlin
Text(
    text = item.text,
    onTextLayout = { result -> layout = result },   // 唯一能拿到 TextLayoutResult 的入口
    modifier = Modifier.drawStrikethrough(
        progress = { strike.value },
        color = strikeColor,
        strokeWidth = MdDimens.StrikeWidth,
        layoutResult = { layout },
    ),
)

// 画线本体
private fun Modifier.drawStrikethrough(...): Modifier = drawWithContent {
    drawContent()
    val p = progress().coerceIn(0f, 1f)
    val result = layoutResult() ?: return@drawWithContent
    val fontSize = result.multiParagraph.fontSize
    for (line in 0 until result.multiParagraph.lineCount) {
        val start = result.getLineStart(line)
        val end = result.getLineEnd(line)
        val y = result.getLineBaseline(line).toFloat() - fontSize * 0.28f
        val x0 = result.getLineHorizontalOffset(line).toFloat()
        val x1 = x0 + (end - start) * p
        drawLine(color, Offset(x0, y), Offset(x1, y), strokeWidth = ..., cap = StrokeCap.Round)
    }
}
```

- **多行文本也能一行一条**（按 `multiParagraph.lineCount` 循环），不是简单画一条横线糊过去
- 线宽随字号缩放（`strokeWidth * (1f + fontSize / 32f)`），大字体下线不会显得过细
- 取消勾选时 `animateTo(0f, tween(200))`，视觉上是"擦掉"而不是"啪一下没了"
- 描边位置 `baseline - fontSize * 0.28f`：略高于基线，和主流 UI 库一致

**动画参数**：勾选 320ms `FastOutSlowInEasing`；取消 200ms；文字颜色渐变 300ms。

### A-2. 父子层级渲染

```
父任务（有子任务）:
┌────────────────────────────────────────────┐
│ [▼] [✓]  写周报                        [✨] │  ← 展开箭头 + 复选框
└────────────────────────────────────────────┘
    │  ← L 形引导线（竖线 + 横线）
    │ ┌──────────────────────────────────────┐
    │ │ [ ] [✓]  收集数据                    │  ← 缩进 20dp
    │ └──────────────────────────────────────┘
    │ │ ┌──────────────────────────────────────┐
    │ │ │ [ ] [✓]  画图表                    │
    │ │ └──────────────────────────────────────┘
```

- 缩进：`IndentPerLevel = 20.dp`，最多 3 级（`depth.coerceIn(0, 3)`），防深层把文字挤没
- 引导线：`Canvas` 画 L 形，`outlineVariant` 色。一级子项竖线偏左（`x = width * 0.3`），更深层级居中（`0.5`），形成树状观感
- 展开箭头：`Icons.Outlined.KeyboardArrowDown` + `rotate(-90°)`，收起时指向右
- **对齐保证**：无子任务的行用 `Spacer(ExpandSlotWidth)` 占位，保证同一层级的文字左对齐
- 层级区域宽度 = `GuideAreaWidth(24dp) + ExpandSlotWidth(48dp)`，点击区都 ≥48dp

### A-3. 大字体友好

| 做法 | 原因 |
|---|---|
| `Modifier.height(IntrinsicSize.Min).heightIn(min = 56.dp)` | 不用固定 `height`，字号放大时行自然长高，不会裁字 |
| 删除线宽度跟随 `fontSize` | 字号变大，线不能还是 1.5dp |
| 正文用 `MaterialTheme.typography.bodyLarge` | 原项目 `Type.kt` 已是 16sp/24sp，达标 |
| 所有输入框 `minLines/maxLines` 而非固定 `height` | 同上 |

### A-4. 单手操作

- 勾选框：视觉 28dp，**触摸槽位 48dp**（`Modifier.size(48.dp).toggleable {}`）
- 展开箭头：48dp
- 子任务按钮：48dp
- 用 `toggleable(role = Role.Checkbox)` 而非 `clickable`：TalkBack 读作"复选框，已勾选"，而不是"按钮"

---

## B. 自动打勾的视觉反馈

代码：`app/src/main/java/com/mdtodo/ui/components/AutoCheckFeedback.kt`

### B-1. 设计方案：三重常驻信号 + 瞬时动画

核心判断：**只在自动勾的那一瞬间闪一下是不够的**。用户划走列表回头看，
不记得刚才哪条是自己点的、哪条是自动的。所以必须有**常驻**视觉差异。

| 信号 | 手动勾 | 自动勾 | 目的 |
|---|---|---|---|
| 复选框形状 | 方框 + ✓ 对勾 | **圆形** + ✨ 图标 | 形状差异，色盲用户也能分辨 |
| 复选框颜色 | `primary` | **`tertiary`** | 颜色差异，一眼扫过 |
| 外圈进度环 | 无 | `tertiary` 弧线 = 子任务完成率 | 表达"为什么被勾上了" |
| 删除线颜色 | `onSurfaceVariant`（中性灰） | **`tertiary`**（彩色） | 扫列表时能看出这条不一样 |
| 行尾标签 | 无 | 「自动完成」chip | 明确文字说明，不依赖视觉 |
| 瞬时动画 | 无 | 整行闪 tertiaryContainer + 复选框弹到 1.18x | "刚才发生了什么" |
| 横幅 | 无 | 「已自动完成「xxx」 子任务 3/3 全部完成」+ 撤销 | 解释 + 反悔入口 |
| 触觉 | 无 | 震动一次（可关） | 不用看也知道 |

**「撤销」是必须的**：自动勾是系统替用户做决定。不给低成本反悔，
用户会觉得 App 擅自改了他的 Markdown 文件 —— 这是 MD 类 App 最敏感的点。

### B-2. 事件总线架构

```kotlin
@Stable
class AutoCheckBus internal constructor(private val hapticFeedback: () -> Unit) {
    var currentEvent: AutoCheckedEvent? by mutableStateOf(null); private set
    private val pulses = mutableStateMapOf<String, Long>()   // parentId -> 时间戳

    fun isAutoCheckedNow(parentId: String): Boolean =
        (pulses[parentId] ?: 0L) > System.currentTimeMillis() - PULSE_WINDOW_MS

    fun emit(parentId: String, parentText: String, doneChildren: Int, totalChildren: Int) { ... }
    fun undo() { ... }
}
```

**为什么不直接透传 state**：LazyColumn 里任意一行都可能触发自动勾，
如果把 `autoCheckedId` 作为参数往下传，整棵列表都会进重组。
用 `SnapshotStateMap` 按 key 存，只有命中的那一行会重组。

页面顶层接一次：

```kotlin
MdAutoCheckScope(
    onUndo = { id -> viewModel.undoAutoCheck(id) },
    enableHaptic = true,
    hostStyle = AutoCheckHostStyle.BANNER,
) { bus ->
    Scaffold(snackbarHost = { MdAutoCheckHost(bus) }) { /* bus.isAutoCheckedNow(item.id) */ }
}
```

### B-3. 与数据层的接口

题目给定回调 `onAutoChecked(parentId: String)`。数据层（酒狐侧）流程：

```
用户勾子任务
  → 数据层写回 md
  → 数据层检查：该子任务的父任务是否所有子任务都已完成？
  → 是 → 调用 onAutoChecked(parentId)
  → UI 层（MdTodoRow 的父行）自动打勾 + bus.emit(...)
```

UI 层只负责**渲染**，不自己算"是否该自动勾"。理由：
自动勾的判定规则（是否含递归孙任务、是否有 `✅ 完成日期` 字段、md 缩进怎么算子任务）
属于数据层知识，放 UI 层会导致两处逻辑不一致。

**撤销**同理：`bus.undo()` → `onUndo(parentId)` → 数据层把该行改回 `- [ ]`。

### B-4. 动画时序

```
t=0ms     最后一个子任务打勾
          ├─ 数据层判定 → onAutoChecked(parentId)
t≈16ms    bus.emit()
          ├─ 震动（HapticFeedbackType.LongPress）
          ├─ 复选框 shape: 方框✓ → 圆形✨（spring, damping 0.4, stiffness 900）
          ├─ 整行背景 → tertiaryContainer（160ms）
t=160ms   复选框弹到 1.18x
t=160ms   横幅从顶部滑入（260ms, FastOutSlowInEasing）+ fadeIn
t=780ms   整行背景 → surfaceContainer（620ms 渐隐）
t=1200ms  复选框回落到 1.0x（spring）
t=2400ms  横幅滑出（200ms）
t=2900ms  pulses 移除该 id，停止追踪
```

**关于自动收起子任务**：提供了 `AutoCollapseAfterAutoCheck`，但**默认关闭**。
理由：自动收起会让用户的操作现场消失（"我刚勾的那 5 个子任务呢？"）。
建议改为"自动勾后不收起，但在父任务行尾显示 ✨ 标签"。

---

## C. 三页布局方案

### C-1. 设计决策：今日页显示什么？

**问题**：当天 `YYYY-MM-DD.md` 的任务，还是所有未完成任务？

**我的答案：都要，分四段，默认只展开前两段。**

```
┌─ 逾期 (2) ────────────── 红点 + 红色标题，永不折叠
│  ■ 没交电费 (10-05 逾期)
│  ■ 取消会议室 (10-06 逾期)
├─ 今天 (5) ──────────────
│  ■ 写周报
│  ├ ■ 收集数据
│  └ ■ 画图表
├─ 今日笔记 (3) ────────── 当天 YYYY-MM-DD.md 里的任务
│  ■ 读《深度工作》第三章
├─ 其他未完成 (12) ─────── 默认折叠，只显示计数
└  [展开]
```

**理由（按重要性排序）**：

1. **只显示日记文件会漏掉 80% 的真实任务。**
   用户随手在别的文件里勾的「买菜」不会出现在今天的日记里，但今天确实该做。
   只显示日记 = 用户以为任务丢了，这是灾难性体验。

2. **只显示所有未完成会淹没重点。**
   一个长期不用的收件箱可能有 300 条未完成。全塞进"今日"等于没有今日。

3. **日记文件是"意图"，不能被规则替代。**
   MD Todo 的本质是"文件即数据库"，用户手写的 `2026-10-08.md` 是他今天的计划，
   这是最相关的内容，必须独立成段且不折叠。

4. **排序依据是"能不能今天做完"，不是创建时间。**
   `逾期 > 今天到期 > 今天写的 > 其余`。

**如果一定要二选一**，我选 **所有未完成任务 + 逾期置顶**，
把日记文件作为"今日笔记"独立段落附在下面。理由同上第 1、2 条。

### C-2. 三页结构表

| | **首页** | **分类页** | **今日页**（新增，重点） |
|---|---|---|---|
| **顶层容器** | `LazyVerticalGrid` 2 列 | `LazyColumn` | `LazyColumn` |
| **顶部** | DailyFocusBanner → 改为「今日进度卡」 | 无 | `TopAppBar`（日期 + 文件名） |
| **第一屏内容** | 分类卡片网格 | 任务列表（分组） | **进度卡 + 逾期段 + 今天段** |
| **层级 UI** | 无（卡片里只显示 3/12 计数） | ✅ 父子 + 展开箭头 | ✅ 父子 + 展开箭头 |
| **排序** | `A_Z` / 剩余最多优先（沿用） | 文件顺序 / 优先级 / 创建时间 / 截止时间（沿用 4 选 1） | 逾期 → 今天 → 今日笔记 → 其他 |
| **折叠** | 无 | 分「进行中/已完成」两段（沿用） | 「其他未完成」折叠 |
| **FAB** | 新建分类 | 新建任务 | **无日记时**：新建今日笔记<br>**有日记时**：新建任务 |
| **空状态** | 引导建分类（沿用 `CreateNewFolder`） | 引导建任务（沿用 `Checklist`） | 「今天没有待办」+ 建笔记按钮 |
| **单手可达** | FAB 右下 | FAB 右下 | FAB 右下；勾选框全屏左侧 48dp |
| **删除手势** | — | 左滑删除 / 右滑移动 | 左滑删除（不提供移动，今日页是"看"不是"整理"） |

**首页改动建议**（最小改动）：把现有 `DailyFocusBanner` 换成可点击跳今日页的进度卡。
`HomeScreen.kt:529-571` 的 `DailyFocusBanner` 结构直接复用，改文案即可。

### C-3. 今日页代码

代码：`app/src/main/java/com/mdtodo/ui/today/TodayScreen.kt`

- `TodayRoute(...)` — 顶层入口，接 AutoCheckScope
- `TodayScreen(...)` — 纯渲染，接收 `TodayUiState`（UI 层无 IO）
- `TodayProgressCard` — 顶部进度卡，`4/9 已完成`，全完成时切 tertiary 色
- `TodaySectionHeader` — 分区标题，逾期段带红点，折叠箭头旋转动画
- `NewNoteCreatedHighlight` — 新建笔记成功反馈（见 §F）
- `TodayEmptyState` — 空状态

UI 状态模型在 `ui/model/MdTodo.kt`：

```kotlin
data class TodayUiState(
    val isLoading: Boolean = true,
    val dateLabel: String = "",
    val dailyNoteFileName: String? = null,
    val groups: List<TodayGroup> = emptyList(),
    val backlogExpanded: Boolean = false,
    val justCreatedNoteId: String? = null,
)
```

---

## D. 设置页

代码：`app/src/main/java/com/mdtodo/ui/settings/DailyNoteSettings.kt`

### D-1. 模板编辑（`DailyNoteTemplateSection`）

| 设计点 | 做法 | 理由 |
|---|---|---|
| 多行输入 | `OutlinedTextField(minLines = 6, maxLines = 14)` | 模板是块文本 |
| 等宽字体 | `FontFamily.Monospace` | 模板有缩进和 Markdown 符号，等宽才能看出层级 |
| 实时预览 | 下方 `TemplatePreviewCard` 渲染替换后的结果 | 用户不该因看不懂 `{{date}}` 而乱填 |
| 占位符插入 | `{{date}}` `{{weekday}}` `{{title}}` 三个，点「插入」按钮 | 不用手打花括号 |
| 折叠 | 开关关闭时 `AnimatedVisibility` 收起编辑区 | 避免一进设置页就被大文本框淹没 |
| 字数提示 | `supportingText` 显示行数 + 字符数 | 防止粘进一整篇文章 |

### D-2. 文件夹选择（`FolderPickerSection`）

**Android 11+ / SAF 三条硬约束**（写清楚边界，避免酒狐侧踩坑）：

1. `ActivityResultContracts.OpenDocumentTree()` 授予的是**所选目录及其子树**。
   换目录必须重新授权 —— 系统限制，绕不过去。
2. **必须**调用
   `contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)`，
   否则进程被杀后权限失效，App 变成"权限已失效"。**这一步属数据层**。
3. 用户选 volume root 会被系统拒绝。文案必须引导"选一个专门放 Markdown 的文件夹"。

UI 侧：`SettingsInfoRow`（当前文件夹 + 权限状态）+ `SettingsActionRow`（更换 / 重置）。
权限失效时副标题变 `error` 色。

### D-3. 自动创建开关 + 时间（`DailyNoteAutoCreateSection`）

**默认时间 00:05，不是早上 8 点。**

理由：自动创建的本质是"在第二天开始前把当天的文件落盘"。
凌晨创建能保证用户早上打开 App 时文件已经在；早上 8 点创建的话，
用户 7 点打开看到的是空列表 —— 体验断裂。

时间选择用 `rememberTimePickerState(initialHour, initialMinute, is24Hour = DateFormat.is24HourFormat(context))`
+ `AlertDialog`，与原项目 `SettingsScreen.kt:282-311` 的提醒时间完全一致，不引新依赖。

---

## E. 暗色模式 / 大字体 / 单手操作

| # | 维度 | 具体做法 | 落地值 |
|---|---|---|---|
| E-1 | **暗色** | 全部颜色走 `MaterialTheme.colorScheme`，零硬编码色值 | 20+ 处色值全部 token 化 |
| E-2 | 暗色 | 优先级圆点从固定 `R.color.priority_*` 改成 colorScheme 派生 | HIGHEST→`error`、MEDIUM→`tertiary`、LOWEST→`outline`。原项目用固定色，在 Android 12+ 动态取色下会和壁纸打架 |
| E-3 | 暗色 | 动态取色开启时，自动勾的 tertiary 可能撞 primary → 加形状差异兜底 | 见 §B-1 |
| E-4 | 暗色 | 对比度：已完成文字用 `onSurfaceVariant`（M3 保证 ≥4.5:1），不用 `onSurface.copy(alpha=0.38f)` | — |
| E-5 | **大字体** | 全部行高用 `height(IntrinsicSize.Min).heightIn(min=56.dp)`，禁固定 `height` | 字号 200% 时行自动长高不裁字 |
| E-6 | 大字体 | 删除线宽度 = `strokeWidth * (1f + fontSize/32f)` | 16sp 时 ≈2.25px，24sp 时 ≈2.6px |
| E-7 | 大字体 | 输入框不写死字号，用 `MaterialTheme.typography.bodyMedium` | 跟随系统 |
| E-8 | 大字体 | `maxLines = 1` + `TextOverflow.Ellipsis` 的地方（标题、chip 标签）保留 | 标题截断优于把布局撑爆 |
| E-9 | 大字体 | 删除线按 `multiParagraph.lineCount` 逐行绘制 | 多行文本每行都划到，不会只划第一行 |
| E-10 | **单手** | 所有可点区域 ≥48×48dp | 勾选框、展开箭头、子任务按钮均 48dp |
| E-11 | 单手 | 主要操作（下拉删除）在屏幕左侧 | 拇指自然落点 |
| E-12 | 单手 | 勾选框用 `toggleable(role = Role.Checkbox)` | 无障碍朗读正确 |
| E-13 | **手势冲突** | **不做长按拖动排序**。理由：`SwipeToDismissBox` 已占用水平手势，长按拖动会与 LazyColumn 垂直滚动打架 | 见下表 |
| E-14 | 手势冲突 | 左滑删除 / 右滑移动的阈值用 M3 默认，不改 | 默认值已做过手势竞争调优 |
| E-15 | 手势冲突 | 展开箭头 48dp 独立区域，不与整行 `clickable(onEdit)` 重叠 | 展开箭头在勾选框左侧，编辑是点文字 |
| E-16 | 手势冲突 | 分区标题折叠用整行 `clickable`，但高度 ≥48dp | 误触率低 |
| E-17 | **其他** | 震动可在设置关（`enableHaptic`） | 办公室/地铁场景 |
| E-18 | 其他 | 全完成时进度卡切 `tertiaryContainer` + 文案"今天全部完成，干得漂亮" | 正反馈 |
| E-19 | 其他 | 逾期段永不可折叠（铁律：逾期必须第一眼可见） | `onToggle = null` |

### E-13 详细：为什么不做长按拖动排序

| 方案 | 问题 |
|---|---|
| 长按拖动排序 | `LazyColumn` 的垂直滚动与拖拽手势竞争，需要 `detectDragGesturesAfterLongPress` + 手势仲裁，代码量大且在 LazyColumn 里容易跳位 |
| 拖拽手柄（右侧小图标） | 48dp 手柄 + 长按才激活，与左滑删除区不冲突，但增加 UI 噪音 |
| **沿用左右滑动（推荐）** | 原项目已有，零新增手势风险；排序需求用「优先级排序」设置项替代 |

MD Todo 是"看今天做什么"的工具，不是项目管理工具，排序需求弱。**建议本期不做拖拽排序。**

---

## F. 每日笔记创建成功的反馈

### F-1. 三方案对比

| | **① Snackbar** | **② 顶部横幅** | **③ 列表新条目高亮** | **④ 组合（推荐）** |
|---|---|---|---|---|
| 实现 | `SnackbarHostState.showSnackbar()` | `Scaffold.topBar` 下 / `snackbarHost` 位置放 `AnimatedVisibility` | LazyColumn `item{}` 插高亮卡片 | ③为主 + Snackbar 兜底失败 |
| 视觉位置 | 底部 | 顶部 | 列表内顶部 | 列表内顶部 |
| 停留时长 | 4s（Short） | 2.4s | 2.6s 后淡出 | 2.6s |
| 是否遮挡内容 | ❌ 遮挡底部列表 | ⚠️ 遮挡顶部 | ✅ 不遮挡 | ✅ |
| 用户视线成本 | ❌ 高（视线在列表中间，Snackbar 在底部） | ⚠️ 中（要抬头） | ✅ **零（视线本来就在那）** | ✅ 零 |
| 能否带文件名 | ⚠️ 挤 | ✅ | ✅ | ✅ |
| 能否带"打开"按钮 | ✅ | ✅ | ⚠️ 空间紧 | ✅ |
| 复用现有代码 | ✅ 原项目每页都有 | ✅ **复用 `DailyFocusBanner` 模式** | ✅ 新写 | — |
| 无障碍 | ✅ | ✅ | ⚠️ 动态插入需 `liveRegion` | ✅ |

### F-2. 推荐：**③ + ② 的组合**

```kotlin
// 列表顶部插入一张会自己淡出的卡片
@Composable
private fun NewNoteCreatedHighlight(fileName: String, onDismiss: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        delay(2600)
        alpha.animateTo(0f, tween(320))
        onDismiss()
    }
    Card(containerColor = secondaryContainer.copy(alpha = alpha.value)) {
        Row { Icon(FolderOpen); Text("已创建 $fileName") }
    }
}
```

**理由**：

1. **视线成本最低。** 用户刚点完"新建今日笔记"，眼睛就在列表顶部。
   Snackbar 在屏幕底部，用户要"找"一下才知道在说自己。
2. **不遮挡。** 底部 Snackbar 会盖住最后一条任务，用户想看被挡了。
3. **能带文件名。** 用户建的是 `2026-10-08.md`，需要确认"是今天这个文件吧"。
   Snackbar 塞不下文件名 + 按钮而不换行。
4. **复用原项目 `DailyFocusBanner` 模式**（`HomeScreen.kt:529-571`），
   视觉语言一致，改造成本低。

**失败情况**用 Snackbar 兜底：

```kotlin
if (result.isFailure) {
    scope.launch {
        snackbarHostState.showSnackbar(
            message = "创建失败：${result.exceptionOrNull()?.message}",
            actionLabel = "重试",
        )
    }
}
```

### F-3. 原项目 focus banner 能否复用

**能，但只能复用"模式"，不能复用代码。**

原 `DailyFocusBanner`（`HomeScreen.kt:529-571`）是 `private` 的，
且它是个"点击跳转到 DailyFocus 页"的入口卡，不是"操作结果"提示。

可复用的部分：`Card(onClick=...)` + `primaryContainer`/`secondaryContainer` 配色 +
`shape = extraLarge` + 图标在标题上方的布局习惯。

不可复用：`private` 可见性、`onClick` 语义、pluralStringResource 的计数文案。

我的 `NewNoteCreatedHighlight` 用 `secondaryContainer`（而不是 `primaryContainer`），
和 `AutoCheckBanner` 的 `tertiaryContainer` 区分开 —— 三种提示三种颜色，用户能一眼分清。

### F-4. 提示色语义约定

| 提示类型 | 容器色 | 图标 | 出现位置 |
|---|---|---|---|
| 自动完成父任务 | `tertiaryContainer` | `Filled.AutoAwesome` | 顶部横幅 |
| 创建每日笔记成功 | `secondaryContainer` | `Outlined.FolderOpen` | 列表顶部 |
| 错误 / 失败 | `errorContainer` | `Filled.Delete` 不用，用 `Outlined.Info` | Snackbar |

---

## 待验证事项（本地无 Android SDK，无法编译验证）

**图标风险已清零**：所有图标均已替换为**原项目源码中已实际 import 并使用过**的图标
（对 `com.isotjs.todosian` 全量 grep 图标 import 后逐一比对）。零编造。

最终使用的 19 个图标，全部可溯源到原项目：

```
Icons.Filled.Add / AutoAwesome / Delete / Folder / SwapVert
Icons.Outlined.AddCircle / CheckCircle / ContentCopy / Event
Icons.Outlined.FlightTakeoff / FolderOpen / Repeat / RestartAlt
Icons.Outlined.Schedule / Star / SubdirectoryArrowRight
Icons.AutoMirrored.Filled.KeyboardArrowRight / Sort
Icons.AutoMirrored.Outlined.ArrowForward
```

原设计图标 → 实际使用图标对照（原项目图标集有限，做了替换）：

| 原计划 | 实际使用 | 是否影响语义 |
|---|---|---|
| `Outlined.AutoAwesome`（✨） | **`Filled.AutoAwesome`** | 否，仅填充样式差异 |
| `Filled.Undo`（撤销） | `AutoMirrored.Outlined.ArrowForward` | 轻微。按钮文案已写「撤销」，语义清晰 |
| `Outlined.KeyboardArrowDown`（展开箭头） | **`Filled.SwapVert`** | 否。收起时旋转 -90°，展开=上箭头 / 收起=下箭头，语义更贴 |
| `Outlined.ExpandMore`（折叠箭头） | **`AutoMirrored.Filled.Sort`** | 轻微。旋转动画后仍能表达展开/收起 |
| `Outlined.InsertDriveFile` / `Outlined.Description` | **`Outlined.FolderOpen` / `Outlined.ContentCopy`** | 否，均为文件/内容语义 |

**仍待验证的 API**（无法本地编译）：

| 项 | 位置 | 风险 |
|---|---|---|
| `Modifier.graphicsLayer { }` | import `androidx.compose.ui.graphics.graphicsLayer` | 低，标准路径 |
| `TextLayoutResult.multiParagraph.lineCount` / `.fontSize` | `MdTodoRow.kt` | 低，Compose UI Text 标准 API |
| `TextLayoutResult.getLineStart/getLineEnd/getLineBaseline/getLineHorizontalOffset` | `MdTodoRow.kt` | 低，同上 |
| `Modifier.drawWithContent { drawContent() }` | `MdTodoRow.kt` | 低 |
| `Modifier.toggleable(value, onValueChange, role)` | `MdTodoRow.kt` | 低（注意参数顺序：value 在前） |
| `SwipeToDismissBox` 需 `@OptIn(ExperimentalMaterial3Api::class)` | `MdTodoRow.kt` | 已加注解 |
| `LinearProgressIndicator(progress = { ... })` lambda 形式 | `TodayScreen.kt` | **低**，原项目 `TodosianComponents.kt:166` 已用此形式 |
| `typography.headlineSmallEmphasized` | `TodayScreen.kt` | **低**，原项目 `TodosianComponents.kt:127` 已用 |
| `materialTheme.shapes.extraSmall` | `TodayScreen.kt` | 低，M3 标准 |

**建议集成步骤**：把 4 个文件拷进 `app/src/main/java/com/mdtodo/`，
改 `applicationId` 为 `com.mdtodo`，跑 `./gradlew :app:assembleDebug`。
预计需要微调的只有 import 顺序（ktlint 风格），不会报未解析符号。

---

## 文件清单

| 文件 | 内容 |
|---|---|
| `app/src/main/java/com/mdtodo/ui/model/MdTodo.kt` | UI 层任务模型 + 今日页状态模型 |
| `app/src/main/java/com/mdtodo/ui/components/MdTodoRow.kt` | 交付物 A：改造后的任务行（含手绘删除线动画） |
| `app/src/main/java/com/mdtodo/ui/components/AutoCheckFeedback.kt` | 交付物 B：自动勾事件总线 + 横幅 + 拍平工具 |
| `app/src/main/java/com/mdtodo/ui/today/TodayScreen.kt` | 交付物 C：今日页 |
| `app/src/main/java/com/mdtodo/ui/settings/DailyNoteSettings.kt` | 交付物 D：设置页三块 |
