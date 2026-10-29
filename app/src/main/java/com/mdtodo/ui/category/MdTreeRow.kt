package com.mdtodo.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.mdtodo.utils.rememberMarkdownText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.mdtodo.R
import com.mdtodo.ui.components.TodoSwipeBackground
import com.mdtodo.data.model.Todo
import com.mdtodo.data.model.TodoNode
import com.mdtodo.ui.model.MdTodo

/**
 * MD Todo：把父子树摊平成可直接喂给 LazyColumn 的列表。
 *
 * ## 为什么需要摊平
 * Compose 的 `items()` 只接受扁平的 List<T>，没有原生的「递归渲染树」能力。
 * 常见做法是自己写 `forEach` 递归 Composable，但它无法享受 LazyColumn 的
 * 惰性回收——文件里有几百条待办时会一次性全部组合，滚动明显卡顿。
 *
 * 做法：递归展开成带`depth` 的 List，交给 LazyColumn 惰性渲染。
 * 折叠时只吐出根节点，展开时吐出整棵子树。
 *
 * ## 关于折叠状态
 * 用 `remember(node.id)` 保存展开状态，切到别的分类再回来时状态会丢
 * （因为 LazyColumn 的 item key 变了）。若以后发现体验不对，
 * 可改成把展开状态提到 ViewModel 的 `expandedIds: Set<String>`。
 */
/**
 * 树节点渲染项：MdTodo + 稳定节点 id。
 *
 * 为什么需要 nodeId 单独传：`Todo.id` 是 UUID.randomUUID()（原项目如此），
 * 每次解析都会变；而 `TodoNode.id` 是 StableTodoId 从「父id+文本+序号」生成的
 * 稳定 id。展开/折叠状态必须挂在稳定 id 上，否则刷新一次状态就丢。
 * 这也顺带说明 LazyColumn 的 key 不该用 Todo.id——那是原项目的潜在缺陷，
 * 本文件的 items key 改用 nodeId。
 */
data class MdTreeItem(
    val md: MdTodo,
    val nodeId: String,
)

/**
 * 把父子树摊平成可直接喂给 LazyColumn 的列表。
 *
 * [collapsedIds] 存的是**被收起**的节点 id（而不是被展开的）。
 *
 * 为什么用「收起集」而不是「展开集」：默认状态必须是**全展开**。
 * 用展开集时默认空集 = 全部收起，带子任务的文件一进来像「子任务没了」；
 * 尤其「父 [x] + 子 [ ]」这类文件，子任务会被完全藏在收起态后面。
 * 收起集默认空 = 全展开，语义正好相反且更安全。
 */
fun flattenTodoTree(
    roots: List<TodoNode>,
    allTodos: List<Todo>,
    collapsedIds: Set<String>,
    depthLimit: Int = Int.MAX_VALUE,
): List<MdTreeItem> = buildList {
    fun walk(node: TodoNode, depth: Int) {
        val todo = allTodos.firstOrNull { it.lineIndex == node.lineIndex } ?: return
        val expanded = !collapsedIds.contains(node.id)
        add(
            MdTreeItem(
                md = MdTodo(
                    todo = todo,
                    childCount = node.children.size,
                    doneChildCount = node.children.count { it.isDone },
                    // T3：不再推导 checkSource。
                    // 原实现「isDone && allChildrenDone → AUTO」是**状态推导**：
                    // 用户手勾一个「子任务本来就全完成」的父任务，同样会被判成 AUTO。
                    // Markdown 不记录「谁勾的」，任何常驻标记都是会话级猜测，迟早骗人。
                    // 改用默认值 MANUAL，不显示任何来源标记。
                    depth = depth,
                    isExpanded = expanded,
                ),
                nodeId = node.id,
            ),
        )
        if (expanded && depth < depthLimit) {
            node.children.forEach { walk(it, depth + 1) }
        }
    }
    roots.forEach { walk(it, 0) }
}

/** 递归收集某个节点下所有子孙的 lineIndex（含自身），用于删除时连带确认。 */
fun TodoNode.collectLineIndices(): Set<Int> =
    buildSet {
        fun walk(n: TodoNode) {
            add(n.lineIndex)
            n.children.forEach { walk(it) }
        }
        walk(this@collectLineIndices)
    }

