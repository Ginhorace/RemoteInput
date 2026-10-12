package com.github.remotecontrol.util.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 模板语法的解析 / 序列化，重点是「停用的步骤」。
 *
 * 停用一步的写法是**把它注释掉**（`; tap Numpad0`），这条约定有两个方向都要守住：
 * 注释行要能读回一条停用的步骤，而普通说明文字**不能**被误认成步骤。
 */
class MacroTemplateParserTest {

    @Test
    fun `commentLineIsReadAsDisabledStep`() {
        val parsed = MacroTemplateParser.parse(TEMPLATE)
        val steps = parsed.template.steps

        assertEquals(4, steps.size)
        assertEquals(listOf(true, false, true, true), steps.map { it.enabled })

        val disabled = steps[1]
        assertTrue("注释行应当读成一条步骤：$disabled", disabled is MacroTemplateStep.KeyTap)
        assertEquals("Numpad0", (disabled as MacroTemplateStep.KeyTap).keyName)

        // 停用不是错误：它只是不参与发送
        assertTrue("不该有诊断：${parsed.diagnostics}", parsed.diagnostics.isEmpty())
    }

    @Test
    fun `formatWritesDisabledStepBackAsComment`() {
        val parsed = MacroTemplateParser.parse(TEMPLATE)
        val text = MacroTemplateParser.format(parsed.template.header, parsed.template.steps)

        assertTrue("停用的步骤应当写成注释行：\n$text", text.contains("\n; tap Numpad0\n"))

        // 写回再读回来，勾选状态不能变（行号会随文本变化，所以不比较整个对象）
        val reparsed = MacroTemplateParser.parse(text)
        assertEquals(stepsOf(parsed), stepsOf(reparsed))
        assertEquals(
            parsed.template.steps.map { it.enabled },
            reparsed.template.steps.map { it.enabled },
        )
    }

    @Test
    fun `plainCommentIsNotAStep`() {
        val parsed = MacroTemplateParser.parse(
            """
            charset = UTF-16
            radix = 10

            ; Alt Code template: hold Alt, type the code on the numpad
            ; up
            ; sleep soon
            <code>
            """.trimIndent()
        )

        assertEquals(1, parsed.template.steps.size)
        assertTrue(parsed.template.steps.single() is MacroTemplateStep.CodeDigits)
        // 说明文字被当成步骤的后果不只是多一行，还会凭空冒出 UNKNOWN_KEY 之类的诊断
        assertTrue("说明文字不该带来诊断：${parsed.diagnostics}", parsed.diagnostics.isEmpty())
    }

    @Test
    fun `disabledStepDoesNotJoinPairingCheck`() {
        val parsed = MacroTemplateParser.parse(
            """
            charset = UTF-16
            radix = 10

            down Alt
            ; up Alt
            <code>
            """.trimIndent()
        )

        // 松开被停用 = 真的没有松开，所以这条照报（停用不等于「不存在」被忽略掉）；
        // 而且是**错误**：卡住的键在宿主端会连发、修饰键会粘住后面所有字符，这类模板不放行
        val unbalanced = parsed.diagnostics.filter { it.issue == MacroTemplateIssue.UNBALANCED_KEY }
        assertEquals("应当恰好报一条「按下未松开」：${parsed.diagnostics}", 1, unbalanced.size)
        assertEquals(TemplateSeverity.ERROR, unbalanced.single().severity)
        assertTrue("按下未松开属于错误，要拦住发送与测试", parsed.hasError)
    }

