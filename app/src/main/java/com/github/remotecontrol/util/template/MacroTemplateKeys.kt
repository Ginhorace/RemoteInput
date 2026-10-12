package com.github.remotecontrol.util.template

import com.github.remotecontrol.util.KeyboardConstants
import com.github.remotecontrol.util.KeyboardConstants.Key

/**
 * 模板键名解析：把模板里的字符串（`Alt` / `Numpad5` / `Enter` / `a` / `;`）
 * 解析成 [KeyboardConstants.Keys] 里已有的按键常量。
 *
 * ## 键名只表示物理键，永远不隐含 Shift
 *
 * 报告里的 modifier 位只由模板里**显式写下的修饰键**给出（`tap Shift+2`、
 * `down Shift` ……），一个键名自己不会带出 Shift：
 * - `a` = 不按 Shift 的 A 键（宿主收到的是 `a`），要打大写得写 `tap Shift+a`；
 * - `@` 这一类的**上档字符不是键名**：`tap @` 报未知键，正确写法是 `tap Shift+2`
 *   （编辑器在步骤行把这类步骤的产出字符标成副标签，仅作展示、不写进文件）。
 *
 * 数据来源完全复用现有代码，不新增任何键码常量：
 * - 具名键（含别名）走 [KeyboardConstants.Keys] 的反射字段表；
 * - 单字符键查 [KeyboardConstants.Keys.charToKeyMap]，但只认主档那一半（见 [resolve]）。
 */
object MacroTemplateKeys {

    /**
     * @param key 匹配到的按键常量
     * @param modifierMask 修饰键位掩码，非修饰键为 0
     */
    data class ResolvedKey(
        val key: Key,
        val modifierMask: Int,
    ) {
        val isModifier: Boolean get() = modifierMask != 0
    }

    /** 常用别名 -> [KeyboardConstants.Keys] 中的字段名 */
    private val aliases: Map<String, String> = buildMap {
        put("CTRL", "LEFT_CTRL")
        put("CONTROL", "LEFT_CTRL")
        put("LCTRL", "LEFT_CTRL")
        put("RCTRL", "RIGHT_CTRL")
        put("RCONTROL", "RIGHT_CTRL")
        put("ALT", "LEFT_ALT")
        put("LALT", "LEFT_ALT")
        put("RALT", "RIGHT_ALT")
        put("ALTGR", "RIGHT_ALT")
        put("SHIFT", "LEFT_SHIFT")
        put("LSHIFT", "LEFT_SHIFT")
        put("RSHIFT", "RIGHT_SHIFT")
        put("WIN", "LEFT_WIN")
        put("GUI", "LEFT_WIN")
        put("META", "LEFT_WIN")
        put("SUPER", "LEFT_WIN")
        put("LWIN", "LEFT_WIN")
        put("RWIN", "RIGHT_WIN")

        put("RETURN", "ENTER")
        put("ESCAPE", "ESC")
        put("SPACEBAR", "SPACE")
        put("BS", "BACKSPACE")
        put("BKSP", "BACKSPACE")
        put("DEL", "DELETE")
        put("INS", "INSERT")
        put("PGUP", "PAGE_UP")
        put("PGDN", "PAGE_DOWN")
        put("UP", "ARROW_UP")
        put("DOWN", "ARROW_DOWN")
        put("LEFT", "ARROW_LEFT")
        put("RIGHT", "ARROW_RIGHT")

        for (digit in 0..9) {
            put("NUMPAD$digit", "KP_NUM_$digit")
            put("NP$digit", "KP_NUM_$digit")
            put("KP$digit", "KP_NUM_$digit")
        }
        put("NUMPADADD", "KP_PLUS")
        put("NUMPADPLUS", "KP_PLUS")
        put("NPADD", "KP_PLUS")
        put("NUMPADSUB", "KP_MINUS")
        put("NUMPADMINUS", "KP_MINUS")
        put("NPSUB", "KP_MINUS")
        put("NUMPADMULT", "KP_MULTIPLY")
        put("NUMPADMULTIPLY", "KP_MULTIPLY")
        put("NPMULT", "KP_MULTIPLY")
        put("NUMPADDIV", "KP_DIVIDE")
        put("NUMPADDIVIDE", "KP_DIVIDE")
        put("NPDIV", "KP_DIVIDE")
        put("NUMPADDOT", "KP_DOT")
        put("NUMPADENTER", "KP_ENTER")
    }

    /** 归一化：忽略大小写、下划线与空白 */
    fun normalize(raw: String): String =
        raw.trim().uppercase().filter { it != '_' && !it.isWhitespace() }

