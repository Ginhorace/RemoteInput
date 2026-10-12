package com.github.remotecontrol.util

import androidx.annotation.StringRes
import com.github.remotecontrol.R
import java.util.Locale

/**
 * 一种可选的语言与界面译文状态。
 *
 * @param tag BCP-47 标签，同时决定 `Locale` 与目标资源目录：`zh-CN` → `values-zh-rCN`、
 *   `zh-Hant` → `values-b+zh+Hant`、其余与目录同名（`values-ja` 等）。两边保持一致，
 *   否则 BCP-47 匹配会回退到默认语包。
 * @param nativeName 语言的自称。**不随界面语言翻译** —— 中文在任何语言下都写作「简体中文」，
 *   所以这些字符串在 `strings.xml` 里标了 `translatable = false`，只在默认语包里定义一次。
 * @param uiTranslated 是否已提供界面译文。没有译文的条目只作为扩展位留在清单里，
 *   不进设置页下拉 —— 选了会整屏回退英文，不如先不摆出来。
 */
data class LanguageOption(
    val tag: String,
    @StringRes val nativeName: Int,
    val uiTranslated: Boolean,
)

/**
 * 一门语言对应的宿主代码页：Alt Code 的两条路各要一张。
 *
 * 「ANSI / OEM」不是两个字符集，而是 Windows 解释 Alt Code 的两条路径（见 [CharsetCatalog] 的
 * 类注释）：带前导 0 的 `Alt + 0 + 十进制` 走 ACP（[ansi]），不带前导 0 的走 OEMCP（[oem]）。
 * 两列相同时（简中 / 繁中 / 日语 / 朝鲜语）说明那门语言的 ACP 与 OEMCP 本来就是同一张页。
 *
 * @param ansi ANSI 代码页（ACP），ANSI 版 Alt Code 用它
 * @param oem OEM 代码页（OEMCP），OEM 版 Alt Code 用它
 */
data class HostCharsetPair(val ansi: String, val oem: String)

/**
 * 界面语言清单。
 *
 * ## 界面语言与默认宿主代码页
 *
 * 界面语言本身只决定译文与资源目录；[HOST_CHARSETS_BY_TAG] 这一张表例外地由语言给出码页，
 * 且**只服务一件事**：新建两条 Alt Code 模板时填空默认字符集
 * （`MacroTemplatePresets.resolveCharset`）—— OEM 版取表里的 OEMCP，ANSI 版取 ACP。
 *
 * 依据是实测结论：语言的区域设置决定宿主的 ACP / OEMCP，而单字节语言的键盘布局会去读它，
 * 所以这两条路的默认页都能按语言定。2026-09-17 曾把「语言 → 码页」整条撤掉，当时的理由是
 * 外面的实测显示码集很复杂；这轮按新结论重新落回来，且**只落回 Alt Code 两条预设**。
 *
 * 这张表只在**新建那一刻**生效，写进文件后与语言脱钩（换界面语言不改已有模板）；
 * 给的是默认值，用户在编辑器里随时能改选（可选项见 [CharsetCatalog]）。其余模板
 * （十六进制那几条、空白模板）仍写死字符集。
 *
 * ## 排序依据
 *
 * 主口径是**网民占比**（OBDILCI《互联网语言存在度》V6，数据 2025-07，基于 Ethnologue + ITU，
 * 置信区间 ±20%）。这份统计明确纠正了 W3Techs 对英语的系统性高估 —— 后者只统计
 * 「内容语言已声明」的网站，不计入同一网站的多语言版本，因此把英语抬到了 49.5%、
 * 把中文压到 1.3%。两者对照如下（第二列起为网站内容占比，W3Techs 2026-09-16）：
 *
 * | 排名 | 语言 | 网民占比 | 内容占比 | 联网率 L1+L2 |
 * | --- | --- | --- | --- | --- |
 * | 1 | 汉语（宏观，含简繁） | 17.57% | 19.04% | 81.90% |
 * | 2 | 英语 | 15.48% | 20.08% | 70.66% |
 * | 3 | 西班牙语 | 6.63% | 7.70% | 48.48% |
 * | 4 | 阿拉伯语 | 4.36% | 3.65% | 67.75% |
 * | 5 | 印地语 | 4.27% | 3.77% | 89.53% |
 * | 6 | 俄语 | 3.31% | 3.75% | 68.79% |
 * | 7 | 法语 | 3.05% | 3.42% | 77.76% |
 * | 8 | 葡萄牙语 | 2.97% | 3.16% | 87.02% |
 * | 9 | 德语 | 1.80% | 2.15% | 93.00% |
 * | 10 | 日语 | 1.55% | 2.23% | 93.00% |
 *
 * **第 11 名之后没有权威网民口径**（OBDILCI 只公开前 10，完整数据 362 种语言需另行下载），
 * 所以本表后半段按内容占比（W3Techs）配上各国联网率估算排序，只能当量级参考。
 * 韩语即属此列：约 5100 万人口 × 约 97% 联网率 ≈ 0.9%。
 */
