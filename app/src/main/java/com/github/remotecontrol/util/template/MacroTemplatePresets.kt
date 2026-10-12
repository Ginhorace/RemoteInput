package com.github.remotecontrol.util.template

import android.content.Context
import androidx.annotation.StringRes
import com.github.remotecontrol.R
import com.github.remotecontrol.util.LanguageCatalog
import java.util.Locale

/**
 * 预设的字符集该按哪张宿主代码页填空（见 [MacroTemplatePresets.resolveCharset]）。
 *
 * 两条路都是「按界面语言取该语言的宿主码页」，差别只在取 ACP 还是 OEMCP —— 由预设自己声明，
 * 免得调用点各自判断（新建与首次 seed 两处必须一致）。
 */
enum class PresetCharsetTarget { ANSI, OEM }

/**
 * 一个内置模板预设。
 *
 * @param name 预设名，同时也是新建模板时的文件名
 * @param content 预设正文，语法见 [MacroTemplateParser]
 * @param descriptionRes 说明用的字符串资源；`null` = 这张预设不带说明（解释仍写在注释行里）。
 *   与字符集相反，说明是**给使用者看的界面文案**，按界面语言各写一份，落盘前由
 *   [MacroTemplatePresets.localize] 补进正文
 * @param charsetTarget 字符集按哪种宿主码页填空；`null` = 该预设写死了字符集（十六进制那几条、
 *   空白模板），正文里也没有 [MacroTemplatePresets.CHARSET_PLACEHOLDER] 占位符
 */
data class MacroTemplatePreset(
    val name: String,
    val content: String,
    @StringRes val descriptionRes: Int? = null,
    val charsetTarget: PresetCharsetTarget? = null,
)

/**
 * 内置模板预设。
 *
 * 首次启动只落盘 [SEEDED]（即默认的「OEM Alt Code」），其余预设仅在点「新建」时供选择：
 * 这些方案要么依赖宿主系统的额外设置（注册表、切换输入法），要么只对特定软件生效，
 * 放在默认列表里只会干扰「开箱即用」的那一条。
 *
 * ## 字符集：两条 Alt Code 跟随界面语言，其余写死
 *
 * [OEM_ALT_CODE] 与 [ANSI_ALT_CODE] 的正文里放占位符 `charset = follow_language`，由
 * [resolveCharset] 在**创建那一刻**换成界面语言对应的宿主码页：OEM 版取 OEMCP、ANSI 版取 ACP
 * （两张表都在 `LanguageCatalog`）。依据：语言的区域设置决定宿主的 ACP / OEMCP，而单字节语言的
 * 键盘布局会去读它，所以默认值按语言给是可靠的。落盘后与语言脱钩，换界面语言不会改到已有模板。
 *
 * 其余预设写死：十六进制那几条走的是 Unicode 码位，与宿主码页无关；[BLANK] 保持 `UTF-16`。
 *
 * ## 说明跟随界面语言
 *
 * 说明是给使用者看的界面文案，所以按界面语言各写一份（`template_preset_*_description`），
 * 由 [localize] 在落盘前把当前界面语言的那一份补进正文。
 * **顺序固定：先 [resolveCharset] 再 [localize]** —— [localize] 遇到解析有错的正文会原样返回，
 * 而带占位符的正文解析出来必然报 `UNKNOWN_CHARSET`。
 *
 * 说明走的是**落盘前补进正文**，预设正文里不放任何「按语言替换」的占位符：说明是内容，
 * 不是正文里的标记 —— 正文里唯一的标记是字符集占位符 [CHARSET_PLACEHOLDER]，
 * 且它在落盘前一定会被换掉。
 */
object MacroTemplatePresets {

    /**
     * 正文里的字符集占位符：新建时换成界面语言对应的宿主码页（见 [resolveCharset]）。
     *
     * 只有 [OEM_ALT_CODE] 与 [ANSI_ALT_CODE] 用它 —— 其余预设的字符集与宿主码页无关
     * （十六进制那几条走 Unicode 码位），或者写死（[BLANK]）。
     */
    const val CHARSET_PLACEHOLDER = "follow_language"

    /**
     * 空白模板：只有头部参数，步骤留空交给用户自己拼。
     *
     * 名字保持英文，与其余五个预设一致 —— 预设名同时是文件名，混着本地化名会让
     * 「同一套预设」在不同语言下看起来像两批东西。
     */
    val BLANK = MacroTemplatePreset(
        name = "Blank",
        content = """name = Blank
charset = UTF-16
radix = 10
templateGap = 50
keyHold = 10
keyGap = 50

; Blank template: use "Add step" to build the key sequence that types one character
""",
    )

