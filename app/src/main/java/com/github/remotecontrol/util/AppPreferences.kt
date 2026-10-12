package com.github.remotecontrol.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


/**
 * 应用偏好设置管理类
 * 使用SharedPreferences存储应用设置
 */
class AppPreferences private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "remote_control_prefs"

        /** 自定义字符集落盘时的分隔符；字符集名里不可能出现换行 */
        private const val CUSTOM_CHARSET_SEPARATOR = "\n"

        @Volatile
        private var instance: AppPreferences? = null

        fun getInstance(context: Context): AppPreferences {
            return instance ?: synchronized(this) {
                instance ?: AppPreferences(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 主题模式（跟随系统/浅色/深色），默认跟随系统
    var themeMode: ThemeMode
        get() = SettingConstants.THEME_MODE.getPref(prefs)
        set(value) = prefs.edit { putString(SettingConstants.THEME_MODE.key, value.name) }

    // 色相方案（决定强调色与整条浓度阶梯）
    var hueScheme: HueScheme
        get() = SettingConstants.HUE_SCHEME.getPref(prefs)
        set(value) = prefs.edit { putString(SettingConstants.HUE_SCHEME.key, value.name) }

    // 语言模式（英文/中文），默认英文
    var languageMode: LanguageMode
        get() = SettingConstants.LANGUAGE_MODE.getPref(prefs)
        set(value) = prefs.edit { putString(SettingConstants.LANGUAGE_MODE.key, value.name) }

    // 宿主键盘布局（决定屏幕键盘印什么字 / 主机按下某个位置输出什么字符）
    var keyboardLayoutMode: KeyboardLayoutMode
        get() = SettingConstants.KEYBOARD_LAYOUT.getPref(prefs)
        set(value) = prefs.edit { putString(SettingConstants.KEYBOARD_LAYOUT.key, value.name) }

    // 最近连接的设备地址
    var lastConnectedDeviceAddress: String?
        get() = prefs.getString(SettingConstants.KEY_LAST_CONNECTED_DEVICE_ADDRESS, null)
        set(value) = prefs.edit { putString(SettingConstants.KEY_LAST_CONNECTED_DEVICE_ADDRESS, value) }

    // ==================== 字符集 ====================

    private val _disabledCharsets = MutableStateFlow(
        prefs.getStringSet(SettingConstants.KEY_DISABLED_CHARSETS, null)?.toSet().orEmpty()
    )

    /**
     * 被禁用的字符集（规范名）。
     *
     * 只影响模板编辑器里的下拉选项，**不影响模板的 charset 能不能执行** —— 解析、展开、发送
     * 一律不看它。所以这里是个 StateFlow：设置页改写后，正在打开的编辑器下拉能立刻跟着变，
     * 而模板列表与诊断完全不受影响。
     */
    val disabledCharsets: StateFlow<Set<String>> = _disabledCharsets.asStateFlow()

    private val _customCharsets = MutableStateFlow(readNameList(SettingConstants.KEY_CUSTOM_CHARSETS))

    /** 用户新增的字符集（规范名，按加入顺序） */
    val customCharsets: StateFlow<List<String>> = _customCharsets.asStateFlow()

    init {
        // 解析（CharsetCatalog.isSupported）不查注册表、只看清单，所以自定义条目必须先登记进清单，
        // 否则「用户加过、也写进了模板」的名字在下一次启动时会被判 UNKNOWN_CHARSET
        CharsetCatalog.registerCustom(_customCharsets.value)
    }

    /** 启用 / 禁用某个字符集（名字取规范名） */
    fun setCharsetEnabled(name: String, enabled: Boolean) {
        val next = if (enabled) _disabledCharsets.value - name else _disabledCharsets.value + name
        if (next == _disabledCharsets.value) return
        _disabledCharsets.value = next
        prefs.edit { putStringSet(SettingConstants.KEY_DISABLED_CHARSETS, next) }
    }

    /**
     * 加入一个自定义字符集。
     *
     * 只负责落盘与登记，**不做校验** —— 「能不能加」由 [CharsetCatalog.validateAdd] 判断，
     * 调用方（设置页）拿到 [CharsetAddResult.Ok] 才调这里，免得两处各判一遍。
     */
    fun addCustomCharset(name: String) {
        val next = (_customCharsets.value + name).distinct()
        if (next == _customCharsets.value) return
        applyCustomCharsets(next)
    }

    /**
     * 删除一个自定义字符集（内置条目删不掉，不想用就禁用）。
     *
     * 它与「禁用」的区别值得记住：禁用只是编辑器下拉少一项，**删除会把名字从
     * [CharsetCatalog] 里拿走**，于是已经在用这个名字的模板会重新报 `UNKNOWN_CHARSET`
     * —— 设置页那句确认对话框讲的就是这件事。
     *
     * 顺带把它从禁用集里清掉：条目都没了，留着只会变成一份永远对不上的残留数据。
     */
    fun removeCustomCharset(name: String) {
        val next = _customCharsets.value - name
        if (next == _customCharsets.value) return
        applyCustomCharsets(next)

        val disabled = _disabledCharsets.value - name
        if (disabled == _disabledCharsets.value) return
        _disabledCharsets.value = disabled
        prefs.edit { putStringSet(SettingConstants.KEY_DISABLED_CHARSETS, disabled) }
    }

    /** 新增 / 删除的共同收尾：落盘 + 登记进清单（解析层就靠这次登记认这个名字） */
    private fun applyCustomCharsets(next: List<String>) {
        _customCharsets.value = next
        prefs.edit {
            putString(SettingConstants.KEY_CUSTOM_CHARSETS, next.joinToString(CUSTOM_CHARSET_SEPARATOR))
        }
        CharsetCatalog.registerCustom(next)
    }

    private fun readNameList(key: String): List<String> =
        prefs.getString(key, null)
            ?.split(CUSTOM_CHARSET_SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
}