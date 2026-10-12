package com.github.remotecontrol

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.github.remotecontrol.ui.ControlScreen
import com.github.remotecontrol.ui.theme.RemoteInputTheme
import com.github.remotecontrol.util.AppPreferences
import com.github.remotecontrol.util.HueScheme
import com.github.remotecontrol.util.KeyboardLayoutMode
import com.github.remotecontrol.util.LocaleHelper
import com.github.remotecontrol.util.ThemeMode
import com.github.remotecontrol.viewmodel.BluetoothHidViewModel
import com.github.remotecontrol.viewmodel.hasActiveLink

/**
 * 控制页。
 *
 * ## 它现在只是「视图」
 * 蓝牙 HID 连接归 [BluetoothHidViewModel] 的**进程级单例**所有，本页只把状态画出来、
 * 把用户动作转成 ViewModel 调用。由此定下三条：
 *
 * 1. **销毁本页不再断开连接** —— 这是「切到别的应用就断开」的修复点：老实现在 `onDestroy`
 *    里 `cleanup()`，Activity 一被系统回收，链路就跟着拆了；
 * 2. **断开是显式动作** —— 走顶栏那个错误色按钮 + 确认框，后退键只退出界面
 *    （见 [exitControlScreen] / [disconnectAndExit]）；
 * 3. 通知栏点「回到控制页」时 `singleTop` 会复用同一个实例（走 [onNewIntent]），
 *    所以设备地址必须是 state，而不是只在 `onCreate` 里读一次 intent。
 */
class ControlActivity : ComponentActivity() {

    /**
     * 不使用 `by viewModels()`：那是 Activity 作用域，Activity 一销毁连接就跟着没了。
     */
    private val bluetoothHidViewModel: BluetoothHidViewModel =
        BluetoothHidViewModel.getInstance()

    private var isDark by mutableStateOf(false)
    private var hue by mutableStateOf(HueScheme.AZURE)
    private var langVersion by mutableIntStateOf(0)
    private var keyboardLayoutMode by mutableStateOf(KeyboardLayoutMode.FOLLOW_UI)

    /** 本次要连的设备，由 [onCreate] / [onNewIntent] 从 intent 读入 */
    private var deviceAddress by mutableStateOf<String?>(null)
    private var deviceName by mutableStateOf("")

    /**
     * 当前屏幕方向。
     *
     * ControlActivity 拦截了 orientation 配置变更（见 AndroidManifest 的 configChanges），
     * 旋转时不会重建 Activity，也就不会重走 onCreate —— 这意味着
     * Compose 的 LocalConfiguration 不会自动刷新，必须由 [onConfigurationChanged] 显式更新，
     * 否则横竖屏布局不会切换。
     */
    private var orientation by mutableIntStateOf(Configuration.ORIENTATION_UNDEFINED)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        readDeviceFromIntent(intent)

        val prefs = AppPreferences.getInstance(this)
        isDark = when (prefs.themeMode) {
                ThemeMode.SYSTEM -> isSystemDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
        hue = prefs.hueScheme
        langVersion = prefs.languageMode.ordinal
        // 宿主键盘布局：与 hue / isDark 一样只在 Compose 侧生效，不需要 recreate
        keyboardLayoutMode = prefs.keyboardLayoutMode
        // 首次创建（含 uiMode / 语言变更引起的重建）从当前资源配置取初值；
        // 之后的方向变化走 onConfigurationChanged，不会回到这里
        orientation = resources.configuration.orientation
        applySystemBarsForOrientation(orientation)

        setContent {
            RemoteInputTheme(
                darkTheme = isDark,
                hue = hue
            ) {
                ControlScreen(
                    viewModel = bluetoothHidViewModel,
                    deviceName = deviceName,
                    deviceAddress = deviceAddress,
                    orientation = orientation,
                    keyboardLayoutMode = keyboardLayoutMode,
                    onBack = { exitControlScreen() },
                    onDisconnect = { disconnectAndExit() }
                )
            }
        }
    }

    /**
     * `singleTop` 下从常驻通知的「回到控制页」打进来时走这里：实例被复用，只有 intent 是新的。
     * 把设备地址重新读出来交给 Compose，`DeviceConnectionEffect` 才会按新设备（或同一台）办事。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readDeviceFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val prefs = AppPreferences.getInstance(this)
        val newDark = when (prefs.themeMode) {
                ThemeMode.SYSTEM -> isSystemDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
        val newHue = prefs.hueScheme
        if (newDark != isDark || newHue != hue) {
            isDark = newDark
            hue = newHue
        }
        val newLangVersion = prefs.languageMode.ordinal
        if (newLangVersion != langVersion) {
            langVersion = newLangVersion
            recreate()
        }
        // 本页没有通往设置页的入口，正常路径下这个值不会中途变化；仍按 hue 的写法比对一次，
        // 免得以后加了入口之后键盘页还停留在旧布局上
        val newKeyboardLayout = prefs.keyboardLayoutMode
        if (newKeyboardLayout != keyboardLayoutMode) {
            keyboardLayoutMode = newKeyboardLayout
        }
    }

    /**
     * 旋转屏幕时系统不会重建 Activity（configChanges 已拦截），配置变更只会走到这里。
     * 这里把方向写进 state，Compose 侧才能重组出横屏 / 竖屏布局。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        orientation = newConfig.orientation
        applySystemBarsForOrientation(newConfig.orientation)
    }

    /**
     * 后退：只退出界面，**连接留在后台**（由前台服务与常驻通知承载）。
     *
     * 提示一句是必要的 —— 后退在别的应用里往往意味着「结束这次使用」，这里却什么都不结束，
     * 不提醒的话用户会以为连接断了。链路本来就没建起来时不提示，免得反而误导。
     */
    private fun exitControlScreen() {
        if (bluetoothHidViewModel.state.value.hasActiveLink) {
            Toast.makeText(this, R.string.connection_kept_toast, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    /** 断开连接：用户显式点断开按钮、并在确认框里确认之后才走 */
    private fun disconnectAndExit() {
        bluetoothHidViewModel.cleanup()
        finish()
    }

    private fun readDeviceFromIntent(intent: Intent?) {
        deviceAddress = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
        deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME) ?: getString(R.string.unknown_device)
    }

    /**
     * 横屏隐藏状态栏。
     *
     * 横屏布局没有 Scaffold（也就没人处理系统栏 insets），而键盘页的分页标签贴在内容顶部 ——
     * 状态栏会压在标签上，导致顶部那一条点不动。隐藏后仍然可以从顶部下滑临时唤出，
     * 不影响用户看时间 / 通知。
     */
    private fun applySystemBarsForOrientation(orientation: Int) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    private fun isSystemDarkTheme(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_DEVICE_NAME = "device_name"
    }
}
