package com.github.remotecontrol.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.remotecontrol.HidForegroundService
import com.github.remotecontrol.hid.HidError
import com.github.remotecontrol.hid.HidLedSync
import com.github.remotecontrol.hid.HidSession
import com.github.remotecontrol.hid.HidState
import com.github.remotecontrol.hid.LinkCoordinator
import com.github.remotecontrol.hid.HidTiming
import com.github.remotecontrol.hid.PointerKind
import com.github.remotecontrol.hid.hasActiveLink
import com.github.remotecontrol.util.HidReportDescriptors
import com.github.remotecontrol.util.KeyboardConstants
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * 本类所有蓝牙 HID 调用均发生在用户已授予 BLUETOOTH_CONNECT / BLUETOOTH_SCAN 之后
 * （授权入口见 BluetoothScannerScreen，权限状态校验见 BluetoothScannerViewModel），
 * 且各调用点均已用 try/catch 兜底，故统一抑制 MissingPermission 误报。
 */
@SuppressLint("MissingPermission")
class BluetoothHidViewModel : ViewModel(), HidDeviceCallbackHost, HidSession {

    private val _state = MutableStateFlow<HidState>(HidState.Idle)

    /**
     * HID 链路的唯一真源。
     *
     * 取代了原来的 isAppRegistered / connectionState / error 三份状态：三者可以互相矛盾
     * （注册失败时连接状态仍是「未连接」，而「连接失败」得靠「未连接 + 有错误」拼出来），
     * 收敛成一个状态机之后，矛盾状态无法被构造，「失败」也成了能直接渲染的状态。
     */
    override val state: StateFlow<HidState> = _state

    /**
     * 键盘灯（Num / Caps / Scroll）与「这台主机理不理 Num Lock」的整块逻辑，见 [HidLedSync]。
     * 下面两个 flow 只是把它转出来给界面读。
     *
     * [::tapKey] 交出去是安全的：它真正被调用要等 [syncKeyboardLeds]，那时本类早已构造完成。
     */
    private val ledSync = HidLedSync(viewModelScope, _state, ::tapKey)

    /** 经典蓝牙注册的是鼠标 + 键盘（见 [HidReportDescriptors.mouseCombinedDescriptor]） */
    override val pointerKind: PointerKind = PointerKind.MOUSE

    /** 键盘灯状态 + 这份值是不是主机确认过的，见 [HidLedSync.leds] */
    override val keyboardLeds: StateFlow<KeyboardConstants.KeyboardLedsState> = ledSync.leds

    /** 「主机连 Num Lock 都不理」的提示，见 [HidLedSync.numpadWarning] */
    override val numpadWarning: StateFlow<Boolean> = ledSync.numpadWarning

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothHidDevice: BluetoothHidDevice? = null

    /**
     * 当前链路上的设备名 / 地址，没有链路时都是 null。
     *
     * 扫描页的「正在保持的连接」卡片靠它显示是哪台设备 —— 那台设备不一定在扫描页的列表里
     * （用户可能刚从控制页返回、列表还没扫），所以不能反查列表。
     */
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName

    private val _connectedDeviceAddress = MutableStateFlow<String?>(null)
    val connectedDeviceAddress: StateFlow<String?> = _connectedDeviceAddress

    /**
     * 本次会话的目标设备（正在连接的，或已连接的）。
     *
     * 由 [connectToDevice] 从地址解析出来；主机主动发起连接时由连接回调补上。
     *
     * 它同时是对外那两个状态流的**唯一写入点**：把「改设备」与「通知界面」绑在一起，
     * 省得在连接回调 / 断开回调 / cleanup 各处漏掉一处同步。
     */
    private var currentDevice: BluetoothDevice? = null
        set(value) {
            field = value
            _connectedDeviceAddress.value = value?.address
            // 读名字要 BLUETOOTH_CONNECT，个别 ROM 在权限被中途收回时会抛
            _connectedDeviceName.value = runCatching { value?.name }.getOrNull()
        }

    /**
     * 应用级 Context，仅用于查询配对状态与监听配对广播。
     * 存 applicationContext 而不是 Activity，避免 ViewModel 比 Activity 活得久时泄漏。
     */
    private var appContext: Context? = null

