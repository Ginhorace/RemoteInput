package com.github.remotecontrol.util

/**
 * 键盘控制常量
 * 包含键盘按键的显示名称、蓝牙HID编码和布局属性
 */
object KeyboardConstants {

    /**
     * 键盘 HID 输入报告
     * @param modifier 修饰键位掩码
     * @param keyCode 按键 HID Usage ID
     */
    data class KeyboardReport(
        val modifier: Byte = 0,
        val keyCode: Byte = 0
    ) {
        fun toByteArray(): ByteArray {
            val report = ByteArray(8)
            report[0] = modifier
            report[2] = keyCode
            return report
        }
    }

    /**
     * 键盘灯（Num / Caps / Scroll）状态，由主机经 LED Output Report 下发。
     *
     * 键盘灯是**主机 → 设备**的单向信息：设备侧改不了它，只能如实显示主机告诉它的结果，
     * 所以这是「主机现在认为的三个灯」，不是「用户点了什么」。
     */
    data class KeyboardLeds(
        val numLock: Boolean = false,
        val capsLock: Boolean = false,
        val scrollLock: Boolean = false,
    ) {

        /**
         * 这个键位上挂着哪盏灯，返回它的亮灭；不挂灯返回 null。
         *
         * 挂灯的就是实体键盘上自己带指示灯的那三颗锁定键 —— Caps(0x39) / ScrLk(0x47) / NumLk(0x53)。
         * 灯条用它把「哪一个按钮对应哪一盏灯」和「这盏灯亮没亮」对上。
         */
        fun lampFor(keyValue: Byte): Boolean? = when (keyValue) {
            Keys.CAPS_LOCK.value -> capsLock
            Keys.SCROLL_LOCK.value -> scrollLock
            Keys.NUM_LOCK.value -> numLock
            else -> null
        }

        /** 按下 [keyValue] 这颗锁键之后的灯态（锁键是翻转语义）；不是锁键返回 null */
        fun toggledBy(keyValue: Byte): KeyboardLeds? = when (keyValue) {
            Keys.CAPS_LOCK.value -> copy(capsLock = !capsLock)
            Keys.SCROLL_LOCK.value -> copy(scrollLock = !scrollLock)
            Keys.NUM_LOCK.value -> copy(numLock = !numLock)
            else -> null
        }
    }

    /**
     * 界面用的灯态：三个灯的值 + 这份值**是不是主机确认过的**。
     *
     * 键盘灯是主机 → 设备的单向信息，设备侧没有查询手段（HID 里没有这种请求），
     * 所以存在两种可信度不同的来源：
     *
     * | [confirmed] | 来源 | 可信度 |
     * | --- | --- | --- |
     * | `true` | 主机的 LED Output Report | 权威值 |
     * | `false` | 本应用按下的锁键累计翻转（见 [inferredAfterPress]） | 推测，可能已经和主机不一致 |
     *
     * 之所以要带着 [confirmed] 一起显示，是因为主机**不上报时界面就没有真值可显示**，
     * 那时只能给一个标注过的推测值，而不是假装不知道、也不是假装知道。
     */
    data class KeyboardLedsState(
        val leds: KeyboardLeds = KeyboardLeds(),
        val confirmed: Boolean = false,
    ) {

        /**
         * 本应用按下一颗锁键之后的推断态。
         *
         * 主机已经确认过就**不动**：它接下来会自己把新灯态推回来，本地抢先改只会先闪一下错的
         * （而且推断和主机结果一致的话，这一改本身就是多余的）。
         */
        fun inferredAfterPress(keyValue: Byte): KeyboardLedsState =
            if (confirmed) this
            else leds.toggledBy(keyValue)?.let { copy(leds = it) } ?: this

        /** 降级为推测：链路断开后主机那个值不再代表现状，但作为下次连接的初值仍然有用 */
        fun demoted(): KeyboardLedsState = if (confirmed) copy(confirmed = false) else this
    }

