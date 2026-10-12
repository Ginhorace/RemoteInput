package com.github.remotecontrol.util

import com.github.remotecontrol.util.template.MacroTemplateExpander
import com.github.remotecontrol.util.template.MacroTemplateIssue
import java.nio.charset.Charset

/**
 * 一个可选的字符集。
 *
 * @param name 写进模板的规范名，也是 `Charset.forName` 认识的名字
 * @param aliases 等价的别名（Windows 代码页写法等），用于界面上的对照提示。
 *   **只列实测过、真的能解析的写法** —— 展示出来的别名也算「支持」，
 *   列一个运行时并不认的写法，会变成「照着提示写却被判 UNKNOWN_CHARSET」。
 * @param androidOnly 只保证在 Android 运行时可用（桌面 JVM 上没有这个字符集）。
 *   现实里只有 `IBM720` 一个是这样：Android 走 ICU，`windows-720` 能解析到 `x-IBM720`；
 *   JDK 的字符集注册表里从来没有 720（连 `jdk.charsets` 的映射表都没生成过），
 *   所以桌面单测解析不出来。带上这个标记，`LanguageCatalogTest` 会在 JVM 上跳过它，
 *   同时反向断言「JDK 上确实不可用」—— 哪天 JDK 支持了，这条断言会红，提醒把标记删掉。
 */
data class CharsetOption(
    val name: String,
    val aliases: List<String> = emptyList(),
    val androidOnly: Boolean = false,
    /**
     * 展开预览打开时预填的样本，取自 `docs/summary/编码参考表.md` 总表的「测试文本」列。
     *
     * 每一格都是**在这张码页上会走出非平凡编码**的字符（`€ŠŽ` 落在 1252 的 0x80 段、
     * `øØ×Áı` 落在 850 的高位区），用它们当样本才看得出「编码 → 数字 → 按键」这条链对不对；
     * 换成 ASCII 字符则任何码页都一样，测不出东西。
     *
     * 这是**数据**而不是界面文案，所以留在清单里（界面层只负责按当前语言渲染文案）。
     * 空串 = 参考表里还没定，此时预览退回默认样本 `中`。
     */
    val sampleText: String = "",
    /**
     * 用户自己加进来的条目（设置页单独上色、可删除），内置条目一律 false。
     *
     * 只是**界面上的标记**：解析、展开、诊断一律不看它 —— 对那条链来说自定义与内置完全等价。
     * 也正因为如此，删除一个自定义条目会真的把它从清单里拿走，只会影响「还能不能查到这个
     * 名字」，不会连带改动模板文件。
     */
    val isCustom: Boolean = false,
)

/**
 * 字符集清单。
 *
 * 前 11 项是 `docs/summary/编码参考表.md` 去重后的 Windows 码页（也就是 Alt Code 的目标码页），
 * 按「ANSI 页 + 它的 OEM 页」成对排列：
 *
 * | 语言 / 地区 | ANSI | OEM |
 * | --- | --- | --- |
 * | 简体中文 | `GBK`（CP936） | 同左（自配对） |
 * | 繁体中文 | `Big5`（CP950） | 同左（自配对） |
 * | 日语 | `windows-31j`（CP932） | 同左（自配对） |
 * | 韩语 | `windows-949`（CP949） | 同左（自配对） |
 * | 英 · 西 · 法 · 葡 · 德 | `windows-1252`（CP1252） | `IBM437`（美国）/ `IBM850`（多语言） |
 * | 俄语 | `windows-1251`（CP1251） | `IBM866`（CP866） |
 * | 阿拉伯语 | `windows-1256`（CP1256） | `IBM720`（CP720） |
 *
 * 「ANSI / OEM」不是两个字符集，而是 Windows 解释 Alt Code 的两条路径：带前导 0 的
 * `Alt + 0 + 十进制` 走 ACP（ANSI）页，不带前导 0 的 `Alt + 十进制` 走 OEMCP（OEM）页。
 * ACP ≠ OEMCP 时同一个数字输入的是不同字符，所以两张页都得摆出来 —— 取哪一条由模板里
 * `pad` 的位数决定（补零到 4 位 = 带前导 0）。
 *
 * 后 3 项不属于代码页，而是「一个数值 = 一个字符」的三种口径：
 * `Unicode` 是**完整码点**（Linux / ChromeOS 的 `Ctrl+Shift+U`），`UTF-16` 是**代码单元**
 * （Windows 十六进制、Word `Alt+X`、macOS Unicode Hex Input —— 辅助平面靠代理对拆两次），
 * `US-ASCII` 是纯 ASCII。
 *
 * 解析校验与编辑器下拉框都从这里取，保证「能选到的」和「能用到的」是同一份；
 * 别名也算支持，否则界面提示里展示的别名会变成「照着写却被判错」。
 *
 * 清单不是封闭集合：设置页可以把用户填的字符集加进来（[registerCustom]，存盘见
 * [AppPreferences.customCharsets]），也可以禁掉某几项（[AppPreferences.disabledCharsets]）、
 * 或者删掉自己加的那些（[AppPreferences.removeCustomCharset]）。
 *
 * 三种干预的力度依次递增，别混为一谈：
 *
 * | 干预 | 影响范围 |
 * | --- | --- |
 * | 禁用 | **只影响编辑器下拉**：能解析、能展开、能发送这条线完全由本清单决定，不受禁用影响 —— 否则「关掉一个选项」会悄悄弄坏已经在用的模板 |
 * | 新增 | 名字进清单，解析层从此认它（模板里写它不再报 `UNKNOWN_CHARSET`） |
 * | 删除 | 名字**离开**清单，用它写过的模板会重新报 `UNKNOWN_CHARSET` —— 所以设置页要先弹确认 |
 */