    private val byName: Map<String, Key> by lazy {
        val fields = buildMap<String, Key> {
            for (field in KeyboardConstants.Keys::class.java.declaredFields) {
                if (field.type != Key::class.java) continue
                field.isAccessible = true
                put(normalize(field.name), field.get(KeyboardConstants.Keys) as Key)
            }
        }
        val merged = fields.toMutableMap()
        for ((alias, target) in aliases) {
            fields[normalize(target)]?.let { merged[normalize(alias)] = it }
        }
        merged
    }

    /** 解析失败返回 null */
    fun resolve(raw: String): ResolvedKey? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // 单字符优先：`a` / `1` / `;` / `[` 等。charToKeyMap 里主档与上档并存
        // （`2` 与 `@` 指向同一个键），这里只认主档那一半 —— 词表里没有「按着 Shift 的键」，
        // 上档字符要么由下面的 byName 兜底，要么报未知键
        if (trimmed.length == 1) {
            KeyboardConstants.Keys.charToKeyMap[trimmed[0]]
                ?.takeUnless { it.needsShift }
                ?.let { return ResolvedKey(it, it.modifierType.toMask()) }
        }

        // 具名键：`a`–`z` 的物理键名恰好就是大写字母（HID 的 0x04 就叫 "A"），
        // 所以 `tap A` 仍然解析得出来 —— 它同样是不按 Shift 的 A 键
        val key = byName[normalize(trimmed)] ?: return null
        return ResolvedKey(key, key.modifierType.toMask())
    }

    /** 是否是修饰键（Ctrl / Alt / Shift / Win 及其左右变体） */
    fun isModifier(raw: String): Boolean = resolve(raw)?.isModifier == true

    /** 是不是某个 Shift（左右都算）—— 步骤副标签只认「Shift 单独按住」那种写法 */
    fun isShift(raw: String): Boolean {
        val key = resolve(raw)?.key ?: return false
        return key == KeyboardConstants.Keys.LEFT_SHIFT || key == KeyboardConstants.Keys.RIGHT_SHIFT
    }

    /**
     * 这个键名对应的键**按住 Shift 时**输出的字符，界面副标签用；该键没有上档档位时返回 null。
     *
     * 注意这与 [resolve] 的口径不同：键名本身不表示 Shift，所以这里取的是「另写一个 `Shift`」
     * 之后的结果 —— 纯展示，不会因此给展开出来的帧补上 Shift 位。
     */
    fun shiftedCharOf(raw: String): Char? = resolve(raw)?.key?.shiftedChar

    /** 这个写法是不是某个键的**上档档位**（`@` / `!` / 大写字母），也就是 [resolve] 不认的那些单字符 */
    private fun isShiftedChar(raw: String): Boolean {
        val trimmed = raw.trim()
        return trimmed.length == 1 && KeyboardConstants.Keys.charToKeyMap[trimmed[0]]?.needsShift == true
    }

    /** 用来给「未知键名」提示最近似的候选，提升可编辑性 */
    fun suggest(raw: String): String? {
        val target = normalize(raw)
        if (target.isEmpty()) return null
        // 上档字符（`@` / `!`）根本不是键名，按编辑距离猜出来的候选（`2` / `5`）只会把人带偏：
        // 想发这个字符得把 `Shift` 当成修饰键写出来，那已经不是「一个键名」能表达的事
        if (isShiftedChar(raw)) return null
        return byName.keys
            .filter { kotlin.math.abs(it.length - target.length) <= 2 }
            .minByOrNull { distance(it, target) }
            ?.takeIf { distance(it, target) <= 2 }
            ?.let { normalized -> knownDisplayName(normalized) }
    }

    private fun knownDisplayName(normalized: String): String? =
        byName.entries.firstOrNull { it.key == normalized }?.value?.displayName

    private fun distance(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            current.copyInto(prev)
        }
        return prev[b.length]
    }

    private fun KeyboardConstants.ModifierKeyType?.toMask(): Int = when (this) {
        KeyboardConstants.ModifierKeyType.LeftControl -> 0x01
        KeyboardConstants.ModifierKeyType.LeftShift -> 0x02
        KeyboardConstants.ModifierKeyType.LeftAlt -> 0x04
        KeyboardConstants.ModifierKeyType.LeftGUI -> 0x08
        KeyboardConstants.ModifierKeyType.RightControl -> 0x10
        KeyboardConstants.ModifierKeyType.RightShift -> 0x20
        KeyboardConstants.ModifierKeyType.RightAlt -> 0x40
        KeyboardConstants.ModifierKeyType.RightGUI -> 0x80
        null -> 0
    }
}
