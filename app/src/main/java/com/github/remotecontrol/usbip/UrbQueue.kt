package com.github.remotecontrol.usbip

/**
 * 中断 IN 端点上的「URB ↔ 报告」配对。
 *
 * ## 为什么必须两边都排队
 * 中断 IN 不是一问一答：主机**一直**挂着 URB 等数据（通常只挂一条，收完立刻再挂），
 * 而设备在有报告时才回。于是任何一侧先到都要能等另一侧：
 *
 * - 主机先挂 URB、报告还没产生 → URB 挂起（这正是真实设备的 NAK）；
 * - 报告先产生、主机还没来得及挂 URB → 报告排队（键盘的「按下 + 抬起」两条报告就总是
 *   在同一毫秒里产生，一条 URB 装不下，必须排队而不是覆盖）。
 *
 * **不能用「只留最新一帧」的写法**：那样第二次按键的抬起报告会把按下报告顶掉，
 * 主机只看到一次「抬起」，键盘就彻底打不出字了。
 *
 * ## 配对是成对的
 * 每一条 URB 恰好配一帧报告、每一帧报告恰好配一条 URB；少配一条主机那边就少一个完成事件，
 * 端点会静默僵住（表现为设备卡死）。所以两侧的增删都收在这一个类里、由同一把锁保护。
 *
 * @param capacity 报告队列上限。超出后丢**最旧**的一帧：主机若比设备慢，宁可让指针落后，
 *   也不能让队列无限增长（越积越久的延迟在触控里等于漂移）
 * @param log 诊断日志出口
 */
internal class UrbQueue(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val log: (String) -> Unit,
) {

    private val lock = Any()

    /** 主机挂着等数据的 URB（先到先配） */
    private val pendingUrbs = ArrayDeque<UsbIpPdu>()

    /** 设备已产生、还没交出去的报告（先到先配） */
    private val queuedReports = ArrayDeque<ByteArray>()

    /** 统计用：一共丢了多少帧（只按每 100 帧打一条日志，免得刷屏） */
    private var droppedReports = 0

    /** 主机挂上来一条中断 IN URB：有排队报告就直接配上，否则挂起等 */
    fun onUrb(pdu: UsbIpPdu): Pair<UsbIpPdu, ByteArray>? = synchronized(lock) {
        if (queuedReports.isEmpty()) {
            pendingUrbs.addLast(pdu)
            null
        } else {
            pdu to queuedReports.removeFirst()
        }
    }

    /** 设备产生一帧报告：有挂起的 URB 就直接配上，否则排队（超限则丢最旧一帧） */
    fun onReport(report: ByteArray): Pair<UsbIpPdu, ByteArray>? = synchronized(lock) {
        if (pendingUrbs.isEmpty()) {
            queuedReports.addLast(report)
            if (queuedReports.size > capacity) {
                queuedReports.removeFirst()
                droppedReports++
                if (droppedReports == 1 || droppedReports % 100 == 0) {
                    log("中断 IN 报告积压，已丢弃最旧的一帧（累计 $droppedReports 帧）")
                }
            }
            null
        } else {
            pendingUrbs.removeFirst() to report
        }
    }

    /**
     * 取消一条挂起的 URB（主机发了 `OP_CMD_UNLINK`）。
     *
     * 已配出去的不算：那种情况主机拿到的是 `ret_submit`，它自己的 unlink 会超时收尾。
     *
     * @return 是否真的从挂起队列里摘掉了一条
     */
    fun unlink(seqnum: Int): Boolean = synchronized(lock) {
        pendingUrbs.removeAll { it.seqnum == seqnum }
    }

    /** 会话结束（或开始时）：两侧队列都不该把上一次的残留带进这一次 */
    fun reset() = synchronized(lock) {
        pendingUrbs.clear()
        queuedReports.clear()
    }

    /** 当前挂起的 URB 条数，只用于日志 */
    val pendingCount: Int get() = synchronized(lock) { pendingUrbs.size }

    private companion object {
        /**
         * 报告队列上限。
         *
         * 32 帧 ≈ 触摸板 200Hz 下 160ms 的余量：够吸收一次 GC 或调度抖动，
         * 又短到不会让用户感觉到延迟。
         */
        const val DEFAULT_CAPACITY = 32
    }
}
