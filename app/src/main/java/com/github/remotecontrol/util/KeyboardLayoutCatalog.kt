package com.github.remotecontrol.util

import com.github.remotecontrol.util.KeyboardConstants.Key
import java.util.Locale

/**
 * 某个物理位置在某个宿主键盘布局下的键面图例。
 *
 * @param label 主档图例，也就是键上印得最大的那个字
 * @param subLabel 角标（贴右上角的小字）。两种东西共用这一个位置：
 *   - 多数布局是**按住 Shift 的档位** —— US 的 `1`/`!`、AZERTY 的 `&`/`1`；
 *   - 日语是**键帽上的假名** —— JIS 键帽本来就印着「字母 + 假名」两行，
 *     角标只是把第二行挪到右上角（假名与字母是同一键位的两种输入法状态，不是 Shift 档）。
 *   字母键的 Shift 只改变大小写，与小字无关，因此一律传 null，保持键面干净。
 */
data class KeyLegend(
    val label: String,
    val subLabel: String? = null,
) {
    /** 与 [Key.fontSize] 同一套规则：单字符 15sp，其余 13sp */
    val fontSize: Int get() = keyLabelFontSize(label)
}

/**
 * 一种宿主键盘布局。
 *
 * ## 为什么需要它
 *
 * HID 报告里送的是 **Usage ID，也就是 US 参考布局上的物理位置**，不是字符；
 * 主机（Windows 等）用它自己当前激活的布局把位置翻译成字符。所以屏幕键盘如果永远按 US
 * 印字、又不告诉用户这件事，宿主换成 AZERTY 之后「看 A 打出 Q」的错位就无从解释。
 * 本类把「位置 → 该位置在宿主布局下的图例」独立成数据，行结构（`KeyboardLayouts`）保持不变 ——
 * 绝大多数布局只是换键帽，物理矩阵与 US 完全一致，换个图例表就够了。
 *
 * @param id 稳定标识，也是 [KeyboardLayoutMode.layoutId] 的取值
 * @param legends 位置（HID Usage ID）→ 图例。**只写与 US 基线不同的位置**，缺项回落到 [Key] 自身。
 *   US 矩阵里有、而该布局没有对应键的位置也保持 US 图例：本表只保证有对应关系的位置。
 * @param extraKeys 该布局有、而 US 矩阵没有的键。按既定规则**统一插在主键盘 ENTER 左边**
 *   （见 `KeyboardLayouts.mainRows`），不追求物理位置保真。
 * @param notice 用美式键位发送前的提醒档位，见 [HostLayoutNotice]。**没有默认值**：
 *   每张布局都必须自己声明一次，新增布局时编译器会顺手拦下来。
 */
data class HostKeyboardLayout(
    val id: String,
    val legends: Map<Int, KeyLegend> = emptyMap(),
    val extraKeys: List<Key> = emptyList(),
    val notice: HostLayoutNotice,
) {
    /** 该物理位置在本布局下的图例：先查本布局的表，没有就用 US 基线 */
    fun legendOf(key: Key): KeyLegend = legends[key.usage] ?: key.usLegend
}

/**
 * 「用美式键位发送时，宿主该处在什么状态」——由每张布局表自己声明，模板编辑器据此提示。
 *
 * 起因：HID 报告送的是美式位置码，宿主用**自己当前激活的布局**把它翻译成字符。所以语言或宿主
 * 布局设置一旦离开 US，送出去的十六进制 `A`–`F`、`keys=main` 的数字就可能变成别的字符，
 * 而宿主实际状态**应用探测不到**（见 [KeyboardLayoutCatalog] 的说明），只能提前提醒。
 *
 * 分档依据不是「与 US 差多少」，而是**用户能不能自己看出来**这个设置不对：
 *
 * - [NONE]：字母位与主键盘数字行都与 US 一致，不用管。西语、巴西 ABNT2 只差标点。
 * - [ENGLISH_MODE]：字母位换成了别的文字，但**英文（英数）模式下的 26 个字母与 US 完全一致**
 *   （朝鲜语 두벌식、日语 JIS）—— 切一下输入法模式就行，不必换布局。
 * - [US_LAYOUT]：字母被换掉或搬走，英文模式下也不一致（法语 AZERTY、俄语、阿拉伯语），
 *   或者**差异小到看不出来**（德语只差 `Y`/`Z` 互换，反而更容易被当成没问题）。
 *   必须让宿主切到美式键盘，或加一个美式布局的输入法。
 *
 * **枚举顺序由轻到重**（`NONE` < `ENGLISH_MODE` < `US_LAYOUT`）：[KeyboardLayoutCatalog.noticesFor]
 * 按它给两个来源的结果排序（重的在前），改顺序会改变提醒的顺序。
 */
