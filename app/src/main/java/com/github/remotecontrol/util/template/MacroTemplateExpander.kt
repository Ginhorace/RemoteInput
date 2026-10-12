package com.github.remotecontrol.util.template

import java.nio.charset.Charset

/**
 * 模板展开器：把「模板 + 字符」展开成最终要发送的报告序列。
 *
 * 编辑器里的预览和后续的实际发送共用这一份逻辑，
 * 保证「预览所见」就是「实际所发」。
 *
 * 修饰键和普通键的区别在于：修饰键进入报告的 modifier 字节，
 * 可以多个同时按下；普通键占报告里唯一的 keyCode 槽位，同时只能有一个，
 * 因此这里分别用 modifier 位掩码和 heldKey 跟踪状态。
 */
object MacroTemplateExpander {

    /**
     * 一帧报告。
     *
     * @param label 展开来源，例如 `down Alt` / `code 5` / `sleep 20`
     * @param modifier 修饰键位掩码
     * @param keyCode 按键 HID Usage ID，0 表示没有普通键按下
     * @param durationMs 发出该帧之后等待的时长
     * @param isDelay 是否是纯等待（预览时可以弱化显示）
     */
    data class Frame(
        val label: String,
        val modifier: Int,
        val keyCode: Int,
        val durationMs: Long,
        val isDelay: Boolean = false,
    )

    /**
     * 按 [sample] 里的每个「注入单位」依次展开模板，两次展开之间插入 `templateGap`。
     *
     * 一个模板只注入一个值，所以这个间隔实际落在相邻两次注入之间；
     * 首次之前、末次之后都没有等待。切法见 [injectionUnits]。
     */
    fun expand(template: MacroTemplate, sample: String): List<Frame> {
        val frames = mutableListOf<Frame>()
        injectionUnits(template.header.charset, sample).forEachIndexed { index, value ->
            if (index > 0 && template.header.templateGapMs > 0) {
                frames += Frame(
                    label = "templateGap",
                    modifier = frames.lastOrNull()?.modifier ?: 0,
                    keyCode = frames.lastOrNull()?.keyCode ?: 0,
                    durationMs = template.header.templateGapMs,
                    isDelay = true,
                )
            }
            expandUnit(template, value, frames)
        }
        return frames
    }

    /**
     * 把样本切成「一次注入一个值」的序列 —— **切法由字符集口径决定**，这是最容易走叉的一处：
     *
     * - 码点口径（`Unicode` / `UTF-32`）：按**完整码点**切。`😀` 是一个单位，
     *   对应 Linux `Ctrl+Shift+U` 要的 1~6 位十六进制；
     * - 代码单元 / 代码页口径：按 `Char` 切。`😀` 是两个单位，各自配一次模板 ——
     *   Windows 十六进制、Word `Alt+X`、macOS Unicode Hex Input 要的就是逐步补代理对。
     */
    fun injectionUnits(charsetName: String, sample: String): List<Int> =
        if (usesCodePointCharset(charsetName)) {
            sample.codePoints().toArray().toList()
        } else {
            sample.map { it.code }
        }

    /** 一个「这套字符集编不出来」的注入单位 */
    data class UnencodableUnit(
        /** 原文里那一段。辅助平面是**完整的一对**代理项，不拆开显示 */
        val text: String,
        /** 单位的码点 —— 代码页口径按 `Char` 切，落在代理项后半时取到的是那半个值 */
        val codePoint: Int,
        /** 它在文本里的下标（`String` 的 char 下标，从 0 起） */
        val index: Int,
    )

    /**
     * 找出**第一个**这套字符集编不出来的注入单位；都编得出来返回 `null`。
     *
     * 发送前用它拦一道：展开时遇到编不出来的单位是**静默跳过**（那个字符不发），
     * 不拦的话用户只会看到少打了一个字，还以为是自己粘错了。
     *
     * 判定口径与发送一致（都按 [injectionUnits] 的同一切法）：
     *
     * - `Unicode` / `UTF-32` / `UTF-16*` 直接返回 `null`：这几个口径取的就是数值本身
     *   （见 [encodeValue]），不存在「编不出来」这回事，连注册表都不用查；
     * - 其余（代码页）按 `Char` 逐个问编码器：复用**同一个** `CharsetEncoder` 实例，
     *   碰到第一个就收工 —— 全编得出来的正常文本也只付一次线性扫描，不会为每个字符
     *   新建编码器（那是 [encodeValue] 逐单位做的重活，这里刻意不重复）。
     *
     * 字符集名字认不出来（`UNKNOWN_CHARSET`）**不在这里报**：那是解析与诊断那条线的事，
     * 这里只回答「这个字符编不编得出来」。
     */
    fun firstUnencodable(header: MacroTemplateHeader, text: String): UnencodableUnit? {
        val charsetName = header.charset
        val upper = charsetName.trim().uppercase()
        if (usesCodePointCharset(charsetName) || upper.startsWith("UTF-16")) return null
        if (text.isEmpty()) return null

        val encoder = try {
            Charset.forName(charsetName).newEncoder()
        } catch (_: Exception) {
            return null
        }

        text.forEachIndexed { index, char ->
            if (!encoder.canEncode(char)) {
                val codePoint = text.codePointAt(index)
                return UnencodableUnit(
                    text = String(Character.toChars(codePoint)),
                    codePoint = codePoint,
                    index = index,
                )
            }
        }
        return null
    }

