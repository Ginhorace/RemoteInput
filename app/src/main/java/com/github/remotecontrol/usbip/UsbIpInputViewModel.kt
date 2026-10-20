package com.github.remotecontrol.usbip

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.remotecontrol.HidForegroundService
import com.github.remotecontrol.hid.HidError
import com.github.remotecontrol.hid.HidLedSync
import com.github.remotecontrol.hid.HidSession
import com.github.remotecontrol.hid.HidState
import com.github.remotecontrol.hid.HidTiming
import com.github.remotecontrol.hid.LinkCoordinator
import com.github.remotecontrol.hid.PointerKind
import com.github.remotecontrol.hid.isListening
import com.github.remotecontrol.util.AppPreferences
import com.github.remotecontrol.util.HidReportDescriptors
import com.github.remotecontrol.util.KeyboardConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 局域网（USB/IP）链路的 ViewModel：手机当设备，电脑用 `usbip attach` 挂上来。
 *
 * ## 与蓝牙那条链路的关系
 * 它与 [com.github.remotecontrol.bluetooth.BluetoothHidViewModel] 都实现 [HidSession]，
 * 因此 `ui/` 下那份控制页对两条链路是同一份（曾经是两处副本，现已合并）。
 * 报告的口径、键盘灯的判断都直接复用蓝牙那套（[HidLedSync] 零改动）——
 * 两条链路的差别被压到「报告怎么出去」这一件事上，`sendReport` 里那三行就是全部差异。
 * 两者**互斥**（见 [LinkCoordinator]）：本链路占住时蓝牙让位，反之亦然。
 *
 * ## 状态怎么读
 * | [HidState] | 这里的含义 |
 * | --- | --- |
 * | [HidState.Idle] | 没在监听（初值、或用户点了停止） |
 * | [HidState.Ready] | 正在监听，还没有电脑挂上来 |
 * | [HidState.Connected] | 已挂载：报告可以直接发 |
 * | [HidState.RegisterFailed] | 端口被占 / 启动失败，页面显示失败原因 |
 *
 * 与蓝牙不同的是，这里**没有**「正在初始化 / 正在注册」那几档：局域网链路没有系统服务要绑定，
 * 绑端口是一瞬间的事。
 *
 * ## 生命周期
 * 与 [com.github.remotecontrol.bluetooth.BluetoothHidViewModel] 同一写法：**进程级单例**。
 * 监听不该随控制页销毁而停 —— 用户退到首屏甚至别的应用时，电脑那边的挂载要还在。
 */
class UsbIpInputViewModel private constructor(context: Context) : ViewModel(), HidSession {

    /** 只用 applicationContext：监听端口是设置项，得从偏好读 */
    private val prefs = AppPreferences.getInstance(context)

    private val _state = MutableStateFlow<HidState>(HidState.Idle)
    override val state: StateFlow<HidState> = _state

    /**
     * 键盘灯与「这台主机理不理 Num Lock」的判断：与蓝牙链路**共用同一份实现**。
     * 局域网这边唯一多出来的一步是把主机下发的 Output Report 从 USB 控制传输里取出来
     * （见 [VirtualUsbDevice]），再交给它。
     */
    private val ledSync = HidLedSync(viewModelScope, _state, ::tapKey)

    /** 局域网注册的是触摸板 + 键盘（见 [UsbHidDescriptors.reportDescriptor]），与经典蓝牙分开 */
    override val pointerKind: PointerKind = PointerKind.TOUCHPAD

    override val keyboardLeds: StateFlow<KeyboardConstants.KeyboardLedsState> = ledSync.leds

    override val numpadWarning: StateFlow<Boolean> = ledSync.numpadWarning

    /** 本机局域网地址，卡片上显示 `地址:端口`；拿不到时是 null */
    private val _localAddress = MutableStateFlow<String?>(null)
    val localAddress: StateFlow<String?> = _localAddress

    /**
     * 监听端口。取值来自设置（见 [AppPreferences.usbipPort]），出厂值 3240，卡片与标题直接显示它。
     *
     * 是 StateFlow 而不是普通值：用户在设置里改完返回时，卡片上的「地址:端口」要立刻跟着变。
     */
    val port: StateFlow<Int> = prefs.usbipPort