    @Test
    fun `disablingCodeStepIsAWarningNotAnError`() {
        val parsed = MacroTemplateParser.parse(
            """
            charset = UTF-16
            radix = 10

            down Alt
            ; <code>
            up Alt
            """.trimIndent()
        )

        assertEquals(3, parsed.template.steps.size)
        assertFalse(parsed.template.steps[1].enabled)
        assertFalse("没有启用的编码位", parsed.template.steps.hasActiveCode())

        // 勾掉 <code> 是「临时试别的步骤」，所以只给警告：展开预览里的「测试」照跑，
        // 发送文本则由编辑器单独拦（门禁 = 有没有启用的编码位，同样是 hasActiveCode）
        val issue = parsed.diagnostics.singleOrNull { it.issue == MacroTemplateIssue.CODE_DISABLED }
        assertNotNull("应当报 CODE_DISABLED：${parsed.diagnostics}", issue)
        assertEquals(TemplateSeverity.WARNING, issue!!.severity)
        assertTrue(
            "不该报 MISSING_CODE：${parsed.diagnostics}",
            parsed.diagnostics.none { it.issue == MacroTemplateIssue.MISSING_CODE },
        )
        assertFalse("警告不算错误，不该拦住「测试」", parsed.hasError)
    }

    @Test
    fun `missingCodeEntirelyIsStillAnError`() {
        val parsed = MacroTemplateParser.parse(
            """
            charset = UTF-16
            radix = 10

            tap a
            """.trimIndent()
        )

        // 文件里根本没有 <code>：结构上就送不出字符，仍然是错误（列表卡片会标红）
        val issue = parsed.diagnostics.singleOrNull { it.issue == MacroTemplateIssue.MISSING_CODE }
        assertNotNull("应当报 MISSING_CODE：${parsed.diagnostics}", issue)
        assertEquals(TemplateSeverity.ERROR, issue!!.severity)
        assertFalse(parsed.template.steps.hasActiveCode())
    }

    @Test
    fun `presetCommentsAreNotMistakenForSteps`() {
        MacroTemplatePresets.ALL.forEach { preset ->
            val steps = MacroTemplateParser.parse(preset.content).template.steps
            assertEquals(
                "${preset.name} 的说明文字被当成了停用步骤：$steps",
                0,
                steps.count { !it.enabled },
            )
        }
    }

    /**
     * 预设的说明是**落盘前补进正文**的（`MacroTemplatePresets.localize`），补完就是一份普通模板文件：
     * 说明占一行、正文里不再有解释性注释、步骤原样。
     *
     * 顺带钉住「解释性注释从正文里消失」这件事：普通 `;` 行既不在界面上显示，`format` 也会把它丢掉，
     * 所以预设的解释只能走说明字段。
     */
    @Test
    fun `localizedPresetWritesOneDescriptionLine`() {
        // 占位符必须先换掉：带占位符的正文解析出来有错，localize 见到错误会原样返回
        val preset = MacroTemplatePresets.resolveCharset(
            MacroTemplatePresets.OEM_ALT_CODE,
            Locale.forLanguageTag("zh-CN"),
        )
        val description = "第一行\n第二行 \"带引号\" 与 C:\\path"
        val localized = MacroTemplatePresets.localize(preset, description)

        assertEquals(
            "说明只占一行",
            1,
            localized.content.lines().count { it.startsWith("description = ") },
        )
        assertEquals(
            "解释性注释不该留在预设正文里",
            0,
            localized.content.lines().count { it.startsWith(";") },
        )

        val parsed = MacroTemplateParser.parse(localized.content)
        assertEquals("说明要一字不差", description, parsed.template.header.description)
        assertTrue("补说明不该带来诊断：${parsed.diagnostics}", parsed.diagnostics.isEmpty())

        // 头部多了一行，步骤的行号会跟着后移，所以比对步骤那一段的原文，而不是整条步骤模型
        assertEquals(
            "步骤原样保留",
            preset.content.substringAfter("\n\n"),
            localized.content.substringAfter("\n\n"),
        )
    }

    /**
     * 名字解析不出来时**原样保留**，不静默换成默认字符集。
     *
     * 回退的话界面上显示 `UTF-16`、文件里写着 `UTF-8`，用户看不到哪里错了；
     * 保留原文才能让编辑器把这个值标红、堵住发送。
     */
    @Test
    fun `unknownCharsetIsKeptAndReported`() {
        val parsed = MacroTemplateParser.parse(
            """
            charset = UTF-8
            radix = 10

            <code>
            """.trimIndent()
        )

        assertEquals("UTF-8", parsed.template.header.charset)

        val issue = parsed.diagnostics.singleOrNull { it.issue == MacroTemplateIssue.UNKNOWN_CHARSET }
        assertNotNull("应当报 UNKNOWN_CHARSET：${parsed.diagnostics}", issue)
        assertEquals(TemplateSeverity.ERROR, issue!!.severity)
        assertEquals(TemplateField.CHARSET, issue.field)
        assertTrue("字符集错了就不该放行发送", parsed.hasError)
    }

