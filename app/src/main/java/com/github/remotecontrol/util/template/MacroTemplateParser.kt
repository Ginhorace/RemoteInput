package com.github.remotecontrol.util.template

import com.github.remotecontrol.util.CharsetCatalog

/**
 * 模板解析 / 序列化 / 校验。
 *
 * 文件格式（关键字大小写不敏感，整行 `;` 或 `#` 开头为注释）：
 * ```
 * name = OEM Alt Code
 * description = Hold Alt and type the decimal code point on the numpad
 * charset = GBK
 * radix = 10
 * templateGap = 50
 * keyHold = 10
 * keyGap = 10
 *
 * down Alt
 * sleep 20
 * <code>
 * up Alt
 * ```
 *
 * 头部参数里只有 `description` 能**换行**：文件本身是行式的，换行只能塞进引号里 ——
 * `description = "第一行\n第二行"`（只认 `\\` `\"` `\n` `\r` 四种转义，见 [parseDescription]）。
 * 不带换行 / 引号 / 首尾空白的说明照旧写成裸串（`description = Alt Code`），手写时更好读。
 *
 * `tap` 的参数里带 `+` 就是**组合键**（`tap Ctrl+Shift+u`，也可以只有修饰键 `tap Ctrl+Shift`）：
 * 按写的顺序按下、逆序松开，等价于写成 `down` / `tap` / `up` 的多步。
 *
 * 注释行（`;` 或 `#` 开头）如果正好是一条合法步骤，就是**停用的步骤** —— 展开与发送都跳过它，
 * 但编辑器里仍把它显示成一条步骤（勾选框未勾选）。停用一步不删掉它，靠的就是这个写法。
 *
 * 职责边界：
 * - [parse] 只负责把文本切成语法结构，并按模型补全默认值；
 * - [validateModel] 负责语义检查（键名是否存在、按键是否配对等）；
 * - [format] 是 [parse] 的逆运算，供宏编辑器把步骤列表写回文件。
 *
 * 解析失败不抛异常，而是把问题收集成 [MacroTemplateDiagnostic]，
 * 保证编辑器可以「带错编辑」，用户能看到问题出在哪里。
 */
object MacroTemplateParser {

    private val HEADER_LINE = Regex("""^([A-Za-z]+)\s*=\s*(.*)$""")
    private val CODE_PLACEHOLDER = Regex("""^<\s*([A-Za-z]+)\s*([^>]*?)\s*>$""")
    private val WHITESPACE = Regex("""\s+""")

    /**
     * 停用步骤写回文件时的注释前缀。
     *
     * 用 `;` 而不是 `#`：与 [isCommentLine] 接受的两个前缀等价，写回时统一成前者，
     * 文件里不会出现两种混写的停用标记。
     */
    private const val COMMENT_PREFIX = ";"

    /**
     * 低于该值的延时会被提示「可能被蓝牙压缩」。
     *
     * 判定是**严格小于**：10 ms 及以上视为安全，与
     * [MacroTemplateHeader.MIN_KEY_DELAY_MS] 是同一个口径 ——
     * 否则编辑器设成下限（10）反而会报警告。
     */
    private const val SMALL_DELAY_THRESHOLD_MS = 10L

    /** 说明带引号形式的定界符，见 [parseDescription] */
    private const val QUOTE = '"'

    /**
     * `<code pad=n>` 的位数上限。
     *
     * 现实中最长的编码是十六进制的 6 位（Linux 的 `Ctrl+Shift+U` 支持到 `10FFFF`），
     * 这里留到 8 位只是为了编辑器不校验失败，同时挡住离谱的输入。
     */
    const val MAX_PAD_DIGITS = 8

    // ─────────────────────────────────────────────
    // 解析
    // ─────────────────────────────────────────────

