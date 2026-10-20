package com.github.remotecontrol.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.remotecontrol.util.AppPreferences
import com.github.remotecontrol.util.CharsetAddResult
import com.github.remotecontrol.util.CharsetCatalog
import com.github.remotecontrol.util.HueScheme
import com.github.remotecontrol.util.KeyboardLayoutMode
import com.github.remotecontrol.util.LanguageMode
import com.github.remotecontrol.util.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 设置界面的ViewModel
 * 负责管理设置相关的状态和逻辑
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences.getInstance(getApplication())

    // 主题设置状态
    private val _themeMode = MutableStateFlow(prefs.themeMode)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _hueScheme = MutableStateFlow(prefs.hueScheme)
    val hueScheme: StateFlow<HueScheme> = _hueScheme.asStateFlow()

    // 语言设置状态
    private val _languageMode = MutableStateFlow(prefs.languageMode)
    val languageMode: StateFlow<LanguageMode> = _languageMode.asStateFlow()

    // 宿主键盘布局状态（默认跟随界面语言）
    private val _keyboardLayoutMode = MutableStateFlow(prefs.keyboardLayoutMode)
    val keyboardLayoutMode: StateFlow<KeyboardLayoutMode> = _keyboardLayoutMode.asStateFlow()

    // 字符集：禁用的那些 + 用户自己加的那些（都由 prefs 持有，这里直接转发）
    val disabledCharsets: StateFlow<Set<String>> = prefs.disabledCharsets
    val customCharsets: StateFlow<List<String>> = prefs.customCharsets

    // 局域网监听端口（由 prefs 持有，这里直接转发）
    val usbipPort: StateFlow<Int> = prefs.usbipPort

    // 经典蓝牙鼠标触摸区的长按判定时长（由 prefs 持有，这里直接转发）
    val longPressDuration: StateFlow<Int> = prefs.longPressDuration

    /**
     * 更新主题模式（跟随系统/浅色/深色）
     */
    fun updateThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        viewModelScope.launch(Dispatchers.IO) {
            prefs.themeMode = mode
        }
    }

    /**
     * 更新色相方案（决定强调色与整条浓度阶梯）
     */
    fun updateHueScheme(hue: HueScheme) {
        _hueScheme.value = hue
        viewModelScope.launch(Dispatchers.IO) {
            prefs.hueScheme = hue
        }
    }

    /**
     * 更新语言模式（跟随系统/中文/英文/日文）
     */
    fun updateLanguageMode(mode: LanguageMode) {
        _languageMode.value = mode
        viewModelScope.launch(Dispatchers.IO) {
            prefs.languageMode = mode
        }
    }

    /**
     * 更新宿主键盘布局（决定屏幕键盘印什么字、主机按下某个位置输出什么字符）
     */
    fun updateKeyboardLayoutMode(mode: KeyboardLayoutMode) {
        _keyboardLayoutMode.value = mode
        viewModelScope.launch(Dispatchers.IO) {
            prefs.keyboardLayoutMode = mode
        }
    }

    /**
     * 启用 / 禁用某个字符集。只影响模板编辑器的下拉，不影响模板能否执行。
     */
    fun setCharsetEnabled(name: String, enabled: Boolean) {
        prefs.setCharsetEnabled(name, enabled)
    }

    /**
     * 校验并加入一个自定义字符集。
     *
     * 返回校验结果供界面提示（重复 / 不存在 / 成功），调用方按结果决定显示哪句文案。
     */
    fun addCharset(raw: String): CharsetAddResult {
        val result = CharsetCatalog.validateAdd(raw)
        if (result is CharsetAddResult.Ok) {
            prefs.addCustomCharset(result.name)
        }
        return result
    }

    /**
     * 删除一个自定义字符集。界面侧点过确认对话框才调这里 ——
     * 删掉之后用它写过的模板会报 `UNKNOWN_CHARSET`，这一步没有撤销。
     */
    fun removeCharset(name: String) {
        prefs.removeCustomCharset(name)
    }

    /**
     * 更新局域网监听端口。
     *
     * 直接落盘（不绕 IO 协程）：局域网链路那边观察着这个 flow，端口一变就把正在跑的监听停掉，
     * 免得卡片显示的 `地址:端口` 与实际绑定的端口对不上。
     */
    fun updateUsbipPort(port: Int) {
        prefs.setUsbipPort(port)
    }

    /**
     * 更新鼠标长按判定时长。
     *
     * 只影响经典蓝牙链路鼠标页的「单击 / 长按」判定，不涉及资源配置，改完不需要 recreate。
     */
    fun updateLongPressDuration(millis: Int) {
        prefs.setLongPressDuration(millis)
    }
}