package com.github.remotecontrol.hid

/**
 * HID 连接流程里的各种等待时长。
 *
 * 集中在一处是因为它们**互相牵制**：绑定超时定了「多久算不支持」，配对超时定了「多久算没配上」，
 * 而两个拆链延迟（配对后重建、换设备）讲的是同一件事 —— L2CAP 通道关闭是异步的，
 * 不等一下就会在中间态上再发连接请求。调其中任何一个时，一眼能看到旁边那几个。
 */
internal object HidTiming {

    /** 连接建立后等主机完成 HID 初始化，再补发空报告的延迟 */
    const val WAKE_UP_REPORT_DELAY_MS = 300L

    /** `registerApp` 返回后，等 SDP 记录 / HID 通道就绪的延迟 */
    const val REGISTER_SETTLE_DELAY_MS = 500L

    /** 等待配对完成的超时；超时或配对被取消则不再重建连接 */
    const val BOND_WAIT_TIMEOUT_MS = 30_000L

    /**
     * 等配对时轮询 `bondState` 的间隔。
     *
     * 除了 `ACTION_BOND_STATE_CHANGED` 广播，还要有这一路兜底：真机上出现过
     * 「链路侧配对成功、应用侧却整整等满 30 s 超时」的现场，只信广播的话，
     * 那一次就会被判成「没配上」（日志里 `Bond state broadcast` 一行都没有、
     * 只有 `Bond detected by polling`）。
     */
    const val BOND_POLL_INTERVAL_MS = 250L

    /** 拆掉旧链路之后，等它彻底干净再连新的延迟（配对后重建、换设备都用它） */
    const val RECONNECT_DELAY_MS = 800L

    /**
     * 绑定 HID profile 服务的超时。
     *
     * ROM 未启用 HID Device profile 时 `getProfileProxy` 的绑定不会失败、也不会回调，
     * 只能靠超时兜住。取值要明显大于正常绑定耗时（通常几十毫秒），又不能太长。
     */
    const val BIND_TIMEOUT_MS = 5_000L
}
