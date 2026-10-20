package com.github.remotecontrol

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.github.remotecontrol.hid.HidSession
import com.github.remotecontrol.hid.hasActiveLink
import com.github.remotecontrol.ui.ControlScreen
import com.github.remotecontrol.ui.NotificationPermissionEffect
import com.github.remotecontrol.ui.theme.RemoteInputTheme
import com.github.remotecontrol.util.AppPreferences
import com.github.remotecontrol.util.HueScheme
import com.github.remotecontrol.util.KeyboardLayoutMode
import com.github.remotecontrol.util.LocaleHelper
import com.github.remotecontrol.util.ThemeMode

/**
 * 控制页的**共用外壳**：三条链路的控制页只差「控制哪个会话、顶栏写什么、有没有专属副作用」，
 * 其余全是同一件事，收在这里。
 *
 * 它在**根包**而不是 `ui/`：它是「三条链路控制页的组装点」，与 [HidForegroundService] 对称 ——
 * 放在 `ui/` 就会让共享界面反向依赖根包（这个类要用 [HidForegroundService.start] 在通知权限
 * 授予后重画一次通知）。`ui/` 那边只放纯粹的 Compose 界面件。
 *
 * ## 它包住的东西
 * 主题 / 界面语言 / 宿主键盘布局 / 屏幕方向的读入与刷新、系统栏处理、后退与断开两个出口、
 * 以及把 [ControlScreen] 挂上去。这些都是**界面外壳**的事，与报告怎么发出去无关。
 *
 * ## 它不包住的东西（留给子类）
 * | 差异 | 由谁给 |
 * | --- | --- |
 * | 控制哪个会话 | [session]（各链路自己的进程级单例） |
 * | 顶栏标题 | [controlTitle]：蓝牙是设备名，BLE 是已连主机名，局域网是「本机地址:端口」 |
 * | 链路专属副作用 | [LinkEffects]：蓝牙要在进页时初始化 HID 应用 / 按地址连接 + 维护快捷方式；BLE 与局域网没有 |
 * | 入参 | [readExtras]：蓝牙从 intent 读设备地址与名字，BLE 与局域网没有入参 |
 *
 * 反过来，**通知权限申请三条链路都要**，所以它不留给子类，由本类的 `setContent` 统一挂上
 * （见 [NotificationPermissionEffect]）—— 曾经它长在蓝牙那份副作用里，结果局域网控制页永远不问，
 * 局域网那种「一进页就有一条常驻通知」的链路在 Android 13+ 上一直没有通知栏。
 *
 * ## 为什么是两个 Activity，而不是一个带「链路」参数的 Activity
 * 合并到这一层（共用基类）就够了 —— 三条链路的**入口、入参、标题来源**都不同：
 * 蓝牙由扫描页带设备地址启动、标题是设备名，BLE 由首屏卡片开启广播、标题是已连主机名，
 * 局域网由卡片启动、标题是本机地址。
 * 若硬合成一个 Activity，就得塞一个链路枚举进去，并且会撞上一个真问题：
 * `singleTop` 下「蓝牙控制页在栈顶时，从通知栏点进局域网控制页」会**复用同一个实例**、
 * 中途换链路 —— Compose 里 `remember` 的当前模式、模板编辑器草稿会跨链路保留下来，
 * 而这三条链路是互斥的，界面状态本就不该串。
 *
 * 三条不该被破坏的约定（三条链路共同承担）：
 *
 * 1. **本页只是「视图」**：链路归各自的进程级单例所有，`onDestroy` 里**不许**出现 `cleanup()` ——
 *    那正是「切到别的应用就断开」的老病根；
 * 2. **后退与断开是两件事**：后退只 `finish()`（链路还活着时 Toast 说明一句），
 *    断开走顶栏那个错误色按钮 + 确认框（见 [exitControlScreen] / [disconnectAndExit]）；
 * 3. **旋转不重建**（manifest 里拦了 `orientation`）：Compose 的 `LocalConfiguration` 不会自动刷新，
 *    方向必须由 [onConfigurationChanged] 显式写进 state。
 */
