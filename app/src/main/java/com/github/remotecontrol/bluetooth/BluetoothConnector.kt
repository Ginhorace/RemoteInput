package com.github.remotecontrol.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.github.remotecontrol.hid.HidError
import com.github.remotecontrol.hid.HidState
import com.github.remotecontrol.hid.HidTiming
import com.github.remotecontrol.hid.hasActiveLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 蓝牙链路的**连接编排**：从「用户给了个地址」到「发出连接请求」之间的全部节奏。
 *
 * ## 它为什么单独成文件
 * 这些步骤互相牵制、顺序不能换（等注册 → 等注册稳定 → 未配对时先配对再重建 → 发起连接），
 * 又各自带着一段等待；和状态机、HID 回调、报告出口挤在 `BluetoothHidViewModel` 里，
 * 会把那条主线的时序淹掉（该文件也因此贴在 700 行阈值上）。
 *
 * ## 外部依赖都从构造参数进来
 * 它不持有 ViewModel：状态、设备引用、代理、Context 全部以读取器 / 写入器传入
 * （写入器一律走 `BluetoothHidViewModel.currentDevice` 的 setter，那两个对外的设备 flow
 * 才会跟着同步）。因此这里**没有**「谁比谁先构造」的约束，也不引出一条反向依赖。
 *
 * ## 与 [HidState] 的关系
 * 它只推进状态机（`Connecting` / `ConnectFailed` / 中途回到 `Ready`），失败原因照旧用
 * [HidError] 表达；「断开」本身不在这里 —— 那是 [disconnectCurrent]，由 ViewModel 提供。
 *
 * ## 首次连接为什么这么绕
 * 首次连接时「配对 + HID 通道建立 + 主机侧设备安装」三件事叠在一起，主机侧会在这段时间里
 * 读取报告描述符与 Feature Report，而握手失败的结果会一直保留到下次连接，
 * 表现出来就是「首次配对后能连上却无法输入，必须手动断开重连一次」。
 * 所以未配对时必须等配对真正结束、再把连接拆掉重建一次。
 */
