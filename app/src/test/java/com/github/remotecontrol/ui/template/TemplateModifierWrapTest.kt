package com.github.remotecontrol.ui.template

import com.github.remotecontrol.util.template.MacroTemplateStep
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「修饰键 + 下面添加的步骤」的包装规则。
 *
 * 模板里没有组合键写法（修饰键是状态），所以 `Ctrl+Shift+U` 必须展开成
 * `down` / `down` / 步骤 / `up` / `up`，且按下的顺序与松开相反。
 */
class TemplateModifierWrapTest {

    @Test
    fun `no modifier keeps the step as is`() {
        val tap = MacroTemplateStep.KeyTap("A")

        assertEquals(listOf("tap A"), wrapWithModifiers(emptySet(), listOf(tap)).map { it.label() })
    }

    @Test
    fun `ctrl shift u expands into downs a tap and reversed ups`() {
        val wrapped = wrapWithModifiers(
            selected = setOf("Ctrl", "Shift"),
            steps = listOf(MacroTemplateStep.KeyTap("u")),
        )

        assertEquals(
            listOf("down Ctrl", "down Shift", "tap u", "up Shift", "up Ctrl"),
            wrapped.map { it.label() },
        )
    }

    @Test
    fun `press order comes from the modifier list and release is reversed`() {
        // 选中集合是无序的：按下永远按 Ctrl / Shift / Alt / Win，松开逆序
        val wrapped = wrapWithModifiers(
            selected = setOf("Win", "Ctrl", "Alt"),
            steps = listOf(MacroTemplateStep.CodeDigits()),
        )

        assertEquals(
            listOf("down Ctrl", "down Alt", "down Win", "<code>", "up Win", "up Alt", "up Ctrl"),
            wrapped.map { it.label() },
        )
    }

    private fun MacroTemplateStep.label(): String = when (this) {
        is MacroTemplateStep.KeyDown -> "down $keyName"
        is MacroTemplateStep.KeyUp -> "up $keyName"
        is MacroTemplateStep.KeyTap -> "tap $keyName"
        is MacroTemplateStep.ChordTap -> "tap ${keyNames.joinToString("+")}"
        is MacroTemplateStep.Delay -> "sleep $ms"
        is MacroTemplateStep.CodeDigits -> "<code>"
        is MacroTemplateStep.Unknown -> "unknown $raw"
    }
}