    /**
     * Windows 原生 Alt Code 的 **OEM 版本**：按住 Alt + 小键盘十进制（不带前导 0），开箱即用。
     *
     * 「OEM 版本」说的是**写法**：不带前导 0 的数字由 Windows 按 OEMCP（OEM 代码页）解释，
     * 带前导 0 的才是 ANSI 版本（那一版见 [ANSI_ALT_CODE]）。
     *
     * 字符集是**占位符**（[CHARSET_PLACEHOLDER]），新建那一刻由 [resolveCharset] 换成界面语言
     * 对应的宿主 OEM 码页（简中 `GBK`、繁中 `Big5`、日语 `windows-31j`、朝鲜语 `windows-949`、
     * 英语 `IBM437`、西欧各语言 `IBM850`、俄语 `IBM866`、阿拉伯语 `IBM720`、印地语 `US-ASCII`；
     * 表见 `LanguageCatalog.HOST_CHARSETS_BY_TAG` 的 `oem` 列）。
     *
     * 解释性文字全部收进说明（[MacroTemplatePreset.descriptionRes]），正文里不再放成段注释：
     * 普通 `;` 注释在界面上看不见，存盘时还会被 [MacroTemplateParser.format] 丢掉。
     */
    val OEM_ALT_CODE = MacroTemplatePreset(
        name = "OEM Alt Code",
        content = """name = OEM Alt Code
charset = $CHARSET_PLACEHOLDER
radix = 10
templateGap = 50
keyHold = 10
keyGap = 50

down Alt
<code>
up Alt
""",
        descriptionRes = R.string.template_preset_oem_alt_code_description,
        charsetTarget = PresetCharsetTarget.OEM,
    )

    /**
     * Windows 原生 Alt Code 的 **ANSI 版本**：按住 Alt + 小键盘 `0` + 十进制，开箱即用。
     *
     * 与 [OEM_ALT_CODE] 只差一步按键：`down Alt` 之后多一个 `tap Numpad0`。
     * 前导 0 让 Windows 走 ACP（ANSI 代码页）而不是 OEMCP —— 需要这张页的场合才用它
     * （宿主 ACP ≠ OEMCP，如英文 1252 / 437、俄文 1251 / 866）；简体中文、繁体中文、日语、
     * 朝鲜语两张页是同一张，用哪一份结果都一样。
     *
     * 字符集与 OEM 那条同一套机制、只是取 ACP 那一列（[PresetCharsetTarget.ANSI]）：新建时由
     * [resolveCharset] 换成界面语言对应的宿主 ANSI 码页（简中 `GBK`、繁中 `Big5`、
     * 日语 `windows-31j`、朝鲜语 `windows-949`、英语与西欧各语言 `windows-1252`、
     * 俄语 `windows-1251`、阿拉伯语 `windows-1256`、印地语 `US-ASCII`；
     * 表见 `LanguageCatalog.HOST_CHARSETS_BY_TAG` 的 `ansi` 列）。
     */
    val ANSI_ALT_CODE = MacroTemplatePreset(
        name = "ANSI Alt Code",
        content = """name = ANSI Alt Code
charset = $CHARSET_PLACEHOLDER
radix = 10
templateGap = 50
keyHold = 10
keyGap = 50

down Alt
tap Numpad0
<code>
up Alt
""",
        descriptionRes = R.string.template_preset_ansi_alt_code_description,
        charsetTarget = PresetCharsetTarget.ANSI,
    )

    /**
     * Windows 十六进制 Alt Code（`EnableHexNumpad`）。
     *
     * 十六进制字符里 0-9 走小键盘、A-F 走主键盘，正好是 Windows 该机制的按键要求。
     * 主键盘那几个字母键由展开器按**小写键位**送出（不补 Shift，见 `MacroTemplateExpander.digitKeyName`）：
     * 宿主若不认小写，要自己在 `<code>` 外面包 `down Shift` / `up Shift`。
     *
     * 名字里的 `Numpad` 说的是「走小键盘的 Alt+小键盘加号」这个入口；注册表开关的具体参数名
     * （`EnableHexNumpad`）放在正文第一行注释里 —— 名字同时是文件名，长度留给能一眼看懂的部分。
     */
    val WINDOWS_HEX = MacroTemplatePreset(
        name = "Windows Hex Numpad",
        content = """name = Windows Hex Numpad
charset = UTF-16
radix = 16
templateGap = 50
keyHold = 10
keyGap = 50

; First set HKCU\Control Panel\Input Method\EnableHexNumpad to the string "1" in the registry and reboot
; Hold Alt, tap the numpad plus to enter hex mode, then type the code: "中" is 4E2D
; BMP only (U+FFFF and below)
down Alt
tap NumpadAdd
<code>
up Alt
""",
    )

