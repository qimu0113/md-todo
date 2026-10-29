package com.mdtodo.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object TodosianDimens {
    val ScreenHorizontalPadding: Dp = 16.dp
    val ProgressHeight: Dp = 6.dp
    val LoadingIndicatorSize: Dp = 48.dp
    val EmptyStateBadgeSize: Dp = 112.dp
    val EmptyStateIconSize: Dp = 48.dp
}

@Composable
fun TodosianSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(top = 16.dp, bottom = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TodosianLoadingIndicator(
    modifier: Modifier = Modifier,
) {
    LoadingIndicator(
        modifier = modifier.size(TodosianDimens.LoadingIndicatorSize),
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * FAB 形状。
 *
 * 【ANR 修复 2026-10-17】原实现是 `MaterialShapes.Cookie9Sided.toShape()`
 * （Material 3 Expressive 的九边「饼干」形，边缘内凹的**凹多边形**）。
 *
 * 为什么必须换掉——ANR trace 的 RenderThread 堆栈给出了铁证：
 * ```
 * SkBaseShadowTessellator::stitchConcaveRings
 * SkBaseShadowTessellator::computeConcaveShadow
 * SkSpotShadowTessellator::SkSpotShadowTessellator
 * draw_shadow<SpotVerticesFactory>
 * SkDevice::drawShadow
 * ```
 * FloatingActionButton 默认带 6dp elevation，需要绘制 spot shadow。
 * 对**凹多边形**做阴影三角剖分走的是 Skia 的 concave 路径
 * （`stitchConcaveRings`），在 Mali GPU + Skia Ganesh 组合上会退化到
 * 近似死循环：RenderThread 100% CPU，主线程卡在
 * `DrawFrameTask::postAndWait` 等它画完 → 输入事件处理超时 → ANR。
 *
 * 现改为圆角矩形（凸形），阴影三角剖分走快速路径。
 * 若以后要恢复 Expressive 视觉，正确做法是**保留形状但把 elevation 设为 0**
 * （用 tonal 底色代替阴影），而不是给凹形画阴影。
 */
@Composable
fun todosianFabShape(): Shape = RoundedCornerShape(20.dp)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TodosianEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    val badgeScale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.7f,
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "emptyStateBadgeScale",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "emptyStateContentAlpha",
    )
    val badgeShape = MaterialShapes.Clover4Leaf.toShape()

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(TodosianDimens.EmptyStateBadgeSize)
                    .scale(badgeScale)
                    .clip(badgeShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(TodosianDimens.EmptyStateIconSize),
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .alpha(contentAlpha)
                    .widthIn(max = 320.dp),
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .alpha(contentAlpha)
                        .widthIn(max = 320.dp),
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(24.dp))
                FilledTonalButton(
                    onClick = onAction,
                    modifier = Modifier.alpha(contentAlpha),
                ) {
                    Text(text = actionLabel)
                }
            }
        }
    }
}

@Composable
fun TodosianLinearProgress(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(TodosianDimens.ProgressHeight / 2)
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier
            .height(TodosianDimens.ProgressHeight)
            .clip(shape),
    )
}