    /**
     * 解析模板文本。
     *
     * 即使存在错误也会尽量返回可用的模板，方便编辑器继续展示与修复。
     */
    fun parse(text: String): MacroTemplateParseResult {
        val diagnostics = mutableListOf<MacroTemplateDiagnostic>()

        var name: String? = null
        var description: String? = null
        var charset = MacroTemplateHeader.DEFAULT_CHARSET
        var radix = MacroTemplateHeader.DEFAULT_RADIX
        var templateGap = MacroTemplateHeader.DEFAULT_TEMPLATE_GAP_MS
        var keyHold = MacroTemplateHeader.DEFAULT_KEY_HOLD_MS
        var keyGap = MacroTemplateHeader.DEFAULT_KEY_GAP_MS

        val steps = mutableListOf<MacroTemplateStep>()
        var inHeader = true

        text.lines().forEachIndexed { index, rawLine ->
            val lineNo = index + 1
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachIndexed

            // ── 注释：`;` / `#` 后面如果正好是一条合法步骤，那它就是「停用的步骤」 ──
            //
            // 先用一份临时诊断探一遍，只有**干净地解析出一条步骤**才算：`; down Alt` 是停用的
            // 步骤，而 `; up`、`; sleep x` 这类说明文字照旧当注释丢掉，也不会凭空冒出诊断。
            if (isCommentLine(line)) {
                val probe = mutableListOf<MacroTemplateDiagnostic>()
                val step = parseStep(line.drop(1).trim(), lineNo, probe)
                if (step != null && probe.isEmpty() && step.isComplete()) {
                    steps += step.withEnabled(false)
                }
                return@forEachIndexed
            }

            // ── 头部参数 ──
            if (inHeader) {
                val match = HEADER_LINE.matchEntire(line)
                if (match != null) {
                    val key = match.groupValues[1].lowercase()
                    val value = match.groupValues[2].trim()
                    when (key) {
                        "name" -> name = value.ifBlank { null }
                        // 说明是自由文本：`=` 之后整行都归它，不校验。换行这类「一行装不下」
                        // 的内容写成带引号的转义形式，裸串则原样收下 —— 两种都认，见 parseDescription
                        "description" -> description = parseDescription(value)
                        // 原样收下，不在这里判可用性：认不认识这个名字交给 validate（那里是
                        // 文本解析与模型校验共用的唯一入口），解析层再判一遍就会报两条同样的错。
                        // 也正因为原样保留，编辑器才能把写错的那个值标红给用户看
                        "charset" -> charset = value
                        "radix" -> {
                            val parsed = value.toIntOrNull()
                            if (parsed != null && parsed in MacroTemplateHeader.SUPPORTED_RADIX) {
                                radix = parsed
                            } else {
                                diagnostics += error(
                                    lineNo,
                                    MacroTemplateIssue.INVALID_RADIX,
                                    value,
                                    TemplateField.RADIX,
                                )
                            }
                        }
                        // charGap 是旧名：含义与现在完全一致，只是改了叫法，
                        // 所以静默接受（与字符集别名同一个口径），保存时会被写成新名
                        "templategap", "chargap" ->
                            templateGap = parseMillis(
                                value, templateGap, lineNo, TemplateField.TEMPLATE_GAP, diagnostics,
                            )
                        "keyhold" -> keyHold = parseMillis(
                            value, keyHold, lineNo, TemplateField.KEY_HOLD, diagnostics,
                        )
                        "keygap" -> keyGap = parseMillis(
                            value, keyGap, lineNo, TemplateField.KEY_GAP, diagnostics,
                        )
                        else -> diagnostics += warning(
                            lineNo,
                            MacroTemplateIssue.UNKNOWN_PARAM,
                            key,
                            TemplateField.UNKNOWN_PARAM,
                        )
                    }
                    return@forEachIndexed
                }
                inHeader = false
            }

            parseStep(line, lineNo, diagnostics)?.let { steps += it }
        }

        val header = MacroTemplateHeader(
            name = name,
            description = description,
            charset = charset,
            radix = radix,
            templateGapMs = templateGap,
            keyHoldMs = keyHold,
            keyGapMs = keyGap,
        )
        diagnostics += validate(header, steps) { _, step -> step.line }
        return MacroTemplateParseResult(MacroTemplate(header, steps), diagnostics)
    }