    /** LED 位图的位定义，与描述符里 `Usage Min 0x01 (Num Lock) / Max 0x05` 的顺序一致 */
    private const val LED_BIT_NUM_LOCK = 0x01
    private const val LED_BIT_CAPS_LOCK = 0x02
    private const val LED_BIT_SCROLL_LOCK = 0x04

    /**
     * 解析键盘 LED Output Report 的负荷，返回三个灯位。
     *
     * 负荷只有 1 字节，但主机给 [data] 的形态不止一种，三种都要认：
     *
     * | 形态 | data | 位图 |
     * | --- | --- | --- |
     * | 报告协议，不含报告 ID | `[bits]` | `data[0]` |
     * | 报告协议，把报告 ID 也带上了 | `[0x02, bits]` | `data[1]` |
     * | Boot 协议（报告 ID 为 0） | `[bits]` | `data[0]` |
     *
     * 第 2 种与「1 字节负荷 + 1 字节填充」在字面上无法区分，但 LED 报告本来就只有 1 字节负荷、
     * 多出来的字节只可能是填充，所以按**长度恰好为 2 且首字节等于键盘报告 ID** 来判定。
     *
     * @return 灯态；[data] 为空时返回 null（这不是一条可用的报告）
     */
    fun parseKeyboardLeds(data: ByteArray?): KeyboardLeds? {
        if (data == null || data.isEmpty()) return null

        val bits = (if (data.size == 2 && data[0].toInt() == HidReportDescriptors.REPORT_ID_KEYBOARD) {
            data[1]
        } else {
            data[0]
        }).toInt() and 0xFF

        return KeyboardLeds(
            numLock = (bits and LED_BIT_NUM_LOCK) != 0,
            capsLock = (bits and LED_BIT_CAPS_LOCK) != 0,
            scrollLock = (bits and LED_BIT_SCROLL_LOCK) != 0,
        )
    }

    /**
     * 键盘按键定义
     * @property displayName 显示在界面上的文本
     * @property value 蓝牙HID编码值
     * @property weight 按键宽度权重（默认1.0f）
     * @property isModifier 是否为修饰键（Ctrl/Shift/Alt等）
     * @property modifierType 修饰键类型（如果是修饰键）
     * @property isSpacer 布局占位：只占宽度，不绘制、不可点（方向键反 T 形排布用）
     * @property rowSpan 纵向占几行（默认 1）；小键盘的 KP+ / KP Enter 占两行高
     * @property fontSize 字体大小（根据显示名称长度自动计算）
     */
    data class Key(
        val displayName: String,
        val value: Byte,
        val weight: Float = 1.0f,
        val isModifier: Boolean = false,
        val needsShift: Boolean = false,
        val modifierType: ModifierKeyType? = null,
        /** 按键直接按下时输入的字符，null 表示不可直接用于文本输入 */
        val char: Char? = null,
        /** 按下 Shift 时输入的字符，null 表示此键无 Shift 上档字符 */
        val shiftedChar: Char? = null,
        /** 布局占位：只按 [weight] 占宽度，不绘制也不响应点击 */
        val isSpacer: Boolean = false,
        /** 纵向占几行：2 表示跨两行的高度（下一行的同列要放占位键让出位置） */
        val rowSpan: Int = 1,
    ) {
        /**
         * 键位文字大小：只分两档 —— 单字符 15sp，其余 13sp。
         *
         * 键位上的空间其实够（最窄的键也有 40dp+ 宽），档位分太细反而让同屏字号不齐；
         * 字重由绘制侧取 Medium。最长标签「KP Enter」(8 字符 → 13sp) 在小键盘单键里也放得下。
         *
         * 规则本身在 [keyLabelFontSize]：键盘页的键位文字不再直接取自 [displayName]
         * （宿主键盘布局会换掉图例），绘制侧拿的是 `KeyLegend.label`，两边必须同一个档位。
         */
        val fontSize: Int
            get() = keyLabelFontSize(displayName)
    }
    
