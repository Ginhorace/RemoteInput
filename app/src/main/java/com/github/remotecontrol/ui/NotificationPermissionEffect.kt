package com.github.remotecontrol.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.github.remotecontrol.R
import com.github.remotecontrol.hid.HidSession
import com.github.remotecontrol.hid.HidState
import com.github.remotecontrol.util.AppPreferences

/** 现场诊断用：`adb logcat -s HidPermission` */
private const val TAG = "HidPermission"

/**
 * 通知权限（Android 13+）的申请时机。**两条链路共用同一份**，由 [com.github.remotecontrol.HidControlActivity] 挂上。
 *
 * 它曾长在蓝牙那份副作用里（`bluetooth/BluetoothLinkEffects`），于是局域网控制页永远不问，
 * 局域网那种「一进页就有一条常驻通知」的链路在 Android 13+ 上就一直没有通知栏 ——
 * 而它恰恰比蓝牙更需要那条通知（蓝牙靠通知回控制页，局域网靠通知看电脑挂没挂上）。
 * 这件事与「报告怎么送出去」无关，所以它属于共享界面，不属于任何一条链路。
 *
 * ## 时机：链路真的连上（[HidState.Connected]）那一刻问一次，而且全局只问一次
 * - **为什么是那一刻**：那时用户刚连上设备，「连接会在后台保持」一句话就说得清那条常驻通知
 *   是干什么的；放在首屏随蓝牙权限一起问，用户还没连过任何设备，反而说不明白、还浪费申请机会。
 * - **为什么全局只问一次**：两个坑值得一并记住 —— ① 对话框会让本页 `onPause`，
 *   回来时这段逻辑会再跑一遍，不先记下就会重复申请；② 两条链路共用同一个键
 *   （[AppPreferences.notificationPermissionAsked]），蓝牙问过之后局域网不会再问一遍。
 * - **被拒之后不挂常驻提示**：只在拒绝当下说一句（`notification_off_toast`），之后不再打扰。
 *
 * ## 为什么用 [repeatOnLifecycle] 而不是「状态变化 + 一个 isResumed 标志」
 * 老写法是 `LaunchedEffect(state, isResumed)`，两个触发点是「状态变成 Connected」与「回到前台」。
 * 它对蓝牙够用（连接建立要花 0.5 秒以上，状态一定是在本页 `RESUMED` 之后才变的），
 * 对局域网却**永远等不到**：局域网的控制页是首屏卡片在电脑挂上之后**自动拉起**的，
 * 进页时 `state` 已经是 `Connected`、之后再不变，而首次组合时那个 `isResumed` 标志还是 `false`
 * （它靠 `LifecycleEventEffect` 置位 —— 只认**将来发生**的事件，进页前发生的那次 ON_RESUME 收不到）。
 * 2026-10-13 现场就是「电脑挂上、自动进控制页，权限请求一直不弹」。
 *
 * 现在改成生命周期订阅：只要页面进了 `RESUMED` 就订阅一次链路状态，`StateFlow` 会**立刻**把当前值
 * 发过来 —— 进页时已经连上也照样问得到，不必指望「状态再变一次」。回到前台时这段订阅会重启，
 * 顺带覆盖了原来那个「连接是在后台建立起来的」触发点。
 */
@Composable
fun NotificationPermissionEffect(
    session: HidSession,
    /**
     * 权限刚被授予时回调一次，用来让前台服务**重画**那条常驻通知。
     *
     * 为什么必须重画：没有 `POST_NOTIFICATIONS` 权限时 `notify()` 是**静默丢弃**的，
     * 之前那几次调用什么都没留下，通知不会在授权之后自己冒出来 ——
     * 而两条链路都靠它当后台入口。重画是幂等的
     * （再走一次 [com.github.remotecontrol.HidForegroundService.start]）。
     */
    onPermissionGranted: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { AppPreferences.getInstance(context) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "Permission result: granted=$granted")
        if (granted) {
            onPermissionGranted()
        } else {
            Toast.makeText(context, R.string.notification_off_toast, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 该不该问。每个提前退出的分支都留一行日志 —— 这条链路上「没反应」与「已经问过」
     * 在界面上长得一模一样，只有日志能分清（`adb logcat -s HidPermission`）。
     */
    fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.d(TAG, "Skip: Android 12 or lower, no notification runtime permission")
            return
        }
        if (prefs.notificationPermissionAsked) {
            Log.d(TAG, "Skip: already asked once (either link), not asking again")
            return
        }
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) {
            Log.d(TAG, "Skip: POST_NOTIFICATIONS already granted")
            return
        }
        // 先记下再弹：对话框会让本页 onPause，回来时这段逻辑会再跑一遍，不先记下就会重复申请
        Log.d(TAG, "Requesting POST_NOTIFICATIONS")
        prefs.notificationPermissionAsked = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    LaunchedEffect(lifecycleOwner, session) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // 这一行是「本 effect 确实跑到了」的正向信号：没有它，日志里空空如也时
            // 分不清是没挂上、还是挂上了但链路没连上
            Log.d(TAG, "Observing link state, current=${session.state.value}")
            session.state.collect { linkState ->
                if (linkState is HidState.Connected) askNotificationPermissionOnce()
            }
        }
    }
}
