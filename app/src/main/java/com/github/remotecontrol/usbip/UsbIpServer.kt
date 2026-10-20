package com.github.remotecontrol.usbip

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * USB/IP 服务端：手机这一侧当「设备」，电脑用现成的 `usbip attach` 把它挂上来。
 *
 * ## 一次会话的走法
 * ```
 * 电脑                                  手机（本类）
 *  │  OP_REQ_DEVLIST ─────────────────▶  │  回一条设备 + 一个接口，然后关连接
 *  │  OP_REQ_IMPORT ──────────────────▶  │  回设备记录，进入 URB 收发（长连接）
 *  │  OP_CMD_SUBMIT（控制传输） ──────▶  │  交给 VirtualUsbDevice 答
 *  │  OP_CMD_SUBMIT（中断 IN） ───────▶  │  挂起，等报告；有报告立刻回 RET_SUBMIT
 *  │  OP_CMD_UNLINK ──────────────────▶  │  把挂起的那条摘掉，回 RET_UNLINK
 * ```
 *
 * ## 三个线程，一条铁律
 * | 线程 | 干什么 |
 * | --- | --- |
 * | accept | 收新连接（`usbip list` 会开好几条） |
 * | reader（每条会话一个） | 读 URB、生成应答，**只读** |
 * | writer（每条会话一个） | 把应答写回 TCP |
 *
 * 写回统一走队列 + 单写线程：报告是**界面线程**产生的（触摸板划一下就要发帧），
 * 直接由界面线程写 socket 会撞上 TCP 缓冲满，那就是一次 ANR。
 *
 * 另一条铁律是**逐条配平**：进来的每条 URB 恰好回一次（`RET_SUBMIT` 或 `RET_UNLINK`）。
 * 漏一条主机那边就有一个 URB 永远完不成，端点会静默停住（现象就是「设备卡死」）。
 */