    /**
     * 修饰键类型枚举
     */
    enum class ModifierKeyType {
        LeftControl, LeftShift, LeftAlt, LeftGUI, RightControl, RightShift, RightAlt, RightGUI
    }
    
    /**
     * 修饰键状态数据类
     */
    data class ModifierKeys(
        val leftControl: Boolean = false,
        val leftShift: Boolean = false,
        val leftAlt: Boolean = false,
        val leftGUI: Boolean = false,
        val rightControl: Boolean = false,
        val rightShift: Boolean = false,
        val rightAlt: Boolean = false,
        val rightGUI: Boolean = false
    ) {
        fun toByte(): Byte {
            var result = 0.toByte()
            if (leftControl) result = (result.toInt() or 0x01).toByte()
            if (leftShift) result = (result.toInt() or 0x02).toByte()
            if (leftAlt) result = (result.toInt() or 0x04).toByte()
            if (leftGUI) result = (result.toInt() or 0x08).toByte()
            if (rightControl) result = (result.toInt() or 0x10).toByte()
            if (rightShift) result = (result.toInt() or 0x20).toByte()
            if (rightAlt) result = (result.toInt() or 0x40).toByte()
            if (rightGUI) result = (result.toInt() or 0x80).toByte()
            return result
        }
    }
    
    /**
     * 所有键盘按键的常量定义
     */
    object Keys {
        // 功能键
        val ESC = Key("Esc", 0x29)
        val F1 = Key("F1", 0x3A)
        val F2 = Key("F2", 0x3B)
        val F3 = Key("F3", 0x3C)
        val F4 = Key("F4", 0x3D)
        val F5 = Key("F5", 0x3E)
        val F6 = Key("F6", 0x3F)
        val F7 = Key("F7", 0x40)
        val F8 = Key("F8", 0x41)
        val F9 = Key("F9", 0x42)
        val F10 = Key("F10", 0x43)
        val F11 = Key("F11", 0x44)
        val F12 = Key("F12", 0x45)
        
        // 数字和符号键
        val BACKTICK = Key("`", 0x35, char = '`', shiftedChar = '~')
        val NUM_1 = Key("1", 0x1E, char = '1', shiftedChar = '!')
        val NUM_2 = Key("2", 0x1F, char = '2', shiftedChar = '@')
        val NUM_3 = Key("3", 0x20, char = '3', shiftedChar = '#')
        val NUM_4 = Key("4", 0x21, char = '4', shiftedChar = '$')
        val NUM_5 = Key("5", 0x22, char = '5', shiftedChar = '%')
        val NUM_6 = Key("6", 0x23, char = '6', shiftedChar = '^')
        val NUM_7 = Key("7", 0x24, char = '7', shiftedChar = '&')
        val NUM_8 = Key("8", 0x25, char = '8', shiftedChar = '*')
        val NUM_9 = Key("9", 0x26, char = '9', shiftedChar = '(')
        val NUM_0 = Key("0", 0x27, char = '0', shiftedChar = ')')
        val MINUS = Key("-", 0x2D, char = '-', shiftedChar = '_')
        val EQUAL = Key("=", 0x2E, char = '=', shiftedChar = '+')
        
