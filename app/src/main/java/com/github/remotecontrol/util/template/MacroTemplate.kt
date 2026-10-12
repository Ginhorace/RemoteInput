package com.github.remotecontrol.util.template

/**
 * 模板头部参数，对应模板文件开头的 `key = value`。
 *
 * 例如：
 * ```
 * name = OEM Alt Code
 * description = Hold Alt and type the decimal code point on the numpad
 * charset = GBK
 * radix = 10
 * templateGap = 50
 * keyHold = 10
 * keyGap = 10
 * ```
 *
 * @param name 模板名称，同时也是保存时的文件名
 * @param description 模板说明（备注），见 [description] 字段自己的注释
 * @param charset 字符集，决定字符的编码取值方式，可选项见 [CharsetCatalog]
 * @param radix 进制，10 = Windows Alt Code 十进制，16 = Unicode 十六进制
 * @param templateGapMs 一次模板展开结束到下一次开始之间的等待。
 *                      一个模板只负责发送一个字符，所以它实际就是相邻字符之间的间隔；
 *                      这属于外层循环的节奏，模板内部的步骤感知不到
 * @param keyHoldMs 一次按键从按下到松开的保持时长；
 *                  `tap` / `down` 与 `<code>` 的每一位编码都取这个值
 * @param keyGapMs 按键松开后到下一个动作之间的间隔；编码位之间也用它
 */
data class MacroTemplateHeader(
    val name: String? = null,

    /**
     * 模板说明（备注），给人看的。
     *
     * 存在的理由：模板的**名称**同时是文件名，受文件名长度与非法字符的限制，写不长；
     * 而步骤列表只看得到按键（`down Alt` / `<code>` / `up Alt`），看不出这张模板
     * 「为什么要这么按」。中间的语义只能由这一行补上，例如「Windows 十六进制 Alt Code，
     * 需要先在注册表打开 EnableHexNumpad」。
     *
     * 与 [name] 的分工：`name` 是**标识**（改名会连文件一起改，见
     * [MacroTemplateStore.save]）；说明是**内容**，改它不碰文件名，也不参与重名判定。
     *
     * `null` = 没写过这一行。留空保存时按没写处理（空串与纯空白同样算没写）——
     * 界面上清空输入框就等于删掉这一行，不会在文件里留下一条空的 `description =`。
     *
     * **可以换行**：文件格式是行式的，换行写进文件时靠引号 + 转义承载
     * （`description = "第一行\n第二行"`），单行的说明则照旧写成裸串。两种写法的取舍与
     * 转义清单见 [MacroTemplateParser]。
     */
    val description: String? = null,

    val charset: String = DEFAULT_CHARSET,
    val radix: Int = DEFAULT_RADIX,
    val templateGapMs: Long = DEFAULT_TEMPLATE_GAP_MS,
    val keyHoldMs: Long = DEFAULT_KEY_HOLD_MS,
    val keyGapMs: Long = DEFAULT_KEY_GAP_MS,
) {
    companion object {
        /**
         * 模板里**没写** `charset =` 时的兜底字符集。
         *
         * 注意兜底只覆盖「没写」这一种情况：写了个解析不出来的名字时，头部照旧保留用户写下的
         * 原值并报 [MacroTemplateIssue.UNKNOWN_CHARSET]，不静默替换 —— 否则编辑器里显示的
         * 是 `UTF-16` 而文件里写着 `UTF-8`，用户根本看不到哪里错了（见
         * [MacroTemplateParser.parse]）。
         *
         * 取 `UTF-16` 而不是某个 ANSI 代码页：模板描述的这几套注入机制收发的是
         * **一个数值 = 一个字符**，不是字节流 —— 把「中」编成 UTF-8 的 `E4B8AD`
         * 只是三个字节，逐字节当数值送进去，宿主端只会得到别的字符。
         *
         * 注意 `UTF-16` 在本项目里的语义是「`Char` 的代码单元值」（见
         * [MacroTemplateExpander.encodeValue]）：BMP 内等于码点，BMP 外等于代理项的一半。
         * 这与 Windows 十六进制、Word `Alt+X`、macOS Unicode Hex Input 的口径一致。
         * Linux 的 `Ctrl+Shift+U` 要的是**完整码点**，需要时把 charset 换成
         * `Unicode`（见 [CharsetCatalog]），别用 `UTF-16`。
         *
         * 需要具体码页的模板（传统 Alt Code 走 ANSI / OEM 页时要精确到字节）必须显式写出来，
         * 由用户按宿主机的区域设置选（可选项见 [CharsetCatalog]）；这一步与界面语言无关，
         * 理由见 [LanguageCatalog] 的类注释。
         */
        const val DEFAULT_CHARSET = "UTF-16"
        const val DEFAULT_RADIX = 10
        const val DEFAULT_TEMPLATE_GAP_MS = 50L

        /**
         * 按键保持 / 间隔的下限，也就是编辑器里这两个数值能设到的最小值。
         *
         * 取 10 而不是更小的值：蓝牙链路上低于 10 ms 的停顿可能被压缩，
         * 界面上设得再小也不一定真的生效。
         *
         * 这里只约束「界面能设到什么程度」——手改文件写到 10 以下仍然能解析出来，
         * 但会报 [MacroTemplateIssue.SMALL_DELAY_HEADER]。解析时不做静默钳制，
         * 否则用户看不到文件里的问题。
         */
        const val MIN_KEY_DELAY_MS = 10L

        /** 一次按键从按下到松开的默认保持时长：10 ms 已经够宿主认出这一下 */
        const val DEFAULT_KEY_HOLD_MS = MIN_KEY_DELAY_MS

        /**
         * 按键松开后到下一个动作之间的默认间隔。
         *
         * 比保持时长宽得多（50 ms）：保持时长决定「这一下按多久」，间隔决定的是
         * **宿主两次输入之间留多少时间** —— 间隔太短时字符可能还没落进文档，
         * 紧跟其后的组合键（如 RichEdit 的 `Alt+X`）就会扑空。下限仍是
         * [MIN_KEY_DELAY_MS]，编辑器里可以往下调。
         */
        const val DEFAULT_KEY_GAP_MS = 50L

        /** 支持的进制 */
        val SUPPORTED_RADIX = setOf(10, 16)
    }
}