enum class HostLayoutNotice {
    NONE,
    ENGLISH_MODE,
    US_LAYOUT,
}

/** usage 是带符号的 Byte（修饰键从 0xE0 起），统一取低 8 位当无符号位置用 */
private val Key.usage: Int get() = value.toInt() and 0xFF

/** US 基线：直接用 [Key] 自己那两条字段 */
private val Key.usLegend: KeyLegend
    get() = KeyLegend(
        label = displayName,
        // 字母键的 shiftedChar 只是同一个字母的大小写，印到角上是噪音；
        // 数字 / 符号键才是「另一个字符」，那才是角标要表达的东西
        subLabel = shiftedChar
            ?.takeUnless { char != null && it.equals(char, ignoreCase = true) }
            ?.toString(),
    )

/** 位置 → 图例的短写法 */
private fun legend(label: String, subLabel: String? = null) = KeyLegend(label, subLabel)

/**
 * 26 个字母位的物理顺序（与布局无关，取自 US 矩阵）：
 *
 * ```
 * Q W E R T Y U I O P
 * A S D F G H J K L
 * Z X C V B N M
 * ```
 *
 * 只换键帽、不换位置的布局（德 / 西 / 葡 / 俄 / 阿 / 韩 / 日）都能用 [letters] 按这个顺序直填，
 * 位置写错的概率降到零；位置被互换或被别的字符占掉的布局（法语 AZERTY）另加覆盖项。
 */
private val letterPositions = listOf(
    0x14, 0x1A, 0x08, 0x15, 0x17, 0x1C, 0x18, 0x0C, 0x12, 0x13,
    0x04, 0x16, 0x07, 0x09, 0x0A, 0x0B, 0x0D, 0x0E, 0x0F,
    0x1D, 0x1B, 0x06, 0x19, 0x05, 0x11, 0x10,
)

/** 按 [letterPositions] 的顺序铺 26 个主图例（不设角标：字母键的 Shift 只改大小写） */
private fun letters(vararg labels: String): Map<Int, KeyLegend> {
    require(labels.size == letterPositions.size) {
        "字母位固定 ${letterPositions.size} 个，实际给了 ${labels.size} 个"
    }
    return letterPositions.zip(labels).associate { (usage, label) -> usage to legend(label) }
}

/**
 * 在 [letters] 的结果上，按同一顺序给 26 个字母位补角标（日语键帽上的假名）。
 *
 * 顺序同样由 [letterPositions] 固定，所以调用点只要把两份 26 项的清单并排写出来即可对齐，
 * 不必逐个写位置码。
 */
private fun Map<Int, KeyLegend>.withSubLabels(vararg subLabels: String): Map<Int, KeyLegend> {
    require(subLabels.size == letterPositions.size) {
        "角标固定 ${letterPositions.size} 个，实际给了 ${subLabels.size} 个"
    }
    return letterPositions.zip(subLabels).fold(this) { acc, (usage, sub) ->
        acc + (usage to acc.getValue(usage).copy(subLabel = sub))
    }
}

/**
 * 各布局独有的键。
 *
 * **刻意不放进 [KeyboardConstants.Keys]**：`Keys` 会被两处反射遍历 ——
 * `Keys.charToKeyMap` 与 `MacroTemplateKeys.byName`（后者收下**所有** `Key` 类型的字段），
 * 一旦放进去，这些键会立刻变成模板里可写的键名。占位键出于同样的理由留在布局文件里。
 *
 * 这些键同样**不设 `char` / `shiftedChar`**：字符语义一律由 [KeyLegend] 承载，
 * 这样键盘页的行为不会顺着 `charToKeyMap` 漏进模板层。
 */