object CharsetCatalog {

    val OPTIONS: List<CharsetOption> = listOf(
        CharsetOption("GBK", listOf("windows-936", "MS936"), sampleText = "汉语"),
        CharsetOption("Big5", listOf("windows-950"), sampleText = "冇佢"),
        // 日语务必用 windows-31j：它才是微软 CP932 里 NEC 选定符号与 IBM 扩展汉字的严格实现
        CharsetOption("windows-31j", listOf("MS932", "Shift_JIS"), sampleText = "匂栞"),
        CharsetOption("windows-949", listOf("x-windows-949", "MS949"), sampleText = "언어"),
        CharsetOption("windows-1252", listOf("CP1252"), sampleText = "€ŠŽ"),
        CharsetOption("IBM437", listOf("cp437"), sampleText = "πΣΩ"),
        // 西欧多语言 OEM 页：437 只对应美国英语，英式 / 西 / 法 / 葡 / 德的 DOS 页是 850
        CharsetOption("IBM850", listOf("cp850"), sampleText = "øØ×Áı"),
        CharsetOption("windows-1251", listOf("CP1251"), sampleText = "ЋЂ"),
        CharsetOption("IBM866", listOf("cp866"), sampleText = "Язык"),
        // 别名只写 `CP1256`：`MS1256` 这套写法在 JDK / Android 的字符集表里并不存在，
        // 写了会让「照着对话框提示写却被判成解析失败」
        CharsetOption("windows-1256", listOf("CP1256"), sampleText = "اللغة"),
        // 720 的别名里**不能写 `cp720`**：JDK 与 Android 都不认这个写法（实测），
        // 只有 `IBM720` / `windows-720` / `x-IBM720` / `DOS-720` 能解析
        CharsetOption(
            "IBM720",
            listOf("windows-720", "DOS-720"),
            androidOnly = true,
            sampleText = "عربي",
        ),
        // 项目自定义的「完整码点」口径，对应 Linux / ChromeOS 的 `Ctrl+Shift+U`（ISO 14755）：
        // 那条路收 1~6 位十六进制，一次能表达完整码点（含辅助平面）。取值就是 **UTF-32 去掉补零**
        // 的写法（`中` 是 `4E2D`、`😀` 是 `1F600`），位数因此不定。
        // 样本取 😀，与下面 `UTF-16` 那条用同一个字符：两条路的差别（一次发完 / 拆成 2 段）
        // 在展开预览里能直接对比出来。
        //
        // ⚠️ `Unicode` **不是运行时字符集名**：`unicode` 在 JDK / ICU 里是 `UTF-16` 的别名，
        // 真交给 `Charset.forName` 会按 UTF-16 编码并带上 BOM，取值就错了。
        // 所以 [MacroTemplateExpander] 对它特判；只有本项目的模板解析认这个名字
        // （解析走 [isSupported]，不查注册表）。
        CharsetOption("Unicode", sampleText = "😀"),
        // 印地语没有 ANSI / OEM 码页，应用里它的默认字符集是 US-ASCII（见 [LanguageCatalog]），
        // 对话框的语言列挂在 ASCII 那一行；这一行的语言说明改成了 UTF-16 自己的机制
        // （辅助平面按代码单元拆成 2 段），样本也换成 😀 与 `Unicode` 那条对齐 ——
        // 同一个字符，展开时一个一次发完、一个发 2 段。
        CharsetOption("UTF-16", sampleText = "😀"),
        CharsetOption("US-ASCII", listOf("ASCII"), sampleText = "Language"),
    )

