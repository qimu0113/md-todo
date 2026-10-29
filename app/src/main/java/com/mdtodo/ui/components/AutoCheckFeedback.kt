package com.mdtodo.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import com.mdtodo.ui.model.MdTodo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ==========================================================================
 * 交付物 B —— 「子任务全勾 → 父任务自动勾」的瞬间反馈
 * ==========================================================================
 *
 * 四重反馈，弱→强叠加（任何一重单独都不够）：
 *
 *   1) 视觉  整行 tertiaryContainer 闪光（AutoCheckedFlash）+ 复选框弹到 1.18x
 *   2) 语义  复选框从「✓ 对勾」换成「✨ 圆环」，primary → tertiary
 *   3) 文案  顶部横幅「已自动完成「xxx」 子任务 3/3 全部完成」+「撤销」按钮，2.4s
 *   4) 触觉  震动一次（可在设置里关）
 *
 * 「撤销」是必须的：自动勾是系统替用户做决定，不给低成本反悔，
 * 用户会觉得 App 擅自改了他的 Markdown。
 */

/** 一次自动勾事件 */
data class AutoCheckedEvent(
    val parentId: String,
    val parentText: String,
    val doneChildren: Int,
    val totalChildren: Int,
)

/**
 * 自动勾事件总线。
 *
 * 为什么不用普通 state 透传：LazyColumn 里任意一行都可能触发，
 * 透传会把整棵列表拉进重组。改成 CompositionLocal + SnapshotStateMap 按 key 存，
 * 只有命中的那一行重组。
 */
@Stable
class AutoCheckBus internal constructor(
    private val hapticFeedback: () -> Unit,
) {
    /** 当前展示中的自动勾事件；null = 无 */
    var currentEvent: AutoCheckedEvent? by mutableStateOf(null)
        private set

    /** parentId -> 自动勾发生的时间戳，用于驱动行闪光脉冲 */
    private val pulses = mutableStateMapOf<String, Long>()

    private var undoHandler: ((String) -> Unit)? = null
    private var scope: CoroutineScope? = null

    internal fun attach(scope: CoroutineScope, undo: (String) -> Unit) {
        this.scope = scope
        this.undoHandler = undo
    }

    /** 该行是否处于自动勾闪光窗口内（列表 item 内调用） */
    fun isAutoCheckedNow(parentId: String): Boolean =
        (pulses[parentId] ?: 0L) > System.currentTimeMillis() - PULSE_WINDOW_MS

    /**
     * 数据层在判定"子任务全勾"后调用。
     * 题目约定的回调是 `onAutoChecked(parentId: String)`，
     * 数据层拿到 id 后再调这里把文案补全。
     */
    fun emit(parentId: String, parentText: String, doneChildren: Int, totalChildren: Int) {
        currentEvent = AutoCheckedEvent(parentId, parentText, doneChildren, totalChildren)
        pulses[parentId] = System.currentTimeMillis()
        hapticFeedback()

        scope?.launch {
            delay(EVENT_WINDOW_MS)
            dismiss()
            delay(PULSE_TAIL_MS)
            pulses.remove(parentId)
        }
    }

    fun undo() {
        val id = currentEvent?.parentId ?: return
        undoHandler?.invoke(id)
        dismiss()
    }

    fun dismiss() {
        currentEvent = null
    }

    internal companion object {
        const val EVENT_WINDOW_MS = 2400L
        const val PULSE_TAIL_MS = 500L
        const val PULSE_WINDOW_MS = 1600L
    }
}

