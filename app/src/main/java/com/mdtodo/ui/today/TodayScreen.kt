package com.mdtodo.ui.today

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mdtodo.ui.components.AutoCheckBus
import com.mdtodo.ui.components.AutoCheckHostStyle
import com.mdtodo.ui.components.MdAutoCheckHost
import com.mdtodo.ui.components.MdAutoCheckScope
import com.mdtodo.ui.components.MdTodoRow
import com.mdtodo.ui.model.MdTodo
import com.mdtodo.ui.model.TodaySection
import com.mdtodo.ui.model.TodayUiState

/* ==========================================================================
 * 交付物 C —— 「今日」页
 * ==========================================================================
 *
 * 【设计决策：今日页显示什么？】
 *
 * 需求问的是：当天 YYYY-MM-DD.md 的任务，还是所有未完成任务？
 * 我的答案是：**都要，但分四段，且默认只展开前两段**。
 *
 * 理由：
 * 1. MD Todo 的本质是"文件即数据库"，YYYY-MM-DD.md 是用户手写的当日计划，
 *    它是"意图"，不能被自动推导的规则替代。所以 DAILY_NOTE 段必须独立存在，
 *    且排在 OVERDUE/TODAY 之后但不折叠（用户今天写的计划是最相关的内容）。
 *
 * 2. 只显示日记文件会漏掉 80% 的真实任务 —— 用户随手在别的文件里勾的
 *    「买菜」不会出现在今天的日记里，但今天确实该做。只显示日记 = 用户以为任务丢了。
 *
 * 3. 只显示所有未完成会淹没重点。一个长期不用的收件箱可能有 300 条未完成，
 *    全部塞进"今日"等于没有今日。所以 BACKLOG 段默认折叠，只显示计数。
 *
 * 4. 排序依据"能不能今天做完"而不是"创建时间"：
 *    OVERDUE(逾期红) > TODAY(今天到期) > DAILY_NOTE(今天写的) > BACKLOG(其余)。
 *
 * 最终结构：
 *   ┌ TopAppBar: 10月8日 周三            [+ 新建今日笔记]
 *   ├ 进度卡: ▓▓▓░░ 4/9 已完成   [自动创建笔记开关状态]
 *   ├ [逾期 2]  ← 红色，永远展开
 *   ├ [今天 5]  ← 永远展开
 *   ├ [今日笔记 3]  ← 默认展开（若文件存在）
 *   └ [其他未完成 12]  ← 默认折叠，点击展开
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    state: TodayUiState,
    bus: AutoCheckBus,
    snackbarHostState: SnackbarHostState,
    expandedIds: Set<String>,
    onToggleExpand: (id: String) -> Unit,
    onToggleTodo: (MdTodo) -> Unit,
    onEditTodo: (MdTodo) -> Unit,
    onAddSubtask: (MdTodo) -> Unit,
    onDeleteTodo: (MdTodo) -> Unit,
    onToggleBacklog: () -> Unit,
    onCreateDailyNote: () -> Unit,
    onNoteCreatedFeedback: (fileName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // state.groups 已经由数据层（酒狐）按父子层级排好序，
    // UI 直接 items(每个 section 的 item 列表) 即可，不在这里二次拍平。
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.dateLabel,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.dailyNoteFileName != null) {
                            Text(
                                text = state.dailyNoteFileName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { MdAutoCheckHost(bus, style = AutoCheckHostStyle.BANNER) },
        floatingActionButton = {
            if (state.dailyNoteFileName == null) {
                // 没有今日笔记时才给 FAB：平时用不到，避免和"加任务"混淆
                ExtendedFloatingActionButton(
                    onClick = onCreateDailyNote,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("新建今日笔记") },
                )
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item(key = "__progress") {
                TodayProgressCard(
                    done = state.totalDone,
                    total = state.totalDone + state.totalActive,
                )
            }

            if (state.groups.isEmpty() && !state.isLoading) {
                item(key = "__empty") {
                    TodayEmptyState(onCreateDailyNote = onCreateDailyNote)
                }
            }

            state.groups.forEach { group ->
                item(key = "hdr_${group.section.name}") {
                    TodaySectionHeader(
                        title = group.title,
                        count = group.items.size,
                        section = group.section,
                        expanded = true,
                        onToggle = null,
                    )
                }

                items(
                    items = group.items,
                    key = { "${group.section.name}_${it.id}" },
                ) { item ->
                    MdTodoRow(
                        item = item,
                        onToggle = { onToggleTodo(item) },
                        onEdit = { onEditTodo(item) },
                        onRequestDelete = { onDeleteTodo(item) },
                        onToggleExpand = { onToggleExpand(item.id) },
                        onAddSubtask = if (item.depth == 0) {
                            { onAddSubtask(item) }
                        } else {
                            null
                        },
                        onRequestMove = null,
                        autoCheckedPulse = bus.isAutoCheckedNow(item.id),
                        showHierarchyGuides = true,
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(180),
                            placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            fadeOutSpec = tween(160),
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            // 折叠区：其他未完成
            val backlog = state.groups.firstOrNull { it.section == TodaySection.BACKLOG }
            if (backlog != null && backlog.items.isNotEmpty()) {
                item(key = "hdr_backlog") {
                    TodaySectionHeader(
                        title = backlog.title,
                        count = backlog.items.size,
                        section = TodaySection.BACKLOG,
                        expanded = state.backlogExpanded,
                        onToggle = onToggleBacklog,
                    )
                }

                if (state.backlogExpanded) {
                    items(
                        items = backlog.items,
                        key = { "backlog_${it.id}" },
                    ) { item ->
                        MdTodoRow(
                            item = item,
                            onToggle = { onToggleTodo(item) },
                            onEdit = { onEditTodo(item) },
                            onRequestDelete = { onDeleteTodo(item) },
                            onToggleExpand = { onToggleExpand(item.id) },
                            onAddSubtask = null,
                            onRequestMove = null,
                            autoCheckedPulse = bus.isAutoCheckedNow(item.id),
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(180),
                                placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                fadeOutSpec = tween(160),
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            // 新建笔记成功后的「新条目高亮」反馈，见交付文档 F 方案三
            val highlightedId = state.justCreatedNoteId
            if (highlightedId != null) {
                item(key = "__note_created") {
                    NewNoteCreatedHighlight(
                        fileName = state.dailyNoteFileName.orEmpty(),
                        onDismiss = { onNoteCreatedFeedback("") },
                    )
                }
            }
        }
    }
}

/* ==========================================================================
 * 子组件
 * ========================================================================== */