/**
 * 模板中的一条步骤。
 *
 * 词汇表只有几项，复杂度全部交给「对每个字符展开一次模板」这个外层循环：
 * - [KeyDown] / [KeyUp]：按下 / 松开一个键并保持状态；
 * - [KeyTap]：点按一个键（按下 + 松开），可以拆分成 [KeyDown] + [KeyUp]；
 * - [ChordTap]：点按一组键（`tap Ctrl+Shift+u`），可以拆分成若干个 `down` / `tap` / `up`；
 * - [Delay]：等待；
 * - [CodeDigits]：编码占位符，展开成当前字符编码的每一位数字。
 *
 * 节奏不在这一层配置：普通按键与编码位的保持时长、间隔统一取
 * [MacroTemplateHeader.keyHoldMs] / [MacroTemplateHeader.keyGapMs]，
 * 所以 `tap A` 与 `<code>` 展开出的帧结构完全相同 —— 所有按键是同等地位的。
 *
 * 之所以 `down` / `up` 不只限于修饰键，是因为界面上的 `tap` 需要能拆成
 * 「按下 + 松开」两步（例如按住 A 的同时做别的操作）。
 *
 * 每条步骤都带一个 [enabled]：停用的步骤留在列表里但不参与展开与发送，
 * 用来「临时关掉一步看看效果」，不需要删掉再敲回来。
 */
sealed interface MacroTemplateStep {

    /** 该步骤在模板文件中所在的行号（从 1 开始），用于诊断定位 */
    val line: Int

    /**
     * 是否参与展开与发送。
     *
     * `false` = 停用的步骤：展开器整个跳过，语义校验也不看它，编辑器步骤列表里左侧的
     * 勾选框取消勾选即为此状态。文件里写作**注释行**（`; tap A`）—— 停用一步就是把它
     * 注释掉，与 AHK 同一套习惯，所以语法表里不多出一个词，别的工具读这个文件也不受影响。
     */
    val enabled: Boolean

    /**
     * 同一条步骤换成另一个启用状态。
     *
     * `copy()` 是各子类独有的，所以在接口层按类型分发一次，免得每个调用点各写一遍 `when`。
     */
    fun withEnabled(value: Boolean): MacroTemplateStep = when (this) {
        is KeyDown -> copy(enabled = value)
        is KeyUp -> copy(enabled = value)
        is KeyTap -> copy(enabled = value)
        is ChordTap -> copy(enabled = value)
        is Delay -> copy(enabled = value)
        is CodeDigits -> copy(enabled = value)
        is Unknown -> copy(enabled = value)
    }

