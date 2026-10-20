package com.github.remotecontrol.bluetooth

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.remotecontrol.HidControlActivity
import com.github.remotecontrol.R
import com.github.remotecontrol.hid.HidSession

/**
 * 蓝牙链路的控制页。
 *
 * 它只交代三件事：**控制哪个会话**（[session]）、**顶栏写什么**（设备名）、
 * **进页要做哪些蓝牙专属动作**（[LinkEffects]：初始化 HID 应用、按地址发起连接 + 更新快捷方式、
 * 连接成功那一刻申请通知权限）。
 *
 * 界面外壳（主题 / 语言 / 方向 / 系统栏 / 后退与断开两个出口）全在 [HidControlActivity]，
 * 与局域网那条链路共用同一份 —— 曾经两边各写一遍。
 */
class ControlActivity : HidControlActivity() {

    /**
     * 不使用 `by viewModels()`：那是 Activity 作用域，Activity 一销毁连接就跟着没了。
     */
    private val bluetoothHidViewModel: BluetoothHidViewModel = BluetoothHidViewModel.getInstance()

    override val session: HidSession get() = bluetoothHidViewModel

    /**
     * 本次要连的设备。
     *
     * 必须是 state：`singleTop` 下从常驻通知打进来时实例被复用（走 `onNewIntent` → [readExtras]），
     * 只在 `onCreate` 里读一次 intent 会停在旧设备上，`BluetoothLinkEffects` 也就不会按新设备办事。
     */
    private var deviceAddress by mutableStateOf<String?>(null)
    private var deviceName by mutableStateOf("")

    override fun readExtras(intent: Intent?) {
        deviceAddress = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
        deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME) ?: getString(R.string.unknown_device)
    }

    @Composable
    override fun controlTitle(): String = deviceName

    @Composable
    override fun LinkEffects() {
        BluetoothLinkEffects(
            viewModel = bluetoothHidViewModel,
            deviceAddress = deviceAddress,
            deviceName = deviceName,
        )
    }

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_DEVICE_NAME = "device_name"
    }
}