    private val device = VirtualUsbDevice(
        log = ::logLine,
        onKeyboardLedReport = { ledSync.onHostReport(it) },
    )

    /**
     * 服务是否在监听。
     *
     * 单独记一份而不是每次问 [UsbIpServer.isListening]：断开回调里要判断「监听还在不在」，
     * 而那个回调是在 `server` 的初始化表达式里定义的 —— 去读 `server` 自己会让编译器
     * 掉进「属性初始化递归」。
     */
    @Volatile
    private var listening = false

    private val server: UsbIpServer = UsbIpServer(device, log = ::logLine).apply {
        onHostConnected = {
            // 新的一次会话：灯态要重新问一遍（主机不会主动说）
            ledSync.onConnected()
            _state.value = HidState.Connected
            logLine("电脑已挂载本设备")
            sendWakeUpReports()
        }
        onHostDisconnected = {
            logLine("电脑已断开")
            // 监听还开着就回到「等下一台电脑」，用户停了监听才回到 Idle
            _state.value = if (listening) HidState.Ready else HidState.Idle
        }
    }

    /**
     * 在 [LinkCoordinator] 里代表本链路（见「两条链路二选一」）。
     *
     * 「占住」的口径是 [isListening] —— **开始监听就算**（不必等电脑挂上来）：用户点了
     * 「开始监听」就是选了这条链路，此刻该把蓝牙那条收掉。让位动作是 [stopListening]。
     */
    private val link = object : LinkCoordinator.Link {
        override val engaged: StateFlow<Boolean> =
            _state.map { it.isListening }
                .stateIn(viewModelScope, SharingStarted.Eagerly, _state.value.isListening)

        override fun yield() {
            Log.d(TAG, "Yielding to the Bluetooth link, stopping listening")
            stopListening()
        }
    }