    /**
     * 解析一行步骤：`<code …>` 或 `down` / `up` / `tap` / `sleep`。
     *
     * 解析不出来时返回一条 [MacroTemplateStep.Unknown]（把原文与原因带上），**不在这里报诊断** ——
     * 诊断统一由 [validate] 报，那边同时服务文本解析与模型校验；两处各报一遍就会变成
     * 「同一个错误显示两条」，或者更糟：编辑器只认模型那一侧，文本那侧报了也看不见。
     *
     * [diagnostics] 仍然要传进来，但只用于 `<code>` 属性这类**认得出步骤、只是属性有毛病**的情况
     * （那种情况步骤本身可用，诊断只能挂在文本上）。
     */
    private fun parseStep(
        line: String,
        lineNo: Int,
        diagnostics: MutableList<MacroTemplateDiagnostic>,
    ): MacroTemplateStep? {
        // ── 编码占位符 ──
        if (line.startsWith("<")) {
            val match = CODE_PLACEHOLDER.matchEntire(line)
            if (match == null) {
                return MacroTemplateStep.Unknown(line, MacroTemplateIssue.UNCLOSED_PLACEHOLDER, line = lineNo)
            }
            if (!match.groupValues[1].equals("code", ignoreCase = true)) {
                return MacroTemplateStep.Unknown(line, MacroTemplateIssue.UNKNOWN_STEP, line, lineNo)
            }

            var useNumpad = true
            var padDigits = 0
            match.groupValues[2].trim().split(WHITESPACE)
                .filter { it.isNotBlank() }
                .forEach { attr ->
                    val attrKey = attr.substringBefore('=').trim().lowercase()
                    val attrValue = attr.substringAfter('=', "").trim()
                    when (attrKey) {
                        "pad" -> {
                            val parsed = attrValue.toIntOrNull()
                            if (parsed != null && parsed in 0..MAX_PAD_DIGITS) padDigits = parsed
                            else diagnostics += error(lineNo, MacroTemplateIssue.INVALID_VALUE, attrValue)
                        }
                        "keys", "keytype" -> when (attrValue.lowercase()) {
                            "main", "keyboard" -> useNumpad = false
                            "numpad", "keypad" -> useNumpad = true
                            else -> diagnostics += error(lineNo, MacroTemplateIssue.INVALID_VALUE, attrValue)
                        }
                        // 旧语法残留：这两个属性曾能覆盖头部节奏，现在节奏统一由头部决定
                        "hold", "gap" -> diagnostics += warning(
                            lineNo,
                            MacroTemplateIssue.IGNORED_ATTR,
                            attr,
                        )
                        else -> diagnostics += warning(lineNo, MacroTemplateIssue.UNKNOWN_PARAM, attr)
                    }
                }

            return MacroTemplateStep.CodeDigits(useNumpad, padDigits, lineNo)
        }

        // ── 普通步骤（键名是否合法交给 validateModel） ──
        val parts = line.split(WHITESPACE, 2)
        val keyword = parts[0].lowercase()
        val argument = parts.getOrNull(1)?.trim().orEmpty()

        return when (keyword) {
            "down" -> MacroTemplateStep.KeyDown(argument, lineNo)
            "up" -> MacroTemplateStep.KeyUp(argument, lineNo)
            // `tap` 的参数里带 `+` 就是组合键：`tap Ctrl+Shift+u`。留空段（`tap Ctrl+`）算无效值，
            // 免得把写坏了的一行悄悄降级成「只点 Ctrl」
            "tap" -> if ('+' !in argument) {
                MacroTemplateStep.KeyTap(argument, lineNo)
            } else {
                val names = argument.split('+').map { it.trim() }
                if (names.any { it.isEmpty() }) {
                    MacroTemplateStep.Unknown(line, MacroTemplateIssue.INVALID_VALUE, argument, lineNo)
                } else {
                    MacroTemplateStep.ChordTap(names, lineNo)
                }
            }
            "sleep", "delay" -> {
                val ms = argument.toLongOrNull()
                if (ms == null || ms < 0) {
                    MacroTemplateStep.Unknown(line, MacroTemplateIssue.INVALID_NUMBER, argument, lineNo)
                } else {
                    MacroTemplateStep.Delay(ms, lineNo)
                }
            }
            else -> MacroTemplateStep.Unknown(line, MacroTemplateIssue.UNKNOWN_STEP, line, lineNo)
        }
    }