    /**
     * 编辑器编辑的是**模型**（不经过文本解析），所以字符集检查必须也长在 `validateModel` 上，
     * 否则「打开一张坏模板 → 编辑器一片正常 → 还能发送」。
     */
    @Test
    fun `modelValidationReportsUnknownCharset`() {
        val diagnostics = MacroTemplateParser.validateModel(
            MacroTemplateHeader(charset = "nope"),
            listOf(MacroTemplateStep.CodeDigits()),
        )

        val issue = diagnostics.singleOrNull { it.issue == MacroTemplateIssue.UNKNOWN_CHARSET }
        assertNotNull("模型校验也要报 UNKNOWN_CHARSET：$diagnostics", issue)
        assertEquals(TemplateField.CHARSET, issue!!.field)
        assertEquals(TemplateSeverity.ERROR, issue.severity)
    }

    /**
     * 头部参数的错误要带上是哪个字段：编辑器没有行号可用，靠这个字段把出错的输入框标红。
     */
    @Test
    fun `headerParameterErrorsCarryTheirField`() {
        val parsed = MacroTemplateParser.parse(
            """
            charset = UTF-16
            radix = 8
            keyHold = abc

            <code>
            """.trimIndent()
        )

        val radix = parsed.diagnostics.single { it.issue == MacroTemplateIssue.INVALID_RADIX }
        assertEquals(TemplateSeverity.ERROR, radix.severity)
        assertEquals(TemplateField.RADIX, radix.field)

        val hold = parsed.diagnostics.single { it.issue == MacroTemplateIssue.INVALID_NUMBER }
        assertEquals(TemplateSeverity.ERROR, hold.severity)
        assertEquals(TemplateField.KEY_HOLD, hold.field)
        assertEquals("原始写法要留在 arg 里", "abc", hold.arg)
    }

    /**
     * 说明是自由文本：`=` 之后**整行**都归它，行里的 `;`、`=`、引号这些在别处有含义的
     * 字符都不参与解析，也不产生任何诊断。
     */
    @Test
    fun `descriptionIsParsedAndWrittenBack`() {
        val text = """
            name = Alt Code
            description = Windows Alt Code: hold Alt, type 20013 for "中"; radix = 10
            charset = UTF-16
            radix = 10

            <code>
            """.trimIndent()

        val parsed = MacroTemplateParser.parse(text)
        assertEquals(
            """Windows Alt Code: hold Alt, type 20013 for "中"; radix = 10""",
            parsed.template.header.description,
        )
        assertTrue("说明不该带来诊断：${parsed.diagnostics}", parsed.diagnostics.isEmpty())

        val formatted = MacroTemplateParser.format(parsed.template.header, parsed.template.steps)
        assertTrue("说明要写回文件：\n$formatted", formatted.contains("description = "))
        assertEquals(
            "写回再读回，说明要一字不差",
            parsed.template.header.description,
            MacroTemplateParser.parse(formatted).template.header.description,
        )
    }

    /**
     * 单行的普通说明照旧写成**裸串**，不加引号 —— 手写模板时更好读。
     *
     * 裸串里不解析转义：`C:\path` 的反斜杠必须原样留着（当成转义吃掉的话，
     * 说明就与用户写的不是一回事了）。
     */
    @Test
    fun `plainSingleLineDescriptionStaysBare`() {
        val parsed = MacroTemplateParser.parse(headerOnly("""description = Alt Code, 见 C:\path"""))
        assertEquals("""Alt Code, 见 C:\path""", parsed.template.header.description)

        val formatted = MacroTemplateParser.format(parsed.template.header, parsed.template.steps)
        assertEquals(
            "能写成裸串就不加引号",
            """description = Alt Code, 见 C:\path""",
            formatted.lines().single { it.startsWith("description") },
        )
    }