/**
 * 单个树节点行。
 *
 * T1 修复（2026-10-27）：原来签名只有三个回调（勾选/点击/展开），
 * **没有删除、移动、加子任务**。带子任务的文件走树渲染，于是这类文件里
 * 删不掉任务，而 enableSubtasks 默认 false 时加子任务入口 100% 不可达。
 *
 * 现在补上三个可选回调，并用 SwipeToDismissBox 承载手势：
 * - 左滑 → 删除（弹确认框，不真删）
 * - 右滑 → 移动（仅 depth == 0）
 * - 行尾 + → 加子任务
 *
 * 注意 confirmValueChange 恒返回 false：只借手势触发回调，
 * 行本身绝不真正 dismiss（真删由 ViewModel 写盘后刷新列表）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MdTreeRow(
    item: MdTodo,
    enableTasksPluginSupport: Boolean,
    useEmojisInUi: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onToggleExpand: () -> Unit,
    onRequestDelete: (() -> Unit)? = null,
    onRequestMove: (() -> Unit)? = null,
    onAddSubtask: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isParent = item.isParent
    val indent = (item.depth * 16).dp
    val canMove = item.depth == 0 && onRequestMove != null
    // T8：层级引导线颜色。原 MdTreeRow.kt 注释声称有竖线，实现里其实没有。
    val guideColor = MaterialTheme.colorScheme.outlineVariant

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> onRequestDelete?.invoke()
                SwipeToDismissBoxValue.StartToEnd -> if (canMove) onRequestMove?.invoke()
                else -> Unit
            }
            false
        },
    )

    SwipeToDismissBox(
        modifier = modifier,
        state = dismissState,
        enableDismissFromEndToStart = onRequestDelete != null,
        enableDismissFromStartToEnd = canMove,
        backgroundContent = {
            TodoSwipeBackground(
                dismissState = dismissState,
                shape = MaterialTheme.shapes.large,
            )
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 基线 A5：不挂 animateContentSize()——与 animateItem 叠加是抖动放大器
                .drawBehind {
                    // T8：每级缩进区画一条 1.5dp 竖线，让层级一眼可见
                    if (item.depth > 0) {
                        val x = (item.depth * 16).dp.toPx() - 8.dp.toPx()
                        drawLine(
                            color = guideColor,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                    }
                }
                // 必须有不透明底色：否则 SwipeToDismissBox 的背景层（删除/移动图标）
                // 会透过透明的内容显示出来，看起来像行尾挂着两个图标。
                // 用 surface 而不是 surfaceContainer —— 保持平铺，不做成卡片。
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onClick)
                .padding(start = indent, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        // 展开箭头：绑定点击展开/折叠（v4.1 修复：原来只是图标，点了没反应）
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(
                    onClick = onToggleExpand,
                    enabled = isParent,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isParent) {
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = stringResource(
                        if (item.isExpanded) R.string.cd_collapse_subtasks
                        else R.string.cd_expand_subtasks
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(if (item.isExpanded) 0f else -90f),
                )
            }
        }

        // 复选框
        MdCheckbox(
            checked = item.isDone,
            onCheckedChange = { onToggle() },
        )

        Spacer(Modifier.width(8.dp))

        // 正文
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val displayText = if (enableTasksPluginSupport && useEmojisInUi) {
                    item.text
                } else {
                    // 不显示 emoji 时把元数据前缀从标题里剥掉，
                    // 否则会出现「标题里带📅 但用户关了 emoji 显示」的错位
                    stripMetaPrefix(item.text)
                }
                Text(
                    // Markdown 内联渲染：`**标题**` 显示成真粗体，星号不再露出来
                    text = rememberMarkdownText(displayText),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isParent) FontWeight.Medium else FontWeight.Normal,
                    textDecoration = if (item.isDone) TextDecoration.LineThrough else null,
                    color = if (item.isDone) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    // 单条任务最多两行。用户的描述动辄几十上百字，
                    // 全展开会让一屏只看得到两条待办；完整内容进详情页看。
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (isParent) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${item.doneChildCount}/${item.childCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // T1：行尾「加子任务」入口（原来树行完全没有这个能力）
        if (onAddSubtask != null) {
            IconButton(
                onClick = onAddSubtask,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.cd_add_subtask),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        }
    }
}

/**
 * 复选框。
 *
 * v4.1 修复：原实现未勾选状态画的是 Color.Transparent——视觉上完全不可见，
 * 用户不知道哪里能点。现在未勾选画 outline 色圆角方框，勾选画 primary 实心+对勾。
 * 视觉 24dp + 外层 44dp 触控区（Material 建议最小 48dp，行高约束下取 44dp）。
 */
@Composable
private fun MdCheckbox(
    checked: Boolean,
    onCheckedChange: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onCheckedChange() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(
                    color = if (checked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                    shape = RoundedCornerShape(5.dp),
                )
                .then(
                    if (!checked) {
                        Modifier.border(
                            width = 1.5.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(5.dp),
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * 剥掉标题里的 Obsidian Tasks 元数据前缀（📅🛫⏳✅➕🔺等）。
 *
 * 原项目的 TodoRow 在关闭 emoji 显示时不做剥离，导致列表里出现
 * 「任务名 📅 2026-10-12」而设置里却关着 emoji 显示的错位。
 */
private fun stripMetaPrefix(text: String): String =
    text.replace(Regex("""\s*(➕|📅|🛫|⏳|✅|🔺|🔫|🔼|🔽|🔬|🔁)\s*[^\s]*"""), "").trim()