    /**
     * 把编辑器里改好的一行重新解析一遍（「改一行」对话框用）。
     *
     * 与 [parse] 不同的是：这里要求**这一行干净可用** —— 解析得出一条真步骤、且不产生任何诊断
     * （`<code pad=x>` 那种「步骤能用但属性写坏了」也算不干净）。返回 null 表示这行还没改好，
     * 调用方据此禁用「确定」。
     */
    fun parseLine(line: String): MacroTemplateLineParse {
        val probe = mutableListOf<MacroTemplateDiagnostic>()
        val step = parseStep(line.trim(), 0, probe)
        return MacroTemplateLineParse(step, probe)
    }

    // ─────────────────────────────────────────────
    // 序列化
    // ─────────────────────────────────────────────

    /** 把模型写回模板文本，[parse] 的逆运算 */
    fun format(header: MacroTemplateHeader, steps: List<MacroTemplateStep>): String {
        val builder = StringBuilder()
        header.name?.takeIf { it.isNotBlank() }?.let { builder.append("name = ").append(it).append('\n') }
        // 与 name 一样：没写就不写这一行，读回来仍是 null。
        // 能写成裸串就写裸串，装不下的（换行 / 引号 / 首尾空白）才加引号转义，见 formatDescription
        header.description?.takeIf { it.isNotBlank() }?.let {
            builder.append("description = ").append(formatDescription(it)).append('\n')
        }
        builder.append("charset = ").append(header.charset).append('\n')
        builder.append("radix = ").append(header.radix).append('\n')
        builder.append("templateGap = ").append(header.templateGapMs).append('\n')
        builder.append("keyHold = ").append(header.keyHoldMs).append('\n')
        builder.append("keyGap = ").append(header.keyGapMs).append('\n')
        builder.append('\n')
        steps.forEach { step ->
            // 停用的步骤写成注释行：读回来仍是「停用的步骤」—— [parse] 的逆运算要成立，
            // 勾选状态才不会在保存后丢掉
            if (!step.enabled) builder.append(COMMENT_PREFIX).append(' ')
            builder.append(
                when (step) {
                    is MacroTemplateStep.KeyDown -> "down ${step.keyName}"
                    is MacroTemplateStep.KeyUp -> "up ${step.keyName}"
                    is MacroTemplateStep.KeyTap -> "tap ${step.keyName}"
                    // 组合键写回成 `tap Ctrl+Shift+u`，顺序原样保留（拆分顺序依赖它）
                    is MacroTemplateStep.ChordTap -> "tap ${step.keyNames.joinToString("+")}"
                    is MacroTemplateStep.Delay -> "sleep ${step.ms}"
                    is MacroTemplateStep.CodeDigits -> buildString {
                        append("<code")
                        if (!step.useNumpad) append(" keys=main")
                        if (step.padDigits > 0) append(" pad=").append(step.padDigits)
                        append('>')
                    }
                    // 认不出来的一行原样写回：保存一次不该把用户写的坏行弄丢
                    is MacroTemplateStep.Unknown -> step.raw
                }
            ).append('\n')
        }
        return builder.toString()
    }