    /**
     * 换行的说明写成**带引号的转义形式**，读回来一字不差。
     *
     * 文件是行式的：换行直接写进去会断成两行，第二行还会被当成一条步骤 ——
     * 所以整条说明必须始终只占一行，换行、引号、反斜杠都靠 `\n` `\"` `\\` 承载。
     */
    @Test
    fun `multilineDescriptionIsQuotedAndUnescaped`() {
        val model = "第一行\n第二行 \"带引号\" 与 C:\\path"
        val text = MacroTemplateParser.format(
            MacroTemplateHeader(description = model),
            listOf(MacroTemplateStep.CodeDigits()),
        )

        // 期望的文件行：结尾那个引号用 Char 拼出来，免得与原始字符串的三引号打架
        // （串里的 \n 是字面的反斜杠 + n，不是换行）
        val expected = """description = "第一行\n第二行 \"带引号\" 与 C:\\path""" + '"'
        assertEquals(expected, text.lines().single { it.startsWith("description") })

        val parsed = MacroTemplateParser.parse(text)
        assertEquals("写回再读回要一字不差", model, parsed.template.header.description)
        assertEquals("整条说明仍然只占一行：${parsed.template.steps}", 1, parsed.template.steps.size)
        assertTrue("多行说明不该带来诊断：${parsed.diagnostics}", parsed.diagnostics.isEmpty())
    }

    /**
     * 只有**两头都是引号**才算带引号的形式。
     *
     * 单边引号是正文：`Alt "Code` 剥掉引号就成了「改一次说明，引号莫名其妙没了」。
     */
    @Test
    fun `quotesAreOnlyStrippedWhenTheyPair`() {
        val paired = MacroTemplateParser.parse(headerOnly("description = \"Alt Code\""))
        assertEquals("Alt Code", paired.template.header.description)

        val oneSided = MacroTemplateParser.parse(headerOnly("description = Alt \"Code"))
        assertEquals("Alt \"Code", oneSided.template.header.description)
    }

    /**
     * 没写说明读回 `null`，而且写回时**不产生**那一行。
     *
     * 空值只有一种表示：界面上把输入框清空就等于删掉这一行，文件里不会留下空的
     * `description =` 或 `description = ""`（否则「写过但没内容」与「从没写过」会长得不一样）。
     */
    @Test
    fun `missingOrBlankDescriptionStaysNull`() {
        assertNull(MacroTemplateParser.parse(TEMPLATE).template.header.description)
        assertNull(
            "带引号的空串同样当作没写",
            MacroTemplateParser.parse(headerOnly("description = \"\"")).template.header.description,
        )

        val blank = MacroTemplateParser.parse(
            """
            charset = UTF-16
            radix = 10
            description =

            <code>
            """.trimIndent()
        )
        assertNull("空说明当作没写：${blank.template.header.description}", blank.template.header.description)

        val formatted = MacroTemplateParser.format(blank.template.header, blank.template.steps)
        assertFalse("没写说明就不该写出这一行：\n$formatted", formatted.contains("description"))
    }