    /**
     * RichEdit（Word 的内核）的 `Alt+X` 原地转换。
     *
     * 先打 `u+` 再打十六进制码：`Alt+X` 的扫描是**向左贪婪**的（最多吞 6 位十六进制），
     * 紧邻的正文会被一起吸进去 —— `u+` 前缀正是挡它的那道墙，转换时会连前缀一起删掉。
     *
     * 字符集取 `Unicode`（**完整码点**，1~6 位十六进制）：RichEdit 自己就能一次合成辅助平面
     * （`😀` = `u+1F600`），不必像 macOS / Linux 那两条那样按 UTF-16 代码单元拆两次 ——
     * 用 `UTF-16` 的话 emoji 会被拆成两个代理对、各转换一次。
     *
     * 正文里不放注释：用法、支持范围与输入法注意事项都在说明里，跟界面语言走。
     */
    val RICHEDIT_ALT_X = MacroTemplatePreset(
        name = "RichEdit Alt+X",
        content = """name = RichEdit Alt+X
charset = Unicode
radix = 16
templateGap = 50
keyHold = 10
keyGap = 50

tap u
tap Shift+=
<code keys=main>
tap Alt+x
""",
        descriptionRes = R.string.template_preset_richedit_alt_x_description,
    )

    /**
     * macOS 的 Unicode Hex Input（Unicode 十六进制输入法）。
     *
     * **未上机验证**：没有 macOS 设备可测，所以名字里带了 `unverified` —— 预设名同时是文件名，
     * 从「新建」列表里就能看出这一条没经过实测。
     *
     * 保留它的另一个用处：这是**唯一**用 `<code pad=4>` 的预设，去掉就没人演示「补零位数」
     * 这个参数了（`CodeDigits.padDigits` 仍在，用户也能手写）。
     *
     * 该输入法单次只接受 4 位十六进制，所以必须 `pad=4` 补零；
     * 辅助平面字符（Emoji 等）在展开时按 UTF-16 代码单元拆成两次 `Option+xxxx`，
     * 由 macOS 自行合并，无需额外处理。
     *
     * 用法与注意事项都在说明（[MacroTemplatePreset.descriptionRes]）里，跟界面语言走；
     * 正文里不放注释 —— 带说明的预设落盘时会走 `parse` → `format`，注释本来也留不住。
     */
    val MACOS_HEX = MacroTemplatePreset(
        name = "macOS unicode Hex (unverified)",
        content = """name = macOS unicode Hex (unverified)
charset = UTF-16
radix = 16
templateGap = 50
keyHold = 10
keyGap = 50

down Alt
<code keys=main pad=4>
up Alt
""",
        descriptionRes = R.string.template_preset_macos_hex_description,
    )

    /**
     * Linux / ChromeOS 的 `Ctrl + Shift + U` 序列（ISO 14755）。
     *
     * 字符集取 `Unicode`（**完整码点**，1~6 位十六进制）：GTK / IBus / Fcitx 与 ChromeOS 收的就是码点，
     * 所以辅助平面字符（`😀` = `1F600`）一次注入即可，不必像 macOS 那两条按 UTF-16 代码单元拆两次 ——
     * 用 `UTF-16` 的话 emoji 会被拆成两个代理对、各注入一次。
     *
     * 编码字符走**主键盘**（`keys=main`），提交用 `tap Space` 确认（`Enter` 同样有效）。
     *
     * 说明（[MacroTemplatePreset.descriptionRes]）里特地点出这条序列用的是「离散式」按法：
     * 它还有「按住 Ctrl+Shift 不放、松手时自动提交」的保持式，那一版的提交时机握在宿主手里，
     * 模板不用它 —— 使用者照着别的教程按却看不到效果时，这行说明就是答案。
     *
     * 这条的正文里不再放注释：用法与注意事项都在说明里，跟界面语言走。
     */
    val LINUX_CTRL_SHIFT_U = MacroTemplatePreset(
        name = "Linux Ctrl+Shift+U",
        content = """name = Linux Ctrl+Shift+U
charset = Unicode
radix = 16
templateGap = 50
keyHold = 10
keyGap = 50

tap Ctrl+Shift+u
<code keys=main>
tap Space
""",
        descriptionRes = R.string.template_preset_linux_ctrl_shift_u_description,
    )

