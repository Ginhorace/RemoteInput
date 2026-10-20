package com.github.remotecontrol.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.github.remotecontrol.hid.HidTiming
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "BluetoothHid"

/**
 * 等待 [address] 完成配对，返回是否配对成功。
 *
 * 配对（SMP + 链路加密）期间 HID 通道已经建立，主机侧会在这段时间里尝试读取
 * 报告描述符与 Feature Report；握手失败的结果会被主机一直保留到下次连接，
 * 所以首次连接必须等配对结束之后再重建一次连接。
 *
 * ## 广播 + 轮询两条路（轮询是必需品，不是保险）
 * 真机上出现过「抓包侧 Simple Pairing Complete → Link Key Notification → Encryption Change
 * 全正常，应用侧却一条 `BOND_STATE_CHANGED` 都没收到、把 30 秒超时等满」的现场 ——
 * 日志里没有 `Bond state broadcast`，只有 `Bond detected by polling`。
 * 那一轮差点被当成「配对失败」把链路收掉，所以这里除了广播，还按
 * [HidTiming.BOND_POLL_INTERVAL_MS] 轮询 `bondState` 当兜底：谁先给出结论用谁。
 *
 * 超时、被取消、或尚未持有 Context（[context] 为 null）都返回 false。
 *
 * 独立成文件是因为它自带一个生命周期：注册广播接收器 → 等结果 → 无论成功、超时还是
 * 被取消都要把接收器与轮询一起收掉。挂在连接流程里会把 ViewModel 的正文淹掉。
 *
 * 读取 `bondState` / `address` 需要 BLUETOOTH_CONNECT：调用链的入口（控制页）已在
 * 扫描页取得该权限，且这里没有可回退的分支，故与 ViewModel 同样统一抑制 Lint 误报。
 */
@SuppressLint("MissingPermission")
suspend fun awaitBonded(context: Context?, address: String, timeoutMs: Long): Boolean {
    if (context == null) return false
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? BluetoothManager)?.adapter ?: return false
    val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false

    // 快路：早就配好了，不必等广播
    if (runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE) ==
        BluetoothDevice.BOND_BONDED
    ) {
        Log.d(TAG, "Bond already present: addr=$address")
        return true
    }

    return withTimeoutOrNull(timeoutMs) {
        coroutineScope {
            suspendCancellableCoroutine { continuation ->
                // 轮询兜底：与广播并行，谁先判出结果谁 `resume`；
                // 协程结束时 [coroutineScope] 会把它一起取消掉
                val poller = launch {
                    while (isActive) {
                        delay(HidTiming.BOND_POLL_INTERVAL_MS)
                        val state = runCatching { device.bondState }
                            .getOrDefault(BluetoothDevice.BOND_NONE)
                        if (state == BluetoothDevice.BOND_BONDED) {
                            Log.d(TAG, "Bond detected by polling: addr=$address")
                            if (continuation.isActive) continuation.resume(true)
                            return@launch
                        }
                    }
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
                        Log.d(TAG, "Bond state broadcast: addr=$address, state=${changed.bondState}")
                        poller.cancel()
                        continuation.resume(bonded)
                    }
                }

                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    android.content.IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                // 超时被取消时也要收摊，否则接收器与轮询都会泄漏
                continuation.invokeOnCancellation {
                    poller.cancel()
                    runCatching { context.unregisterReceiver(receiver) }
                }
            }
        }
    } ?: false
}