private object InternationalKeys {

    /** ISO 105 的第 105 颗键（左 Shift 左侧）：德/西/法的尖括号、巴西键盘的 `\` */
    val ISO_EXTRA = Key("<>", 0x64)

    /** 德语把 `#` 单独做成一颗键（HID 的「Non-US # and ~」） */
    val NUMBER_SIGN = Key("#", 0x32)

    /** 巴西 ABNT2 在右 Shift 左侧多出的 `/?` 键 */
    val SLASH_QUESTION = Key("/?", 0x87.toByte())

    /** 日语：かな / 変換 / 無変換。缺了这三颗，宿主侧的输入法状态在屏幕上无法切换 */
    val JIS_KANA = Key("かな", 0x88.toByte())
    val JIS_HENKAN = Key("変換", 0x8A.toByte())
    val JIS_MUHENKAN = Key("無変換", 0x8B.toByte())
}

/** 布局 id：与 [KeyboardLayoutMode.layoutId] 一一对应，由 `KeyboardLayoutCatalogTest` 守住 */
private const val ID_US = "us-qwerty"
private const val ID_FRENCH = "fr-azerty"
private const val ID_GERMAN = "de-qwertz"
private const val ID_SPANISH = "es"
private const val ID_PORTUGUESE = "pt-abnt2"
private const val ID_RUSSIAN = "ru"
private const val ID_ARABIC = "ar-101"
private const val ID_KOREAN = "ko-dubeolsik"
private const val ID_JAPANESE = "ja-jis"

/**
 * 法语 AZERTY（Windows KBDFR）。
 *
 * 三处与 US 完全不同，也是「副标签必须做」的直接证据：
 *
 * 1. **数字行整体反档** —— 主档是 `& é " ' ( - è _ ç à )`，数字落在 Shift 档；
 * 2. `A`/`Q`（0x04 ↔ 0x14）与 `Z`/`W`（0x1A ↔ 0x1D）互换；
 * 3. `M` 搬到 0x33，0x10 位变成逗号，且 `?` 是 Shift+`,`（不是 Shift+/）。
 */
private val FRENCH_AZERTY = HostKeyboardLayout(
    id = ID_FRENCH,
    legends = letters(
        "A", "Z", "E", "R", "T", "Y", "U", "I", "O", "P",
        "Q", "S", "D", "F", "G", "H", "J", "K", "L",
        "W", "X", "C", "V", "B", "N", ",",
    ) + mapOf(
        // 0x10 位的逗号另有 Shift 档，而 M 占了 US 分号的位置，两处单独补
        0x10 to legend(",", "?"),
        0x33 to legend("M"),
        0x35 to legend("²"),
        0x1E to legend("&", "1"),
        0x1F to legend("é", "2"),
        0x20 to legend("\"", "3"),
        0x21 to legend("'", "4"),
        0x22 to legend("(", "5"),
        0x23 to legend("-", "6"),
        0x24 to legend("è", "7"),
        0x25 to legend("_", "8"),
        0x26 to legend("ç", "9"),
        0x27 to legend("à", "0"),
        0x2D to legend(")", "°"),
        0x2E to legend("=", "+"),
        0x2F to legend("^", "¨"),
        0x30 to legend("$", "£"),
        0x31 to legend("*", "µ"),
        0x34 to legend("ù", "%"),
    ),
    extraKeys = listOf(InternationalKeys.ISO_EXTRA),
    notice = HostLayoutNotice.US_LAYOUT,
)

/**
 * 德语 QWERTZ（Windows KBDGR）。
 *
 * `Y`/`Z` 互换，变音字母占掉 US 的 `[ ] ; '`，`-` 搬到底行末尾；
 * 数字行上档是 `!"§$%&/()=`，与 US 也不同。
 */
