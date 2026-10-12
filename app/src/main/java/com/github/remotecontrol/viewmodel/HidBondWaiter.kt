package com.github.remotecontrol.viewmodel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 等待 [address] 完成配对，返回是否配对成功。
 *
 * 配对（SMP + 链路加密）期间 HID 通道已经建立，主机侧会在这段时间里尝试读取
 * 报告描述符与 Feature Report；握手失败的结果会被主机一直保留到下次连接，
 * 所以首次连接必须等配对结束之后再重建一次连接。
 *
 * 超时、被取消、或尚未持有 Context（[context] 为 null）都返回 false。
 *
 * 独立成文件是因为它自带一个生命周期：注册广播接收器 → 等结果 → 无论成功、超时还是
 * 被取消都要把接收器注销掉。挂在连接流程里会把 ViewModel 的正文淹掉。
 *
 * 读取 `bondState` / `address` 需要 BLUETOOTH_CONNECT：调用链的入口（控制页）已在
 * 扫描页取得该权限，且这里没有可回退的分支，故与 ViewModel 同样统一抑制 Lint 误报。
 */
@SuppressLint("MissingPermission")
suspend fun awaitBonded(context: Context?, address: String, timeoutMs: Long): Boolean {
    if (context == null) return false
    return withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                    as? BluetoothManager)?.adapter
            if (adapter?.getRemoteDevice(address)?.bondState == BluetoothDevice.BOND_BONDED) {
                continuation.resume(true)
                return@suspendCancellableCoroutine
            }

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                    val changed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (changed?.address != address) return

                    // BOND_BONDING 只是中间态，继续等；BONDED / BOND_NONE 才是结果
                    val bonded = when (changed.bondState) {
                        BluetoothDevice.BOND_BONDED -> true
                        BluetoothDevice.BOND_NONE -> false
                        else -> return
                    }
                    runCatching { context.unregisterReceiver(this) }
                    continuation.resume(bonded)
                }
            }

            ContextCompat.registerReceiver(
                context,
                receiver,
                android.content.IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            // 超时被取消时也要注销，否则接收器会泄漏
            continuation.invokeOnCancellation {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    } ?: false
}
