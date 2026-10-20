package com.github.remotecontrol.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.util.Log

/**
 * [HidDeviceCallback] 把系统蓝牙栈的事件转给谁。
 *
 * 由 [BluetoothHidViewModel] 实现：回调体里要改状态机、发报告、读灯态，
 * 这些都留在 ViewModel 侧，本文件只负责「系统边界 → 应用」这一跳与日志。
 */
interface HidDeviceCallbackHost {

    /** 主机被装上 / 卸下（`registerApp` 结果与注销），只关心注册位 */
    fun onHidAppStatusChanged(registered: Boolean)

    /** 链路状态变化，[state] 为 `BluetoothProfile.STATE_*` 之一 */
    fun onHidConnectionStateChanged(device: BluetoothDevice, state: Int)

    /** 主机读取报告（高精度触摸板的 Feature Report 要在这里应答） */
    fun onHidGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int)

    /** 主机下发 Output Report（键盘灯态走这条） */
    fun onHidSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?)

    /** 主机从中断通道下发输出数据（部分主机不走 SET_REPORT，走这条） */
    fun onHidInterruptData(reportId: Byte, data: ByteArray?)

    /** 主机主动断开（虚拟拔线），不是连接失败 */
    fun onHidVirtualCableUnplug(device: BluetoothDevice?)
}

/**
 * 注册给 `BluetoothHidDevice.registerApp` 的回调。
 *
 * 系统回调发生在蓝牙栈自己的线程上，日志统一在这里打（`onSetProtocol` 只记日志、无应用侧动作，
 * 因此没有再往 [HidDeviceCallbackHost] 上开一个方法）。
 *
 * 日志里读 `device.name` 需要 BLUETOOTH_CONNECT，这行日志失败也不该影响回调本身，
 * 故与 ViewModel 同样统一抑制 Lint 的 MissingPermission 误报。
 */
@SuppressLint("MissingPermission")
class HidDeviceCallback(private val host: HidDeviceCallbackHost) : BluetoothHidDevice.Callback() {

    override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
        Log.d("BluetoothHid", "App status changed: device=$pluggedDevice, registered=$registered")
        host.onHidAppStatusChanged(registered)
    }

    override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
        Log.d("BluetoothHid", "Connection state changed: device=${device.name}, state=$state")
        host.onHidConnectionStateChanged(device, state)
    }

    override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
        Log.d("BluetoothHid", "Get report: type=$type, id=$id, bufferSize=$bufferSize")
        host.onHidGetReport(device, type, id, bufferSize)
    }

    override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
        Log.d("BluetoothHid", "Set report: type=$type, id=$id, data=${data?.contentToString()}")
        host.onHidSetReport(device, type, id, data)
    }

    override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) {
        Log.d("BluetoothHid", "Set protocol: protocol=$protocol")
    }

    override fun onInterruptData(device: BluetoothDevice?, reportId: Byte, data: ByteArray?) {
        Log.d(
            "BluetoothHid",
            "Interrupt data: reportId=$reportId, data=${data?.contentToString()}"
        )
        host.onHidInterruptData(reportId, data)
    }

    override fun onVirtualCableUnplug(device: BluetoothDevice?) {
        Log.d("BluetoothHid", "Virtual cable unplugged")
        host.onHidVirtualCableUnplug(device)
    }
}