    /**
     * ANSI 版与 OEM 版只差「前导 0」那一步：头部参数一致，步骤就是在 `sleep 20` 之后插一句
     * `tap Numpad0`。
     *
     * 位置也要钉住：前导 0 必须落在编码数字**之前**（`tap Numpad0` 在 `<code>` 上一条），
     * 否则 Windows 会把整个数字当无前导 0 的 OEM 路径解释。以后动 OEM 那条时这条也会红，
     * 提醒别漏改 ANSI 那条。
     *
     * 两条都按 **zh-CN** 替换字符集：这门语言的两张页是同一张（`GBK`），所以头部能整块比对下来
     * —— 换成 en 之类的语言就会因 ACP / OEMCP 不同而只在字符集上有差别。
     */
    @Test
    fun `ansiAltCodeIsTheOemOneWithALeadingZero`() {
        val locale = Locale.forLanguageTag("zh-CN")
        val oem = MacroTemplateParser.parse(
            MacroTemplatePresets.resolveCharset(MacroTemplatePresets.OEM_ALT_CODE, locale).content
        )
        val ansi = MacroTemplateParser.parse(
            MacroTemplatePresets.resolveCharset(MacroTemplatePresets.ANSI_ALT_CODE, locale).content
        )

        assertEquals(
            "ANSI 版的头部参数应当与 OEM 版一致（名字除外）",
            oem.template.header.copy(name = null),
            ansi.template.header.copy(name = null),
        )

        // 步骤模型带行号，插入一步会让后面整体后移，所以比的是写回文件后的那一段原文
        val oemSteps = MacroTemplateParser.format(MacroTemplateHeader(), oem.template.steps).substringAfter("\n\n")
        val ansiSteps = MacroTemplateParser.format(MacroTemplateHeader(), ansi.template.steps).substringAfter("\n\n")
        assertEquals(
            "ANSI 版应当就是 OEM 版多一句 tap Numpad0",
            oemSteps.replace("down Alt\n", "down Alt\ntap Numpad0\n"),
            ansiSteps,
        )
    }

    /**
     * 认不出来的那一行要**留在模型里**。
     *
     * 以前解析不出来就丢掉：步骤列表里看不见它，保存时还会被 `format` 静默删除 ——
     * 用户既看不到错在哪，一保存连原文都没了。
     */
    @Test
    fun `unparseableLineIsKeptAsAStepAndReported`() {
        val parsed = MacroTemplateParser.parse(BAD_TEMPLATE)

        assertEquals("坏行要留在步骤列表里：${parsed.template.steps}", 4, parsed.template.steps.size)
        val unknown = parsed.template.steps[1]
        assertTrue("第 2 条应当是认不出来的那一行：$unknown", unknown is MacroTemplateStep.Unknown)
        assertEquals("slee 20", (unknown as MacroTemplateStep.Unknown).raw)
        assertEquals(MacroTemplateIssue.UNKNOWN_STEP, unknown.issue)

        val issue = parsed.diagnostics.single { it.issue == MacroTemplateIssue.UNKNOWN_STEP }
        assertEquals(TemplateSeverity.ERROR, issue.severity)
        assertEquals("slee 20", issue.arg)

        // 原样写回：保存一次不该把坏行弄丢，读回来还是同一条
        val text = MacroTemplateParser.format(parsed.template.header, parsed.template.steps)
        assertTrue("坏行要原样写回：\n$text", text.contains("\nslee 20\n"))
        assertTrue(
            "写回再读回，坏行还在：${MacroTemplateParser.parse(text).template.steps}",
            MacroTemplateParser.parse(text).template.steps[1] is MacroTemplateStep.Unknown,
        )
    }

    /** `; slee 20` 是说明文字，不是「被停用的步骤」——停用的前提是它本来是一条合法步骤 */
    @Test
    fun `commentedBadLineStaysAComment`() {
        val parsed = MacroTemplateParser.parse(
            """
            charset = UTF-16
            radix = 10

            down Alt
            ; slee 20
            <code>
            up Alt
            """.trimIndent()
        )

        assertEquals("注释里的坏行不该变成步骤：${parsed.template.steps}", 3, parsed.template.steps.size)
        assertTrue(parsed.template.steps.none { it is MacroTemplateStep.Unknown })
        assertTrue("说明文字不该带来诊断：${parsed.diagnostics}", parsed.diagnostics.isEmpty())
    }