    /** 按下并保持，例如 `down Alt` / `down A` */
    data class KeyDown(
        val keyName: String,
        override val line: Int = 0,
        override val enabled: Boolean = true,
    ) : MacroTemplateStep

    /** 松开，例如 `up Alt` / `up A` */
    data class KeyUp(
        val keyName: String,
        override val line: Int = 0,
        override val enabled: Boolean = true,
    ) : MacroTemplateStep

    /** 点按（按下 + 松开），例如 `tap Numpad5` */
    data class KeyTap(
        val keyName: String,
        override val line: Int = 0,
        override val enabled: Boolean = true,
    ) : MacroTemplateStep

    /**
     * 点按**一组**键（组合键），例如 `tap Ctrl+Shift+u`。
     *
     * 修饰键按写的顺序按下、逆序松开 —— 顺序是有意义的：界面上「先点 Ctrl 再点 Shift」
     * 就是这个顺序，[split] 拆出来的 `down` 也照它排列。只有修饰键也算合法组合
     * （`tap Ctrl+Shift`，用来切输入法 / 键盘布局），这时组合里没有普通键。
     *
     * 与 `down` / `up` 的分工：需要「按住修饰键夹住中间的东西」时（Alt Code 的
     * `down Alt` / `<code>` / `up Alt`）照样用 [KeyDown] / [KeyUp]；「整组按下再整组松开」
     * 才是这一条。展开结果与 [split] 出来的多步**完全等价**（有单测钉住）。
     *
     * @param keyNames 组合里的键名，按文件里写的顺序；写法见 [MacroTemplateKeys]
     */
    data class ChordTap(
        val keyNames: List<String>,
        override val line: Int = 0,
        override val enabled: Boolean = true,
    ) : MacroTemplateStep

    /** 等待，例如 `sleep 20` */
    data class Delay(
        val ms: Long,
        override val line: Int = 0,
        override val enabled: Boolean = true,
    ) : MacroTemplateStep

    /**
     * 编码占位符 `<code>`：展开成当前字符编码的每一位数字。
     *
     * 之所以要有占位符，是因为编码位数不固定（GBK 的「中」是 54992 共 5 位，
     * 而 ASCII 的「A」是 65 共 2 位），模板里无法逐位写死。
     *
     * 本步骤不持有节奏 —— 每一位的保持时长与位间间隔与 `tap` 一样取头部参数，
     * 这里只描述「编码怎么呈现」与「用哪套数字键」。
     *
     * @param useNumpad true = 用小键盘数字（Alt Code），false = 用主键盘数字
     * @param padDigits 左侧补零到几位，0 = 不补零。
     *                  macOS 的 Unicode Hex Input 单次只收 4 位十六进制，必须 `pad=4`
     */
    data class CodeDigits(
        val useNumpad: Boolean = true,
        val padDigits: Int = 0,
        override val line: Int = 0,
        override val enabled: Boolean = true,
    ) : MacroTemplateStep

    /**
     * 认不出来的一行。
     *
     * 它存在的理由只有一个：**别把坏行从模型里弄丢**。以前解析不出来就返回 null，那行既不在步骤列表里
     * 显示、保存时还会被 [MacroTemplateParser.format] 静默删掉 —— 用户看不到错在哪，一保存连原文都没了。
     * 现在它作为一条「步骤」留在列表里（[raw] 原样保存），编辑器把它标红，用户可以改掉或删掉。
     *
     * 语义上它什么都不做：既不是按键也不是编码位，[MacroTemplateExpander] 直接跳过；而且它必然带着
     * [issue] 这条**错误**，`hasError` 会拦住发送，所以「跳过」不会导致静默发错。
     *
     * 认不出的是哪一行由 [issue] 区分（认不出的关键字 / 没闭合的占位符 / 无效值……），
     * 诊断统一在 [MacroTemplateParser.validate] 里报 —— 那里同时服务文本解析与模型校验，
     * 两处各报一遍就会变成「同一个错误显示两条」。
     *
     * @param raw 原始那一行（去掉行首空白），保存时原样写回
     * @param issue 认不出来的原因，也就是这条步骤的诊断
     * @param arg 诊断文案的参数（多数情况就是 [raw]，但 `sleep abc` 这类只取坏掉的那一段）
     */
    data class Unknown(
        val raw: String,
        val issue: MacroTemplateIssue,
        val arg: String? = null,
        override val line: Int = 0,
        override val enabled: Boolean = true,
    ) : MacroTemplateStep
}

