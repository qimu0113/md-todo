package com.mdtodo.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 全屏编辑容器：替代原来的 ModalBottomSheet。
 *
 * 为什么换：便签点进一条笔记是「整屏推上来」，返回是「整屏滑下去」，
 * 而 ModalBottomSheet 始终带一张圆角卡片和拖拽条，观感是「弹层」不是「页面」。
 * 换成全屏 Dialog 后，视觉上和独立页面一致（无遮罩、无圆角、铺满全屏），
 * 同时保留了弹层方案的好处：编辑状态还留在调用方内存里，
 * 不需要为详情页重新读一遍文件、也不用来回传一遍保存结果。
 *
 * 转场曲线照着便签调：
 * - 进入 280ms，从屏幕下方整屏升起
 * - 退出 240ms，整屏滑下去（比进入略快，收得起）
 *
 * 注意：Dialog 的退出动画需要自己驱动。这里 onDismissRequest 只负责
 * 把 visible 置 false，真正销毁由 Dialog 在动画后完成——
 * 但系统 Dialog 的 dismiss 是即时的，所以退出动画只对「外部触发关闭」生效，
 * 用户按返回键时走系统默认关闭。要完全对齐需要在 Activity 层接管，
 * 这里接受这个差异（按下返回键只是一瞬间的事）。
 */
@Composable
fun FullScreenEditorDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }

        AnimatedVisibility(
            visible = entered,
            enter = slideInVertically(
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                initialOffsetY = { it },
            ),
            exit = slideOutVertically(
                animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                targetOffsetY = { it },
            ),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    // 系统栏（状态栏/导航栏）留出安全区，键盘由内容自己处理
                    .windowInsetsPadding(WindowInsets.systemBars),
                color = MaterialTheme.colorScheme.surface,
            ) {
                content()
            }
        }
    }
}