    /**
     * 用户自己加进来的字符集（规范名，按加入顺序）。
     *
     * 与 [OPTIONS] 不同，这一份**不是编译期常量**：模板解析要认它（否则用户加完的字符集
     * 写进模板会立刻被判 UNKNOWN_CHARSET），而解析层拿不到 Context，所以由
     * [AppPreferences] 在读盘时把存下来的清单登记进来（见那里的 `init`）。
     *
     * 界面上的「启用 / 禁用」不在这里 —— 禁用只影响模板编辑器的下拉，
     * 不能影响解析与展开（见 [AppPreferences.disabledCharsets]）。
     */
    private var custom: List<String> = emptyList()

    /** 内置 + 自定义，内置在前；编辑器下拉、设置页清单都取它 */
    val ALL: List<CharsetOption>
        get() = if (custom.isEmpty()) {
            OPTIONS
        } else {
            OPTIONS + custom.map { CharsetOption(it, isCustom = true) }
        }

    /** 自定义条目的规范名，按加入顺序 */
    fun customNames(): List<String> = custom

    /** 登记 / 覆盖自定义清单（存盘值变化时由 [AppPreferences] 调用） */
    fun registerCustom(names: Collection<String>) {
        custom = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /**
     * 按名称取选项，规范名与别名都能查到；查不到返回 null。
     *
     * 模板头部的 `charset` 存的是用户当初写下的写法（可能是别名），所以取测试文本这类
     * 「跟着字符集走」的附加数据也得走别名。
     */
    fun of(name: String): CharsetOption? {
        if (name.isBlank()) return null
        return ALL.firstOrNull { option ->
            option.name.equals(name, ignoreCase = true) ||
                option.aliases.any { it.equals(name, ignoreCase = true) }
        }
    }

    /** 规范名或别名都算支持 */
    fun isSupported(name: String): Boolean = of(name) != null

    /**
     * 校验一个「新增字符集」的输入，供设置页的添加框使用。
     *
     * 只查两件事，顺序也是这个顺序：
     *
     * 1. **重复**：规范名或别名与清单里已有条目撞了（大小写不敏感）。先查它能给出更准的提示
     *    （「与 `GBK` 相同」比「这个名字不可用」有用得多）；
     * 2. **可用**：`Charset.isSupported` 认不认这个名字。这里**只用运行时判断**，不再比对
     *    编码表 —— Android 上同一张码页的几种写法就是同一个 `Charset`、映射也一致，
     *    没有 JDK 那种「两张表差几个字符」的问题（见 `编码参考表.md`），所以名字能解析就够了。
     *
     * 非法名字要分两路接住：含空格 / 括号这类**描述性文字**（参考表里那条 `Unicode (LE)`）
     * 会让 `Charset.isSupported` 直接抛 `IllegalCharsetNameException`，而不是返回 false，
     * 所以这里连同 [IllegalArgumentException] 一起折算成 [CharsetAddResult.NotFound]。
     */
    fun validateAdd(raw: String): CharsetAddResult {
        val name = raw.trim()
        if (name.isEmpty()) return CharsetAddResult.Blank
        of(name)?.let { return CharsetAddResult.Duplicate(it.name) }
        val available = try {
            Charset.isSupported(name)
        } catch (_: IllegalArgumentException) {
            false
        }
        if (!available) return CharsetAddResult.NotFound(name)
        return CharsetAddResult.Ok(name)
    }
}

/**
 * 「新增字符集」的校验结果。
 *
 * 是数据而不是文案：提示语在 UI 层按当前语言解析（与 [MacroTemplateIssue] 同一套做法）。
 */
sealed interface CharsetAddResult {

    /** 可用且不重复，可以加入；[name] 是用户写下的写法 */
    data class Ok(val name: String) : CharsetAddResult

    /** 与清单里已有条目重复；[existing] 是已有条目的规范名 */
    data class Duplicate(val existing: String) : CharsetAddResult

    /** 运行时解析不了这个名字（写错了，或这台设备没有这张表） */
    data class NotFound(val name: String) : CharsetAddResult

    /** 什么都没填 */
    data object Blank : CharsetAddResult
}