    /** 绑定 HID profile 服务的超时计时，见 [startBindTimeout] */
    private var bindTimeoutJob: Job? = null

    /**
     * 连接编排（等注册 → 等注册稳定 → 未配对时先配对再重建 → 发起连接），见 [BluetoothConnector]。
     *
     * 这里把本类那几个「读 / 写」入口交给它：写入一律走 [currentDevice] 的 setter，
     * 那两个对外的设备名 / 地址 flow 才会跟着同步。构造顺序无关紧要 —— 交出去的都是读取器，
     * 真正被调用要等用户发起连接。
     */
    private val connector = BluetoothConnector(
        scope = viewModelScope,
        state = _state,
        readDevice = { currentDevice },
        writeDevice = { currentDevice = it },
        readAdapter = { bluetoothAdapter },
        readHidDevice = { bluetoothHidDevice },
        readContext = { appContext },
        disconnectCurrent = ::disconnect,
    )

    /**
     * 在 [LinkCoordinator] 里代表本链路（见「两条链路二选一」）。
     *
     * 蓝牙一旦「连接中 / 已连接」就算占住 —— 用户点了连接就是它的意图；
     * 让位动作是 [cleanup]（注销 HID 应用、拆链）。
     */
    private val link = object : LinkCoordinator.Link {
        override val engaged: StateFlow<Boolean> =
            _state.map { it.hasActiveLink }
                .stateIn(viewModelScope, SharingStarted.Eagerly, _state.value.hasActiveLink)

        override fun yield() {
            Log.d("BluetoothHid", "Yielding to the LAN link, cleaning up")
            cleanup()
        }
    }

    init {
        // 链路一进入「连接中 / 已连接」，就把前台服务拉起来兜住进程。
        // 按 hasActiveLink 去重：连接过程中状态会连变几次（Connecting → Connected），
        // 每次都 startForegroundService 既没必要，也容易撞上 Android 12+ 的后台启动限制。
        // 这里只负责**启动**：收摊交给服务自己判断（它同时看着两条链路，
        // 蓝牙收摊时局域网可能还在监听，反过来 stopService 会误停）。
        viewModelScope.launch {
            state.map { it.hasActiveLink }.distinctUntilChanged().collect { active ->
                if (!active) return@collect
                val context = appContext ?: return@collect
                Log.d("BluetoothHid", "Active link, starting keep-alive service")
                HidForegroundService.start(context)
            }
        }

        // 两条链路二选一：本链路占住时，另一条（若还开着）让位
        LinkCoordinator.register(LINK_NAME, link)
    }