    init {
        // 两条链路二选一：本链路占住时，蓝牙那条（若还连着）让位
        LinkCoordinator.register(LINK_NAME, link)

        // 端口是设置项。改了之后**必须把正在跑的监听停掉**：服务端绑的还是旧端口，
        // 而卡片与标题显示的是新端口 —— 不停的话界面会开始骗人（显示 `IP:新端口`，
        // 实际却在旧端口上听）。停的是显式动作，用户回卡片再点一次「开始监听」即可。
        viewModelScope.launch {
            prefs.usbipPort.drop(1).collect {
                if (listening) {
                    Log.d(TAG, "Port changed while listening, stopping")
                    stopListening()
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    // 监听开关
    // ─────────────────────────────────────────────

    /**
     * 开始监听。
     *
     * @param context 只用来拉起那个前台服务（见 [HidForegroundService]）—— **监听期间就要保活**，
     *   这才是「退到后台还能等电脑挂上来」的前提，所以这里要一个 Context；本身不持有它
     * @return 是否启动成功；失败（端口被占等）时状态是 [HidState.RegisterFailed]
     */
    fun startListening(context: Context): Boolean {
        if (listening) return true
        val started = server.start(port.value)
        if (started) {
            listening = true
            _localAddress.value = LanAddress.ipv4()
            _state.value = HidState.Ready
            HidForegroundService.start(context)
        } else {
            _state.value = HidState.RegisterFailed(HidError.RegisterFailed)
        }
        return started
    }

    /**
     * 停止监听：电脑那边的设备会随之被拔出。
     *
     * 不在这里收掉前台服务：那个服务同时看着两条链路，由它自己判断「是不是都空闲了」。 */
    fun stopListening() {
        listening = false
        server.stop()
        ledSync.onCleanup()
        _state.value = HidState.Idle
    }

    /** 卡片上那个开关按钮 */
    fun toggleListening(context: Context): Boolean =
        if (listening) {
            stopListening()
            false
        } else {
            startListening(context)
        }

    /** 用户关掉失败提示：回到「没在监听」，按钮可以再按一次 */
    override fun dismissError() {
        if (_state.value is HidState.RegisterFailed || _state.value is HidState.ConnectFailed) {
            _state.value = HidState.Idle
        }
    }

    // ─────────────────────────────────────────────
    // 报告出口（界面副本只认这几个方法）
    // ─────────────────────────────────────────────

    override fun sendReport(reportId: Int, data: ByteArray) {
        if (reportId == HidReportDescriptors.REPORT_ID_KEYBOARD) {
            // 锁键按一下会翻转主机那边的锁定状态（主机随后把新的灯态推回来）。
            // 第 2 字节是按键 Usage ID，抬起报告 / 空报告那里是 0，会被原样返回
            ledSync.onLocalKeyPress(data.getOrNull(2) ?: 0)
        }
        sendViaUsb(reportId, data)
    }

    override fun sendNeutralReports() {
        sendViaUsb(
            HidReportDescriptors.REPORT_ID_TOUCHPAD,
            ByteArray(HidReportDescriptors.TOUCHPAD_REPORT_SIZE)
        )
        sendViaUsb(
            HidReportDescriptors.REPORT_ID_KEYBOARD,
            ByteArray(HidReportDescriptors.KEYBOARD_REPORT_SIZE)
        )
    }

    override fun syncKeyboardLeds() {
        ledSync.sync()
    }

    override fun dismissNumpadWarning() {
        ledSync.dismissNumpadWarning()
    }

    // ─────────────────────────────────────────────
    // 内部
    // ─────────────────────────────────────────────

    /**
     * 报告出口。
     *
     * 没有电脑挂着时直接丢掉：报告是**实时状态**（手指现在在哪、哪个键现在按着），
     * 攒下来等主机来了再补发只会让主机看到一串过期的手指位置。这与蓝牙那边
     * 「没有目标设备就丢弃」是同一个口径。
     */
    private fun sendViaUsb(reportId: Int, data: ByteArray) {
        if (_state.value !is HidState.Connected) return
        server.sendReport(reportId, data)
    }

    /** 按一次某键：按下 + 抬起两条报告，不带修饰位、也不计入本地灯态推断 */
    private fun tapKey(key: KeyboardConstants.Key) {
        val press = KeyboardConstants.KeyboardReport(keyCode = key.value).toByteArray()
        val release = KeyboardConstants.KeyboardReport().toByteArray()
        sendViaUsb(HidReportDescriptors.REPORT_ID_KEYBOARD, press)
        sendViaUsb(HidReportDescriptors.REPORT_ID_KEYBOARD, release)
    }

    /**
     * 连接刚建立时，主机侧的 HID 集合 / PTP 初始化还没走完，这一小段窗口里发的报告会被丢掉。
     * 延迟片刻补一帧空报告，让主机不依赖用户操作就收到一次有效数据。
     */
    private fun sendWakeUpReports() {
        viewModelScope.launch {
            delay(HidTiming.WAKE_UP_REPORT_DELAY_MS)
            if (_state.value !is HidState.Connected) return@launch
            logLine("补发空报告，唤醒主机侧坐标跟踪")
            sendNeutralReports()
        }
    }

    private fun logLine(message: String) {
        Log.d(TAG, message)
    }

    /** 收摊：停止监听。由控制页的「断开」调用 */
    override fun cleanup() {
        stopListening()
    }

    companion object {

        /**
         * 日志标签。
         *
         * USB/IP 这一条链路的现场诊断全靠日志（`Get report:` / `Control ` 两族），
         * 用短而唯一的标签是为了 `adb logcat -s UsbIp` 能一条不落地捞出来。
         */
        private const val TAG = "UsbIp"

        /** 在 [LinkCoordinator] 里的登记名 */
        private const val LINK_NAME = "lan"

        /**
         * 进程级单例：与 [com.github.remotecontrol.bluetooth.BluetoothHidViewModel.getInstance]
         * 同一写法 —— 监听不该随 Activity 销毁而停。
         *
         * 比蓝牙那边多一个 [context]：监听端口是**设置项**（见 [AppPreferences.usbipPort]），
         * 得从偏好读。存进去的是 `applicationContext`，不会泄漏；调用方（卡片 / 控制页 / 前台服务）
         * 本来就都是 Context 持有者。
         */
        @Volatile
        private var instance: UsbIpInputViewModel? = null

        fun getInstance(context: Context): UsbIpInputViewModel =
            instance ?: synchronized(this) {
                instance ?: UsbIpInputViewModel(context.applicationContext).also { instance = it }
            }
    }
}