    /**
     * 把 `name = xxx` 头部与文件名同步。
     *
     * 文件名是模板的唯一标识（列表里显示的名字就是文件名），
     * 这里保证文件内容里的 `name` 头部不会和文件名脱节。
     */
    fun syncNameHeader(content: String, name: String): String {
        val lines = content.lines().toMutableList()
        val index = lines.indexOfFirst { line ->
            HEADER_LINE.matchEntire(line.trim())?.groupValues?.get(1)?.lowercase() == "name"
        }
        if (index >= 0) lines[index] = "name = $name" else lines.add(0, "name = $name")
        return lines.joinToString("\n")
    }

    // ─────────────────────────────────────────────
    // 校验
    // ─────────────────────────────────────────────

    /**
     * 只对模型做语义校验（不涉及文本语法），供宏编辑器在每次编辑后实时调用。
     *
     * 诊断里的 `line` 字段在这里表示「第几条步骤」（从 1 开始），
     * 文本解析时则表示「第几行」——两者在定位上是等价的语义。
     */
    fun validateModel(
        header: MacroTemplateHeader,
        steps: List<MacroTemplateStep>,
    ): List<MacroTemplateDiagnostic> = validate(header, steps) { index, _ -> index + 1 }

    /**
     * 编辑器要显示的全量诊断（打开文件时用）。
     *
     * = 模型校验 + 「只在文本里存在、模型表达不出来」的头部诊断（[TEXT_ONLY_HEADER_FIELDS]）。
     *
     * **字符集不取文件那一份**：它原样留在模型里（[MacroTemplateHeader.charset]），
     * [validateModel] 每次都会重算 —— 再带一份文件诊断就会变成「同一个错误显示两条」。
     * 步骤同理：认不出来的那一行是一条 [MacroTemplateStep.Unknown]，由模型校验报，
     * 所以它既会出现步骤列表里（标红）、诊断也只有一条。
     */
    fun editorDiagnostics(
        header: MacroTemplateHeader,
        steps: List<MacroTemplateStep>,
        fileDiagnostics: List<MacroTemplateDiagnostic>,
    ): List<MacroTemplateDiagnostic> =
        validateModel(header, steps) + fileDiagnostics.filter { it.field in TEXT_ONLY_HEADER_FIELDS }

    /**
     * 模型层表达不出来的头部字段：`radix` 是 `Int`、三个间隔是 `Long`，
     * 文件里写的 `radix = 8` / `keyHold = abc` 在解析时就被回退成默认值了，
     * 模型里再也看不出错，只能靠打开文件时留下的那份诊断。
     */
    private val TEXT_ONLY_HEADER_FIELDS = setOf(
        TemplateField.RADIX,
        TemplateField.TEMPLATE_GAP,
        TemplateField.KEY_HOLD,
        TemplateField.KEY_GAP,
        TemplateField.UNKNOWN_PARAM,
    )