/**
 * 这条步骤是不是「参与发送的编码位」。
 *
 * 「这张模板能不能编出字符」只看它：解析层的校验与编辑器里的发送门禁都取这一个定义，
 * 免得两边各写一遍 `is CodeDigits && enabled`，日后走叉。
 */
val MacroTemplateStep.isActiveCode: Boolean
    get() = this is MacroTemplateStep.CodeDigits && enabled

/** 这串步骤里有没有启用的编码位 */
fun List<MacroTemplateStep>.hasActiveCode(): Boolean = any { it.isActiveCode }

/** 组合键里的修饰键，按写的顺序（按下顺序与拆分顺序都取它） */
fun MacroTemplateStep.ChordTap.modifierNames(): List<String> =
    keyNames.filter { MacroTemplateKeys.isModifier(it) }

/** 组合键里的普通键（正常只有一个；多了会被 MULTIPLE_HELD_KEYS 提醒） */
fun MacroTemplateStep.ChordTap.plainKeyNames(): List<String> =
    keyNames.filterNot { MacroTemplateKeys.isModifier(it) }

/**
 * 这一步按下 Shift 之后产出的字符；不成立时返回 null。
 *
 * 只给编辑器的**副标签**用（`tap Shift+2` 那行标一个 `(@)`）：模板里的键名不表示 Shift，
 * 光看 `Shift+2` 看不出会打出什么字符，这个字符是算出来给人看的，**不会写进模板文件**。
 *
 * 三种情况不给：没写 Shift（这一步不会按下它）、修饰键不止 Shift（多按着 Ctrl / Alt 出来的
 * 不是这个字符）、普通键不是恰好一个（键盘报告只有一个普通键槽位）。
 */
fun MacroTemplateStep.shiftResultChar(): Char? {
    val keyNames = when (this) {
        is MacroTemplateStep.KeyTap -> listOf(keyName)
        is MacroTemplateStep.ChordTap -> keyNames
        else -> return null
    }
    val modifiers = keyNames.filter { MacroTemplateKeys.isModifier(it) }
    val plainKeys = keyNames.filterNot { MacroTemplateKeys.isModifier(it) }
    if (modifiers.size != 1 || !MacroTemplateKeys.isShift(modifiers.single())) return null
    if (plainKeys.size != 1) return null
    return MacroTemplateKeys.shiftedCharOf(plainKeys.single())
}

/**
 * 把组合键拆成等价的多步：修饰键 `down` → 普通键 `tap` → 修饰键逆序 `up`。
 *
 * `tap Ctrl+Shift+u` → `down Ctrl` / `down Shift` / `tap u` / `up Shift` / `up Ctrl`。
 * 顺序取自 [MacroTemplateStep.ChordTap.keyNames]，所以界面上「先点 Ctrl 再点 Shift」
 * 拆出来就是 Ctrl 在前；只有修饰键的组合则没有中间那一步。
 */
fun MacroTemplateStep.ChordTap.split(): List<MacroTemplateStep> =
    modifierNames().map { MacroTemplateStep.KeyDown(it) } +
        plainKeyNames().map { MacroTemplateStep.KeyTap(it) } +
        modifierNames().reversed().map { MacroTemplateStep.KeyUp(it) }

/** 模板模型 */
data class MacroTemplate(
    val header: MacroTemplateHeader,
    val steps: List<MacroTemplateStep>,
)

/** 诊断级别 */
enum class TemplateSeverity { ERROR, WARNING }

/**
 * 诊断条目。
 *
 * 只保存「错误码 + 参数」，具体文案在 UI 层按当前语言解析，
 * 这样单元测试断言的是错误码，不受文案改动影响。
 */
enum class MacroTemplateIssue {
    EMPTY_TEMPLATE,

    /**
     * 整张步骤列表里都没有 `<code>`：结构上就送不出字符。
     *
     * 判定看**整张列表**（不是只看启用的）：模板从没写过 `<code>` 与「写了但被勾掉」是两件事，
     * 后者见 [CODE_DISABLED]。
     */
    MISSING_CODE,

    /**
     * `<code>` 全被停用了。
     *
     * 与 [MISSING_CODE] 的差别在意图：文件里明明写着 `<code>`，只是被勾掉了，属于「临时试别的
     * 步骤」，所以是**警告** —— 展开预览里的「测试」照跑；但发送文本仍然要拦（编辑器那边有
     * 单独的门禁），因为一张没有启用编码位的模板按字符发出去什么都不会打出来。
     */
    CODE_DISABLED,
    UNKNOWN_KEY,
    INVALID_NUMBER,
    INVALID_VALUE,
    UNCLOSED_PLACEHOLDER,
    UNKNOWN_STEP,
    UNKNOWN_CHARSET,
    INVALID_RADIX,
    UNKNOWN_PARAM,