    /**
     * 打开编辑器时看到的那份诊断：**两个不同的错误各自出现一次**。
     *
     * 这是 `charset = gbk` 被手改成 `gb`、`sleep` 被改成 `slee` 那个样例的回归测试：
     * 以前 charset 会被「文件诊断」与「模型校验」各报一遍（显示成两条一样的），
     * 而坏步骤因为没进模型则完全不显示。
     */
    @Test
    fun `editorDiagnosticsShowsBothErrorsExactlyOnce`() {
        val parsed = MacroTemplateParser.parse(BAD_TEMPLATE_WITH_BAD_CHARSET)
        val editor = MacroTemplateParser.editorDiagnostics(
            parsed.template.header,
            parsed.template.steps,
            parsed.diagnostics,
        )

        assertEquals("只该有两条错误：$editor", 2, editor.count { it.severity == TemplateSeverity.ERROR })
        assertEquals(1, editor.count { it.issue == MacroTemplateIssue.UNKNOWN_CHARSET })
        assertEquals(1, editor.count { it.issue == MacroTemplateIssue.UNKNOWN_STEP })

        // 两条错的定位方式不同：字符集是头部字段（用来标红那个下拉），坏行是「第几条步骤」（用来标红那一行）
        assertEquals(TemplateField.CHARSET, editor.single { it.issue == MacroTemplateIssue.UNKNOWN_CHARSET }.field)
        val stepIssue = editor.single { it.issue == MacroTemplateIssue.UNKNOWN_STEP }
        assertNull("步骤诊断不该挂头部字段", stepIssue.field)
        assertEquals("坏行是第 2 条步骤", 2, stepIssue.line)
    }

    /** 「改一行」对话框：只认干净可用的那一行 */
    @Test
    fun `parseLineAcceptsOnlyCleanAndUsableLines`() {
        val delay = MacroTemplateParser.parseLine("sleep 20")
        assertTrue("合法步骤应当可用：$delay", delay.isUsable)
        assertEquals(20L, (delay.step as MacroTemplateStep.Delay).ms)

        assertFalse("认不出的关键字", MacroTemplateParser.parseLine("slee 20").isUsable)
        assertFalse("空行", MacroTemplateParser.parseLine("").isUsable)
        assertFalse("留空段落的组合键", MacroTemplateParser.parseLine("tap Ctrl+").isUsable)
        assertFalse("数字写坏", MacroTemplateParser.parseLine("sleep abc").isUsable)
        // 步骤认得出、只是属性写坏了，也算「还没改好」，免得「确定」之后把坏属性悄悄丢掉
        assertFalse("无效属性", MacroTemplateParser.parseLine("<code pad=x>").isUsable)
    }

    /**
     * 只改头部的一行、其余保持最小的合法模板 ——
     * 用来单独验某个头部参数的读写，不必每处都把 charset / radix / `<code>` 抄一遍。
     */
    private fun headerOnly(headerLine: String): String = """
        $headerLine
        charset = UTF-16
        radix = 10

        <code>
    """.trimIndent()

    private fun stepsOf(result: MacroTemplateParseResult): List<String> =
        result.template.steps.map { step ->
            when (step) {
                is MacroTemplateStep.KeyDown -> "down ${step.keyName}"
                is MacroTemplateStep.KeyUp -> "up ${step.keyName}"
                is MacroTemplateStep.KeyTap -> "tap ${step.keyName}"
                is MacroTemplateStep.ChordTap -> "tap ${step.keyNames.joinToString("+")}"
                is MacroTemplateStep.Delay -> "sleep ${step.ms}"
                is MacroTemplateStep.CodeDigits -> "<code keys=${step.useNumpad} pad=${step.padDigits}>"
                is MacroTemplateStep.Unknown -> "unknown ${step.raw}"
            }
        }

    private companion object {
        /** Alt Code 的典型写法，其中补 0 那一步被注释掉了 */
        const val TEMPLATE = """charset = UTF-16
radix = 10

down Alt
; tap Numpad0
<code>
up Alt
"""

        /** 手改坏的样例：`sleep` 被写成了 `slee` */
        const val BAD_TEMPLATE = """charset = UTF-16
radix = 10

down Alt
slee 20
<code>
up Alt
"""

        /** 手改坏的样例：字符集与步骤同时写坏（上一版会把字符集重复报两条的那一例） */
        const val BAD_TEMPLATE_WITH_BAD_CHARSET = """charset = gb
radix = 10

down Alt
slee 20
<code>
up Alt
"""
    }
}
