package com.github.remotecontrol.bluetooth

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon as AndroidIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.github.remotecontrol.R
import com.github.remotecontrol.util.AppPreferences

/**
 * 蓝牙链路**专属的界面副作用**：界面一挂上就要做、且只有蓝牙这一条链路才有的那两件事。
 *
 * ## 它为什么在 `bluetooth/` 而不是共享界面里
 * 共享的 `ui/ControlScreen` 只认 [com.github.remotecontrol.hid.HidSession]，它描述的是
 * 「界面要读什么」；而下面这两件事都是**动作**，且只在蓝牙这条链路上说得通 ——
 * 局域网的「连接」由电脑执行 `usbip attach` 发起，没有「按地址连过去」这回事，
 * 也就没有快捷方式要维护。它们原先嵌在 `ControlScreen` 里，正是那份副本
 * （已删除的 `usbip/ui/ControlScreenUSBIP.kt`）要删掉的那 83 行。
 *
 * ## 两件事
 * 1. **初始化 HID 应用**（[BluetoothHidViewModel.initialize]）：幂等 —— 连接归进程级单例所有，
 *    从通知栏回到控制页会再走一遍；
 * 2. **按设备地址发起连接 + 更新动态快捷方式**（见 [DeviceConnectionEffect]）。
 *
 * ## 不在这里的
 * **通知权限申请**两条链路都要，已经下沉到共享界面的 `ui/NotificationPermissionEffect`，
 * 由 [com.github.remotecontrol.HidControlActivity] 统一挂上 —— 放在这里就又会变成只有蓝牙问。
 *
 * 位置要求：与共享的 `ControlScreen` **并列**，由蓝牙控制页在同一段 `setContent` 里调用 ——
 * 它是 composable 的「副作用半边」，不画任何东西。
 */
@Composable
fun BluetoothLinkEffects(
    viewModel: BluetoothHidViewModel,
    deviceAddress: String?,
    deviceName: String,
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences.getInstance(context) }

    // 初始化（幂等：连接归进程级单例所有，从通知栏回到本页会再走一遍这里）
    LaunchedEffect(Unit) {
        viewModel.initialize(context)
    }

    // 连接设备 + 快捷方式
    DeviceConnectionEffect(
        viewModel = viewModel,
        context = context,
        deviceAddress = deviceAddress,
        deviceName = deviceName,
        prefs = prefs
    )
}

/**
 * 连接设备 + 维护桌面快捷方式。
 *
 * 「等注册完成才发起连接」的门禁已经收进连接器（见 [BluetoothConnector]），这里只负责把地址交出去；
 * 地址相同的重复进入会在那边短路，不会把已建好的链路拆掉重建。
 */
@Composable
private fun DeviceConnectionEffect(
    viewModel: BluetoothHidViewModel,
    context: Context,
    deviceAddress: String?,
    deviceName: String,
    prefs: AppPreferences
) {
    // 快捷方式的长标签要跟随界面语言，而 LaunchedEffect 的块内不能调用 stringResource，
    // 所以在组合作用域里先解析好
    val connectToLabel = stringResource(id = R.string.shortcut_connect_to, deviceName)

    LaunchedEffect(deviceAddress) {
        if (deviceAddress == null) return@LaunchedEffect

        prefs.lastConnectedDeviceAddress = deviceAddress

        // 更新快捷方式
        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        val shortcut = ShortcutInfo.Builder(context, "last_device")
            .setShortLabel(deviceName)
            .setLongLabel(connectToLabel)
            .setIcon(AndroidIcon.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(
                Intent(context, ControlActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra(ControlActivity.EXTRA_DEVICE_ADDRESS, deviceAddress)
                    putExtra(ControlActivity.EXTRA_DEVICE_NAME, deviceName)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            .build()

        shortcutManager?.addDynamicShortcuts(listOf(shortcut))

        // 连接蓝牙设备
        // 地址 → 设备的解析与连接节奏都在 ViewModel / 连接器里（那边本来就已经持有 adapter，不必再取一次）
        viewModel.connectToDevice(deviceAddress)
    }
}