    // ─────────────────────────────────────────────
    // 落盘前：字符集占位符换成码页、说明按界面语言补进正文
    // ─────────────────────────────────────────────

    /**
     * 把正文里的字符集占位符换成界面语言对应的宿主码页（纯函数，可单测）。
     *
     * 取哪张页由预设自己声明（[MacroTemplatePreset.charsetTarget]）：[OEM_ALT_CODE] 取 OEMCP、
     * [ANSI_ALT_CODE] 取 ACP，两列都来自 `LanguageCatalog.HOST_CHARSETS_BY_TAG`；没有声明的
     * 预设（`null`）写死了字符集，原样返回。
     *
     * 调用点两处，都在落盘之前：`TemplateModeScreen`（选预设时替换，因此对话框摘要里显示的
     * 就是真实字符集）与 `MacroTemplateStore.seedIfFirstLaunch()`（首次启动的种子模板）。
     *
     * **必须先于 [localize]**：带占位符的正文解析出来会报 `UNKNOWN_CHARSET`，而 [localize]
     * 遇到解析有错的正文会原样返回，说明就补不进去了。
     *
     * @param locale 界面语言 —— 取 AppPreferences 里的语言模式，不是系统语言
     */
    fun resolveCharset(preset: MacroTemplatePreset, locale: Locale): MacroTemplatePreset {
        val charset = when (preset.charsetTarget) {
            PresetCharsetTarget.ANSI -> LanguageCatalog.ansiCharsetFor(locale)
            PresetCharsetTarget.OEM -> LanguageCatalog.oemCharsetFor(locale)
            null -> return preset
        }
        return preset.copy(content = preset.content.replace(CHARSET_PLACEHOLDER, charset))
    }

    /**
     * 把说明补进预设正文（纯函数，可单测）。
     *
     * 走的是**模型往返**：`parse` 出头部 → 换掉说明 → `format` 回来。不自己拼 `description = `
     * 那一行，是因为说明是唯一能换行的头部参数，换行 / 引号 / 反斜杠的转义只有
     * [MacroTemplateParser] 一处实现（`formatDescription` 是私有的）。
     *
     * 三种情况原样返回、不动正文：
     * - 预设没有说明资源（其余 2 条，解释仍写在注释行里）；
     * - 传进来的说明是空白（界面上清空过，等同于没写）；
     * - 预设正文本身解析就有错 —— 这时 `format` 会把认不出的头部参数丢掉，宁可保持原样。
     */
    fun localize(preset: MacroTemplatePreset, description: String?): MacroTemplatePreset {
        if (preset.descriptionRes == null) return preset
        val text = description?.takeIf { it.isNotBlank() } ?: return preset
        val parsed = MacroTemplateParser.parse(preset.content)
        if (parsed.hasError) return preset
        val header = parsed.template.header.copy(description = text)
        return preset.copy(content = MacroTemplateParser.format(header, parsed.template.steps))
    }

    /**
     * [localize] 的薄壳：从资源里取当前界面语言的那一份说明。
     *
     * [context] 必须**已经带上界面语言** —— Activity Context（`attachBaseContext` 包过的那一份）
     * 或 `LocaleHelper.wrapContext(...)` 的结果。`applicationContext` 的 configuration 永远是
     * 系统语言，首次 seed 那一侧尤其要注意（见 [MacroTemplateStore]）。
     */
    fun localize(context: Context, preset: MacroTemplatePreset): MacroTemplatePreset {
        val descriptionRes = preset.descriptionRes ?: return preset
        return localize(preset, context.getString(descriptionRes))
    }

    /** 「新建」时可选的全部预设，顺序即界面顺序 */
    val ALL: List<MacroTemplatePreset> = listOf(
        BLANK,
        OEM_ALT_CODE,
        ANSI_ALT_CODE,
        WINDOWS_HEX,
        RICHEDIT_ALT_X,
        MACOS_HEX,
        LINUX_CTRL_SHIFT_U,
    )

    /** 首次启动写盘的预设：只保留开箱即用的那一个 */
    val SEEDED: List<MacroTemplatePreset> = listOf(OEM_ALT_CODE)
}