object LanguageCatalog {

    /**
     * 整理结果全集，按上面的排名排序。
     *
     * 前 12 项是已提供界面译文的语言（见 [UI_LANGUAGES]），其余是扩展位：
     * 只差一份 `values-<tag>/strings.xml`。
     */
    val ALL: List<LanguageOption> = listOf(
        // ── 已提供界面译文 ──
        LanguageOption("zh-CN", R.string.language_chinese, uiTranslated = true),
        LanguageOption("zh-Hant", R.string.language_chinese_traditional, uiTranslated = true),
        // 语言名不带地区：这一条是按**语言码**匹配的，跟随系统时系统语言是 en-GB 也会落到它，
        // 写成 English (US) 就与「系统地区是英国 / 国际、OEM 取 850」对不上了。
        // 英美 OEM 页的分工由 [oemCharsetFor] 单独判地区，说明文案里另有交代。
        LanguageOption("en", R.string.language_english, uiTranslated = true),
        LanguageOption("es", R.string.language_spanish, uiTranslated = true),
        LanguageOption("ar", R.string.language_arabic, uiTranslated = true),
        LanguageOption("hi", R.string.language_hindi, uiTranslated = true),
        LanguageOption("ru", R.string.language_russian, uiTranslated = true),
        LanguageOption("fr", R.string.language_french, uiTranslated = true),
        LanguageOption("pt", R.string.language_portuguese, uiTranslated = true),
        LanguageOption("de", R.string.language_german, uiTranslated = true),
        LanguageOption("ja", R.string.language_japanese, uiTranslated = true),
        LanguageOption("ko", R.string.language_korean, uiTranslated = true),

        // ── 扩展位：只差一份译文 ──
        LanguageOption("it", R.string.language_italian, uiTranslated = false),
        LanguageOption("nl", R.string.language_dutch, uiTranslated = false),
        LanguageOption("id", R.string.language_indonesian, uiTranslated = false),
        LanguageOption("fa", R.string.language_persian, uiTranslated = false),
        LanguageOption("uk", R.string.language_ukrainian, uiTranslated = false),
        LanguageOption("sv", R.string.language_swedish, uiTranslated = false),
        LanguageOption("da", R.string.language_danish, uiTranslated = false),
        LanguageOption("fi", R.string.language_finnish, uiTranslated = false),
        LanguageOption("pl", R.string.language_polish, uiTranslated = false),
        LanguageOption("cs", R.string.language_czech, uiTranslated = false),
        LanguageOption("hu", R.string.language_hungarian, uiTranslated = false),
        LanguageOption("tr", R.string.language_turkish, uiTranslated = false),
        LanguageOption("vi", R.string.language_vietnamese, uiTranslated = false),
        LanguageOption("el", R.string.language_greek, uiTranslated = false),
        LanguageOption("he", R.string.language_hebrew, uiTranslated = false),
        LanguageOption("th", R.string.language_thai, uiTranslated = false),
    )

    /**
     * 已提供界面译文的语言，也就是设置页下拉里能选到的那些。
     *
     * 与 [SettingConstants.LANGUAGE_MODE] 一一对应（后者多一个「跟随系统」），
     * 由 `LanguageCatalogTest` 守住这条一致性。
     */
    val UI_LANGUAGES: List<LanguageOption> = ALL.filter { it.uiTranslated }

    fun of(tag: String): LanguageOption? = ALL.firstOrNull { it.tag == tag }

    /**
     * 把运行时的 [Locale] 映射回清单条目。
     *
     * 只有中文需要看 script / 地区，其余按语言码直接匹配：`zh-Hant` / `zh-TW` / `zh-HK` / `zh-MO`
     * 归到繁体，其他中文归到简体 —— 系统语言是 `zh-SG` 这种写法时，简体才是对的。
     */
    fun forLocale(locale: Locale): LanguageOption? {
        val language = locale.language.lowercase()
        if (language == "zh") {
            val script = locale.script
            val traditional = script.equals("Hant", ignoreCase = true) ||
                (script.isBlank() && locale.country.uppercase() in TRADITIONAL_CHINESE_REGIONS)
            return of(if (traditional) "zh-Hant" else "zh-CN")
        }
        return ALL.firstOrNull { it.tag.substringBefore('-').lowercase() == language }
    }