        // 字母键
        val A = Key("A", 0x04, char = 'a', shiftedChar = 'A')
        val B = Key("B", 0x05, char = 'b', shiftedChar = 'B')
        val C = Key("C", 0x06, char = 'c', shiftedChar = 'C')
        val D = Key("D", 0x07, char = 'd', shiftedChar = 'D')
        val E = Key("E", 0x08, char = 'e', shiftedChar = 'E')
        val F = Key("F", 0x09, char = 'f', shiftedChar = 'F')
        val G = Key("G", 0x0A, char = 'g', shiftedChar = 'G')
        val H = Key("H", 0x0B, char = 'h', shiftedChar = 'H')
        val I = Key("I", 0x0C, char = 'i', shiftedChar = 'I')
        val J = Key("J", 0x0D, char = 'j', shiftedChar = 'J')
        val K = Key("K", 0x0E, char = 'k', shiftedChar = 'K')
        val L = Key("L", 0x0F, char = 'l', shiftedChar = 'L')
        val M = Key("M", 0x10, char = 'm', shiftedChar = 'M')
        val N = Key("N", 0x11, char = 'n', shiftedChar = 'N')
        val O = Key("O", 0x12, char = 'o', shiftedChar = 'O')
        val P = Key("P", 0x13, char = 'p', shiftedChar = 'P')
        val Q = Key("Q", 0x14, char = 'q', shiftedChar = 'Q')
        val R = Key("R", 0x15, char = 'r', shiftedChar = 'R')
        val S = Key("S", 0x16, char = 's', shiftedChar = 'S')
        val T = Key("T", 0x17, char = 't', shiftedChar = 'T')
        val U = Key("U", 0x18, char = 'u', shiftedChar = 'U')
        val V = Key("V", 0x19, char = 'v', shiftedChar = 'V')
        val W = Key("W", 0x1A, char = 'w', shiftedChar = 'W')
        val X = Key("X", 0x1B, char = 'x', shiftedChar = 'X')
        val Y = Key("Y", 0x1C, char = 'y', shiftedChar = 'Y')
        val Z = Key("Z", 0x1D, char = 'z', shiftedChar = 'Z')
        
        // 符号和特殊键
        val LEFT_BRACKET = Key("[", 0x2F, char = '[', shiftedChar = '{')
        val RIGHT_BRACKET = Key("]", 0x30, char = ']', shiftedChar = '}')
        val BACKSLASH = Key("\\", 0x31, char = '\\', shiftedChar = '|')
        val SEMICOLON = Key(";", 0x33, char = ';', shiftedChar = ':')
        val QUOTE = Key("'", 0x34, char = '\'', shiftedChar = '"')
        val COMMA = Key(",", 0x36, char = ',', shiftedChar = '<')
        val PERIOD = Key(".", 0x37, char = '.', shiftedChar = '>')
        val SLASH = Key("/", 0x38, char = '/', shiftedChar = '?')
        
        // 控制键
        val BACKSPACE = Key("Back", 0x2A, weight = 1.5f)
        val TAB = Key("Tab", 0x2B, weight = 1.5f, char = '\t')
        val CAPS_LOCK = Key("Caps", 0x39, weight = 1.5f)
        val ENTER = Key("Enter", 0x28, weight = 1.5f, char = '\n')
        val SPACE = Key("Space", 0x2C, weight = 3.0f, char = ' ')
        
        // 修饰键
        val LEFT_CTRL = Key("Ctrl", 0xE0.toByte(), weight = 1.0f, isModifier = true, modifierType = ModifierKeyType.LeftControl)

        val LEFT_SHIFT = Key("Shift", 0xE1.toByte(), weight = 1.5f, isModifier = true, modifierType = ModifierKeyType.LeftShift)
        val LEFT_ALT = Key("Alt", 0xE2.toByte(), weight = 1.0f, isModifier = true, modifierType = ModifierKeyType.LeftAlt)

        val LEFT_WIN = Key("Win", 0xE3.toByte(), weight = 1.0f, isModifier = true, modifierType = ModifierKeyType.LeftGUI)

        val RIGHT_CTRL = Key("Ctrl", 0xE4.toByte(), weight = 1.0f, isModifier = true, modifierType = ModifierKeyType.RightControl)

        val RIGHT_SHIFT = Key("Shift", 0xE5.toByte(), weight = 1.5f, isModifier = true, modifierType = ModifierKeyType.RightShift)
        val RIGHT_ALT = Key("Alt", 0xE6.toByte(), weight = 1.0f, isModifier = true, modifierType = ModifierKeyType.RightAlt)

