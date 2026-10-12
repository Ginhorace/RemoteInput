package com.github.remotecontrol

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.github.remotecontrol.util.LocaleHelper
import com.github.remotecontrol.viewmodel.BluetoothHidViewModel
import com.github.remotecontrol.viewmodel.HidState
import com.github.remotecontrol.viewmodel.hasActiveLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 连接期间的前台服务：**只为保活，不参与连接状态机**。
 *
 * ## 为什么需要它
 * 连接的所有权已经下沉到进程级单例（见 [BluetoothHidViewModel.getInstance]），Activity 销毁
 * 不再拆链；但「进程还活着」是连接继续存在的另一个前提 —— 退到后台的普通进程随时可能被系统
 * 或厂商省电策略回收，进程一死，`BluetoothHidDevice` 的注册就跟着没了，主机侧看到的是设备
 * 被拔出。前台服务就是让系统别回收这个进程的标准手段。
 *
 * ## 职责边界
 * 它做且只做三件事：把进程钉在前台、显示那条常驻通知、处理通知上的两个动作。
 * 连接怎么建、怎么拆仍然全在 ViewModel 里 —— 这里一行蓝牙代码都没有。
 *
 * 锁屏 / 熄屏后连接断掉是可接受的（用户已确认），所以**不申请 WAKE_LOCK**、不做任何额外保活。
 */
class HidForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground 必须紧跟启动出现：onStartCommand 返回后 5 秒内没挂上前台就是 ANR
        startInForeground()

        if (intent?.action == ACTION_DISCONNECT) {
            Log.d("BluetoothHid", "Disconnect requested from notification")
            BluetoothHidViewModel.getInstance().cleanup()
            stopSelf()
            return START_NOT_STICKY
        }

        observeLink()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────
    // 通知
    // ─────────────────────────────────────────────

    private fun startInForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
    }

    /**
     * 链路一变就重画通知（连接中 → 已连接要换个说法），链路收摊就自己收掉。
     *
     * 观察放在服务里而不是让 ViewModel 反复 `startForegroundService`：状态在后台变化时
     * 重新启动前台服务会撞上 Android 12+ 的后台启动限制，而服务已经在前台，自己更新通知没有这个约束。
     */
    private fun observeLink() {
        val viewModel = BluetoothHidViewModel.getInstance()
        scope.launch {
            combine(viewModel.state, viewModel.connectedDeviceName) { state, name -> state to name }
                .distinctUntilChanged()
                .collect { (state, _) ->
                    if (!state.hasActiveLink) {
                        Log.d("BluetoothHid", "Link inactive, stopping foreground service")
                        stopSelf()
                        return@collect
                    }
                    notify(buildNotification())
                }
        }
    }

    // POST_NOTIFICATIONS 被拒（Android 13+）时这条通知不会显示 —— 连接照旧保持，
    // 只是少了那两个操作入口，所以这里不需要也无法要求权限；runCatching 再兜一层 SecurityException
    @SuppressLint("MissingPermission")
    private fun notify(notification: Notification) {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }.onFailure { Log.w("BluetoothHid", "Update notification failed", it) }
    }

    /**
     * 文案跟随**界面语言**：`applicationContext` 的 configuration 恒为系统语言，
     * 只有被 [LocaleHelper.wrapContext] 包过的 Context 才是用户选的界面语言（与全项目同一口径）。
     */
    private fun buildNotification(): Notification {
        val localized = LocaleHelper.wrapContext(applicationContext)
        val viewModel = BluetoothHidViewModel.getInstance()
        val deviceName = viewModel.connectedDeviceName.value
            ?: localized.getString(R.string.unknown_device)
        val text = if (viewModel.state.value is HidState.Connected) {
            localized.getString(R.string.notification_text_connected, deviceName)
        } else {
            localized.getString(R.string.notification_text_connecting, deviceName)
        }

        val openControls = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_CONTROLS,
            Intent(this, ControlActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(ControlActivity.EXTRA_DEVICE_ADDRESS, viewModel.connectedDeviceAddress.value)
                putExtra(ControlActivity.EXTRA_DEVICE_NAME, deviceName)
                // 不用 FLAG_ACTIVITY_CLEAR_TASK：那是动态快捷方式 last_device 的语义
                // （见 ControlActivity 的 DeviceConnectionEffect），这里只需把控制页带到前台
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnect = PendingIntent.getService(
            this,
            REQUEST_DISCONNECT,
            Intent(this, HidForegroundService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(localized.getString(R.string.connection_kept_title))
            .setContentText(text)
            .setContentIntent(openControls)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, localized.getString(R.string.notification_action_open), openControls)
            .addAction(0, localized.getString(R.string.disconnect), disconnect)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val localized = LocaleHelper.wrapContext(applicationContext)
        val channel = NotificationChannel(
            CHANNEL_ID,
            localized.getString(R.string.notification_channel_name),
            // LOW：这条通知是「说明书」而不是提醒，不该出声也不该顶到锁屏最上面
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = localized.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "BluetoothHid"
        private const val CHANNEL_ID = "hid_connection"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_OPEN_CONTROLS = 1
        private const val REQUEST_DISCONNECT = 2

        const val ACTION_DISCONNECT = "com.github.remotecontrol.action.DISCONNECT"

        /**
         * 把服务拉起来（由 ViewModel 在链路进入「连接中 / 已连接」时调用）。
         *
         * 万一被系统拦下（后台启动前台服务限制）也只是失去保活，连接本身不受影响，
         * 所以只记日志、不抛出去 —— 调用方在 ViewModel 的收集协程里，不该为此崩掉。
         */
        fun start(context: Context) {
            val intent = Intent(context, HidForegroundService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "startForegroundService rejected, keep-alive lost", e)
            }
        }

        /** 收掉服务与通知（由 ViewModel 在链路回到「无链路」时调用） */
        fun stop(context: Context) {
            context.stopService(Intent(context, HidForegroundService::class.java))
        }
    }
}
