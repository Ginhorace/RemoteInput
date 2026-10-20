package com.github.remotecontrol.util

import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.github.remotecontrol.R
import java.util.Locale

/**
 * 控制界面模式枚举：指针 / 键盘 / 模板
 *
 * [TOUCHPAD] 是**指针模式的槽位**，具体形态由链路的 `PointerKind` 决定：
 * 经典蓝牙注册鼠标描述符（相对位移），局域网注册触摸板描述符（绝对坐标 PTP）。
 * 枚举名沿用历史值 —— 它会被写进偏好里的当前模式，改名没有收益。
 */
enum class ControlMode {
    KEYBOARD,
    TOUCHPAD,

    /** 模板模式：编写/管理 Alt Code 等输入模板 */
    TEMPLATE
}

/**
 * 主题模式枚举：跟随系统、浅色、深色
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

/**
 * 语言模式枚举：跟随系统，加上 [LanguageCatalog.UI_LANGUAGES] 里的 12 种语言。
 *
 * 每个值自带 BCP-47 标签，`locale` 与 [LanguageCatalog] 里的 tag、与资源目录三者同源
 * （`zh-CN` → `values-zh-rCN`、`zh-Hant` → `values-b+zh+Hant`、其余同名 `values-*`）；
 * 标签为 null 表示跟随系统。
 *
 * 枚举名会以字符串写进 SharedPreferences，[PrefGroup.getPref] 读不出来时静默回退默认值，
 * 所以这些常量**只追加、不改名**：把 `ZH` 改成 `ZH_HANS` 会让老用户的语言设置被重置。
 */
enum class LanguageMode(val tag: String?) {
    SYSTEM(null),
    ZH("zh-CN"),
    EN("en"),
    JA("ja"),
    ZH_HANT("zh-Hant"),
    KO("ko"),
    FR("fr"),
    RU("ru"),
    ES("es"),
    PT("pt"),
    DE("de"),
    AR("ar"),
    HI("hi");

    /**
     * Locale.getDefault() 是一个运行时可变的值，但 enum 常量在类加载时一次性初始化。这意味着如果用户后续更改了系统语言，LanguageMode.SYSTEM.locale 仍然返回的是最初缓存的旧值，不会更新。
     */
    val locale: Locale
        get() = tag?.let(Locale::forLanguageTag) ?: Locale.getDefault()
}

/**
 * 宿主键盘布局模式：跟随界面语言，加上 [KeyboardLayoutCatalog] 里的 9 种布局。
 *
 * 与 [LanguageMode] 同构：枚举自带 `layoutId`（null = 跟随界面语言），
 * 与 [KeyboardLayoutCatalog] 里的 id、以及设置页下拉三者同源，一致性由
 * `KeyboardLayoutCatalogTest` 守住。
 *
 * 宿主布局与界面语言本就是两件事 —— 决定输出什么字符的是主机自己的布局，
 * 而中文界面配 US 宿主是最常见的组合，所以这里是独立选项而不是复用 [LanguageMode]。
 *
 * 枚举名会以字符串写进 SharedPreferences，**只追加、不改名**。
 */
enum class KeyboardLayoutMode(val layoutId: String?) {
    FOLLOW_UI(null),
    US_QWERTY("us-qwerty"),
    FRENCH("fr-azerty"),
    GERMAN("de-qwertz"),
    SPANISH("es"),
    PORTUGUESE("pt-abnt2"),
    RUSSIAN("ru"),
    ARABIC("ar-101"),
    KOREAN("ko-dubeolsik"),
    JAPANESE("ja-jis"),
}

/**
 * 色相方案枚举。
 *
 * 这里只声明"选了哪个色相"，具体色值由 ui/theme/Tokens.kt 按**亮 / 暗两套模式分别**
 * 映射（浅色下必须用压暗版本），因此 util 层不依赖 Compose 图形 API。
 *
 * 旧枚举 `ThemeColor`（BLUE/GREEN/PURPLE/ORANGE/RED/DYNAMIC）已废弃：
 * 它只换了 `primary` 一个角色，其余 surface 角色全部落回 M3 出厂默认，导致
 * "5 套主题色"实际只有按钮变了颜色。
 */
enum class HueScheme {
    AZURE,
    TEAL,
    VIOLET,
    AMBER,
    ROSE,
    GRAPHITE,