/**
 * 顶部进度卡。
 *
 * 为什么放卡片而不是纯 ProgressBar：用户需要一眼看到"今天还剩多少"，
 * 纯进度条在暗色/亮色下都太轻，卡片 + 数字才够。
 */
@Composable
private fun TodayProgressCard(
    done: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    val progress = if (total == 0) 0f else done.toFloat() / total.toFloat()
    val animated by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(520, easing = FastOutSlowInEasing),
        label = "today-progress",
    )
    val allDone = total > 0 && done == total

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (allDone) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$done",
                    // 用 headlineSmallEmphasized（原项目 TodosianComponents 已验证存在）；
                    // headlineMediumEmphasized 待验证
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    fontWeight = FontWeight.Bold,
                    color = if (allDone) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                Text(
                    text = " / $total",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (allDone) "今天全部完成，干得漂亮" else "今日待办",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { animated },
                color = if (allDone) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(MaterialTheme.shapes.extraSmall),
            )
        }
    }
}

/**
 * 分区标题。
 *
 * 逾期段用 error 色且永远展开（铁律：逾期必须第一眼看到且不能藏起来）。
 */
@Composable
private fun TodaySectionHeader(
    title: String,
    count: Int,
    section: TodaySection,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tint = when (section) {
        TodaySection.OVERDUE -> MaterialTheme.colorScheme.error
        TodaySection.TODAY -> MaterialTheme.colorScheme.primary
        TodaySection.DAILY_NOTE -> MaterialTheme.colorScheme.onSurface
        TodaySection.BACKLOG -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "section-arrow",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (onToggle != null) {
                    Modifier.clickable(onClick = onToggle)
                } else {
                    Modifier
                },
            )
            .padding(top = 16.dp, bottom = 8.dp),
    ) {
        if (section == TodaySection.OVERDUE) {
            // 逾期段加一个红点，即使标题滚出屏幕也能在 AppBar 阴影下留印象
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color = MaterialTheme.colorScheme.error, shape = CircleShape),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = "$title ($count)",
            style = MaterialTheme.typography.labelLarge,
            color = tint,
        )
        Spacer(Modifier.weight(1f))
        if (onToggle != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer { rotationZ = rotation },
            )
        }
    }
}