    /**
     * 界面语言 → 该语言宿主的代码页，供 `MacroTemplatePresets.resolveCharset` 填空默认字符集。
     *
     * 取值都是 [CharsetCatalog] 里的规范名，由 `LanguageCatalogTest` 守住。只服务 Alt Code 两条
     * 预设（新建与首次 seed 两处），落盘后与语言脱钩。几条取值的来历：
     *
     * - 简中 / 繁中 / 日语 / 朝鲜语的 ACP 与 OEMCP 恰好是同一张（936 / 950 / 932 / 949），两列相同；
     * - 英语的 ANSI 页是 `windows-1252`、OEM 页是 `IBM437`（美国）；**OEM 这一列另有一层地区
     *   细分**：系统地区是英国 / 国际（`en-GB` / `en-AU` / `en-001`…）时改取 `IBM850`，
     *   见 [oemCharsetFor] 与 [ENGLISH_OEM_BY_REGION]；
     * - 法 / 西 / 葡 / 德的 ANSI 页同样是 1252，OEM 页则是多语言页 `IBM850` —— 437 只对应美国英语；
     * - 俄语 1251 / 866、阿拉伯语 1256 / 720 都是标准双页对；
     * - 印地语两列都取 `US-ASCII`：天城文没有 Windows 码页，而印地语地区普遍是 US 矩阵键盘，
     *   这两条路都只打得出 ASCII。
     */
    val HOST_CHARSETS_BY_TAG: Map<String, HostCharsetPair> = mapOf(
        "zh-CN" to HostCharsetPair("GBK", "GBK"),
        "zh-Hant" to HostCharsetPair("Big5", "Big5"),
        "ja" to HostCharsetPair("windows-31j", "windows-31j"),
        "ko" to HostCharsetPair("windows-949", "windows-949"),
        "en" to HostCharsetPair("windows-1252", "IBM437"),
        "es" to HostCharsetPair("windows-1252", "IBM850"),
        "fr" to HostCharsetPair("windows-1252", "IBM850"),
        "pt" to HostCharsetPair("windows-1252", "IBM850"),
        "de" to HostCharsetPair("windows-1252", "IBM850"),
        "ru" to HostCharsetPair("windows-1251", "IBM866"),
        "ar" to HostCharsetPair("windows-1256", "IBM720"),
        "hi" to HostCharsetPair("US-ASCII", "US-ASCII"),
    )

    /**
     * 归不到清单语言时的兜底页（扩展位语言、以及「跟随系统」时系统语言在清单外的情况）。
     *
     * - ANSI 取 `windows-1252`：西欧 ANSI 页是单字节里最通用的一张；
     * - OEM 取 `IBM850`（西欧多语言页）而不是 `IBM437`：437 只对应美国英语，而清单外那些扩展位
     *   大多是西欧语言（it / nl / id / sv / da / fi），850 覆盖面更宽。
     */
    const val FALLBACK_ANSI_CHARSET = "windows-1252"
    const val FALLBACK_OEM_CHARSET = "IBM850"

    /** 取某个 [Locale] 对应的默认 ANSI 码页（ACP）；归不到清单语言时用 [FALLBACK_ANSI_CHARSET] */
    fun ansiCharsetFor(locale: Locale): String =
        forLocale(locale)?.let { HOST_CHARSETS_BY_TAG[it.tag]?.ansi } ?: FALLBACK_ANSI_CHARSET

    /**
     * 哪些英语地区用美国页 `IBM437`。
     *
     * 参考表把「英语（美国）」与「英语（英国/国际）」分成两行：437 / 850 在同码位常是不同字符
     * （155 是 `¢` / `ø`、181 是 `╡` / `Á`），猜错的症状是静默出别的字符。
     * 能分地区的只有 [Locale] 自带地区的那一档（也就是「跟随系统」）；手动选「English (US)」时
     * locale 是 `en`（无地区），照 [HOST_CHARSETS_BY_TAG] 的 `en` 行取 `IBM437`。
     */
    private val ENGLISH_OEM_BY_REGION = mapOf("US" to "IBM437")

    /** 带地区但不是美国的英语（`en-GB` / `en-AU` / `en-001`…）：国际版多语言页 */
    private const val ENGLISH_OEM_INTERNATIONAL = "IBM850"

    /**
     * 取某个 [Locale] 对应的默认 OEM 码页（OEMCP）；归不到清单语言时用 [FALLBACK_OEM_CHARSET]。
     *
     * 只有英语多一层地区判断（见 [ENGLISH_OEM_BY_REGION]）：其余语言的 ACP / OEMCP 与地区无关
     * （参考表里 es / pt / zh 的各地区是同一张页），ANSI 那一侧英语也全球统一是 `windows-1252`。
     */
    fun oemCharsetFor(locale: Locale): String {
        val tag = forLocale(locale)?.tag ?: return FALLBACK_OEM_CHARSET
        if (tag == "en" && locale.country.isNotEmpty()) {
            return ENGLISH_OEM_BY_REGION[locale.country.uppercase()] ?: ENGLISH_OEM_INTERNATIONAL
        }
        return HOST_CHARSETS_BY_TAG[tag]?.oem ?: FALLBACK_OEM_CHARSET
    }

    /** 用繁体中文的国家 / 地区码：台、港、澳 */
    private val TRADITIONAL_CHINESE_REGIONS = setOf("TW", "HK", "MO")
}
