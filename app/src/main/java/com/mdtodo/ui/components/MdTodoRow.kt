package com.mdtodo.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mdtodo.data.model.TasksPriority
import com.mdtodo.data.model.Todo
import com.mdtodo.ui.model.MdTodo

/* ==========================================================================
 * MD Todo —— 改造后的任务行（交付物 A）
 * ==========================================================================
 *
 * 与原 todosian TodoRow 的复用关系：
 *   直接复用：SwipeToDismissBox 删除/移动、AssistChip 元信息行、优先级圆点、
 *             MaterialTheme.shapes.large 作为圆角卡片形状。
 *   替换实现：TextDecoration.LineThrough -> 手绘删除线动画（原版瞬间出现，无法动）
 *             Checkbox -> MdCheckbox（区分手动 / 自动两种来源）
 *   全新增加：父子层级（缩进 + L 形引导线 + 展开箭头）
 *
 * 为什么原项目"没有父子 UI"：
 *   原 TodoRow 只有 `indentLevel * 12dp` 的左缩进（见原文件 L85），
 *   CategoryScreen 直接 items(sortAllTodos) 平铺，Markdown 的缩进在 UI 上
 *   只体现为"看起来靠左一点"，没有父子语义、没有展开收起、没有"子任务全完成"。
 *   所以层级部分必须新建，数据层用 MdTodo(depth/childCount/…) 显式喂进来。
 */

object MdDimens {
    /** 行最小高度。用 height(IntrinsicSize.Min) + heightIn 双保险，大字体时行会自然长高 */
    val RowMinHeight: Dp = 56.dp
    val RowVerticalPadding: Dp = 10.dp

    /** 每级缩进 20dp，最多 3 级（0/1/2），超过按 3 级算，避免深层缩进把文字挤没 */
    val IndentPerLevel: Dp = 20.dp
    val IndentMaxLevels: Int = 3

    /** 引导线槽位宽：竖线所在区域 */
    val GuideAreaWidth: Dp = 24.dp
    val GuideLineWidth: Dp = 2.dp

    /** 展开箭头槽位 48dp（= 触摸最小尺寸），子项文字要和父项文字左对齐 */
    val ExpandSlotWidth: Dp = 48.dp

    /** 勾选框视觉 28dp，触摸槽位 48dp（单手操作硬指标，不可下调） */
    val CheckVisualSize: Dp = 28.dp
    val CheckTouchSize: Dp = 48.dp

    /** 删除线基础粗细，实际会随字号缩放 */
    val StrikeWidth: Dp = 1.5.dp
}