@SuppressLint("MissingPermission")
internal class BluetoothConnector(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<HidState>,
    /** 本次会话的目标设备（正在连接的，或已连接的） */
    private val readDevice: () -> BluetoothDevice?,
    /** 改目标设备；必须走 ViewModel 的 setter，它会同步对外的设备名 / 地址 flow */
    private val writeDevice: (BluetoothDevice?) -> Unit,
    private val readAdapter: () -> BluetoothAdapter?,
    private val readHidDevice: () -> BluetoothHidDevice?,
    private val readContext: () -> Context?,
    /** 拆掉当前链路（ViewModel 的实现里会一并管好状态与日志） */
    private val disconnectCurrent: () -> Unit,
) {

    /** 当前连接流程（含配对等待与配对后的重建），被取消时不会再继续重连 */
    private var connectionJob: Job? = null

    /**
     * 连接设备。
     *
     * 调用方只给地址，其余全部收在这里：
     * 等注册完成（「什么时候才能连」原本由 UI 侧拿 isAppRegistered 当门禁）→ 等注册稳定 →
     * （未配对时）先触发配对并等它真正结束 → 重建一次连接。
     */
    fun connect(address: String) {
        val adapter = readAdapter()
        if (adapter == null) {
            Log.w(TAG, "Adapter not ready, cannot connect")
            state.value = HidState.ConnectFailed(HidError.DeviceNotReady)
            return
        }

        val target = adapter.getRemoteDevice(address)

        // 目标就是当前这台、且链路还在：直接复用，不要推倒重来。
        // 单例化之后控制页每次创建都会再调一次本方法（换语言、从通知栏回来都会重走 onCreate），
        // 老实现会 disconnect() + 重新 connect()，用户看到的是「已连接好的设备被断开又连上」。
        if (readDevice()?.address == address && state.value.hasActiveLink) {
            Log.d(TAG, "Already connected to ${target.name}, skip reconnect")
            return
        }

        // 换设备时要拆的那一台。必须在改 currentDevice **之前**记下来
        val previous = readDevice()
        writeDevice(target)

        connectionJob?.cancel()
        connectionJob = scope.launch {
            // 0) 换设备：先把旧的那台拆干净。HID Device profile 上同时只该有一台设备，
            //    不清掉的话报告会发到错误的那个主机上
            if (previous != null && previous.address != address) {
                Log.d(TAG, "Switching device, disconnect ${previous.name} first")
                // 先把状态挪出「活跃链路」，随后的 STATE_DISCONNECTED 才不会被判成这次连接失败
                state.value = HidState.Ready
                writeDevice(null)
                try {
                    readHidDevice()?.disconnect(previous)
                } catch (e: Exception) {
                    Log.e(TAG, "Disconnect previous device failed", e)
                }
                // 等旧链路彻底拆干净（L2CAP 通道关闭是异步的）
                delay(HidTiming.RECONNECT_DELAY_MS)
                writeDevice(target)
            }

            // 1) 等绑定 + 注册走完
            if (!awaitReady()) {
                Log.w(TAG, "HID app not ready, give up connecting to ${target.name}")
                return@launch
            }

            // 2) registerApp 刚返回，SDP 记录与 HID 通道还没完全就绪，立刻连接容易撞在中间态上
            delay(HidTiming.REGISTER_SETTLE_DELAY_MS)

            if (target.bondState != BluetoothDevice.BOND_BONDED) {
                // 3) 未配对：先发起连接把配对流程带起来（HID connect 会顺带触发 SMP 配对），
                //    等配对真正结束之后，再重建一次连接
                requestConnection(target)
                if (!awaitBonded(readContext(), target.address, HidTiming.BOND_WAIT_TIMEOUT_MS)) {
                    Log.w(TAG, "Bond for ${target.name} not completed, skip reconnect")
                    // 配对可能其实成了、只是我们那一轮没判出来（真机上出现过：抓包侧
                    // Simple Pairing Complete → Link Key Notification → Encryption Change 全正常，
                    // 应用侧却等满超时）。这时链路往往已经连上，再把状态写成失败会顺手把前台服务
                    // 停掉、界面从「已连接」掉回失败 —— 只在确实没连上时才报失败。
                    if (state.value.hasActiveLink) {
                        Log.w(TAG, "Link is already active, keep it instead of reporting failure")
                    } else {
                        state.value = HidState.ConnectFailed(HidError.PairingFailed)
                    }
                    return@launch
                }
                // 主动断开：先把状态挪出 Connecting，随后的 STATE_DISCONNECTED 才不会被判成失败
                state.value = HidState.Ready
                disconnectCurrent()
                // 4) 等上一次连接彻底拆干净（L2CAP 通道关闭是异步的）
                delay(HidTiming.RECONNECT_DELAY_MS)
            }

            requestConnection(target)
        }
    }

    /**
     * 掐掉连接流程（`cleanup()` 时调用）。
     *
     * 不掐的话流程可能在 cleanup 之后又改状态、把连接建起来。
     */
    fun cancel() {
        connectionJob?.cancel()
        connectionJob = null
    }

    /**
     * 等注册走完，返回现在能不能发起连接。
     *
     * 「什么时候能连」原本由 UI 侧拿 isAppRegistered 当 `LaunchedEffect` 的 key 来决定，
     * 而注册失败时它永远是 false：连接发不出去，界面也表达不出「注册失败」。
     * 这条门禁收在这里 —— 调到 [HidState.RegisterFailed] 就放弃
     * （`cleanup()` 会取消连接流程，所以不会一直挂着）。
     */
    private suspend fun awaitReady(): Boolean =
        state.first {
            it is HidState.Ready || it is HidState.RegisterFailed
        } is HidState.Ready

    /** 单次发起连接请求，不含配对等待与配对后的重建 */
    private fun requestConnection(device: BluetoothDevice) {
        try {
            val hidDevice = readHidDevice()
            if (hidDevice == null) {
                // 代理已经不在了（cleanup 之后或服务掉线），在用户视角这就是「这次没连上」
                state.value = HidState.ConnectFailed(HidError.DeviceNotReady)
                return
            }

            // 已经连上了就别再推一次。配对刚结束时主机往往自己先把链路连回来
            //（实测：我们为「配对后重建」拆链之后约 200 ms，主机就重开了通道），
            // 这时再 connect() 一是多余，二是它可能返回 false 把状态误判成「连接失败」，
            // 而把状态压回「正在连接」会让界面停在「正在连接」上 —— 桌面其实已经能用了。
            val alreadyConnected = runCatching {
                hidDevice.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
            }.getOrDefault(false)
            if (alreadyConnected) {
                Log.d(TAG, "Already connected to ${device.name}, skip connect request")
                if (state.value !is HidState.Connected) {
                    // 链路是通的、只是我们的状态没跟上：补上，界面才会进控制页
                    Log.d(TAG, "Marking state Connected (profile reports connected)")
                    state.value = HidState.Connected
                }
                return
            }

            state.value = HidState.Connecting
            writeDevice(device)

            if (hidDevice.connect(device)) {
                Log.d(TAG, "Connection request sent to ${device.name}")
            } else {
                state.value = HidState.ConnectFailed(HidError.ConnectFailed)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            state.value = HidState.ConnectFailed(HidError.ConnectException(e.message))
        }
    }

    private companion object {
        /** 与 ViewModel / HID 回调同一个标签：现场诊断靠 `adb logcat -s BluetoothHid` 一次捞全 */
        const val TAG = "BluetoothHid"
    }
}