private val GERMAN_QWERTZ = HostKeyboardLayout(
    id = ID_GERMAN,
    legends = letters(
        "Q", "W", "E", "R", "T", "Z", "U", "I", "O", "P",
        "A", "S", "D", "F", "G", "H", "J", "K", "L",
        "Y", "X", "C", "V", "B", "N", "M",
    ) + mapOf(
        0x35 to legend("^", "°"),
        0x1F to legend("2", "\""),
        0x20 to legend("3", "§"),
        0x23 to legend("6", "&"),
        0x24 to legend("7", "/"),
        0x25 to legend("8", "("),
        0x26 to legend("9", ")"),
        0x27 to legend("0", "="),
        0x2D to legend("ß", "?"),
        0x2E to legend("´", "`"),
        0x2F to legend("Ü"),
        0x30 to legend("+", "*"),
        // `#` 独占一颗键，Shift 档是 `'`
        0x32 to legend("#", "'"),
        0x33 to legend("Ö"),
        0x34 to legend("Ä"),
        0x36 to legend(",", ";"),
        0x37 to legend(".", ":"),
        0x38 to legend("-", "_"),
    ),
    extraKeys = listOf(InternationalKeys.ISO_EXTRA, InternationalKeys.NUMBER_SIGN),
    notice = HostLayoutNotice.US_LAYOUT,
)

/**
 * 西班牙语（Windows KBDSP）。
 *
 * 字母位与 US 相同，差异集中在标点：数字行上档是 `!"·$%&/()=`，
 * `'` 与 `¡` 顶掉 `-` / `=`，`ñ` 占 US 的分号，`ç` 占 `\`。
 */
private val SPANISH = HostKeyboardLayout(
    id = ID_SPANISH,
    legends = letters(
        "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P",
        "A", "S", "D", "F", "G", "H", "J", "K", "L",
        "Z", "X", "C", "V", "B", "N", "M",
    ) + mapOf(
        0x35 to legend("º", "ª"),
        0x1F to legend("2", "\""),
        0x20 to legend("3", "·"),
        0x24 to legend("7", "/"),
        0x25 to legend("8", "("),
        0x26 to legend("9", ")"),
        0x27 to legend("0", "="),
        0x2D to legend("'", "?"),
        0x2E to legend("¡", "¿"),
        0x2F to legend("`", "^"),
        0x30 to legend("+", "*"),
        // 只有大小写之差，按字母位惯例印大写、不设角标
        0x31 to legend("Ç"),
        0x33 to legend("ñ", "Ñ"),
        0x34 to legend("´", "¨"),
        0x36 to legend(",", ";"),
        0x37 to legend(".", ":"),
        0x38 to legend("-", "_"),
    ),
    extraKeys = listOf(InternationalKeys.ISO_EXTRA),
    notice = HostLayoutNotice.NONE,
)

/**
 * 巴西 ABNT2（Windows KBDPT）。
 *
 * 字母位与 US 相同，`Ç` 占 US 分号的位置，`'` 与 `´` 顶掉数字行尾与 `[`，
 * 另有两颗 US 没有的键（左 Shift 旁的 `\`、右 Shift 旁的 `/?`）。
 */
private val PORTUGUESE_ABNT2 = HostKeyboardLayout(
    id = ID_PORTUGUESE,
    legends = letters(
        "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P",
        "A", "S", "D", "F", "G", "H", "J", "K", "L",
        "Z", "X", "C", "V", "B", "N", "M",
    ) + mapOf(
        0x35 to legend("'", "\""),
        0x23 to legend("6", "¨"),
        0x2F to legend("´", "`"),
        0x30 to legend("[", "{"),
        0x31 to legend("]", "}"),
        // 只有大小写之差，按字母位惯例印大写、不设角标
        0x33 to legend("Ç"),
        0x34 to legend("~", "^"),
        // ABNT2 把 `;`／`:` 放在 US 的 `/` 位上，`/`／`?` 则搬到右 Shift 左侧那颗新键
        0x38 to legend(";", ":"),
        // 左 Shift 旁那颗 ISO 键在 ABNT2 上是 `\`／`|`（德/西/法才是 `<>`）
        0x64 to legend("\\", "|"),
    ),
    extraKeys = listOf(InternationalKeys.ISO_EXTRA, InternationalKeys.SLASH_QUESTION),
    notice = HostLayoutNotice.NONE,
)

