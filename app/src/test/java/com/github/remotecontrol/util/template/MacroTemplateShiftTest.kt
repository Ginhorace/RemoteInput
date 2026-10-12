package com.github.remotecontrol.util.template

import com.github.remotecontrol.util.KeyboardConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键名与 Shift 的关系：**键名只表示物理键，永不隐含 Shift**。
 *
 * 这条口径有两处容易走叉，都在这里钉住：
 * - 模板里的 `a` / `A` 都只是「不按 Shift 的 A 键」（展开出来的帧里 modifier 必须是 0）——
 *   想打上档字符就把 `Shift` 写成修饰键（`tap Shift+2`），或在编辑器里看步骤行的副标签；
 * - 没有例外：`<code>` 展开十六进制时同样不补 Shift（`a`–`f` 就是主键盘上那几个小写键位），
 *   想让宿主收到大写，就在 `<code>` 外面自己包 `down Shift` / `up Shift`。
 */
class MacroTemplateShiftTest {

    @Test
    fun `letters are raw keys without shift`() {
        // `a` 与 `A` 是同一个物理键（HID 的 0x04 就叫 "A"），谁都不按下 Shift
        listOf("a", "A").forEach { name ->
            val frames = expand(MacroTemplateStep.KeyTap(name))
            assertEquals("$name 不该补 Shift：$frames", listOf(0, 0), frames.map { it.modifier })
            assertEquals("$name 应当落到 A 键", 0x04, frames.first().keyCode)
        }
    }

    @Test
    fun `shift only comes from an explicit modifier`() {
        val frames = expand(MacroTemplateStep.ChordTap(listOf("Shift", "2")))

        // down Shift → tap 2 → release 2 → up Shift：Shift 位只在这条组合键中间那三帧上
        assertEquals(listOf(0x02, 0x02, 0x02, 0), frames.map { it.modifier })
        // 松开那一帧用空报告（keyCode 0 = 没有键按下），把 2 键弹起来
        assertEquals(listOf(0, 0x1F, 0, 0), frames.map { it.keyCode })
    }

    @Test
    fun `shifted characters are not key names`() {
        // `@` / `!` 是上档字符，不是键名：正确的写法是 `Shift+2` / `Shift+1`
        assertNull("`@` 不该解析成 2 键", MacroTemplateKeys.resolve("@"))
        assertNull("`!` 不该解析成 1 键", MacroTemplateKeys.resolve("!"))
        assertEquals(KeyboardConstants.Keys.NUM_2, MacroTemplateKeys.resolve("2")?.key)
    }

    @Test
    fun `hex letters are raw keys without shift`() {
        // 「中」= U+4E2D：radix = 16 的编码位是 4E2D，其中 D / E 落到主键盘的 d / e 键位，
        // 但展开器不补 Shift —— 要送大写得自己在 `<code>` 外面包 down Shift / up Shift
        val header = MacroTemplateHeader(charset = "UTF-16", radix = 16)
        val frames = MacroTemplateExpander.expand(
            MacroTemplate(header, listOf(MacroTemplateStep.CodeDigits(useNumpad = false))),
            "中",
        )
        val modifierOfDigit = frames.associate { it.label to it.modifier }
        val keyOfDigit = frames.associate { it.label to it.keyCode }

        assertTrue("编码位不该带 Shift：$modifierOfDigit", modifierOfDigit.values.all { it == 0 })
        assertEquals(KeyboardConstants.Keys.D.value.toInt() and 0xFF, keyOfDigit["code D"])
        assertEquals(KeyboardConstants.Keys.E.value.toInt() and 0xFF, keyOfDigit["code E"])
        assertEquals("数字位走主键盘", KeyboardConstants.Keys.NUM_4.value.toInt() and 0xFF, keyOfDigit["code 4"])
    }

    /**
     * 副标签（步骤行上那个 `(@)`）：只有「Shift 单独按住 + 恰好一个普通键」才算得出产出字符，
     * 它只是算出来给人看的，[MacroTemplateParser.format] 不会把它写进文件。
     */
    @Test
    fun `sub label is only computed for a shift only step`() {
        assertEquals('@', MacroTemplateStep.ChordTap(listOf("Shift", "2")).shiftResultChar())
        assertEquals('A', MacroTemplateStep.ChordTap(listOf("Shift", "a")).shiftResultChar())

        assertNull("没写 Shift 就不会按下它", MacroTemplateStep.KeyTap("2").shiftResultChar())
        assertNull("没有普通键", MacroTemplateStep.ChordTap(listOf("Shift")).shiftResultChar())
        assertNull("普通键不止一个", MacroTemplateStep.ChordTap(listOf("Shift", "2", "3")).shiftResultChar())
        assertNull("还按着别的修饰键", MacroTemplateStep.ChordTap(listOf("Ctrl", "Shift", "2")).shiftResultChar())
        assertNull("这个键没有上档档位", MacroTemplateStep.ChordTap(listOf("Shift", "Space")).shiftResultChar())
        assertNull("别的步骤类型", MacroTemplateStep.Delay(20).shiftResultChar())
    }

    /** 展开一步（样本随便给一个字符：这些步骤都不含 `<code>`，展开结果与样本无关） */
    private fun expand(vararg steps: MacroTemplateStep): List<MacroTemplateExpander.Frame> =
        MacroTemplateExpander.expand(MacroTemplate(MacroTemplateHeader(), steps.toList()), "x")
}