class UsbIpServer(
    private val device: VirtualUsbDevice,
    private val log: (String) -> Unit,
) {

    /** 电脑导入成功（`usbip attach` 之后），在会话线程上回调 */
    var onHostConnected: (() -> Unit)? = null

    /** 电脑断开（关连接 / 拔掉），在会话线程上回调 */
    var onHostDisconnected: (() -> Unit)? = null

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val sessionLock = Any()

    /**
     * 当前挂着的会话。同一时刻只允许一台电脑导入（第二台回「设备忙」）。
     *
     * `@Volatile`：写发生在会话线程（IMPORT 成功 / 收摊），读发生在界面线程（`sendReport`）。
     */
    @Volatile
    private var session: Session? = null

    val isListening: Boolean get() = serverSocket != null

    val isHostConnected: Boolean get() = session?.isConnected == true

    /**
     * 开始监听。
     *
     * @param port 监听端口。**由调用方给**（取值来自设置，见 [com.github.remotecontrol.util.AppPreferences.usbipPort]），
     *   协议层不持有固定端口
     * @return 端口是否绑上了；false 表示端口被占（或被系统拒绝），界面按「启动失败」处理
     */
    fun start(port: Int): Boolean {
        if (serverSocket != null) return true
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (e: Exception) {
            log("监听端口 $port 失败：${e.message}")
            return false
        }
        serverSocket = socket
        acceptThread = thread(name = "usbip-accept") { acceptLoop(socket) }
        log("USB/IP 服务已启动，等待电脑导入（端口 $port）")
        return true
    }

    /** 停止监听并拆掉当前会话 */
    fun stop() {
        acceptThread?.interrupt()
        acceptThread = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(sessionLock) {
            session?.close()
            session = null
        }
        log("USB/IP 服务已停止")
    }

    /**
     * 发一帧输入报告。
     *
     * @param reportId 报告 ID：2 = 键盘、4 = 触摸板（见 [UsbHidDescriptors]）
     * @param data 负荷，**不含**报告 ID（与 `BluetoothHidDevice.sendReport` 的口径一致）
     * @return 是否真的交给了主机链路；false 表示当前没有电脑挂着
     */
    fun sendReport(reportId: Int, data: ByteArray): Boolean {
        // 中断 IN 上发出去的每一帧都要带报告 ID 作为首字节 —— 这是描述符里声明了多条报告时
        // 主机的硬性要求，少了这一个字节所有报告都会被解析成别的报告
        val payload = ByteArray(data.size + 1)
        payload[0] = reportId.toByte()
        data.copyInto(payload, 1)

        // 记负荷（不带报告 ID）：中断端点要发整帧，主机若改用控制传输把输入报告读回去，
        // 报告 ID 由 VirtualUsbDevice 统一补 —— 见 recordInputReport 的说明
        device.recordInputReport(reportId, data)

        val active = session ?: return false
        if (!active.isConnected) return false
        return active.submitReport(payload)
    }

    // ─────────────────────────────────────────────
    // accept
    // ─────────────────────────────────────────────

    private fun acceptLoop(serverSocket: ServerSocket) {
        while (true) {
            val client = try {
                serverSocket.accept()
            } catch (e: Exception) {
                // 主动 stop() 关掉监听套接字时也会走到这里，不是错误
                if (this.serverSocket == null) return
                log("接受连接失败：${e.message}")
                continue
            }
            thread(name = "usbip-session") {
                Session(client).run()
            }
        }
    }

    // ─────────────────────────────────────────────
    // 会话
    // ─────────────────────────────────────────────

    private inner class Session(private val socket: Socket) {

        private val outgoing = LinkedBlockingQueue<ByteArray>()
        private val pipe = UrbQueue(log = log)

        @Volatile
        private var running = true

        @Volatile
        var isConnected = false
            private set

        private var writer: Thread? = null

        /** 本次会话已经逐帧记录过多少条报告，见 `submitReport` */
        private var reportTraceCount = 0

        fun run() {
            val streams = runCatching {
                socket.tcpNoDelay = true
                // 入/出各包一层缓冲，且**只建一次**：BufferedInputStream 会预读，
                // 建两个就等于把第二个之前的数据永远丢掉（握手之后 URB 全部错位）
                BufferedInputStream(socket.getInputStream()) to
                        BufferedOutputStream(socket.getOutputStream())
            }.getOrElse {
                log("会话初始化失败：${it.message}")
                return
            }
            val input = streams.first
            writer = thread(name = "usbip-writer") { writerLoop(streams.second) }

            try {
                if (!handshake(input)) return
                isConnected = true
                pipe.reset()
                onHostConnected?.invoke()
                urbLoop(input)
            } catch (e: SocketException) {
                // 电脑把 usbip detach 掉就是这一种，属正常收尾
                log("会话结束：${e.message}")
            } catch (e: Exception) {
                log("会话异常：${e.message}")
            } finally {
                val wasConnected = isConnected
                isConnected = false
                // 先把已经排好的应答写完再收摊：设备列表与「拒绝导入」都是回完就关连接，
                // 立刻打断写线程会把那一条应答丢掉 —— 电脑那边表现为「列表里一台设备都没有」
                awaitQueueDrained()
                running = false
                writer?.interrupt()
                pipe.reset()
                runCatching { socket.close() }
                releaseSession(this)
                if (wasConnected) onHostDisconnected?.invoke()
            }
        }

        /** 等写线程把队列里剩下的应答写完（最多 [DRAIN_TIMEOUT_MS]，写失败或收摊就提前退出） */
        private fun awaitQueueDrained() {
            val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
            while (running && outgoing.isNotEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
        }

        fun close() {
            running = false
            runCatching { socket.close() }
        }

        /** 报告 → 主机。已在界面线程调用，所以这里面只能进队列，不能碰 socket */
        fun submitReport(report: ByteArray): Boolean {
            if (!running) return false

            /**
             * 每次会话只前 [REPORT_TRACE_LIMIT] 帧逐帧记日志。
             *
             * 触摸板是 200Hz，全记下来会把 logcat 淹掉；而现场最需要的恰恰是「开局那几帧
             * 长什么样」—— 触摸板没反应时，这一小段就能回答「报告有没有发出去、字节对不对」。
             */
            if (reportTraceCount < REPORT_TRACE_LIMIT) {
                reportTraceCount++
                log(
                    "Report id=${report[0].toInt() and 0xFF} size=${report.size} hex=${report.toHex(16)}"
                )
            }

            val pair = pipe.onReport(report)
            if (pair != null) completeInterrupt(pair.first, pair.second)
            return true
        }

        private fun writerLoop(output: OutputStream) {
            try {
                while (running) {
                    val first = outgoing.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                    output.write(first)
                    // 顺手把队列里攒下的一起写掉，再统一 flush：一次 flush 就是一次系统调用
                    while (true) {
                        val next = outgoing.poll() ?: break
                        output.write(next)
                    }
                    output.flush()
                }
            } catch (_: InterruptedException) {
                // stop() / 会话结束
            } catch (e: Exception) {
                log("写回失败，关闭会话：${e.message}")
                running = false
                runCatching { socket.close() }
            }
        }

        // ── 握手 ──────────────────────────────────

        private fun handshake(input: InputStream): Boolean {
            val commonBytes = readFully(input, UsbIpProtocol.OP_COMMON_SIZE) ?: return false
            val common = OpCommon.parse(commonBytes)
            log("握手：version=0x%04x code=0x%04x".format(common.version, common.code))

            if (common.version != UsbIpProtocol.VERSION) {
                // 版本号是大端：按小端读会得到 0x1101，于是每次都判成「版本不支持」
                log(
                    "版本 0x%04x 不受支持（本设备 0x%04x），拒绝"
                        .format(common.version, UsbIpProtocol.VERSION)
                )
                replyOpCommon(common.code, status = 1)
                return false
            }

            return when (common.code) {
                UsbIpProtocol.OP_REQ_DEVLIST -> {
                    // 列设备是一次性连接：回完就关
                    replyDevlist()
                    false
                }

                UsbIpProtocol.OP_REQ_IMPORT -> importDevice(input)

                else -> {
                    log("未知的握手操作码 0x%04x".format(common.code))
                    false
                }
            }
        }

        private fun replyDevlist() {
            val body = ByteBuffer.allocate(4)   // n_devices
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(1)
                .array()
            outgoing.add(
                OpCommon(UsbIpProtocol.VERSION, UsbIpProtocol.OP_REP_DEVLIST, 0).toBytes() +
                        body +
                        UsbHidDescriptors.buildDeviceRecord() +
                        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(1).array() +
                        UsbHidDescriptors.buildInterfaceRecord()
            )
            log("已回复设备列表：${UsbIpProtocol.BUS_ID}")
        }

        private fun importDevice(input: InputStream): Boolean {
            val busidBytes = readFully(input, UsbIpProtocol.BUSID_SIZE) ?: return false
            val busid = UsbIpProtocol.readNulTerminated(busidBytes)
            if (busid != UsbIpProtocol.BUS_ID) {
                log("导入被拒：请求 busid=\"$busid\"，本设备是 \"${UsbIpProtocol.BUS_ID}\"")
                replyOpCommon(UsbIpProtocol.OP_REQ_IMPORT, status = 1)
                return false
            }
            if (!claimSession(this)) {
                // 同一时刻只允许一台电脑：第二台挂上来会让报告发到错误的主机上
                log("导入被拒：已有电脑在挂载本设备")
                replyOpCommon(UsbIpProtocol.OP_REQ_IMPORT, status = 1)
                return false
            }

            device.reset()
            outgoing.add(
                OpCommon(UsbIpProtocol.VERSION, UsbIpProtocol.OP_REP_IMPORT, 0).toBytes() +
                        UsbHidDescriptors.buildDeviceRecord()
            )
            log("已接受导入（busid=$busid），开始收发 URB")
            return true
        }

        private fun replyOpCommon(requestCode: Int, status: Int) {
            val replyCode = when (requestCode) {
                UsbIpProtocol.OP_REQ_DEVLIST -> UsbIpProtocol.OP_REP_DEVLIST
                UsbIpProtocol.OP_REQ_IMPORT -> UsbIpProtocol.OP_REP_IMPORT
                else -> UsbIpProtocol.OP_REP_IMPORT
            }
            outgoing.add(OpCommon(UsbIpProtocol.VERSION, replyCode, status).toBytes())
        }

        // ── URB 循环 ──────────────────────────────

        private fun urbLoop(input: InputStream) {
            while (running) {
                val header = readFully(input, UsbIpProtocol.HEADER_SIZE) ?: break
                val pdu = UsbIpPdu.parse(header)
                val keepGoing = when (pdu.command) {
                    UsbIpProtocol.OP_CMD_SUBMIT -> handleSubmit(pdu, input)
                    UsbIpProtocol.OP_CMD_UNLINK -> handleUnlink(pdu, header)
                    else -> {
                        log("未知的 PDU command=0x%08x".format(pdu.command))
                        true
                    }
                }
                if (!keepGoing) break
            }
        }

        /** @return false 表示连接已断，循环该收摊了 */
        private fun handleSubmit(pdu: UsbIpPdu, input: InputStream): Boolean {
            /**
             * OUT 的载荷跟在 48 字节头后面，**必须先读掉**。
             *
             * 控制传输的 SET_REPORT（键盘灯）就走这一条；不读的话它会被当成下一个头的开头，
             * 之后的所有 URB 全部错位 —— 现象是枚举到一半设备就没反应了。
             *
             * 控制传输额外看一眼 SETUP 包的方向位：`direction` 字段是客户端按 URB 的传输标志算的，
             * 个别实现（或驱动版本）可能把一次 IN 请求标成 OUT —— 那种情况下**没有载荷**，
             * 照着 `transferBufferLength`（= wLength，可能是 257）去读会直接读死在这里。
             * SETUP 包的 bit7 才是 data stage 方向的权威。
             */
            val hasOutPayload = if (pdu.isControl) {
                (pdu.setup[0].toInt() and 0x80) == 0
            } else {
                true
            }
            val payload = if (pdu.direction == UsbIpProtocol.DIR_OUT &&
                hasOutPayload &&
                pdu.transferBufferLength > 0
            ) {
                readFully(input, pdu.transferBufferLength) ?: return false
            } else {
                null
            }

            when {
                pdu.isControl -> {
                    val response = device.handleControl(pdu.setup, payload)
                    outgoing.add(
                        UsbIpPdu.buildRetSubmit(pdu, response.status, response.data.size) +
                                response.data
                    )
                }

                pdu.isInterruptIn -> {
                    // 有排队的报告就直接回，否则挂起等设备产生报告（见 UrbQueue）
                    val pair = pipe.onUrb(pdu)
                    if (pair != null) completeInterrupt(pair.first, pair.second)
                }

                else -> {
                    log("不支持的端点 ep=0x%02x direction=%d（本设备只有端点 0 与 0x81）"
                        .format(pdu.ep, pdu.direction))
                    outgoing.add(UsbIpPdu.buildRetSubmit(pdu, UsbIpProtocol.STATUS_STALL, 0))
                }
            }
            return true
        }

        private fun handleUnlink(pdu: UsbIpPdu, header: ByteArray): Boolean {
            // 目标 seqnum 在联合体的第一个字段：基础头 20 字节之后
            val target = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getInt(20)
            val cancelled = pipe.unlink(target)
            log(
                "收回 URB seqnum=$target（${if (cancelled) "已从挂起队列摘掉" else "不在挂起队列里"}，"
                        + "挂起 ${pipe.pendingCount} 条）"
            )
            outgoing.add(UsbIpPdu.buildRetUnlink(pdu, UsbIpProtocol.STATUS_OK))
            return true
        }

        private fun completeInterrupt(pdu: UsbIpPdu, report: ByteArray) {
            // 报告比主机给的缓冲区长时按缓冲区截断（主机按端点最大包申请的，正常不会发生）
            val budget = if (pdu.transferBufferLength > 0) {
                pdu.transferBufferLength
            } else {
                UsbHidDescriptors.INTERRUPT_PACKET_SIZE
            }
            val payload = if (report.size > budget) report.copyOf(budget) else report
            outgoing.add(
                UsbIpPdu.buildRetSubmit(pdu, UsbIpProtocol.STATUS_OK, payload.size) + payload
            )
        }
    }

    // ─────────────────────────────────────────────
    // 会话归属
    // ─────────────────────────────────────────────

    private fun claimSession(candidate: Session): Boolean = synchronized(sessionLock) {
        val current = session
        if (current != null && current !== candidate && current.isConnected) return false
        session = candidate
        true
    }

    private fun releaseSession(finished: Session) = synchronized(sessionLock) {
        if (session === finished) session = null
    }

    private companion object {
        const val POLL_MS = 200L

        /** 收摊前等队列写完的上限。正常是一瞬间，卡住也不该把会话线程拖在关闭流程里 */
        const val DRAIN_TIMEOUT_MS = 1_000L

        /** 每次会话逐帧记录的报告条数上限，见 `Session.submitReport` */
        const val REPORT_TRACE_LIMIT = 8

        /**
         * 从流里精确读满 [count] 字节；流结束（电脑关连接）时返回 null。
         *
         * `InputStream.read` 一次不保证读满，一次性 `read` 会把报文读碎 ——
         * usbip 的报文没有分隔符，读少读多都是灾难。
         */
        fun readFully(input: InputStream, count: Int): ByteArray? {
            if (count <= 0) return ByteArray(0)
            val buffer = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = input.read(buffer, offset, count - offset)
                if (read < 0) return null
                offset += read
            }
            return buffer
        }
    }
}