    /**
     * 模板里有多个**启用的** `<code>`。
     *
     * 一个模板只负责发送**一个**字符，位数不定的编码由 `<code>` 在展开时逐位铺开；
     * 出现多个占位符，等于要求一个模板发多个字符，那超出了模板的表达范围
     * （表现是同一串编码被重复发出去），所以这里提示而不是照做。
     *
     * 只数启用的：被勾掉的那个不参与展开，也就不会重复发送 —— 勾掉重复的其中一个
     * 正是「停用步骤」这个功能的正常用法，不该反过来挨一条警告。
     */
    DUPLICATE_CODE,

    /**
     * 某个键 `down` 之后没有配对的 `up`。
     *
     * 是**错误**：后果确定而不是「可能」—— 按住的键在宿主端会连发（一个字符一直打印），
     * 修饰键则会粘住后面所有字符。发送收尾那帧空报告只能在整个文本发完之后兜底，
     * 所以这类模板直接不放行。
     */
    UNBALANCED_KEY,
    MULTIPLE_HELD_KEYS,

    /** 某个 `sleep` 步骤的时长过短 */
    SMALL_DELAY,

    /** 模板级：头部的 [MacroTemplateHeader.keyHoldMs] / [MacroTemplateHeader.keyGapMs] 过短 */
    SMALL_DELAY_HEADER,

    /**
     * 旧语法的残留属性。
     *
     * `<code>` 曾经允许自带 `hold` / `gap`，现在节奏统一由头部决定，
     * 文件里还能解析出这些属性，但它们不再生效 —— 报出来比静默忽略好，
     * 因为「明明写了却没生效」比「多看一眼警告」更难排查。
     */
    IGNORED_ATTR,
}

/**
 * 头部里能被诊断定位的参数。
 *
 * 只在「文本解析」这一侧有行号可用，而编辑器编辑的是**模型**（它只知道字段，不知道行号），
 * 所以头部问题必须带上字段名 —— 编辑器才能把出错的那个输入框标红。
 * 步骤不需要它：步骤的诊断按 [MacroTemplateDiagnostic.line] 定位（模型侧是第几条步骤）。
 */
enum class TemplateField {
    CHARSET,
    RADIX,
    TEMPLATE_GAP,
    KEY_HOLD,
    KEY_GAP,

    /** 认不出来的头部参数：定位不到具体字段，只能在诊断列表里列出来 */
    UNKNOWN_PARAM,
}

data class MacroTemplateDiagnostic(
    val line: Int,
    val severity: TemplateSeverity,
    val issue: MacroTemplateIssue,
    /** 用于填充文案的参数，例如出错的键名 */
    val arg: String? = null,
    /** 可选的修正建议，例如「是否要输入 Numpad5？」 */
    val suggestion: String? = null,
    /**
     * 头部问题落在哪个参数上；步骤问题与模板级问题为 null（它们靠 [line] 定位）。
     *
     * [line] 在文本解析时是**文件行号**、在模型校验时是**第几条步骤**，两者含义不同；
     * 头部问题属于前者，而编辑器里显示的是一组字段，所以这里必须给出字段名。
     */
    val field: TemplateField? = null,
)

data class MacroTemplateParseResult(
    val template: MacroTemplate,
    val diagnostics: List<MacroTemplateDiagnostic>,
) {
    val errorCount: Int get() = diagnostics.count { it.severity == TemplateSeverity.ERROR }
    val warningCount: Int get() = diagnostics.count { it.severity == TemplateSeverity.WARNING }
    val hasError: Boolean get() = errorCount > 0
}

/**
 * 编辑器「改一行」对话框的解析结果（[MacroTemplateParser.parseLine]）。
 *
 * @param step 解析出的步骤；认不出来时是 [MacroTemplateStep.Unknown]
 * @param diagnostics 这一行自己的诊断 —— 用于 `<code pad=x>` 这类「步骤认得出、只是属性写坏了」的情况
 */
data class MacroTemplateLineParse(
    val step: MacroTemplateStep?,
    val diagnostics: List<MacroTemplateDiagnostic>,
) {
    /** 这一行能直接采用：解析出了一条真步骤，而且不再有任何诊断 */
    val isUsable: Boolean
        get() = step != null && step !is MacroTemplateStep.Unknown && diagnostics.isEmpty()
}
