package com.github.remotecontrol.ui.theme

import androidx.compose.ui.graphics.Color
import com.github.remotecontrol.util.HueScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两套模式 + 6 套色相的 token 不变量。
 *
 * 这些断言是为了防止后续微调颜色时把"认真做了浅色模式"这件事做回去：
 * 最容易写反的就是浅色下 panel 与 card 的方向、以及浓度阶梯的单调性。
 */
class TokensTest {

    @Test
    fun panelAlwaysContrastsWithCard() {
        HueScheme.entries.forEach { hue ->
            assertTrue(
                "${hue.name} 深色模式：panel 应比 card 亮",
                darkTokens(hue).isPanelDirectionCorrect(darkTheme = true)
            )
            assertTrue(
                "${hue.name} 浅色模式：panel 应比 card 暗",
                lightTokens(hue).isPanelDirectionCorrect(darkTheme = false)
            )
        }
    }

    @Test
    fun tintRampIsMonotonic() {
        HueScheme.entries.forEach { hue ->
            assertTrue(
                "${hue.name} 深色模式：浓度阶梯应由暗到亮单调递进",
                darkTokens(hue).isRampMonotonic(darkTheme = true)
            )
            assertTrue(
                "${hue.name} 浅色模式：浓度阶梯应由亮到暗单调递进",
                lightTokens(hue).isRampMonotonic(darkTheme = false)
            )
        }
    }

    /**
     * 浅色模式不能用深色那套亮色强调色 —— 白字会过不了对比度，
     * 所以每套方案在两个模式下必须是两个不同的色值。
     */
    @Test
    fun lightAccentIsNotReusedFromDark() {
        HueScheme.entries.forEach { hue ->
            assertNotEquals(
                "${hue.name} 的浅色强调色必须是压暗版本",
                hue.darkAccent,
                hue.lightAccent
            )
        }
    }

    @Test
    fun onAccentFlipsBetweenModes() {
        HueScheme.entries.forEach { hue ->
            // 深色：亮底 + 近黑字；浅色：暗底 + 白字
            assertTrue(darkTokens(hue).onAccent.luminance() < 0.2f)
            assertTrue(lightTokens(hue).onAccent.luminance() > 0.8f)
        }
    }

    @Test
    fun darkAndLightNeutralsAreNotTheSamePalette() {
        val dark = darkTokens(HueScheme.AZURE)
        val light = lightTokens(HueScheme.AZURE)

        // 背景色必须真的反过来了，而不是同一套值
        assertTrue(dark.background.luminance() < 0.2f)
        assertTrue(light.background.luminance() > 0.8f)

        // 浅色模式的 onAccent 是纯白，深色不是
        assertNotEquals(dark.onAccent, light.onAccent)

        // 语义色也是两套独立定值（浅色下必须压暗）
        assertNotEquals(dark.status.ok, light.status.ok)
        assertNotEquals(dark.status.bad, light.status.bad)
    }

    @Test
    fun appBarIsAThinTintOverBackground() {
        HueScheme.entries.forEach { hue ->
            val dark = darkTokens(hue)
            val light = lightTokens(hue)

            // 顶栏是"薄色"：必须比背景更接近强调色，但远不到强调色本身
            assertNotEquals(
                "顶栏应带色相而不是纯背景色",
                dark.background,
                dark.appBar
            )
            assertNotEquals(
                "顶栏应带色相而不是纯背景色",
                light.background,
                light.appBar
            )
        }
    }

    @Test
    fun accentStaysFullyOpaque() {
        HueScheme.entries.forEach { hue ->
            assertTrue(darkTokens(hue).accent.alpha == 1f)
            assertTrue(lightTokens(hue).accent.alpha == 1f)
            // 第 5 档是满浓度，不能像前几档那样是半透明混色
            assertNotEquals(Color.Transparent, darkTokens(hue).accent)
        }
    }

    /**
     * 语义色的「底」两件事：落在卡片与前景之间的那一档；以及**两套模式各配一个反差值**
     * （与 `ok` 同一套待遇：深色用亮色、浅色用压暗版）。
     */
    @Test
    fun semanticContainersAreWashesWithContrastPerMode() {
        HueScheme.entries.forEach { hue ->
            val dark = darkTokens(hue)
            val light = lightTokens(hue)

            assertTrue(
                "${hue.name} 深色：语义色的底应落在卡片与前景之间",
                dark.isSemanticWashOrdered(darkTheme = true)
            )
            assertTrue(
                "${hue.name} 浅色：语义色的底应落在卡片与前景之间",
                light.isSemanticWashOrdered(darkTheme = false)
            )

            assertNotEquals("${hue.name}：警告底要按模式配反差值", dark.warnContainer, light.warnContainer)
            assertNotEquals("${hue.name}：错误底要按模式配反差值", dark.errorContainer, light.errorContainer)
        }
    }

    /**
     * **六套方案之间不重复**：逐套切主题时要看得出颜色变了 ——
     * 「一套通用色 + 个别偏移」正是这条要防的。
     */
    @Test
    fun everySchemeHasItsOwnSemanticPair() {
        listOf(
            "深色" to HueScheme.entries.associateWith { darkTokens(it) },
            "浅色" to HueScheme.entries.associateWith { lightTokens(it) },
        ).forEach { (mode, tokens) ->
            val warns = tokens.mapValues { (_, t) -> t.warn }
            val errors = tokens.mapValues { (_, t) -> t.error }

            warns.forEach { (hue, color) ->
                val owner = warns.entries.firstOrNull { it.key != hue && it.value == color }?.key
                assertNull("$mode：${hue.name} 与 $owner 用了同一个警告色", owner)
            }
            errors.forEach { (hue, color) ->
                val owner = errors.entries.firstOrNull { it.key != hue && it.value == color }?.key
                assertNull("$mode：${hue.name} 与 $owner 用了同一个错误色", owner)
            }
        }
    }

    /**
     * 组内两条不变量：**正常（accent）/ 警告 / 错误三者两两可分**（否则等于没有语义色）；
     * **两套模式各配一个反差值**（与 `ok` 同一套待遇：深色用亮色、浅色用压暗版）。
     *
     * 每套方案各挑一对由 `when (this)` 保证（没有 else 分支，编译器逼着表态），
     * 这里只管挑出来的那对别和 accent 撞。
     */
    @Test
    fun semanticColorsAreDistinctFromAccentInEveryScheme() {
        HueScheme.entries.forEach { hue ->
            val dark = darkTokens(hue)
            val light = lightTokens(hue)

            listOf("深色" to dark, "浅色" to light).forEach { (mode, tokens) ->
                assertNotEquals("${hue.name} $mode：警告色不该等于强调色", tokens.accent, tokens.warn)
                assertNotEquals("${hue.name} $mode：错误色不该等于强调色", tokens.accent, tokens.error)
                assertNotEquals("${hue.name} $mode：警告色与错误色必须分得开", tokens.warn, tokens.error)

                // 连接状态里的「警告 / 异常」直接复用这两档，不另起一套值
                assertEquals(tokens.warn, tokens.status.warn)
                assertEquals(tokens.error, tokens.status.bad)
            }

            assertNotEquals("${hue.name}：警告色要按模式配反差值", dark.warn, light.warn)
            assertNotEquals("${hue.name}：错误色要按模式配反差值", dark.error, light.error)
        }
    }
}