    /** 该字符集是否按「完整码点」注入（而不是代码单元 / 代码页字节） */
    private fun usesCodePointCharset(charsetName: String): Boolean {
        val name = charsetName.trim().uppercase()
        // `UTF-32` 不在下拉里，但可以手写，语义上同样是完整码点
        return name == "UNICODE" || name.startsWith("UTF-32")
    }

    /** 统计展开后的总时长，供编辑器显示 */
    fun totalDurationMs(frames: List<Frame>): Long = frames.sumOf { it.durationMs }

    private fun expandUnit(template: MacroTemplate, value: Int, frames: MutableList<Frame>) {
        val header = template.header
        var modifier = 0
        var heldKey = 0

        for (step in template.steps) {
            // 停用的步骤整个跳过：它就是文件里被注释掉的那一行
            if (!step.enabled) continue
            when (step) {
                is MacroTemplateStep.KeyDown -> {
                    val resolved = MacroTemplateKeys.resolve(step.keyName) ?: continue
                    if (resolved.isModifier) {
                        modifier = modifier or resolved.modifierMask
                        frames += Frame("down ${step.keyName}", modifier, heldKey, header.keyHoldMs)
                    } else {
                        heldKey = resolved.key.value.toInt() and 0xFF
                        frames += Frame(
                            label = "down ${step.keyName}",
                            // 修饰位只取「已经按下的修饰键」：键名自己不隐含 Shift（见 MacroTemplateKeys）
                            modifier = modifier,
                            keyCode = heldKey,
                            durationMs = header.keyHoldMs,
                        )
                    }
                }

                is MacroTemplateStep.KeyUp -> {
                    val resolved = MacroTemplateKeys.resolve(step.keyName) ?: continue
                    if (resolved.isModifier) {
                        modifier = modifier and resolved.modifierMask.inv()
                        frames += Frame("up ${step.keyName}", modifier, heldKey, header.keyHoldMs)
                    } else {
                        heldKey = 0
                        frames += Frame("up ${step.keyName}", modifier, 0, header.keyHoldMs)
                    }
                }

                is MacroTemplateStep.KeyTap -> {
                    val resolved = MacroTemplateKeys.resolve(step.keyName) ?: continue
                    if (resolved.isModifier) {
                        modifier = modifier or resolved.modifierMask
                        frames += Frame("tap ${step.keyName}", modifier, heldKey, header.keyHoldMs)
                        modifier = modifier and resolved.modifierMask.inv()
                        frames += Frame("release ${step.keyName}", modifier, heldKey, header.keyGapMs)
                    } else {
                        frames += Frame(
                            label = "tap ${step.keyName}",
                            modifier = modifier,
                            keyCode = resolved.key.value.toInt() and 0xFF,
                            durationMs = header.keyHoldMs,
                        )
                        frames += Frame("release ${step.keyName}", modifier, heldKey, header.keyGapMs)
                    }
                }

                // 组合键按「拆分后的多步」展开，顺序与 [MacroTemplateStep.ChordTap.split] 一致，
                // 所以一步写完与拆开多步发出的帧**完全相同**（有单测钉住这条不变量）：
                // 修饰键按写的顺序按下 → 普通键点按 → 修饰键逆序松开
                is MacroTemplateStep.ChordTap -> {
                    for (name in step.modifierNames()) {
                        val resolved = MacroTemplateKeys.resolve(name) ?: continue
                        modifier = modifier or resolved.modifierMask
                        frames += Frame("down $name", modifier, heldKey, header.keyHoldMs)
                    }
                    for (name in step.plainKeyNames()) {
                        val resolved = MacroTemplateKeys.resolve(name) ?: continue
                        frames += Frame(
                            label = "tap $name",
                            modifier = modifier,
                            keyCode = resolved.key.value.toInt() and 0xFF,
                            durationMs = header.keyHoldMs,
                        )
                        frames += Frame("release $name", modifier, heldKey, header.keyGapMs)
                    }
                    for (name in step.modifierNames().reversed()) {
                        val resolved = MacroTemplateKeys.resolve(name) ?: continue
                        modifier = modifier and resolved.modifierMask.inv()
                        frames += Frame("up $name", modifier, heldKey, header.keyHoldMs)
                    }
                }

                is MacroTemplateStep.Delay ->
                    frames += Frame("sleep ${step.ms}", modifier, heldKey, step.ms, isDelay = true)

                is MacroTemplateStep.CodeDigits -> {
                    val digits = encodeDigits(value, header.charset, header.radix)?.padStart(step.padDigits, '0')
                        ?: continue
                    for (digit in digits) {
                        val resolved = MacroTemplateKeys.resolve(digitKeyName(digit, step.useNumpad)) ?: continue
                        frames += Frame(
                            label = "code $digit",
                            // 编码位也只用「已经按下的修饰键」：主键盘上的 a–f 不按 Shift 就是小写，
                            // 要送大写由使用者在 `<code>` 外面自己包 down Shift / up Shift
                            //（与「键名不隐含 Shift」同一口径）
                            modifier = modifier,
                            keyCode = resolved.key.value.toInt() and 0xFF,
                            durationMs = header.keyHoldMs,
                        )
                        frames += Frame("release $digit", modifier, heldKey, header.keyGapMs)
                    }
                }

                // 认不出来的一行：什么都不发。它必然带着一条错误诊断，发送早被 hasError 拦住，
                // 所以这里「跳过」不会变成静默发错
                is MacroTemplateStep.Unknown -> Unit
            }
        }
    }