    private fun validate(
        header: MacroTemplateHeader,
        steps: List<MacroTemplateStep>,
        locator: (index: Int, step: MacroTemplateStep) -> Int,
    ): List<MacroTemplateDiagnostic> {
        val diagnostics = mutableListOf<MacroTemplateDiagnostic>()

        // 字符集是模板级参数：名字解析不出来就算不出编码值。放在空模板判断之前，
        // 步骤为空时也要报 —— 两个问题互不顶替
        if (!CharsetCatalog.isSupported(header.charset)) {
            diagnostics += error(
                0,
                MacroTemplateIssue.UNKNOWN_CHARSET,
                header.charset,
                TemplateField.CHARSET,
            )
        }

        if (steps.isEmpty()) {
            diagnostics += error(0, MacroTemplateIssue.EMPTY_TEMPLATE)
            return diagnostics
        }

        // 编码位要分开数，三件事才不会互相顶替：
        // - 整张列表里一个 <code> 都没有 → 结构坏了（错误）；
        // - 有 <code> 但一个都没启用 → 只是被勾掉（警告），展开预览仍可测；
        // - 启用的超过一个 → 同一串编码会被发两遍（警告）。这条也只看启用的：
        //   勾掉重复的那个正是这个功能的用法，不该反过来挨一条警告。
        val codeTotal = steps.count { it is MacroTemplateStep.CodeDigits }
        val codeActive = steps.count { it.isActiveCode }
        when {
            codeTotal == 0 -> diagnostics += error(0, MacroTemplateIssue.MISSING_CODE)
            codeActive == 0 -> diagnostics += warning(0, MacroTemplateIssue.CODE_DISABLED)
            codeActive > 1 -> diagnostics += warning(0, MacroTemplateIssue.DUPLICATE_CODE)
        }

        // 头部节奏是模板级参数，普通按键与编码位都取它，所以只报一条、不挂在具体步骤上。
        // 判定用严格小于：界面下限（MIN_KEY_DELAY_MS）设成 10 时不应该反过来报警告。
        val smallestKeyDelay = minOf(header.keyHoldMs, header.keyGapMs)
        if (smallestKeyDelay in 1 until SMALL_DELAY_THRESHOLD_MS) {
            diagnostics += warning(
                0,
                MacroTemplateIssue.SMALL_DELAY_HEADER,
                smallestKeyDelay.toString(),
            )
        }

        // 归一化键名 -> (定位, 原始写法)，用于检查按下/松开是否配对
        val pressed = LinkedHashMap<String, Pair<Int, String>>()
        val heldNormalKeys = mutableSetOf<String>()
        var multipleHeldReported = false

        // 停用的步骤不参与发送，所以也不参与这一轮检查：否则「临时关掉一步」会凭空冒出诊断。
        // 下标仍取它在原列表里的位置，报出来的「第几条」与界面上看到的行号一致。
        steps.withIndex().filter { it.value.enabled }.forEach { (index, step) ->
            val locatorValue = locator(index, step)
            when (step) {
                is MacroTemplateStep.KeyDown -> {
                    val resolved = resolveOrReport(step.keyName, locatorValue, diagnostics) ?: return@forEach
                    val normalized = MacroTemplateKeys.normalize(step.keyName)
                    pressed[normalized] = locatorValue to step.keyName
                    if (!resolved.isModifier) {
                        heldNormalKeys += normalized
                        if (heldNormalKeys.size > 1 && !multipleHeldReported) {
                            multipleHeldReported = true
                            diagnostics += warning(
                                locatorValue,
                                MacroTemplateIssue.MULTIPLE_HELD_KEYS,
                                heldNormalKeys.joinToString(" + "),
                            )
                        }
                    }
                }

                is MacroTemplateStep.KeyUp -> {
                    val resolved = resolveOrReport(step.keyName, locatorValue, diagnostics) ?: return@forEach
                    val normalized = MacroTemplateKeys.normalize(step.keyName)
                    pressed.remove(normalized)
                    if (!resolved.isModifier) heldNormalKeys -= normalized
                }

                is MacroTemplateStep.KeyTap ->
                    resolveOrReport(step.keyName, locatorValue, diagnostics)

                // 组合键：每个名字都要认得出；普通键多于一个时提醒（键盘报告只有一个普通键
                // 槽位，多出来的会把前一个悄悄挤掉）
                is MacroTemplateStep.ChordTap -> {
                    step.keyNames.forEach { name ->
                        resolveOrReport(name, locatorValue, diagnostics)
                    }
                    val plain = step.plainKeyNames()
                    if (plain.size > 1) {
                        diagnostics += warning(
                            locatorValue,
                            MacroTemplateIssue.MULTIPLE_HELD_KEYS,
                            plain.joinToString(" + "),
                        )
                    }
                }

                is MacroTemplateStep.Delay ->
                    if (step.ms in 1 until SMALL_DELAY_THRESHOLD_MS) {
                        diagnostics += warning(
                            locatorValue,
                            MacroTemplateIssue.SMALL_DELAY,
                            step.ms.toString(),
                        )
                    }

                // 编码位的节奏取头部参数，过短的检查已上移到模板级（SMALL_DELAY_HEADER）
                is MacroTemplateStep.CodeDigits -> Unit

                // 认不出来的一行：解析时把原文原样留在模型里（不清掉），诊断在这里报 ——
                // 编辑器因此既能看到这一行、又能看到它是哪种错
                is MacroTemplateStep.Unknown ->
                    diagnostics += error(locatorValue, step.issue, step.arg)
            }
        }

        // 按下没松开的键报**错误**，不是警告：后果是确定的，不是「可能」——
        // 按住的键在宿主端会连发（一个字符一直打印），修饰键则会粘住后面所有字符
        // （Alt 卡住 → 之后每一步都带着 Alt 发出去）。发送收尾那帧空报告只能在整个文本
        // 发完之后兜底，中途该拦就得拦。
        pressed.values.forEach { (locatorValue, keyName) ->
            diagnostics += error(locatorValue, MacroTemplateIssue.UNBALANCED_KEY, keyName)
        }
        return diagnostics
    }

