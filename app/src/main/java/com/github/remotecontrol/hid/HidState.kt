package com.github.remotecontrol.hid

/**
 * HID 链路的顶级状态。
 *
 * 两类失败被刻意分开，因为它们的「出口」不同：
 *
 * | | [ConnectFailed] | [RegisterFailed] |
 * | --- | --- | --- |
 * | 性质 | 一次**尝试**的结果（被拒 / 超时 / 断链） | **设备能力**问题（系统未启用 HID Device / 协议栈拒绝） |
 * | 出口 | [Ready]，随时可以再连 | 没有，只能退出页面 |
 * | 弹窗关闭后 | 回 [Ready]，不再出现 | 状态不变，重组时会再出现 |
 */
sealed interface HidState {

    /** 未初始化，或 [com.github.remotecontrol.bluetooth.BluetoothHidViewModel.cleanup] 之后 */
    data object Idle : HidState

    /** 正在绑定 HID profile 服务（`getProfileProxy`），有超时保护 */
    data object Initializing : HidState

    /** 服务已绑定，正在注册 HID 应用（`registerApp`） */
    data object Registering : HidState

    /** 注册失败：系统级问题，重试没有意义，只能退出页面 */
    data class RegisterFailed(val error: HidError) : HidState

    /** 已注册：可以连接，也可以重连 */
    data object Ready : HidState

    /** 连接中（首次配对时包含等待配对的时间） */
    data object Connecting : HidState

    /** 连接失败，出口是 [Ready] */
    data class ConnectFailed(val error: HidError) : HidState

    /** 已连接 */
    data object Connected : HidState

    /** 正在断开 */
    data object Disconnecting : HidState
}

/**
 * 是否处于「链路已建立或正在变动」：这几个状态下需要走显式断开（按钮 / 通知栏），
 * 也是「前台服务该不该在跑」「扫描页该不该显示保持中的连接卡片」的判据。
 */
val HidState.hasActiveLink: Boolean
    get() = this is HidState.Connecting ||
            this is HidState.Connected ||
            this is HidState.Disconnecting

/**
 * 「自己开着等对方来」那两条链路（BLE / 局域网）「开着」的判据：
 * **正在广播 / 监听（对方还没连上来）或已连上**。
 *
 * 与 [hasActiveLink] 的区别在这一档：[HidState.Ready] 在它们那里表示「口子开着、等对方来」，
 * 这条链路此刻就该被保活（这正是「退到后台还能等对方连上来」的前提），所以它算「开着」；
 * 而经典蓝牙那侧的 [HidState.Ready] 只是「HID 应用注册好了、可以连」，算不上链路。
 *
 * 用到它的地方：前台服务的保活判断、这两条链路卡片的按钮文案、[LinkCoordinator] 的互斥登记。
 */
val HidState.isListening: Boolean
    get() = this is HidState.Ready ||
            this is HidState.Connecting ||
            this is HidState.Connected

/**
 * HID 连接过程中的用户可见失败。
 *
 * 只描述「出了什么事」，不带文案 —— 文案由 UI 侧映射成字符串资源，
 * 这样 ViewModel 不必持有 Context，多语言也能跟着 Activity 走。
 */
sealed interface HidError {

    /**
     * 系统未启用蓝牙 HID Device profile：绑定 HID profile 服务超时。
     *
     * 这是手机系统的问题（`bluetooth.profile.hid.device.enabled` 默认关闭、厂商裁剪），
     * 与「能连蓝牙键鼠」是两回事，应用侧重试没有意义。
     */
    data object Unsupported : HidError

    /** HID 代理还没就绪（`getProfileProxy` 尚未回调，或服务已掉线） */
    data object DeviceNotReady : HidError

    /** `registerApp()` 返回 false，注册被协议栈拒绝 */
    data object RegisterFailed : HidError

    /** `registerApp()` 抛异常，[detail] 为原因 */
    data class RegisterException(val detail: String?) : HidError

    /** 配对未完成：被用户取消或超时 */
    data object PairingFailed : HidError

    /** 连接失败：`connect()` 返回 false，或连接请求发出后没建起来就断了 */
    data object ConnectFailed : HidError

    /** `connect()` 抛异常，[detail] 为原因 */
    data class ConnectException(val detail: String?) : HidError
}