    private val profileServiceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                bluetoothHidDevice = proxy as? BluetoothHidDevice
                Log.d("BluetoothHid", "BluetoothHidDevice service connected: $bluetoothHidDevice")
                // 绑定成功就不该再被判成「系统不支持」
                bindTimeoutJob?.cancel()
                bindTimeoutJob = null
                registerApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                bluetoothHidDevice = null
                Log.d("BluetoothHid", "BluetoothHidDevice service disconnected")
            }
        }
    }

    /**
     * 绑定 HID profile 服务并注册 HID 应用。**幂等**。
     *
     * 单例化之后本方法会被多个入口重复调用（每次进控制页都会走一遍 `LaunchedEffect(Unit)`），
     * 而重复 `getProfileProxy` 会把正在用的代理顶掉、连接随之断开。
     *
     * 闸门是「代理已就绪，或流程已经在路上」（状态不是 [HidState.Idle]），**不是**
     * `bluetoothAdapter != null`：adapter 是进程级的稳定对象，[cleanup] 不会清它，
     * 拿它当闸门会让「断开之后再次进入控制页」永远走不到重新注册那一步 —— 界面停在
     * 「未连接」、连接发不出去（`awaitReady` 等不到 Ready）。
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (bluetoothHidDevice != null || _state.value !is HidState.Idle) {
            Log.d("BluetoothHid", "Already initialized (state=${_state.value}), skip")
            return
        }
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        _state.value = HidState.Initializing
        startBindTimeout()
        bluetoothAdapter?.getProfileProxy(
            context,
            profileServiceListener,
            BluetoothProfile.HID_DEVICE
        )
        Log.d("BluetoothHid", "Initialized: adapter=$bluetoothAdapter")
    }

    /**
     * 给绑定 HID profile 服务加超时。
     *
     * ROM 没启用 HID Device profile 时 `onServiceConnected` 永远不回调，界面会一直停在
     * 「正在初始化」，用户看到的只是一直转圈。超时即判定为系统不支持。
     *
     * 需要说明的是「能连蓝牙键鼠」并不代表支持这个能力：HID Host 与 HID Device 是两个
     * 独立的 profile，手机当 HID 设备要求系统侧启用 `bluetooth.profile.hid.device.enabled`，
     * 不少厂商 ROM 默认是关的。
     */
    private fun startBindTimeout() {
        bindTimeoutJob?.cancel()
        bindTimeoutJob = viewModelScope.launch {
            delay(HidTiming.BIND_TIMEOUT_MS)
            if (_state.value is HidState.Initializing) {
                Log.w(
                    "BluetoothHid",
                    "Bind HID profile timed out, HID Device profile looks unavailable"
                )
                _state.value = HidState.RegisterFailed(HidError.Unsupported)
            }
        }
    }

    /**
     * 系统蓝牙栈的回调出口（事件与日志都收在 [HidDeviceCallback] 里）。
     *
     * 构造期就把 this 交出去是安全的：回调真正开始送达要等 [registerApp]，
     * 那时 ViewModel 早已构造完成。
     */
    private val hidDeviceCallback = HidDeviceCallback(this)

    // ─────────────────────────────────────────────
    // HidDeviceCallbackHost：系统事件 → 状态机
    // ─────────────────────────────────────────────

    override fun onHidAppStatusChanged(registered: Boolean) {
        if (registered) {
            // registerApp() 里已经置过 Ready，这里只作兜底
            if (_state.value is HidState.Initializing || _state.value is HidState.Registering) {
                _state.value = HidState.Ready
            }
        } else {
            // 注销（cleanup）或系统回收 → 回到初始态；注册失败的状态要留着给用户看
            if (_state.value !is HidState.RegisterFailed) {
                _state.value = HidState.Idle
            }
        }
    }

    override fun onHidConnectionStateChanged(device: BluetoothDevice, state: Int) {
        when (state) {
            BluetoothProfile.STATE_CONNECTING -> {
                _state.value = HidState.Connecting
            }

            BluetoothProfile.STATE_CONNECTED -> {
                // 主机主动发起重连时 connectToDevice() 没有跑过，
                // 这里补上设备引用，否则后续 sendReport 会因为没有目标设备而被丢弃
                if (currentDevice == null) currentDevice = device
                // 新的一次连接：灯态要重新问一遍（主机不会主动说）。
                // 必须写在状态切换**之前** —— 状态一改，等在 sync() 里的协程就会被唤醒去读
                // 这些标志，写在后面会读到上一次连接的旧值
                ledSync.onConnected()
                _state.value = HidState.Connected
                sendWakeUpReports()
            }

            BluetoothProfile.STATE_DISCONNECTING -> {
                // 只有「已连接」之后的断开才值得显示断开中：
                // 配对结束后我们自己的那次重建连接不该闪一下「断开中」
                if (_state.value is HidState.Connected) {
                    _state.value = HidState.Disconnecting
                }
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                currentDevice = null
                _state.value = when (val current = _state.value) {
                    // 请求发出后一直没等到连接建立就断了：被动失败（主机拒绝、配对被拒等）
                    is HidState.Connecting -> HidState.ConnectFailed(HidError.ConnectFailed)
                    // 已经收摊了（cleanup）就别再改状态
                    is HidState.Idle -> current
                    else -> HidState.Ready
                }
            }
        }
    }

    override fun onHidGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
        // 经典蓝牙现在注册的是鼠标描述符，**没有** PTP 的 Feature 5（Contact Count Maximum）
        // 与 Feature 6（PTPHQA），所以主机不会来读这两条；来了也只按「描述符里没声明」回 HANDSHAKE。
        // PTP 那两条应答只属于局域网链路（见 `usbip/VirtualUsbDevice.onGetReport`）——
        // 应答描述符里不存在的报告，会把主机侧的初始化状态带偏。
        // 请求仍然必须明确收尾：用 HANDSHAKE 结束，否则主机会一直等下去
        // （超时的结果被它缓存在设备状态里，直到下次连接才重试）。
        val hidDevice = bluetoothHidDevice
        if (hidDevice == null) {
            // 真机上出现「主机读了、设备没答」时，日志里连一行都没有，
            // 现场只能靠抓包反推。所以这里把 type/id 一起打出来。
            Log.w("BluetoothHid", "Get report dropped: HID device not ready (type=$type, id=$id)")
            return
        }
        hidDevice.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        Log.d("BluetoothHid", "Replied handshake: type=$type, id=$id (mouse descriptor has no feature report)")
    }

    override fun onHidSetReport(
        device: BluetoothDevice?,
        type: Byte,
        id: Byte,
        data: ByteArray?
    ) {
        // 键盘灯走 Output Report：报告协议下 id = 2（键盘），Boot 协议下 id = 0、负荷 1 字节，
        // 两种都要认，否则换到 Boot 协议的主机上灯就不亮了
        if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && id.isKeyboardReport()) {
            ledSync.onHostReport(data)
        }
        // 主机设置报告后必须回 HANDSHAKE，否则这一轮初始化会卡住
        bluetoothHidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
    }

    override fun onHidInterruptData(reportId: Byte, data: ByteArray?) {
        // 部分主机的输出报告不走 SET_REPORT 而走中断通道，所以两个回调都要解析
        if (reportId.isKeyboardReport()) {
            ledSync.onHostReport(data)
        }
    }

    override fun onHidVirtualCableUnplug(device: BluetoothDevice?) {
        currentDevice = null
        // 连接本来已经建起来了，这只是主机侧的正常断开，不是连接失败
        if (_state.value !is HidState.Idle) {
            _state.value = HidState.Ready
        }
    }


    /**
     * 将应用注册成蓝牙HID触摸板+键盘。
     *
     * 失败基本都来自协议栈 / 系统侧（已有应用注册、L2CAP 通道被占、描述符校验不通过、
     * SDP 写入失败、ROM 未启用 HID Device），不是应用重试能解决的，
     * 所以失败后不提供「重试」，只作为终态提示用户去检查手机系统。
     */
    fun registerApp(): Boolean {
        if (bluetoothHidDevice == null) {
            Log.w(
                "BluetoothHid",
                "BluetoothHidDevice not ready, saving context for later registration"
            )
            return false
        }

        // 经典蓝牙链路用「鼠标 + 键盘」：指针走相对位移（触摸板那套 PTP 描述符留给局域网链路）。
        // 描述符会被主机在配对时缓存，改了这一份就必须在电脑上删掉设备重新配对。
        val descriptor = HidReportDescriptors.mouseCombinedDescriptor
        Log.w("BluetoothHid", "Descriptor size:${descriptor.size}")
        _state.value = HidState.Registering

        return try {
            val sdpSettings = BluetoothHidDeviceAppSdpSettings(
                "Remote Control",
                "GitHub Remote Control",
                "Remote Control Device",
                BluetoothHidDevice.SUBCLASS1_COMBO,
                descriptor
            )

            val qosSettings = BluetoothHidDeviceAppQosSettings(
                800,
                800,
                0,
                0,
                0,
                0
            )

            val result = bluetoothHidDevice!!.registerApp(
                sdpSettings,
                qosSettings,
                qosSettings,
                Executors.newSingleThreadExecutor(),
                hidDeviceCallback
            )

            if (result) {
                Log.d("BluetoothHid", "App registered successfully")
                _state.value = HidState.Ready
            } else {
                Log.e("BluetoothHid", "Failed to register app")
                _state.value = HidState.RegisterFailed(HidError.RegisterFailed)
            }

            result
        } catch (e: Exception) {
            Log.e("BluetoothHid", "Failed to register app", e)
            _state.value = HidState.RegisterFailed(HidError.RegisterException(e.message))
            false
        }
    }

    /**
     * 连接设备（节奏全部在 [BluetoothConnector] 里）。
     *
     * 调用方只给地址，其余交给连接器：等注册完成才发起连接、未配对时先配对再重建。
     * 地址相同的重复进入会在那边短路，不会把已建好的链路拆掉重建。
     */
    fun connectToDevice(address: String) {
        connector.connect(address)
    }

    fun disconnect() {
        try {
            currentDevice?.let { device ->
                bluetoothHidDevice?.disconnect(device)
                Log.d("BluetoothHid", "Disconnecting from ${device.name}")
            }
        } catch (e: Exception) {
            Log.e("BluetoothHid", "Disconnect failed", e)
        }
    }

    override fun sendReport(reportId: Int, data: ByteArray) {
        sendReport(reportId, data, inferLockToggles = true)
    }

    /**
     * 发一帧报告。
     *
     * @param inferLockToggles 是否把这次按键计入本地灯态推断。键盘页与模板发出去的按键要计
     *   （那才是用户意图）；[syncKeyboardLeds] 自己制造的那两下不计 —— 那是本应用的动作，
     *   让灯条跟着闪一下只会像 bug
     */
    private fun sendReport(reportId: Int, data: ByteArray, inferLockToggles: Boolean) {
        try {
            if (currentDevice == null || bluetoothHidDevice == null) {
                Log.w("BluetoothHid", "Cannot send report: not connected")
                return
            }

            // 锁键按一下会翻转主机那边的锁定状态（主机随后把新的灯态推回来）。
            // 主机还没确认过灯态时先在本地推断里翻一下，界面才不至于一直停在未确认的初值上。
            // 第 2 字节是按键 Usage ID（见 KeyboardConstants.KeyboardReport.toByteArray）；
            // 抬起报告、修饰键报告、空报告的这个字节都是 0，会被原样返回，不影响别的按键。
            if (inferLockToggles && reportId == HidReportDescriptors.REPORT_ID_KEYBOARD) {
                ledSync.onLocalKeyPress(data.getOrNull(2) ?: 0.toByte())
            }

             bluetoothHidDevice!!.sendReport(currentDevice!!, reportId, data)
//            if (result) {
//                Log.d(
//                    "BluetoothHid",
//                    "Report sent: reportId=$reportId, data=${data.contentToString()}"
//                )
//            } else {
//                Log.e("BluetoothHid", "Failed to send report: reportId=$reportId")
//            }
        } catch (e: Exception) {
            Log.e("BluetoothHid", "Failed to send report", e)
        }
    }

    /** 报告 ID 是否属于键盘：报告协议是 2，Boot 协议是 0 */
    private fun Byte.isKeyboardReport(): Boolean =
        toInt() == HidReportDescriptors.REPORT_ID_KEYBOARD || toInt() == 0

    override fun cleanup() {
        try {
            // 先掐掉连接流程与绑定超时，否则它们可能在 cleanup 之后又改状态、把连接建起来
            connector.cancel()
            bindTimeoutJob?.cancel()
            bindTimeoutJob = null
            // 先收摊：随后的断开回调看到 Idle 就不会再改状态
            _state.value = HidState.Idle
            ledSync.onCleanup()
            disconnect()
            bluetoothHidDevice?.let {
                it.unregisterApp()
                bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it)
            }
            currentDevice = null
            bluetoothHidDevice = null
            Log.d("BluetoothHid", "Cleanup completed")
        } catch (e: Exception) {
            Log.e("BluetoothHid", "Cleanup failed", e)
        }
    }

    /**
     * 用户关掉失败弹窗。
     *
     * 两者的处理不同：连接失败有出口，回到 [HidState.Ready]（随时可以再连，弹窗不再出现）；
     * 注册失败是系统级问题、没有出口，状态保持不变 —— 弹窗在下次重组（含旋转）时会再出现。
     */
    override fun dismissError() {
        if (_state.value is HidState.ConnectFailed) {
            _state.value = HidState.Ready
        }
    }

    /** 用户关掉「小键盘可能不可用」的提示（见 [HidLedSync.numpadWarning]） */
    override fun dismissNumpadWarning() {
        ledSync.dismissNumpadWarning()
    }

    /**
     * 连接刚建立时，主机侧的 HID 集合创建 / PTP 初始化握手还没完成，
     * 这一小段窗口里发出的报告会被直接丢弃。
     *
     * 所以连接成功后延迟片刻补发一帧「空报告」（无接触 / 无按键），
     * 让主机在不依赖用户操作的情况下收到一次有效报告、唤醒坐标跟踪。
     * 只在每次连接时发一次，不参与触摸板自己的报告流。
     */
    private fun sendWakeUpReports() {
        viewModelScope.launch {
            delay(HidTiming.WAKE_UP_REPORT_DELAY_MS)
            if (_state.value !is HidState.Connected) return@launch
            Log.d("BluetoothHid", "Sending wake-up reports")
            sendNeutralReports()
        }
    }

    /**
     * 补发一帧「无按键 / 零位移」的空报告（鼠标 + 键盘各一帧）。
     *
     * 与 [sendWakeUpReports] 共用同一份字节：那边是为了让主机的指针跟踪醒过来，
     * 这边是模式切换（指针 / 键盘 / 模板）时清掉残留的按下状态 ——
     * 键盘画布靠手势取消补 keyUp，修饰键的按住状态更是随界面一起丢，
     * 主机侧会一直认为那个键还按着（表现为连发停不下来）。
     *
     * 长度必须与鼠标描述符一致（4 B），发触摸板那种 9 B 会被栈按描述符丢掉。
     */
    override fun sendNeutralReports() {
        sendReport(
            HidReportDescriptors.REPORT_ID_MOUSE,
            ByteArray(HidReportDescriptors.MOUSE_REPORT_SIZE)
        )
        sendReport(
            HidReportDescriptors.REPORT_ID_KEYBOARD,
            ByteArray(HidReportDescriptors.KEYBOARD_REPORT_SIZE)
        )
    }

    /**
     * 主动让主机把三盏灯的真值说出来（只由键盘页调用，见 `KeyboardModeScreen`）。
     * 为什么要问、怎么问、什么时候不该问，见 [HidLedSync.sync]。
     */
    override fun syncKeyboardLeds() {
        ledSync.sync()
    }

    /** 按一次某键：按下 + 抬起两条报告，不带修饰位 */
    private fun tapKey(key: KeyboardConstants.Key) {
        sendReport(
            HidReportDescriptors.REPORT_ID_KEYBOARD,
            KeyboardConstants.KeyboardReport(keyCode = key.value).toByteArray(),
            inferLockToggles = false,
        )
        sendReport(
            HidReportDescriptors.REPORT_ID_KEYBOARD,
            KeyboardConstants.KeyboardReport().toByteArray(),
            inferLockToggles = false,
        )
    }

    companion object {

        /** 在 [LinkCoordinator] 里的登记名 */
        private const val LINK_NAME = "bluetooth"

        /**
         * 进程级单例。
         *
         * 连接必须活在 Activity 之外：老实现用 `by viewModels()` 拿到的是 **Activity 作用域**实例，
         * 控制页一销毁就 `cleanup()` 拆链 —— 这正是「切到别的应用就断开」的根因
         * （与 `docs/changes/2026-09-15-1548-修复旋转重连.md` 同源，那次只治了旋转）。
         * 改成进程级持有后，Activity 退化成「视图」：进来读状态、发报告，销毁不影响链路；
         * 进程本身则由 [HidForegroundService] 的前台服务兜住，避免后台被回收。
         *
         * 与项目里 `AppPreferences.getInstance` / `MacroTemplateStore.getInstance` 同一写法。
         */
        @Volatile
        private var instance: BluetoothHidViewModel? = null

        /**
         * 取进程级实例。
         *
         * 刻意**不**在这里调 [initialize]：注册 HID 应用是「真要用控制页」才该发生的事，
         * 扫描页只是读一眼状态（连接卡片），不该因此把 HID 应用挂上去。
         * [initialize] 本身幂等，控制页照旧在自己的 `LaunchedEffect` 里调用，并负责传入 Context。
         */
        fun getInstance(): BluetoothHidViewModel =
            instance ?: synchronized(this) {
                instance ?: BluetoothHidViewModel().also { instance = it }
            }
    }
}