    private fun resolveOrReport(
        keyName: String,
        locator: Int,
        diagnostics: MutableList<MacroTemplateDiagnostic>,
    ): MacroTemplateKeys.ResolvedKey? {
        val resolved = MacroTemplateKeys.resolve(keyName)
        if (resolved == null) {
            diagnostics += MacroTemplateDiagnostic(
                line = locator,
                severity = TemplateSeverity.ERROR,
                issue = MacroTemplateIssue.UNKNOWN_KEY,
                arg = keyName,
                suggestion = MacroTemplateKeys.suggest(keyName),
            )
        }
        return resolved
    }

    // ─────────────────────────────────────────────
    // 说明字段：唯一能换行的头部参数
    // ─────────────────────────────────────────────

    /**
     * 说明读回来的取值 —— 两种写法都认：
     * - **裸串**：`description = Alt Code for Windows`，整行原样收下；
     * - **带引号**：`description = "第一行\n第二行"`，剥掉外层引号后按 [unescape] 还原转义。
     *
     * 只有**两头都是引号**才算带引号的形式：`description = Alt "Code"` 里的引号是正文，
     * 一并剥掉的话，用户会发现「改一次说明，引号莫名其妙没了」。
     *
     * 空说明（`description =` 与 `description = ""`）一律按**没写**处理，返回 null ——
     * 否则文件里会多出一条空的 `description =`，与「从没写过」长得不一样。
     *
     * 传进来的 [raw] 已经过整行 `trim`（头部参数的通用规则），带引号时两侧的空白由引号本身
     * 保护，所以「首尾带空白的说明」必须写成带引号的形式才留得住。
     */
    private fun parseDescription(raw: String): String? {
        val text = if (raw.length >= 2 && raw.startsWith(QUOTE) && raw.endsWith(QUOTE)) {
            unescape(raw.substring(1, raw.length - 1))
        } else {
            raw
        }
        return text.ifBlank { null }
    }

    /**
     * 说明写回文件时的形态：能写裸串就写裸串，否则加引号并转义。
     *
     * 为什么不一律加引号：手写的模板里 `description = Alt Code` 比 `description = "Alt Code"`
     * 好读，而绝大多数说明都没有换行。只有 [needsQuoting] 那三种「裸串读不回原样」的情况
     * 才退到带引号的形式。
     */
    private fun formatDescription(text: String): String =
        if (needsQuoting(text)) "$QUOTE${escape(text)}$QUOTE" else text

