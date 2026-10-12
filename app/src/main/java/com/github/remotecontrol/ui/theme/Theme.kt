package com.github.remotecontrol.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.github.remotecontrol.util.HueScheme

/**
 * 应用主题入口。
 *
 * 只接受两个参数：
 * - [darkTheme] 亮 / 暗模式。两套 token 是**独立设计**的，不是明暗反转 ——
 *   混合比、描边策略、[AppTokens.panel] 的方向、[AppTokens.onAccent] 全部不同，
 *   详见 [darkTokens] / [lightTokens]。
 * - [hue] 色相方案。只声明"用哪个色相"，整条浓度阶梯
 *   （顶栏 10% / 分区 16% / 描边 30% / 选中 46% / 按钮 100%）由它自动派生，
 *   所以换方案时不会出现"某个区域忘了跟着改"的割裂感。
 *
 * 旧的 `ThemeColor.DYNAMIC`（Material You 动态取色）已移除：它会返回浅色背景，
 * 直接破坏深色优先的工具风视觉。
 */
@Composable
fun RemoteInputTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    hue: HueScheme = HueScheme.AZURE,
    content: @Composable () -> Unit,
) {
    val tokens = remember(darkTheme, hue) {
        if (darkTheme) darkTokens(hue) else lightTokens(hue)
    }
    val colorScheme = remember(tokens, darkTheme) { buildColorScheme(tokens, darkTheme) }

    CompositionLocalProvider(LocalAppTokens provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = UtilityShapes,
            typography = UtilityTypography,
            content = content,
        )
    }
}