    /**
     * 计算一个注入单位在指定字符集下的编码数字串。
     *
     * 多字节代码页取双字节大端（GBK 的「中」→ `D6D0` → 十进制 `54992`）；
     * 码点 / 代码单元口径直接取数值本身（`😀` 在 `Unicode` 下是一串 `1F600`，
     * 在 `UTF-16` 下是 `D83D` 与 `DE00` 两串）。单位由 [injectionUnits] 决定。
     */
    fun encodeDigits(value: Int, charsetName: String, radix: Int): String? {
        val encoded = encodeValue(value, charsetName) ?: return null
        return encoded.toString(radix).uppercase()
    }

    /**
     * 计算一个注入单位的编码值，无法编码时返回 null。
     *
     * @param value 该字符集口径下的单位（见 [injectionUnits]）：码点口径下是完整码点，
     *   `UTF-16` 下是一个代码单元（代理项的一半），代码页下是一个 `Char`。
     */
    fun encodeValue(value: Int, charsetName: String): Int? {
        return try {
            val upper = charsetName.trim().uppercase()
            when {
                // 码点 / 代码单元口径直接用数值本身：走编码器会被 UTF-16 的 BOM 污染
                usesCodePointCharset(charsetName) || upper.startsWith("UTF-16") -> value
                else -> {
                    val char = value.toChar()
                    val charset = Charset.forName(charsetName)
                    if (!charset.newEncoder().canEncode(char)) return null
                    val bytes = charset.encode(char.toString())
                    when {
                        bytes.limit() >= 2 ->
                            ((bytes.get(0).toInt() and 0xFF) shl 8) or (bytes.get(1).toInt() and 0xFF)
                        bytes.limit() == 1 -> bytes.get(0).toInt() and 0xFF
                        else -> null
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 编码数字用哪个物理键：0-9 按需走小键盘，`a`–`f` 固定走主键盘。
     *
     * `A`–`F` 与 `a`–`f` 是同一个物理键（HID 的 `0x04` 就叫 `A`），[encodeDigits] 大写之后
     * 这里再取小写键名 —— 展开出来的帧**不带 Shift**，宿主侧收到的是小写；要送大写由使用者
     * 在 `<code>` 外面自己包 `down Shift` / `up Shift`（与「键名不隐含 Shift」同一口径）。
     */
    private fun digitKeyName(digit: Char, useNumpad: Boolean): String = when {
        digit in '0'..'9' -> if (useNumpad) "Numpad$digit" else digit.toString()
        else -> digit.lowercaseChar().toString()
    }
}