    /**
     * 跟随系统莫奈取色（Material You）。
     *
     * **只取系统的强调色**，不接管背景 / 卡片 / 描边：那几层仍走本项目的中性色，
     * 否则会像旧版 `ThemeColor.DYNAMIC` 一样把深色工具风冲掉（见 `ui/theme/Theme.kt` 的注释）。
     * 具体色值在 `RemoteInputTheme` 里按当前明暗从系统配色取，`HueScheme` 本身拿不到
     * `Context`，所以 [com.github.remotecontrol.ui.theme] 里给它留了一套兜底值
     * （只在不依赖 Compose 的地方使用，例如单测）。
     */
    DYNAMIC
}

/**
 * 设置选项数据类
 * @param prefKeyName AppPreferences中对应的KEY
 * @param displayName 显示名称的字符串资源ID
 * @param value 存储值，泛型类型
 */
data class SettingOption<T>(
    val prefKeyName: String,
    @StringRes val displayName: Int,
    val value: T
)

/**
 * 设置分组：将 key、默认选项、选项列表打包在一起
 */
@Immutable
class PrefGroup<T : Enum<T>>(
    val key: String,
    val default: SettingOption<T>,
    val allOptions: List<SettingOption<T>>
) {
    /**
     * 「枚举值 -> 显示名称资源 id」列表，供 UI 直接遍历。
     *
     * 在构造时算好并作为实例字段持有：PrefGroup 都是 [SettingConstants] 里的单例，
     * 整个进程只有一份、引用恒定，因此组合里可以直接引用它，
     * 不需要 `remember { allOptions.map { ... } }` 再缓存一次。
     */
    val valueOptions: List<Pair<T, Int>> = allOptions.map { it.value to it.displayName }

    // 查表用，O(1)，避免每次重组对列表做一次线性查找
    private val displayNameMap: Map<T, Int> = allOptions.associate { it.value to it.displayName }

    fun getDisplayNameOrDefault(value: T): Int =
        displayNameMap[value] ?: default.displayName

    /**
     * 从 SharedPreferences 读取该组的枚举设置值。
     *
     * 旧版本写入的枚举名可能已被删除，此时回退到默认值而不是抛异常，
     * 避免升级后读取设置直接崩溃。
     */
    inline fun <reified T : Enum<T>> getPref(prefs: SharedPreferences): T {
        val fallback = default.value as T
        val raw = prefs.getString(key, null) ?: return fallback
        return try {
            enumValueOf<T>(raw)
        } catch (_: IllegalArgumentException) {
            fallback
        }
    }
    companion object {
        fun <T : Enum<T>> of(
            key: String,
            vararg options: Pair<Int, T>
        ): PrefGroup<T> {
            val list = options.map { (label, value) -> SettingOption(key, label, value) }
            return PrefGroup(key = key, default = list.first(), allOptions = list)
        }
    }
}

/**
 * 设置常量定义
 */
object SettingConstants {
    // ==================== Preference Groups（枚举类型设置） ====================
    val THEME_MODE = PrefGroup.of("theme_mode",
        R.string.theme_mode_system to ThemeMode.SYSTEM,
        R.string.theme_mode_light  to ThemeMode.LIGHT,
        R.string.theme_mode_dark   to ThemeMode.DARK,
    )

    /**
     * 色相方案。key 从旧的 `theme_color` 换成 `hue_scheme`：
     * 旧值（"BLUE" 等）读不出来时 [PrefGroup.getPref] 会回退到默认值，
     * 因此升级后不会崩溃，只是回到默认的 AZURE。
     */
    val HUE_SCHEME = PrefGroup.of("hue_scheme",
        R.string.hue_azure    to HueScheme.AZURE,
        R.string.hue_teal     to HueScheme.TEAL,
        R.string.hue_violet   to HueScheme.VIOLET,
        R.string.hue_amber    to HueScheme.AMBER,
        R.string.hue_rose     to HueScheme.ROSE,
        R.string.hue_graphite to HueScheme.GRAPHITE,
        // 跟随系统放最后：默认仍是 AZURE，老用户的选择不受影响
        R.string.hue_dynamic  to HueScheme.DYNAMIC,
    )