/**
 * 任务行。
 *
 * @param item 包装后的任务（含父子信息与勾选来源）
 * @param autoCheckedPulse 由 [com.mdtodo.ui.components.AutoCheckBus] 注入：
 *        true 表示"刚刚（<1s）被自动勾上"，用来触发整行闪光 + 复选框放大。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
) {
    val rowShape = MaterialTheme.shapes.large

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> onRequestDelete()
                SwipeToDismissBoxValue.StartToEnd -> onRequestMove?.invoke()
                else -> Unit
            }
            false
        },
    )

    val indent = if (showHierarchyGuides) depthIndent(item.depth) else 0.dp

    SwipeToDismissBox(
        modifier = modifier.padding(start = indent),
        state = dismissState,
        enableDismissFromStartToEnd = onRequestMove != null && item.depth == 0,
        backgroundContent = {
            MdSwipeBackground(dismissState = dismissState, shape = rowShape)
        },
        content = {
            AutoCheckedFlash(active = autoCheckedPulse) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        // IntrinsicSize.Min 让引导线能撑满整行高度；
                        // heightIn 只是保底，两者叠加大字体也不会裁字。
                        .height(IntrinsicSize.Min)
                        .heightIn(min = MdDimens.RowMinHeight)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = rowShape,
                        )
                        .clip(rowShape)
                        .clickable(onClick = onEdit)
                        .padding(end = 4.dp),
                ) {
                    // —— 层级引导线 ——
                    if (showHierarchyGuides && item.depth > 0) {
                        HierarchyGuide(
                            depth = item.depth,
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(MdDimens.GuideAreaWidth),
                        )
                    }

                    // —— 展开/收起 ——
                    if (item.isParent) {
                        ExpandToggle(
                            expanded = item.isExpanded,
                            onClick = onToggleExpand,
                        )
                    } else if (showHierarchyGuides && item.depth > 0) {
                        // 占位，保证"无子任务的子项"与"有子任务的父项"文字左对齐
                        Spacer(Modifier.width(MdDimens.ExpandSlotWidth))
                    }

                    MdCheckbox(
                        item = item,
                        onToggle = onToggle,
                        pulseScale = if (autoCheckedPulse) 1.18f else 1f,
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(
                                start = 4.dp,
                                top = MdDimens.RowVerticalPadding,
                                bottom = MdDimens.RowVerticalPadding,
                            ),
                    ) {
                        MdTodoText(
                            item = item,
                            modifier = Modifier.padding(end = 4.dp),
                        )

                        if (enableTasksPluginSupport) {
                            val chips = buildMdMetaChips(
                                todo = item.todo,
                                useEmojisInUi = useEmojisInUi,
                            )
                            if (chips.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                ) {
                                    chips.forEach { chip ->
                                        AssistChip(
                                            onClick = {},
                                            enabled = false,
                                            colors = chip.color?.let { c ->
                                                AssistChipDefaults.assistChipColors(
                                                    containerColor = c,
                                                    labelColor = MaterialTheme.colorScheme.onSurface,
                                                    leadingIconContentColor = MaterialTheme.colorScheme.onSurface,
                                                )
                                            } ?: AssistChipDefaults.assistChipColors(),
                                            leadingIcon = chip.icon?.let { icon ->
                                                {
                                                    Icon(
                                                        imageVector = icon,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                            },
                                            label = {
                                                Text(
                                                    text = chip.label,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 自动完成的持久标记
                    if (item.isAutoChecked) {
                        AutoCheckedBadge(modifier = Modifier.padding(start = 4.dp))
                    }

                    if (showSubtaskButton && onAddSubtask != null) {
                        Box(
                            modifier = Modifier
                                .size(MdDimens.CheckTouchSize)
                                .clip(CircleShape)
                                .clickable(onClick = onAddSubtask),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.SubdirectoryArrowRight,
                                contentDescription = "添加子任务",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
    )
}

/* ==========================================================================
 * 删除线动画
 * ==========================================================================
 * 为什么不用 TextDecoration.LineThrough：
 *   1) 它瞬间出现 / 瞬间消失，做不到"平滑展开"；
 *   2) 颜色跟随 Text 的 color，无法单独给删除线染色（自动勾需要区分色）；
 *   3) 无法按行控制绘制进度。
 * 方案：drawWithContent 在文字之上画线，位置用 TextLayoutResult 对齐，
 *      进度由 Animatable 驱动，取消勾选时反向"擦掉"。
 */
@Composable
private fun MdTodoText(
    item: MdTodo,
    modifier: Modifier = Modifier,
) {
    val isDone = item.isDone
    val strike = remember { Animatable(if (isDone) 1f else 0f) }

    LaunchedEffect(isDone) {
        strike.animateTo(
            targetValue = if (isDone) 1f else 0f,
            animationSpec = tween(
                durationMillis = if (isDone) 320 else 200,
                easing = FastOutSlowInEasing,
            ),
        )
    }

    val textColor by animateColorAsState(
        targetValue = if (isDone) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(300),
        label = "md-todo-text-color",
    )

    // 手动勾=中性灰删除线；自动勾=tertiary 色删除线。颜色本身就是"来源"信号
    val strikeColor by animateColorAsState(
        targetValue = if (item.isAutoChecked) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(320),
        label = "md-strike-color",
    )

    val dotColor = priorityDotColor(item.todo.priority)

    // TextLayoutResult 只能通过 onTextLayout 拿到，保存到 state 供 drawWithContent 读取
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color = dotColor, shape = CircleShape),
            )
        }
        Text(
            text = item.text,
            style = MaterialTheme.typography.bodyLarge,
            color = textColor,
            onTextLayout = { result -> layout = result },
            modifier = Modifier
                .weight(1f, fill = false)
                .drawStrikethrough(
                    progress = { strike.value },
                    color = strikeColor,
                    strokeWidth = MdDimens.StrikeWidth,
                    layoutResult = { layout },
                ),
        )
    }
}

