package com.github.remotecontrol.util.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组合键（`tap Ctrl+Shift+u`）：解析、拆分、以及「一步与拆开多步等价」这条不变量。
 *
 * 顺序是这套设计的骨头：文件里怎么写的，按下、拆分就怎么排 —— 逆序只用在松开上。
 */
class MacroTemplateChordTest {

    @Test
    fun `chord is parsed with its order preserved`() {
        val step = parseSingleStep("tap Shift+Ctrl+u")

        assertTrue("应当是组合键：$step", step is MacroTemplateStep.ChordTap)
        assertEquals(
            listOf("Shift", "Ctrl", "u"),
            (step as MacroTemplateStep.ChordTap).keyNames,
        )
        assertEquals(listOf("Shift", "Ctrl"), step.modifierNames())
        assertEquals(listOf("u"), step.plainKeyNames())
    }

    @Test
    fun `modifier-only chord has no plain key`() {
        val step = parseSingleStep("tap Ctrl+Shift") as MacroTemplateStep.ChordTap

        assertEquals(listOf("Ctrl", "Shift"), step.modifierNames())
        assertTrue("没有普通键：${step.plainKeyNames()}", step.plainKeyNames().isEmpty())
    }

    @Test
    fun `split lays out downs, the tap, and reversed ups`() {
        val chord = MacroTemplateStep.ChordTap(listOf("Ctrl", "Shift", "u"))

        assertEquals(
            listOf("down Ctrl", "down Shift", "tap u", "up Shift", "up Ctrl"),
            chord.split().map { it.label() },
        )
    }

    @Test
    fun `modifier-only chord splits without a middle tap`() {
        val chord = MacroTemplateStep.ChordTap(listOf("Ctrl", "Shift"))

        assertEquals(
            listOf("down Ctrl", "down Shift", "up Shift", "up Ctrl"),
            chord.split().map { it.label() },
        )
    }

    @Test
    fun `one chord step expands exactly like its split form`() {
        // 整套设计的关键不变量：两种写法不同，发出去的帧必须一模一样
        val header = MacroTemplateHeader()
        val chord = MacroTemplateStep.ChordTap(listOf("Ctrl", "Shift", "u"))

        val asChord = MacroTemplateExpander.expand(MacroTemplate(header, listOf(chord)), "A")
        val asSplit = MacroTemplateExpander.expand(MacroTemplate(header, chord.split()), "A")

        assertEquals(asSplit, asChord)
    }

    @Test
    fun `chord round trips through the file text`() {
        val steps = listOf(MacroTemplateStep.ChordTap(listOf("Ctrl", "Shift", "u")))
        val text = MacroTemplateParser.format(MacroTemplateHeader(), steps)

        assertTrue("应当写成 tap Ctrl+Shift+u：\n$text", text.contains("tap Ctrl+Shift+u"))
        assertEquals(
            listOf("Ctrl", "Shift", "u"),
            (parseSingleStep("tap Ctrl+Shift+u") as MacroTemplateStep.ChordTap).keyNames,
        )
    }

    @Test
    fun `unknown name inside a chord is reported`() {
        val parsed = MacroTemplateParser.parse("charset = UTF-16\nradix = 10\ntap Ctrl+Foo\n")

        assertTrue(
            "组合里的未知键名要报出来：${parsed.diagnostics}",
            parsed.diagnostics.any { it.issue == MacroTemplateIssue.UNKNOWN_KEY },
        )
    }

    @Test
    fun `broken chord reports a value error instead of degrading to a plain tap`() {
        val parsed = MacroTemplateParser.parse("charset = UTF-16\nradix = 10\ntap Ctrl+\n")

        assertTrue(
            "留空段应当报无效值：${parsed.diagnostics}",
            parsed.diagnostics.any { it.issue == MacroTemplateIssue.INVALID_VALUE },
        )
        // 写坏的一行既不降级成「只点 Ctrl」，也不被丢掉：它以 Unknown 的形式留在步骤列表里
        // （原文照显示、标红，保存时原样写回），诊断由 validate 报
        assertEquals("写坏的一行应当留在列表里：${parsed.template.steps}", 1, parsed.template.steps.size)
        val step = parsed.template.steps.single()
        assertTrue("应当是认不出来的一行：$step", step is MacroTemplateStep.Unknown)
        assertEquals("tap Ctrl+", (step as MacroTemplateStep.Unknown).raw)
    }

    private fun parseSingleStep(line: String): MacroTemplateStep =
        MacroTemplateParser.parse("charset = UTF-16\nradix = 10\n$line\n").template.steps.single()

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
