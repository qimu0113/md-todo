package com.mdtodo.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun TodosianTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(), // 已恢复：确认与 ANR 无关
        // MD Todo：移除 motionScheme = MotionScheme.expressive()（原项目设置）。
        //
        // 为什么（2026-10-17 排查）：
        //   - MotionScheme.expressive() 是 Material 3 Expressive 的「全局动效方案」，
        //     会接管所有 M3 组件的默认动画曲线
        //   - material3 1.5.0-alpha21 声明依赖 compose-animation-core 1.12.0-alpha03，
        //     而 BOM 2026.09.00 实际提供 1.12.1（稳定版）——animation spec 组合未经官方验证
        //   - 症状：RenderThread 100% 死循环、每帧 4950ms、主线程反而休眠，
        //     符合「全局动画永不收敛」的特征；此前改业务代码（树渲染/key/animateItem）全部无效
        //   - 用默认 motionScheme（标准曲线）不改变任何 UI 结构，仅回归系统动画
        typography = Typography,
        content = content
    )
}
