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
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.github.remotecontrol.bluetooth.BluetoothHidViewModel
import com.github.remotecontrol.bluetooth.ControlActivity
import com.github.remotecontrol.hid.HidState
import com.github.remotecontrol.hid.hasActiveLink
import com.github.remotecontrol.hid.isListening
import com.github.remotecontrol.usbip.UsbIpControlActivity
import com.github.remotecontrol.usbip.UsbIpInputViewModel
import com.github.remotecontrol.util.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 连接期间的前台服务：**只为保活，不参与任何链路的状态机**。
 *
 * ## 为什么需要它
 * 两条链路的连接 / 监听所有权都已经下沉到进程级单例（见
 * [BluetoothHidViewModel.getInstance] 与
 * [UsbIpInputViewModel.getInstance]），Activity 销毁不再拆链；但「进程还活着」是链路继续存在的
 * 另一个前提 —— 退到后台的普通进程随时可能被系统或厂商省电策略回收，进程一死：
 * 蓝牙那边 `BluetoothHidDevice` 的注册跟着没了，
 * 局域网那边 `ServerSocket` 也跟着没了，主机侧看到的是设备被拔出。
 * 前台服务就是让系统别回收这个进程的标准手段。
 *
 * ## 它服务两条链路，但只有一条通知
 * 两条链路是**二选一**（见 [com.github.remotecontrol.hid.LinkCoordinator]），所以同一时刻最多
 * 只有一条占住，一条常驻通知就够：标题固定，正文（**开头就写明是哪条链路**：蓝牙 / USB/IP）、
 * 「点它去哪」「断开作用于谁」都按当前链路切换。
 * 局域网那条的判据是「自己开着等对方来」（[isListening]）而不是 [hasActiveLink] ——
 * **只为监听也要保活**，这才是「退到后台还能等电脑挂上来」的前提。
 *
 * ## 职责边界
 * 它做且只做四件事：把进程钉在前台、显示那条常驻通知、处理通知上的断开动作、
 * 在任务被移除（划掉卡片 / 清空全部）时替两条链路收摊（见 [onTaskRemoved]）。
 * 链路怎么建、怎么拆仍然全在各链路的 ViewModel 里 —— 这里一行传输代码都没有。
 *
 * ## 启停分工
 * **启动由各链路自己负责**（进入占住时调 [start]，幂等）；
 * **停止只由本服务自己判断**：两条链路都空闲了才 [stopSelf]。
 * 不能让链路收摊时反过来 `stopService` —— 蓝牙收摊时局域网可能还在监听，那会误停。
 *
 * 锁屏 / 熄屏后连接断掉是可接受的（用户已确认），所以**不申请 WAKE_LOCK**、不做任何额外保活，
 * 服务也是 `START_NOT_STICKY`（避免进程被杀后重启出一条「没有连接的假通知」）。
 */
class HidForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 链路观察协程；只拉起一次（见 [observeLinks]） */
    private var observeJob: Job? = null

    /** 是否已经挂上前台（见 [startInForeground] 失败时的收摊判断） */
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground 必须紧跟启动出现：onStartCommand 返回后 5 秒内没挂上前台就是 ANR。
        // 重复启动只会刷新同一份通知，是幂等的（两条链路都会调 start）
        startInForeground()

        when (intent?.action) {
            ACTION_DISCONNECT_BLUETOOTH -> {
                Log.d(TAG, "Disconnect requested from notification (bluetooth)")
                BluetoothHidViewModel.getInstance().cleanup()
                // 不在这里 stopSelf()：让 observeLinks 统一判断「两条链路都空闲了没有」，
                // 否则一旦出现两条链路同时占住的意外情形，收掉服务会把还在跑的那条也一起丢出保活
                return START_NOT_STICKY
            }

            ACTION_DISCONNECT_LAN -> {
                Log.d(TAG, "Disconnect requested from notification (LAN)")
                UsbIpInputViewModel.getInstance(applicationContext).cleanup()
                return START_NOT_STICKY
            }

        }

        observeLinks()
        return START_NOT_STICKY
    }

    /**
     * 任务被移除（最近任务里划掉卡片 / 清空全部）→ 主动把三条链路都收掉。
     *
     * ## 为什么不能让系统兜底
     * 划掉卡片在原生 AOSP 上行**对带前台服务的进程网开一面**（杀进程那一步会跳过
     * 「还有前台服务」的进程），于是既不杀进程、也不拆链 —— 链路继续在后台跑。
     * 而用户划掉卡片的意图常常正是「停掉它」（例如输入已经失控、要立刻停手），
     * 此时只靠系统兜底等于什么都没发生。所以这里把三条链路都收掉，
     * 把「划掉卡片 = 收摊」这条语义在所有 ROM 上拉齐：原生 ROM 由我们主动收，
     * 把「清理最近任务」实现成强制停止的 ROM（如 HyperOS）则由系统兜底 —— 进程都没了。
     *
     * ## 不涉及后退与 Home
     * 后退只退出界面、链路保持（见各控制页的 `exitControlScreen`）；
     * Home / 切到别的应用同样不会走到这里。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed, cleaning up all links")
        BluetoothHidViewModel.getInstance().cleanup()
        UsbIpInputViewModel.getInstance(applicationContext).cleanup()
        // 收摊后 observeLinks 自己也会 stopSelf，这里再显式停一次：本回调返回之后进程随时可能被回收，
        // 不该指望那个收集协程还能跑到
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────
    // 通知
    // ─────────────────────────────────────────────

    /**
     * 挂上前台。
     *
     * **失败不能崩**。Android 14+ 对每种服务类型各有前置条件（`connectedDevice` 就要求同时持有
     * 蓝牙 / Wi-Fi / USB 那一组权限里的任意一个），而权限可能在运行期被用户在系统设置里收回 ——
     * 那时 `startForeground` 会直接抛 `SecurityException`，从 `onStartCommand` 抛出去就是一次闪退
     * （2026-10-13 19:29 那次闪退就是这一族问题：类型与权限不匹配）。
     *
     * 保活拿不到是可以接受的（链路本身不受影响，只是退到后台可能被回收），所以这里兜住；
     * 但**必须跟着 [stopSelf]**：服务是被 `startForegroundService` 拉起的，没进前台又不收摊，
     * 5 秒后会变成 ANR。已经在前后台跑着的那种情况不收（那会把另一条还在用的链路一起丢出保活）。
     */
    private fun startInForeground() {
        val link = activeLink()
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(link),
                foregroundServiceType(link)
            )
        }.onFailure {
            Log.w(TAG, "startForeground rejected, keep-alive lost", it)
        }.isSuccess

        if (started) {
            inForeground = true
        } else if (!inForeground) {
            stopSelf()
        }
    }

    /**
     * 前台服务类型按当前链路给（manifest 里两个类型都声明了）。
     *
     * ## 蓝牙：connectedDevice
     * 它就是「与外部设备（蓝牙）持续交互」，名正言顺；也满足 Android 14+ 对这个类型的前置条件
     * （`any of` 那一组里有 `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN`，即扫描页申请的那两项）。
     *
     * ## 局域网：specialUse
     * **不能**用 connectedDevice：Android 14 起这个类型要求「持有 `FOREGROUND_SERVICE_CONNECTED_DEVICE`
     * **且**同时持有蓝牙 / Wi-Fi / USB 那一组权限里的任意一个」，而局域网链路只开了 `INTERNET`、
     * 自己开一个普通 TCP 监听 —— 清掉应用数据、还没授过蓝牙权限时点「开始监听」，系统会直接抛
     * `SecurityException: Starting FGS with type connectedDevice ... requires permissions:
     * all of=[FOREGROUND_SERVICE_CONNECTED_DEVICE] any of=[BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT,
     * BLUETOOTH_SCAN, CHANGE_NETWORK_STATE, ...]`（`any of` 列表里的 `BLUETOOTH_ADVERTISE`
     * 来自当时还在的 BLE 链路，该链路已删除；剩下的蓝牙两项由扫描页申请）。为凑这个类型去申请一个并不使用的
     * `CHANGE_WIFI_STATE` 是骗系统，也不该做；它同样不属于 `dataSync`（那是文件 / 数据同步，
     * Android 15 起还有每日 6 小时上限）。[specialUse][ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE]
     * 正是官方给「不符合任何既定类型」留的出口。
     *
     * ## Android 13 及以下
     * 那边既没有 specialUse 这个类型，也没有 connectedDevice 的权限前置条件，沿用 connectedDevice
     * 即可（传一个老 framework 不认识的类型位反而是自找麻烦）。
     *
     * @param link 当前占住的链路；`null` 表示服务被拉起、但两条链路都已空闲（下一帧就 [stopSelf]）。
     *   它跟着局域网那一支走 —— 取那个**不需要任何额外权限**的类型，绝不能在收摊的瞬间再抛一次
     *   `SecurityException`
     */
    private fun foregroundServiceType(link: ActiveLink?): Int = when {
        link == ActiveLink.BLUETOOTH ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

        else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    }

    /**
     * 哪条链路现在占着。
     *
     * 两条链路互斥，理论上不会同时占住；万一同时占住（见 [com.github.remotecontrol.hid.LinkCoordinator]
     * 关于竞态的说明），按**蓝牙 → 局域网**的顺序展示：蓝牙那条还带着具体设备名，信息更具体，
     * 局域网那条只说「本机 IP:端口」。
     */
    private fun activeLink(): ActiveLink? = when {
        BluetoothHidViewModel.getInstance().state.value.hasActiveLink -> ActiveLink.BLUETOOTH
        UsbIpInputViewModel.getInstance(applicationContext).state.value.isListening -> ActiveLink.LAN
        else -> null
    }

    /**
     * 链路一变就重画通知（连接中 → 已连接要换个说法），两条都空闲就自己收掉。
     *
     * 观察放在服务里而不是让 ViewModel 反复 `startForegroundService`：状态在后台变化时
     * 重新启动前台服务会撞上 Android 12+ 的后台启动限制，而服务已经在前台，自己更新通知没有这个约束。
     *
     * 两条链路都会调 [start]（各自进入占住时），因此本方法可能被调多次；只拉起一份观察协程。
     */
    private fun observeLinks() {
        if (observeJob?.isActive == true) return
        val bluetoothActive = BluetoothHidViewModel.getInstance().state.map { it.hasActiveLink }
        val lanActive = UsbIpInputViewModel.getInstance(applicationContext).state.map { it.isListening }
        observeJob = scope.launch {
            combine(bluetoothActive, lanActive, ::pickActiveLink)
                .distinctUntilChanged()
                .collect { link ->
                    if (link == null) {
                        Log.d(TAG, "No active link, stopping foreground service")
                        stopSelf()
                        return@collect
                    }
                    notify(buildNotification(link))
                }
        }
    }

    private fun pickActiveLink(
        bluetoothActive: Boolean,
        lanActive: Boolean,
    ): ActiveLink? = when {
        bluetoothActive -> ActiveLink.BLUETOOTH
        lanActive -> ActiveLink.LAN
        else -> null
    }

    // POST_NOTIFICATIONS 被拒（Android 13+）时这条通知不会显示 —— 链路照旧保持，
    // 只是少了「断开」这一个入口（回控制页那条路还有扫描页卡片），
    // 所以这里不需要也无法要求权限；runCatching 再兜一层 SecurityException
    @SuppressLint("MissingPermission")
    private fun notify(notification: Notification) {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "Update notification failed", it) }
    }

    /**
     * 文案跟随**界面语言**：`applicationContext` 的 configuration 恒为系统语言，
     * 只有被 [LocaleHelper.wrapContext] 包过的 Context 才是用户选的界面语言（与全项目同一口径）。
     */
    private fun buildNotification(link: ActiveLink?): Notification {
        val localized = LocaleHelper.wrapContext(applicationContext)

        val text: String? = when (link) {
            ActiveLink.BLUETOOTH -> {
                val viewModel = BluetoothHidViewModel.getInstance()
                val deviceName = viewModel.connectedDeviceName.value
                    ?: localized.getString(R.string.unknown_device)
                if (viewModel.state.value is HidState.Connected) {
                    localized.getString(R.string.notification_text_bluetooth_connected, deviceName)
                } else {
                    localized.getString(R.string.notification_text_bluetooth_connecting, deviceName)
                }
            }

            ActiveLink.LAN -> {
                val viewModel = UsbIpInputViewModel.getInstance(applicationContext)
                if (viewModel.state.value is HidState.Connected) {
                    localized.getString(R.string.notification_text_lan_attached)
                } else {
                    val address = viewModel.localAddress.value
                        ?: localized.getString(R.string.lan_address_unknown)
                    localized.getString(
                        R.string.notification_text_lan_listening,
                        "$address:${viewModel.port.value}"
                    )
                }
            }

            // 一条链路都没占住：服务是刚被拉起、下一帧就会被 observeLinks 收掉的，
            // 只挂个标题即可（下面点进哪页 / 断开动作也都不给），别去猜「大概是哪条链路」
            null -> null
        }

        // 点通知本体 → 回到**当前那条链路**的控制页。
        // 不用 FLAG_ACTIVITY_CLEAR_TASK：那是动态快捷方式 last_device 的语义
        // （见 BluetoothLinkEffects 里的 DeviceConnectionEffect），这里只需把控制页带到前台
        val openControls: PendingIntent? = when (link) {
            ActiveLink.BLUETOOTH -> PendingIntent.getActivity(
                this,
                REQUEST_OPEN_BLUETOOTH,
                Intent(this, ControlActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra(
                        ControlActivity.EXTRA_DEVICE_ADDRESS,
                        BluetoothHidViewModel.getInstance().connectedDeviceAddress.value
                    )
                    putExtra(
                        ControlActivity.EXTRA_DEVICE_NAME,
                        BluetoothHidViewModel.getInstance().connectedDeviceName.value
                            ?: localized.getString(R.string.unknown_device)
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            ActiveLink.LAN -> PendingIntent.getActivity(
                this,
                REQUEST_OPEN_LAN,
                Intent(this, UsbIpControlActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            null -> null
        }

        // 断开动作两个目标用**两个不同的 action**：PendingIntent 的身份只看
        // action / data / component / type，不看 extras —— 共用一个 action 时两条链路的
        // 「断开」会被系统判成同一个 PendingIntent，后建的那份会把前一份的意图覆盖掉
        val disconnect: PendingIntent? = when (link) {
            ActiveLink.BLUETOOTH -> disconnectIntent(REQUEST_DISCONNECT_BLUETOOTH, ACTION_DISCONNECT_BLUETOOTH)
            ActiveLink.LAN -> disconnectIntent(REQUEST_DISCONNECT_LAN, ACTION_DISCONNECT_LAN)
            null -> null
        }

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
            // 只挂「断开」这一个动作：点通知本体就是回到控制页（setContentIntent），
            // 再放一个同样效果的按钮只是白占一个位置
            .apply {
                if (disconnect != null) {
                    addAction(0, localized.getString(R.string.disconnect), disconnect)
                }
            }
            .build()
    }

    private fun disconnectIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, HidForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

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

    /** 当前占住的那条链路；两条互斥，最多一个 */
    private enum class ActiveLink { BLUETOOTH, LAN }

    companion object {
        private const val TAG = "HidService"
        private const val CHANNEL_ID = "hid_connection"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_OPEN_BLUETOOTH = 1
        private const val REQUEST_OPEN_LAN = 2
        private const val REQUEST_DISCONNECT_BLUETOOTH = 3
        private const val REQUEST_DISCONNECT_LAN = 4
        const val ACTION_DISCONNECT_BLUETOOTH = "com.github.remotecontrol.action.DISCONNECT_BLUETOOTH"
        const val ACTION_DISCONNECT_LAN = "com.github.remotecontrol.action.DISCONNECT_LAN"

        /**
         * 把服务拉起来（由各链路的 ViewModel 在进入占住时调用；重复调用是幂等的）。
         *
         * 万一被系统拦下（后台启动前台服务限制）也只是失去保活，链路本身不受影响，
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
    }
}
