package com.mdtodo.ui.transition

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch

/**
 * 「容器变换」转场（Container Transform）—— 按 ColorOS 便签逆向出来的结论重做。
 *
 * ## 逆向结论（2026-10-28 真机实测，com.coloros.note 16.6.14 / PMB110）
 *
 * 便签从列表点进笔记，**不是页面平移**，是：
 *   1. 一张与被点击列表项**同位置、同宽度、同圆角**的白色卡片原地长出来
 *   2. 卡片长到接近全屏时仍是圆角，四周露出被压暗的列表
 *   3. 最后收紧成全屏直角
 *   返回时完全反着来：卡片缩回原来那一行的位置，背景由暗转亮。
 *
 * 证据：按 30ms 步长逐点采样（`MDTodo/tools/measure_transition.py` 同源的采样脚本），
 * 卡片高度随时间的序列是 71.5% → 74.1% → 90.9% → 95.7% → 99.7%，
 * 宽度始终 ~97% → 99.8%，即**先沿纵向长开、再横向补满**，
 * 这正是「从某个矩形展开」的特征，任何整页平移/缩放都画不出这个序列。
 *
 * ## 为什么不用 SharedTransitionLayout
 * `Modifier.sharedBounds` 会让两个页面在动画期间真实重排（remeasure），
 * 首帧要重新 measure 一整页的内容。这个 App 已经在 ANR 上踩过坑
 * （见 ANR结案报告.md），转场期间重排整页列表风险太高。
 * 这里只用 `graphicsLayer` 做 scale/translation/clip —— 不触发任何 relayout，
 * 原理和 Android 的 MaterialContainerTransform 一致。
 *
 * ## 已知取舍
 * 容器缩放时内部文字会被一起拉伸（Material 的原始实现也这样，它靠内容交叉淡化遮）。
 * 这里用 alpha 让内容在 60% 进度前淡入，避免看到被压扁的字。
 */
object ContainerOriginStore {

    private var pending: TransformOrigin? = null

    /** 点击列表项时调用，记下它当时的窗口矩形和圆角。 */
    fun remember(bounds: Rect, cornerRadiusPx: Float) {
        pending = TransformOrigin(bounds, cornerRadiusPx)
    }

    /**
     * 取走待用的 origin 并清空。
     *
     * 必须「取走」而不是「读」：否则用户从首页进分类页后，
     * 再从设置页返回首页、再点另一个分类时，会拿上一次的旧矩形。
     */
    fun consume(): TransformOrigin? {
        val value = pending
        pending = null
        return value
    }
}

/** 起始矩形的快照，窗口坐标（px）。 */
data class TransformOrigin(
    val bounds: Rect,
    val cornerRadiusPx: Float,
)

/**
 * 把一个页面渲染成「从 [origin] 矩形展开 / 收回」的卡片。
 *
 * @param origin null 时退化为普通全屏页（比如从别处直接跳到这个页面，没有来源矩形）
 * @param onExitFinished 收回动画播完后调用，通常在里面 popBackStack
 * @param content 页面内容，入参是「请求返回」的回调；顶栏返回箭头要调它，
 *                不要直接 pop，否则会跳过收回动画
 */
@Composable
fun ContainerTransformPage(
    origin: TransformOrigin?,
    onExitFinished: () -> Unit,
    content: @Composable (requestBack: () -> Unit) -> Unit,
) {
    if (origin == null) {
        content { onExitFinished() }
        return
    }

    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var exiting by remember { mutableStateOf(false) }

    fun requestBack() {
        if (exiting) return
        exiting = true
        scope.launch {
            progress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
            )
            onExitFinished()
        }
    }

    // 系统返回手势 / 返回键也走收回动画，而不是让 NavHost 直接硬 pop。
    BackHandler(enabled = !exiting) { requestBack() }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        )
    }

    // 遮罩直接做在根 Box 的 background 上。
    // 之前是单独铺一层子 Box，实测被卡片层盖掉、完全看不见——
    // 一层搞定最稳，也不会多一次绘制。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f * progress.value)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = progress.value
                    if (p < 1f) {
                        val w = size.width
                        val h = size.height
                        if (w > 0f && h > 0f) {
                            val sx = lerp(origin.bounds.width / w, 1f, p).coerceAtLeast(0.01f)
                            val sy = lerp(origin.bounds.height / h, 1f, p).coerceAtLeast(0.01f)
                            scaleX = sx
                            scaleY = sy
                            translationX = lerp(origin.bounds.center.x - w / 2f, 0f, p)
                            translationY = lerp(origin.bounds.center.y - h / 2f, 0f, p)
                            shape = RoundedCornerShape(lerp(origin.cornerRadiusPx, 0f, p))
                            clip = true
                        }
                    }
                },
        ) {
            // 卡片底必须是不透明的：便签展开的第一帧是一张实心白卡，
            // 只有里面的内容在渐显。之前把 alpha 加在整个 layer 上，
            // 卡片会变成半透明的鬼影，一眼就不对。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 进入时内容晚一点出现；收回时不淡出——便签收回的那一帧
                    // 卡片里内容仍然看得见，淡出像是「页面先消失、再滑走一块空板」。
                    .alpha(if (exiting) 1f else (progress.value / 0.45f).coerceIn(0f, 1f)),
            ) {
                content { requestBack() }
            }
        }
    }
}