private fun Modifier.drawStrikethrough(
    progress: () -> Float,
    color: Color,
    strokeWidth: Dp,
    layoutResult: () -> TextLayoutResult?,
): Modifier = drawWithContent {
    drawContent()
    val p = progress().coerceIn(0f, 1f)
    if (p <= 0.001f) return@drawWithContent
    val result = layoutResult() ?: return@drawWithContent
    val lineCount = result.multiParagraph.lineCount
    if (lineCount <= 0) return@drawWithContent

    // Compose 1.12 的 TextLayoutResult 没有 fontSize 属性，
    // 也没有 getLineHorizontalOffset / 单参 getLineEnd。
    // 真实签名（javap 实测）：
    //   getLineTop(line): Float / getLineBottom(line): Float
    //   getLineLeft(line): Float / getLineEnd(line, <Boolean>): Int
    // ⚠️ 第二个参数用位置传入：Kotlin 侧的具名参数名与 javap 显示的不一致，
    //    具名调用会报 NAMED_PARAMETER_NOT_FOUND。
    //    语义：是否把行尾空白计入长度（false = 不计）。
    val baseStroke = strokeWidth.toPx()

    // 防御钳制（ANR 排查补丁，2026-10-14）：
    // RenderThread 100% 的疑似路径之一是异常内容导致巨大线宽/坐标，
    // GPU 光栅化异常几何会卡死渲染线程。全部值域钳死，最坏情况是"线画错"，不是"线程挂死"。
    // 行数超限直接放弃逐行动画 —— 宁可丢动画，不赌渲染线程。
    if (lineCount > 200) return@drawWithContent

    for (line in 0 until lineCount) {
        val start = result.getLineStart(line)
        val end = result.getLineEnd(line, false)
        if (end <= start) continue

        val baseline = result.getLineBaseline(line)
        val lineTop = result.getLineTop(line)
        // 行高近似字号，删除线位置按此比例上移
        val lineHeight = (baseline - lineTop).coerceIn(0f, 200f)
        val strokePx = (baseStroke * (1f + lineHeight / 32f)).coerceIn(0.5f, 24f)

        // 中线略高于基线，和主流 UI 库的删除线位置一致
        val y = baseline - lineHeight * 0.28f
        val x0 = result.getLineLeft(line)
        val x1 = (x0 + (end - start) * p).coerceAtMost(size.width * 2f)
        drawLine(
            color = color,
            start = Offset(x0, y),
            end = Offset(x1, y),
            strokeWidth = strokePx,
            cap = StrokeCap.Round,
        )
    }
}

/* ==========================================================================
 * 复选框：手动 vs 自动
 * ==========================================================================
 * 手动勾 -> 标准 M3 Checkbox，primary 填充 + 对勾
 * 自动勾 -> tertiary 实心圆 + AutoAwesome 图标 + 外面一圈"子任务完成度"进度环
 *          + 行尾常驻「自动完成」标签
 * 三重信号（形状 / 颜色 / 文案），任何一项单独都能区分，不依赖颜色盲用户也能分辨。
 */