abstract class HidControlActivity : ComponentActivity() {

    /**
     * 本页控制的会话。
     *
     * 必须是**进程级单例**（各链路自己的 `getInstance()`），**不能**用 `by viewModels()` ——
     * 那是 Activity 作用域，本页一销毁链路就跟着收摊了。
     */
    protected abstract val session: HidSession

    /**
     * 顶栏标题：蓝牙是设备名（可能随 `onNewIntent` 换），BLE 是已连主机名，
     * 局域网是「本机地址:端口」
     */
    @Composable
    protected abstract fun controlTitle(): String

    /**
     * 链路专属的界面副作用，与 [ControlScreen] 并列挂上。
     *
     * 只有蓝牙那条链路有（初始化 HID 应用、按地址发起连接 + 更新快捷方式、连接成功那一刻申请通知权限，
     * 见 `bluetooth/BluetoothLinkEffects.kt`）；BLE 与局域网默认什么都不做。
     */
    @Composable
    protected open fun LinkEffects() = Unit

    /**
     * 读本页的入参。
     *
     * 在 [onCreate] 与 [onNewIntent] 各调一次：`singleTop` 下从通知栏打进来时实例被复用、
     * 只有 intent 是新的，入参必须写成 state 才不会停在旧值上。默认什么都不读。
     */
    protected open fun readExtras(intent: Intent?) = Unit

    private var isDark by mutableStateOf(false)
    private var hue by mutableStateOf(HueScheme.AZURE)
    private var langVersion by mutableIntStateOf(0)
    private var keyboardLayoutMode by mutableStateOf(KeyboardLayoutMode.FOLLOW_UI)
    private var orientation by mutableIntStateOf(Configuration.ORIENTATION_UNDEFINED)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        readExtras(intent)

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
                // 通知权限三条链路共用（时机：链路真的连上那一刻，全局只问一次）。
                // 授予之后要让服务**重画一次**通知：没有权限时 notify 是静默丢弃的，
                // 那条通知不会自己回来 —— 而三条链路都靠它当后台入口（蓝牙回控制页、
                // BLE 看主机连没连上、局域网看电脑挂没挂上）
                NotificationPermissionEffect(
                    session = session,
                    onPermissionGranted = { HidForegroundService.start(this) },
                )
                // 副作用在前、界面在后：蓝牙那边要靠它把「按地址连接」这件事在进页时就发起
                LinkEffects()
                ControlScreen(
                    viewModel = session,
                    deviceName = controlTitle(),
                    orientation = orientation,
                    keyboardLayoutMode = keyboardLayoutMode,
                    onBack = { exitControlScreen() },
                    onDisconnect = { disconnectAndExit() }
                )
            }
        }
    }

    /**
     * `singleTop` 下被复用实例时走这里：只有 intent 是新的，入参要重新读一遍。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readExtras(intent)
    }

    /**
     * 设置页改过的语言 / 主题回到本页时生效。
     *
     * 本页没有通往设置页的入口，正常路径下这些值不会中途变化；仍逐项比对，是为了兜住
     * 「以后加了入口就忘了同步」这件事。语言变了要 `recreate()` —— 译文是编译进 resources 的，
     * 换语言必须重建，主题与布局则只要写进 state 就会重组。
     */
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
     * 后退：只退出界面，**链路留在后台**（由前台服务与常驻通知承载）。
     *
     * 提示一句是必要的 —— 后退在别的应用里往往意味着「结束这次使用」，这里却什么都不结束，
     * 不提醒的话用户会以为连接断了。链路本来就没建起来时不提示，免得反而误导。
     */
    private fun exitControlScreen() {
        if (session.state.value.hasActiveLink) {
            Toast.makeText(this, R.string.connection_kept_toast, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    /** 断开：用户显式点断开按钮、并在确认框里确认之后才走 */
    private fun disconnectAndExit() {
        session.cleanup()
        finish()
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
}