        val RIGHT_WIN = Key("Win", 0xE7.toByte(), weight = 1.0f, isModifier = true, modifierType = ModifierKeyType.RightGUI)

        // 小键盘
        /**
         * Num Lock（HID 0x53 Keypad Num Lock and Clear）：小键盘首行左侧那一格。
         *
         * 放进 [Keys] 会连带让 `NumLk` 成为模板里可写的键名 —— 这正是想要的（模板才切得动 Num Lock）；
         * 也正因为这条反射路径，布局占位键与宿主布局独有的键都刻意留在 [Keys] 之外。
         */
        val NUM_LOCK = Key("NumLk", 0x53)

        // 小键盘数字键
        val KP_NUM_1 = Key("KP1", 0x59)
        val KP_NUM_2 = Key("KP2", 0x5A)
        val KP_NUM_3 = Key("KP3", 0x5B)
        val KP_NUM_4 = Key("KP4", 0x5C)
        val KP_NUM_5 = Key("KP5", 0x5D)
        val KP_NUM_6 = Key("KP6", 0x5E)
        val KP_NUM_7 = Key("KP7", 0x5F)
        val KP_NUM_8 = Key("KP8", 0x60)
        val KP_NUM_9 = Key("KP9", 0x61)
        val KP_NUM_0 = Key("KP0", 0x62)
        
        // 小键盘功能键
        val KP_DIVIDE = Key("KP/", 0x54)
        val KP_MULTIPLY = Key("KP*", 0x55)
        val KP_MINUS = Key("KP-", 0x56)
        val KP_PLUS = Key("KP+", 0x57)
        val KP_ENTER = Key("KP Enter", 0x58)
        val KP_DOT = Key("KP.", 0x63)
        
        // 其他功能键
        val PRINT_SCREEN = Key("PrtSc", 0x46)
        val SCROLL_LOCK = Key("ScrLk", 0x47)
        val PAUSE = Key("Pause", 0x48)
        val INSERT = Key("Ins", 0x49)
        val HOME = Key("Home", 0x4A)
        val PAGE_UP = Key("PgUp", 0x4B)
        val DELETE = Key("Del", 0x4C)
        val END = Key("End", 0x4D)
        val PAGE_DOWN = Key("PgDn", 0x4E)
        
        // 方向键
        val ARROW_UP = Key("↑", 0x52)
        val ARROW_DOWN = Key("↓", 0x51)
        val ARROW_LEFT = Key("←", 0x50)
        val ARROW_RIGHT = Key("→", 0x4F)

        /** 字符到直接按键码的自动映射表，基于 Key 的 char/shiftedChar 属性生成 */
        val charToKeyMap: Map<Char, Key> by lazy {
            buildMap {
                // 通过反射自动收集所有设置了 char 的 Key 常量
                for (field in Keys::class.java.declaredFields) {
                    if (field.type == Key::class.java) {
                        field.isAccessible = true
                        val key = field.get(Keys) as Key
                        key.char?.let { put(it, key.copy(needsShift = false)) }
                        key.shiftedChar?.let { put(it, key.copy(needsShift = true)) }
                    }
                }
                // Enter 键支持 \r
                put('\r', ENTER.copy(needsShift = false))
            }
        }
    }

}

/**
 * 键位文字大小：只分两档 —— 单字符 15sp，其余 13sp。
 *
 * 键位上的空间其实够（最窄的键也有 40dp+ 宽），档位分太细反而让同屏字号不齐；
 * 字重由绘制侧取 Medium。最长标签「KP Enter」(8 字符 → 13sp) 在小键盘单键里也放得下。
 *
 * 抽成顶层函数是因为键盘页的键位文字有两个入口：US 基线走 [KeyboardConstants.Key.displayName]，
 * 换上宿主布局后走 `KeyLegend.label`，两边必须算出同一个字号。
 */
internal fun keyLabelFontSize(label: String): Int = if (label.length == 1) 15 else 13