    /**
     * 语言下拉的 13 个选项：跟随系统 + 12 种语言。
     *
     * 顺序与 [LanguageCatalog.UI_LANGUAGES] 一致（按网民占比排），
     * 两处的一致性由 `LanguageCatalogTest` 守住 —— 语言名一律是原生写法，不随界面语言翻译。
     */
    val LANGUAGE_MODE = PrefGroup.of("language_mode",
        R.string.language_system              to LanguageMode.SYSTEM,
        R.string.language_chinese             to LanguageMode.ZH,
        R.string.language_chinese_traditional to LanguageMode.ZH_HANT,
        R.string.language_english             to LanguageMode.EN,
        R.string.language_spanish             to LanguageMode.ES,
        R.string.language_arabic              to LanguageMode.AR,
        R.string.language_hindi               to LanguageMode.HI,
        R.string.language_russian             to LanguageMode.RU,
        R.string.language_french              to LanguageMode.FR,
        R.string.language_portuguese          to LanguageMode.PT,
        R.string.language_german              to LanguageMode.DE,
        R.string.language_japanese            to LanguageMode.JA,
        R.string.language_korean              to LanguageMode.KO,
    )

    /**
     * 宿主键盘布局：默认「跟随界面语言」，其余 9 项与 [KeyboardLayoutCatalog.ALL] 一一对应。
     *
     * 布局名与语言名同策略 —— 用该布局所属语言的写法，标 `translatable="false"`，
     * 只在默认语包定义一次（`values/strings.xml` 的 keyboard_layout_* 那一组）。
     */
    val KEYBOARD_LAYOUT = PrefGroup.of("keyboard_layout",
        R.string.keyboard_layout_follow_ui  to KeyboardLayoutMode.FOLLOW_UI,
        R.string.keyboard_layout_us_qwerty  to KeyboardLayoutMode.US_QWERTY,
        R.string.keyboard_layout_french     to KeyboardLayoutMode.FRENCH,
        R.string.keyboard_layout_german     to KeyboardLayoutMode.GERMAN,
        R.string.keyboard_layout_spanish    to KeyboardLayoutMode.SPANISH,
        R.string.keyboard_layout_portuguese to KeyboardLayoutMode.PORTUGUESE,
        R.string.keyboard_layout_russian    to KeyboardLayoutMode.RUSSIAN,
        R.string.keyboard_layout_arabic     to KeyboardLayoutMode.ARABIC,
        R.string.keyboard_layout_korean     to KeyboardLayoutMode.KOREAN,
        R.string.keyboard_layout_japanese   to KeyboardLayoutMode.JAPANESE,
    )

    // ==================== 非枚举常量 ====================
    const val KEY_LAST_CONNECTED_DEVICE_ADDRESS = "last_connected_device_address"

    /**
     * 是否已经问过一次通知权限（Android 13+）。
     *
     * 它的申请时机在控制页「连接成功」那一刻，且**全局只问一次**：用户拒绝之后不再打扰
     * （见 [AppPreferences.notificationPermissionAsked]）。
     */
    const val KEY_NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"

    /**
     * 被禁用的字符集（规范名的集合）。
     *
     * 存的是**禁用集**而不是启用集：新加进清单的字符集默认就是启用的，
     * 只有用户手动关掉的才需要落盘 —— 反过来存启用集的话，每次升级加了新码页都得回填。
     */
    const val KEY_DISABLED_CHARSETS = "disabled_charsets"

    /**
     * 用户自己新增的字符集（规范名，按加入顺序，见 [AppPreferences.customCharsets]）。
     *
     * 存成用换行分隔的字符串而不是 StringSet：顺序要保留（设置页按加入顺序列出来），
     * 而字符集名里不可能出现换行，做分隔符是安全的。
     */
    const val KEY_CUSTOM_CHARSETS = "custom_charsets"

    /**
     * 局域网（USB/IP）的监听端口。
     *
     * 出厂值 3240 是 usbip 的惯用端口，能做设置项的理由通常只有一个：3240 被别的程序占了。
     * 范围取 1024–65535 —— 1024 以下在 Android 上根本绑不上（特权端口），65535 以上不是合法端口。
     */
    const val KEY_USBIP_PORT = "usbip_port"
    const val USBIP_PORT_DEFAULT = 3240
    const val USBIP_PORT_MIN = 1024
    const val USBIP_PORT_MAX = 65535

    /**
     * 经典蓝牙链路鼠标触摸区的「长按判定时长」（毫秒）。
     *
     * 只被 `ui/MouseModeScreen` 使用：短于它算单击左键，长于它算长按右键。
     * 局域网链路的 PTP 触摸板不看这个值（长按 / 双指点击由主机侧的 PTP 驱动处理）。
     */
    const val KEY_LONG_PRESS_DURATION = "long_press_duration"
    const val LONG_PRESS_DURATION_DEFAULT = 500
    const val LONG_PRESS_DURATION_MIN = 200
    const val LONG_PRESS_DURATION_MAX = 1000
    const val LONG_PRESS_DURATION_STEP = 50
}