@Composable
private fun MdCheckbox(
    item: MdTodo,
    onToggle: () -> Unit,
    pulseScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = pulseScale,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 900f),
        label = "md-check-scale",
    )

    Box(
        modifier = modifier
            .size(MdDimens.CheckTouchSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // toggleable 而非 clickable：TalkBack 会读成"复选框，已勾选"
            .toggleable(
                value = item.isDone,
                onValueChange = { onToggle() },
                role = Role.Checkbox,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (item.isAutoChecked) {
            AutoCheckMark(progress = item.childProgress)
        } else {
            Checkbox(
                checked = item.isDone,
                onCheckedChange = null, // 已在 toggleable 处理，避免双触发
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

@Composable
private fun AutoCheckMark(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f)
    val fillColor = MaterialTheme.colorScheme.tertiary
    val onFill = MaterialTheme.colorScheme.onTertiary
    val ring by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "auto-check-ring",
    )

    Box(
        modifier = modifier.size(MdDimens.CheckVisualSize),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 2.dp.toPx())
            drawCircle(color = trackColor, style = stroke)
            drawArc(
                color = fillColor,
                startAngle = -90f,
                sweepAngle = 360f * ring.coerceIn(0f, 1f),
                useCenter = false,
                style = stroke,
            )
        }
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(color = fillColor, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = "自动完成",
                tint = onFill,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun AutoCheckedBadge(modifier: Modifier = Modifier) {
    AssistChip(
        onClick = {},
        enabled = false,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
            leadingIconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        },
        label = {
            Text(
                text = "自动完成",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        },
        modifier = modifier.heightIn(min = 32.dp),
    )
}

/* ==========================================================================
 * 层级引导线 + 展开箭头
 * ========================================================================== */

@Composable
private fun HierarchyGuide(
    depth: Int,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        val lw = MdDimens.GuideLineWidth.toPx()
        // 一级子项的竖线偏左（贴着父任务勾选框），更深层级居中，形成树状观感
        val x = if (depth == 1) size.width * 0.3f else size.width * 0.5f
        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = lw,
        )
        drawLine(
            color = color,
            start = Offset(x, size.height * 0.5f),
            end = Offset(size.width, size.height * 0.5f),
            strokeWidth = lw,
        )
    }
}

@Composable
private fun ExpandToggle(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "expand-arrow-rotation",
    )
    Box(
        modifier = modifier
            .size(MdDimens.CheckTouchSize)
            .toggleable(
                value = expanded,
                onValueChange = { onClick() },
                role = Role.Switch,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.SwapVert,
            contentDescription = if (expanded) "收起子任务" else "展开子任务",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(24.dp)
                .rotate(rotation),
        )
    }
}

/* ==========================================================================
 * 自动勾瞬间的整行闪光（交付物 B 的基础件）
 * ========================================================================== */

@Composable
internal fun AutoCheckedFlash(
    active: Boolean,
    content: @Composable () -> Unit,
) {
    val base = MaterialTheme.colorScheme.surfaceContainer
    val flash = MaterialTheme.colorScheme.tertiaryContainer
    val anim = remember { Animatable(0f) }

    LaunchedEffect(active) {
        if (active) {
            anim.snapTo(0f)
            anim.animateTo(1f, animationSpec = tween(160, easing = FastOutSlowInEasing))
            anim.animateTo(0f, animationSpec = tween(620, easing = FastOutSlowInEasing))
        }
    }

    val container by animateColorAsState(
        targetValue = lerpColor(base, flash, anim.value),
        animationSpec = tween(60),
        label = "auto-checked-flash",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(container),
    ) {
        content()
    }
}

/* ==========================================================================
 * 工具
 * ========================================================================== */

internal fun depthIndent(depth: Int): Dp =
    MdDimens.IndentPerLevel * depth.coerceIn(0, MdDimens.IndentMaxLevels)

internal fun lerpColor(start: Color, end: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + (end.red - start.red) * f,
        green = start.green + (end.green - start.green) * f,
        blue = start.blue + (end.blue - start.blue) * f,
        alpha = start.alpha + (end.alpha - start.alpha) * f,
    )
}