/**
 * 俄语 ЙЦУКЕН（Windows KBDRU）。
 *
 * 26 个字母位整体换成西里尔字母，位置不动；标点只换了 `[ ] ; ' , . /` 与 `ё`。
 * 西里尔字母的键帽印大写、Shift 只改大小写，因此不设副标签。
 */
private val RUSSIAN = HostKeyboardLayout(
    id = ID_RUSSIAN,
    legends = letters(
        "Й", "Ц", "У", "К", "Е", "Н", "Г", "Ш", "Щ", "З",
        "Ф", "Ы", "В", "А", "П", "Р", "О", "Л", "Д",
        "Я", "Ч", "С", "М", "И", "Т", "Ь",
    ) + mapOf(
        0x35 to legend("Ё"),
        0x2F to legend("Х"),
        0x30 to legend("Ъ"),
        0x33 to legend("Ж"),
        0x34 to legend("Э"),
        0x36 to legend("Б"),
        0x37 to legend("Ю"),
        0x38 to legend("."),
    ),
    notice = HostLayoutNotice.US_LAYOUT,
)

/**
 * 阿拉伯语 101（Windows KBDAR）。
 *
 * 同样是「位置不动、整排换字」：`ذ` 占 US 的反引号位，`ج د` 占方括号，
 * `ك ط` 占分号与引号，`و ز ظ` 占底行的 `, . /`。阿拉伯字母无大小写，不设副标签；
 * 数字行与 Shift 档与 US 一致。
 */
private val ARABIC_101 = HostKeyboardLayout(
    id = ID_ARABIC,
    legends = letters(
        "ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح",
        "ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م",
        "ئ", "ء", "ؤ", "ر", "لا", "ى", "ة",
    ) + mapOf(
        0x35 to legend("ذ"),
        0x2F to legend("ج"),
        0x30 to legend("د"),
        0x33 to legend("ك"),
        0x34 to legend("ط"),
        0x36 to legend("و"),
        0x37 to legend("ز"),
        0x38 to legend("ظ"),
    ),
    notice = HostLayoutNotice.US_LAYOUT,
)

/**
 * 韩语 두벌식（Windows KBDKOR）。
 *
 * 26 个字母位换成谚文子母音；只有 `ㅂㅈㄷㄱㅅㅐㅔ` 这 7 个位置在 Shift 档是另一个字
 * （紧音 / 双元音），其余位置的 Shift 与主档相同。
 */
private val KOREAN_DUBEOLSIK = HostKeyboardLayout(
    id = ID_KOREAN,
    legends = letters(
        "ㅂ", "ㅈ", "ㄷ", "ㄱ", "ㅅ", "ㅛ", "ㅕ", "ㅑ", "ㅐ", "ㅔ",
        "ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "ㅗ", "ㅓ", "ㅏ", "ㅣ",
        "ㅋ", "ㅌ", "ㅊ", "ㅍ", "ㅠ", "ㅜ", "ㅡ",
    ) + mapOf(
        0x14 to legend("ㅂ", "ㅃ"),
        0x1A to legend("ㅈ", "ㅉ"),
        0x08 to legend("ㄷ", "ㄸ"),
        0x15 to legend("ㄱ", "ㄲ"),
        0x17 to legend("ㅅ", "ㅆ"),
        0x12 to legend("ㅐ", "ㅒ"),
        0x13 to legend("ㅔ", "ㅖ"),
    ),
    notice = HostLayoutNotice.ENGLISH_MODE,
)