/**
 * 页面顶层包一层，把 bus 接到 Scaffold 上。
 *
 * 用法：
 * ```
 * MdAutoCheckScope(onUndo = { id -> viewModel.undoAutoCheck(id) }) { bus ->
 *     Scaffold(
 *         snackbarHost = { MdAutoCheckHost(bus) },
 *     ) { padding -> /* 用 bus.isAutoCheckedNow(item.id) */ }
 * }
 * ```
 *
 * @param enableHaptic 关掉后自动勾不再震动（可在 AppSettings 加开关）
 * @param hostStyle 横幅 / Snackbar / 两者，见交付文档 F 的对比
 */
@Composable
fun MdAutoCheckScope(
    onUndo: (parentId: String) -> Unit,
    enableHaptic: Boolean = true,
    hostStyle: AutoCheckHostStyle = AutoCheckHostStyle.BANNER,
    content: @Composable (AutoCheckBus) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val hapticOn by rememberUpdatedState(enableHaptic)
    val bus = remember {
        AutoCheckBus(
            hapticFeedback = {
                // rememberUpdatedState 保证开关变化后 lambda 读到新值
                if (hapticOn) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            },
        )
    }
    bus.attach(scope, onUndo)

    CompositionLocalProvider(LocalAutoCheckBus provides bus) {
        content(bus)
    }
}

val LocalAutoCheckBus = staticCompositionLocalOf<AutoCheckBus> {
    error("LocalAutoCheckBus 未提供，请在页面顶层包 MdAutoCheckScope")
}

enum class AutoCheckHostStyle {
    /** 顶部横幅（推荐） */
    BANNER,

    /** Snackbar（省空间） */
    SNACKBAR,

    /** 两者都开：横幅给视觉锚点，Snackbar 给长文本和撤销入口 */
    BOTH,
}

/* ==========================================================================
 * 宿主组件
 * ========================================================================== */

/**
 * 放到 Scaffold.snackbarHost 里。
 *
 * 注意：Scaffold 的 snackbarHost 通常在底部，而自动勾发生的位置
 * （父任务行）通常在屏幕中间甚至靠上 —— 底部 Snackbar 会离现场太远，
 * 用户不知道在说谁。这是选顶部横幅而不是 Snackbar 的核心原因。
 */
@Composable
fun MdAutoCheckHost(
    bus: AutoCheckBus,
    style: AutoCheckHostStyle = AutoCheckHostStyle.BANNER,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    modifier: Modifier = Modifier,
) {
    if (style == AutoCheckHostStyle.BANNER || style == AutoCheckHostStyle.BOTH) {
        Box(modifier = modifier.fillMaxWidth()) {
            AnimatedVisibility(
                visible = bus.currentEvent != null,
                enter = slideInVertically(
                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                    initialOffsetY = { -it },
                ) + fadeIn(tween(200)),
                exit = slideOutVertically(
                    animationSpec = tween(200),
                    targetOffsetY = { -it },
                ) + fadeOut(tween(160)),
            ) {
                bus.currentEvent?.let { event ->
                    AutoCheckBanner(
                        event = event,
                        onUndo = { bus.undo() },
                    )
                }
            }
        }
    }

    if (style == AutoCheckHostStyle.SNACKBAR || style == AutoCheckHostStyle.BOTH) {
        val event = bus.currentEvent
        LaunchedEffect(event?.parentId) {
            val target = event ?: return@LaunchedEffect
            val result = snackbarHostState.showSnackbar(
                message = "已自动完成「${target.parentText}」（${target.doneChildren}/${target.totalChildren}）",
                actionLabel = "撤销",
                withDismissAction = false,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                bus.undo()
            } else {
                bus.dismiss()
            }
        }
    }
}

@Composable
private fun AutoCheckBanner(
    event: AutoCheckedEvent,
    onUndo: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "已自动完成「${event.parentText}」",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                )
                Text(
                    text = "子任务 ${event.doneChildren}/${event.totalChildren} 全部完成",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Button(
                onClick = onUndo,
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "撤销",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/* ==========================================================================
 * 可选：自动勾完后自动收起子任务
 * ========================================================================== */

/**
 * 父任务被自动勾完后，等 700ms 再收起子任务 —— 先让用户看清"5 个子任务都勾了"，
 * 再折叠，避免"任务突然消失"的惊吓感。
 */
@Composable
fun AutoCollapseAfterAutoCheck(
    active: Boolean,
    childrenVisible: Boolean,
    onCollapse: () -> Unit,
) {
    LaunchedEffect(active) {
        if (active && childrenVisible) {
            delay(700)
            onCollapse()
        }
    }
}

/* ==========================================================================
 * 列表拍平工具
 * ========================================================================== */

/** 分组数据：根任务 + 每个根任务的孩子表 */
data class MdTreeGroup(
    val roots: List<MdTodo>,
    val children: Map<String, List<MdTodo>>,
) {
    fun childrenOf(id: String): List<MdTodo> = children[id].orEmpty()
}

/** 拍平后的一行 */
data class MdRowSpec(
    val item: MdTodo,
    val depth: Int,
)

/**
 * 把多棵树拍平成 LazyColumn 需要的扁平列表。
 *
 * @param expandedIds 当前展开的父任务 id 集合
 * @param maxDepth 渲染到第几级；需求只要两级，传 1
 */
fun flattenMdTrees(
    groups: List<MdTreeGroup>,
    expandedIds: Set<String>,
    maxDepth: Int = 1,
): List<MdRowSpec> {
    val out = ArrayList<MdRowSpec>(groups.size * 4)
    groups.forEach { group ->
        group.roots.forEach { root ->
            out += MdRowSpec(item = root, depth = 0)
            if (root.id !in expandedIds || maxDepth < 1) return@forEach
            group.childrenOf(root.id).forEach { child ->
                out += MdRowSpec(item = child, depth = 1)
                if (maxDepth >= 2 && child.id in expandedIds) {
                    group.childrenOf(child.id).forEach { grand ->
                        out += MdRowSpec(item = grand, depth = 2)
                    }
                }
            }
        }
    }
    return out
}