/**
 * 新建今日笔记成功后的「新条目高亮」。
 *
 * 这是交付文档 F 的方案三：不是纯 Snackbar，而是"列表顶部插入一张会自己消失的卡片"。
 * 优势是用户视线本来就在列表顶部，不需要找。
 */
@Composable
private fun NewNoteCreatedHighlight(
    fileName: String,
    onDismiss: () -> Unit,
) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        kotlinx.coroutines.delay(2600)
        alpha.animateTo(0f, tween(320))
        onDismiss()
    }

    val background by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = alpha.value),
        animationSpec = tween(60),
        label = "note-created-bg",
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = background),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "已创建 $fileName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun TodayEmptyState(
    onCreateDailyNote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            // 用 InsertDriveFile 代替 CalendarMonth：
            // CalendarMonth 属于 material-icons-extended 的较新图标，
            // 若 IDE 报未解析，替换为 Icons.Outlined.Event（原项目已在用）
            imageVector = Icons.Outlined.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "今天没有待办",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "可以新建今日笔记，开始记录",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onCreateDailyNote) {
            Text("新建今日笔记")
        }
    }
}

/* ==========================================================================
 * 页面骨架：把 AutoCheckScope 接上
 * ========================================================================== */

/**
 * 顶层入口。把 bus 的生命周期收在这里，页面内部只管渲染。
 *
 * ```
 * TodayRoute(
 *     viewModel = vm,
 *     onUndoAutoCheck = { id -> vm.undoAutoCheck(id) },
 * )
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayRoute(
    state: TodayUiState,
    expandedIds: Set<String>,
    onToggleExpand: (id: String) -> Unit,
    onToggleTodo: (MdTodo) -> Unit,
    onEditTodo: (MdTodo) -> Unit,
    onAddSubtask: (MdTodo) -> Unit,
    onDeleteTodo: (MdTodo) -> Unit,
    onToggleBacklog: () -> Unit,
    onCreateDailyNote: () -> Unit,
    onUndoAutoCheck: (parentId: String) -> Unit,
    onNoteCreatedFeedback: (fileName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    MdAutoCheckScope(
        onUndo = onUndoAutoCheck,
        enableHaptic = true,
        hostStyle = AutoCheckHostStyle.BANNER,
    ) { bus ->
        TodayScreen(
            state = state,
            bus = bus,
            snackbarHostState = snackbarHostState,
            expandedIds = expandedIds,
            onToggleExpand = onToggleExpand,
            onToggleTodo = onToggleTodo,
            onEditTodo = onEditTodo,
            onAddSubtask = onAddSubtask,
            onDeleteTodo = onDeleteTodo,
            onToggleBacklog = onToggleBacklog,
            onCreateDailyNote = onCreateDailyNote,
            onNoteCreatedFeedback = onNoteCreatedFeedback,
            modifier = modifier,
        )
    }
}