/**
 * 日语 JIS（Windows KBDJA）。
 *
 * ## 为什么字母位要带角标
 * JIS 键帽在拉丁字母旁边还印着一行假名（假名输入法下同一个键位打出的就是它），
 * 而字母本身的位置与 US 完全一致。只改符号位的话，整块键盘看起来与 US 没有区别 ——
 * 所以这里用角标承载假名（JIS X 6002 的 かな 配列，46 音在这张表里是齐的）。
 *
 * ## 角标只有一个位置，日语下优先印假名
 * JIS 键帽上同一个键最多印三样东西（数字 / Shift 档符号 / 假名），角标放不下。
 * 本表统一**优先假名**，因此数字行的 `!@#$…` 与 `, . / - =` 的 `<>?` `+` `{` `}` 不显示 ——
 * 那几档靠修饰键区的 Shift 仍然打得出来，而假名是「一眼认出这是日语键盘」的关键。
 *
 * ## 主档一律与 US 一致，日语只体现在角标与独占键上
 * 屏幕键盘的行结构就是 US 参考矩阵，送出去的也是 US 位置码，由宿主的激活布局翻译成字符。
 * 所以这一份只允许两种改动：
 *
 * 1. **覆盖项只用来补角标** —— 主档必须与 US 那颗字完全相同。一旦主档印成 JIS 键帽上的
 *    `@ [ ] :` 而宿主实际按 US 出字，屏幕上就会出现「看着是 `@`、按下去是 `[`」的错位；
 * 2. **US 矩阵里没有落点的日语独占键**（かな / 変換 / 無変換）作为新键插在主键盘 ENTER 左边。
 *
 * `¥`（HID 的 International3，扫描码 `0x7D`）与「ろ」（International1，`0x73`）同样只属于
 * 日语硬件布局：宿主按英文布局工作时这两颗发不出任何字符，所以不摆上屏幕。
 */
private val JAPANESE_JIS = HostKeyboardLayout(
    id = ID_JAPANESE,
    legends = letters(
        "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P",
        "A", "S", "D", "F", "G", "H", "J", "K", "L",
        "Z", "X", "C", "V", "B", "N", "M",
    ).withSubLabels(
        // 与上面字母一一对齐：QWERTY 键帽上的假名
        "た", "て", "い", "す", "か", "ん", "な", "に", "ら", "せ",
        "ち", "と", "し", "は", "き", "く", "ま", "の", "り",
        "つ", "さ", "そ", "ひ", "こ", "み", "も",
    ) + mapOf(
        // 数字行：主档仍是数字（好按），角标是假名
        0x1E to legend("1", "ぬ"),
        0x1F to legend("2", "ふ"),
        0x20 to legend("3", "あ"),
        0x21 to legend("4", "う"),
        0x22 to legend("5", "え"),
        0x23 to legend("6", "お"),
        0x24 to legend("7", "や"),
        0x25 to legend("8", "ゆ"),
        0x26 to legend("9", "よ"),
        0x27 to legend("0", "わ"),
        // 符号位：主档与 US 同字（宿主输出的就是它），覆盖项只为了把角标让给假名
        0x2D to legend("-", "ほ"),
        0x2E to legend("=", "へ"),
        0x2F to legend("[", "゛"),
        0x30 to legend("]", "゜"),
        0x31 to legend("\\", "む"),
        0x33 to legend(";", "れ"),
        0x34 to legend("'", "け"),
        0x36 to legend(",", "ね"),
        0x37 to legend(".", "る"),
        0x38 to legend("/", "め"),
    ),
    extraKeys = listOf(
        InternationalKeys.JIS_KANA,
        InternationalKeys.JIS_HENKAN,
        InternationalKeys.JIS_MUHENKAN,
    ),
    notice = HostLayoutNotice.ENGLISH_MODE,
)

/**
 * 宿主键盘布局清单。
 *
 * ## 界面语言 ≠ 宿主键盘布局
 *
 * 前者是手机上的界面语言，后者是决定「按下某个位置出什么字符」的那台主机自己的属性，
 * 两者可以不一致（中文界面配 US 宿主是最常见的一种）。所以设置页给的是一个独立选项，
 * 默认「跟随界面语言」只是「同一地区的人大概率用同一种布局」的合理猜测。
 *
 * 宿主侧按 `Win+Space` 切换布局时不会通过 HID 通知设备，**应用无法探测**，
 * 只能由用户手选 —— 这也是这个清单必须存在的原因。
 */