    /**
     * 这段说明能不能原样写成裸串？不能的三种情况各对应一条「读不回来」：
     * - **含换行**：文件是行式的，换行写进去会断成两行，第二行会被当成一条步骤；
     * - **含双引号**：两头都是引号时会被当成带引号的形式，引号被剥掉；
     * - **首尾有空白**：读的时候整行要 trim，两侧空白会丢。
     *
     * 第一种只有 `\n` / `\r` 两个字符，第二种只判 `"` —— 不是「含反斜杠」也要加引号：
     * 裸串里不解析转义，`C:\path` 这样的说明原样进原样出。
     */
    private fun needsQuoting(text: String): Boolean =
        text != text.trim() || text.any { it == '\n' || it == '\r' || it == QUOTE }

    /** 把说明转成能放进一行引号里的形式（[unescape] 的逆运算） */
    private fun escape(text: String): String = buildString {
        text.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                QUOTE -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
    }

    /**
     * 还原带引号说明里的转义。
     *
     * 只认 `\\` `\"` `\n` `\r` 四种；认不出来的转义（`\t` 之类）**原样保留** ——
     * 说明是自由文本，这里不替用户猜那个反斜杠是什么意思。
     */
    private fun unescape(text: String): String = buildString {
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            val next = if (ch == '\\' && index + 1 < text.length) text[index + 1] else null
            when (next) {
                '\\' -> { append('\\'); index += 2 }
                QUOTE -> { append(QUOTE); index += 2 }
                'n' -> { append('\n'); index += 2 }
                'r' -> { append('\r'); index += 2 }
                else -> { append(ch); index++ }
            }
        }
    }

    // ─────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────

    private fun parseMillis(
        value: String,
        fallback: Long,
        line: Int,
        field: TemplateField,
        diagnostics: MutableList<MacroTemplateDiagnostic>,
    ): Long {
        val parsed = value.toLongOrNull()
        return if (parsed != null && parsed >= 0) {
            parsed
        } else {
            diagnostics += error(line, MacroTemplateIssue.INVALID_NUMBER, value, field)
            fallback
        }
    }

    /**
     * 这条步骤连主体都齐了吗？
     *
     * 只服务「注释行算不算停用步骤」这一处判断：`; up` 这种残句不该被认成一条键名为空的步骤
     * （否则它会以「步骤」的身份出现在编辑器里，让人莫名其妙）。正常写在文件里的 `up`
     * 不在此列 —— 它照旧进模型，由 [validateModel] 报 UNKNOWN_KEY。
     */
    private fun MacroTemplateStep.isComplete(): Boolean = when (this) {
        is MacroTemplateStep.KeyDown -> keyName.isNotBlank()
        is MacroTemplateStep.KeyUp -> keyName.isNotBlank()
        is MacroTemplateStep.KeyTap -> keyName.isNotBlank()
        is MacroTemplateStep.Delay, is MacroTemplateStep.CodeDigits -> true
        // 组合键在解析时就要求每一段都非空（`tap Ctrl+` 直接判无效值），所以到这里一定齐
        is MacroTemplateStep.ChordTap -> true
        // 认不出来的一行不能当「停用的步骤」：`; slee 20` 是说明文字，不是被停用的步骤
        is MacroTemplateStep.Unknown -> false
    }

    /**
     * 整行注释：`;` 或 `#` 开头。
     *
     * 这里刻意不支持行内注释——`tap ;`、`tap #` 都是合法的按键写法，
     * 行内注释会把它们吃掉。
     */
    private fun isCommentLine(line: String): Boolean =
        line.startsWith(";") || line.startsWith("#")

    private fun error(
        line: Int,
        issue: MacroTemplateIssue,
        arg: String? = null,
        field: TemplateField? = null,
    ) = MacroTemplateDiagnostic(line, TemplateSeverity.ERROR, issue, arg, field = field)

    private fun warning(
        line: Int,
        issue: MacroTemplateIssue,
        arg: String? = null,
        field: TemplateField? = null,
    ) = MacroTemplateDiagnostic(line, TemplateSeverity.WARNING, issue, arg, field = field)
}