/**
 * 优先级圆点色。
 * 原项目用的是 res/values/colors.xml 的固定色（priority_high 等），
 * 在动态取色（Android 12+ wallpaper）下会和背景打架，
 * 这里改成从 colorScheme 派生，保证任何壁纸下都有对比度。
 */
@Composable
internal fun priorityDotColor(priority: TasksPriority?): Color? = when (priority) {
    TasksPriority.HIGHEST -> MaterialTheme.colorScheme.error
    TasksPriority.HIGH -> MaterialTheme.colorScheme.error.copy(alpha = 0.75f)
    TasksPriority.MEDIUM -> MaterialTheme.colorScheme.tertiary
    TasksPriority.LOW -> MaterialTheme.colorScheme.secondary
    TasksPriority.LOWEST -> MaterialTheme.colorScheme.outline
    TasksPriority.NONE, null -> null
}

internal data class MdMetaChipUi(
    val label: String,
    val icon: ImageVector?,
    val color: Color? = null,
)

/** 沿用原项目 buildTasksMetaChips 的行为，仅收窄入参类型 */
@Composable
internal fun buildMdMetaChips(
    todo: Todo,
    useEmojisInUi: Boolean,
): List<MdMetaChipUi> {
    val chips = ArrayList<MdMetaChipUi>(8)
    val prio = todo.priority
    if (prio != null && prio != TasksPriority.NONE) {
        chips.add(
            MdMetaChipUi(
                label = prio.name,
                icon = if (useEmojisInUi) null else Icons.Outlined.Star,
                color = priorityDotColor(prio),
            ),
        )
    }
    todo.dueDate?.let {
        chips.add(MdMetaChipUi("截止 $it", if (useEmojisInUi) null else Icons.Outlined.Event))
    }
    todo.scheduledDate?.let {
        chips.add(MdMetaChipUi("计划 $it", if (useEmojisInUi) null else Icons.Outlined.Schedule))
    }
    todo.startDate?.let {
        chips.add(MdMetaChipUi("开始 $it", if (useEmojisInUi) null else Icons.Outlined.FlightTakeoff))
    }
    todo.createdDate?.let {
        chips.add(MdMetaChipUi("创建 $it", if (useEmojisInUi) null else Icons.Outlined.AddCircle))
    }
    todo.completionDate?.let {
        chips.add(MdMetaChipUi("完成 $it", if (useEmojisInUi) null else Icons.Outlined.CheckCircle))
    }
    if (!todo.recurrence.isNullOrBlank()) {
        chips.add(MdMetaChipUi("重复 ${todo.recurrence}", if (useEmojisInUi) null else Icons.Outlined.Repeat))
    }
    return chips.filter { it.label.isNotBlank() }
}

/* ==========================================================================
 * 滑动背景
 * ========================================================================== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MdSwipeBackground(
    dismissState: SwipeToDismissBoxState,
    shape: Shape,
) {
    val direction = dismissState.dismissDirection
    val bg = when (direction) {
        SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
        SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val fg = when (direction) {
        SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.onErrorContainer
        SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon: ImageVector = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Icons.AutoMirrored.Outlined.ArrowForward
        else -> Icons.Filled.Delete
    }
    val reached = dismissState.targetValue != SwipeToDismissBoxValue.Settled
    val scale by animateFloatAsState(
        targetValue = if (reached) 1.25f else 0.85f,
        animationSpec = tween(180),
        label = "md-swipe-icon-scale",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = bg, shape = shape)
            .padding(horizontal = 20.dp),
        contentAlignment = when (direction) {
            SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
            else -> Alignment.CenterEnd
        },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = if (direction == SwipeToDismissBoxValue.StartToEnd) "移动" else "删除",
            tint = fg,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        )
    }
}