object KeyboardLayoutCatalog {

    /** US QWERTY：基线。图例全部来自 [KeyboardConstants.Keys] 自己的字段，没有覆盖项 */
    val US = HostKeyboardLayout(ID_US, notice = HostLayoutNotice.NONE)

    /** 全部布局。按 id 取名即可，不在这里再给一份别名（别名会和文件级私有常量重名） */
    val ALL: List<HostKeyboardLayout> = listOf(
        US,
        FRENCH_AZERTY,
        GERMAN_QWERTZ,
        SPANISH,
        PORTUGUESE_ABNT2,
        RUSSIAN,
        ARABIC_101,
        KOREAN_DUBEOLSIK,
        JAPANESE_JIS,
    )

    fun of(id: String): HostKeyboardLayout? = ALL.firstOrNull { it.id == id }

    /** 设置项 → 布局：选中「跟随界面语言」时才回落到 [forLocale] */
    fun forMode(mode: KeyboardLayoutMode, uiLocale: Locale): HostKeyboardLayout =
        mode.layoutId?.let(::of) ?: forLocale(uiLocale)

    /**
     * 发送模板前该提醒哪几档：**界面语言与宿主布局设置各判一次，两边都非美式就给两条**（见 [HostLayoutNotice]）。
     *
     * 不看单一来源的理由：
     * - 「跟随界面语言」时语言是唯一的依据，只看设置等于永远得不到提示；
     * - 语言与布局是**两处互不相干的手动设置**，各自都可能是把宿主带离美式的那一处 —— 与其替用户
     *   猜哪一处算数，不如两边都摆出来，让他自己看该改哪一处；
     * - 模板发错字符是**静默**的，多一句远好过让人自己猜。
     *
     * 同一档位只出现一次（语言与设置都判出 `ENGLISH_MODE` 时只有一条），更重的一档排前面
     *（顺序见 [HostLayoutNotice]）；两边都是美式时返回空列表。
     */
    fun noticesFor(mode: KeyboardLayoutMode, uiLocale: Locale): List<HostLayoutNotice> =
        listOf(
            forLocale(uiLocale).notice,
            // 「跟随界面语言」时设置这一边没有独立信息，语言那一条已经代表它了
            mode.layoutId?.let { of(it)?.notice } ?: HostLayoutNotice.NONE,
        ).distinct()
            .filter { it != HostLayoutNotice.NONE }
            .sortedByDescending { it.ordinal }

    /**
     * 界面语言 → 宿主布局。
     *
     * 先走 [LanguageCatalog.forLocale] 归类（`zh-Hant` / `zh-TW` / `zh-HK` 等由它统一处理），
     * 再查这张表；清单外的语言（含「跟随系统」下系统语言不在清单里的情况）回落到 [US]。
     * 中简繁、英语、印地语都走 US：前两者的中文输入靠宿主 IME，印地语地区普遍用 US 矩阵配
     * 音译输入法，把 Devanagari InScript 摆上来反而更陌生。
     */
    private val LAYOUT_ID_BY_LANGUAGE_TAG: Map<String, String> = mapOf(
        "zh-CN" to ID_US,
        "zh-Hant" to ID_US,
        "en" to ID_US,
        "hi" to ID_US,
        "es" to ID_SPANISH,
        "ar" to ID_ARABIC,
        "ru" to ID_RUSSIAN,
        "fr" to ID_FRENCH,
        "pt" to ID_PORTUGUESE,
        "de" to ID_GERMAN,
        "ja" to ID_JAPANESE,
        "ko" to ID_KOREAN,
    )

    fun forLocale(locale: Locale): HostKeyboardLayout {
        val tag = LanguageCatalog.forLocale(locale)?.tag ?: return US
        return LAYOUT_ID_BY_LANGUAGE_TAG[tag]?.let(::of) ?: US
    }
}
