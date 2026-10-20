package com.github.remotecontrol.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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
 * ## 跟随系统（[HueScheme.DYNAMIC]）
 * 旧的 `ThemeColor.DYNAMIC` 曾直接用整套 `dynamicColorScheme`，它会返回浅色背景、
 * 把深色优先的工具风冲掉，所以被删过。现在改成**只借系统的主色与两个语义色**：
 * 背景 / 卡片 / 描边这些中性层仍走本项目的中性色，浓度阶梯继续由主色派生，
 * 于是「跟随系统」与其余六套方案在同一个视觉体系里。
 */
@Composable
fun RemoteInputTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    hue: HueScheme = HueScheme.AZURE,
    content: @Composable () -> Unit,
) {
    // 只有跟随系统时才去读系统调色板；其余方案不碰它（动态取色要读资源，没必要每次重组都付）
    val systemScheme: ColorScheme? =
        if (hue == HueScheme.DYNAMIC) systemDynamicColorScheme(LocalContext.current, darkTheme) else null

    val tokens = remember(darkTheme, hue, systemScheme) {
        if (systemScheme != null) {
            // 语义色也跟随系统：tertiary 作警告、error 作错误（与固定方案「各挑一对撞色」的用意一致）
            val semantics = SemanticPair(warn = systemScheme.tertiary, error = systemScheme.error)
            if (darkTheme) {
                darkTokens(hue, accentOverride = systemScheme.primary, semanticsOverride = semantics)
            } else {
                lightTokens(hue, accentOverride = systemScheme.primary, semanticsOverride = semantics)
            }
        } else {
            if (darkTheme) darkTokens(hue) else lightTokens(hue)
        }
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
