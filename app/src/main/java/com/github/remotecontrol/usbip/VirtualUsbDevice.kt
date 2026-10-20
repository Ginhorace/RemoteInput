package com.github.remotecontrol.usbip

import com.github.remotecontrol.util.HidReportDescriptors

/**
 * 端点 0 上的控制传输应答：枚举、`GET_REPORT` / `SET_REPORT` 全在这里。
 *
 * ## 它只管「请求 → 应答」，不碰 TCP
 * 报文怎么发出去由 [UsbIpServer] 负责。分开的好处是这一层可以用纯字节输入输出测：
 * 给一个 SETUP 包，断言回什么状态、什么数据。
 *
 * ## Feature 报告是本职
 * 触摸板（PTP）在主机眼里是不是「精密触摸板」，取决于两条 Feature 报告读得到读不到：
 *
 * | 报告 ID | 主机的问题 | 设备必须回答 |
 * | --- | --- | --- |
 * | 5（Contact Count Maximum + Pad Type） | 「你最多认几根手指、是可按压还是纯触摸板？」 | 1 字节：低 4 位 = 5，高 4 位 = 1（可按压） |
 * | 6（PTPHQA） | 「你的品质筛选数据？」 | 256 字节，全 0 即可 |
 *
 * 这两条只要有一条不应答（或者回了描述符里没声明过的长度），主机的 PTP 初始化就会**静默停在
 * 那一步**：不报错、不重试，设备在设置页里看着是「符合 HID 标准的触摸板」，却怎么划都没反应。
 * 所以这里每一条 `GET_REPORT` 都记日志（`Get report: type=…, id=…, bufferSize=… → Replied report: …`），
 * 现场只要看这两行有没有出现、`size` 对不对，就能把「主机没问」和「设备答错」分开。
 *
 * @param log 诊断日志出口（接到 `Log.d` 上）
 * @param onKeyboardLedReport 收到键盘灯（Output Report）时的回调，负荷是原始字节
 */
class VirtualUsbDevice(
    private val log: (String) -> Unit,
    private val onKeyboardLedReport: (ByteArray) -> Unit,
) {

    /** 当前配置值：`SET_CONFIGURATION` 写它、`GET_CONFIGURATION` 读它 */
    private var configuration = 0

    /** 最近一次发出的输入报告，按报告 ID 存：主机偶尔会用控制传输把输入报告读回去 */
    private val lastInputReports = HashMap<Int, ByteArray>()

    /**
     * 每次导入（IMPORT）前重置。
     *
     * 设备对象跨会话复用，不重置的话重连后 `GET_CONFIGURATION` 会回上一次的值，
     * 而主机在枚举开头就会读它。
     */
    fun reset() {
        configuration = 0
        lastInputReports.clear()
    }

    /**
     * 记录一帧发往中断 IN 的输入报告的**负荷**（不含报告 ID）。
     *
     * 存负荷而不是整帧：整帧是「报告 ID + 负荷」，而报告 ID 属于传输层，
     * 由 [onGetReport] 在主机用控制传输把输入报告读回去时统一补上 —— 两处各存一份
     * 就一定会有一处忘记补，那正是这一轮踩过的坑。
     */
    fun recordInputReport(reportId: Int, payload: ByteArray) {
        lastInputReports[reportId] = payload
    }

    /**
     * 处理一条控制传输。
     *
     * @param setup 8 字节 SETUP 包（小端）
     * @param outData 主机发来的数据阶段（只有 OUT 方向才有）
     */
    fun handleControl(setup: ByteArray, outData: ByteArray?): ControlResponse {
        val bmRequestType = setup[0].toInt() and 0xFF
        val request = setup[1].toInt() and 0xFF
        val wValue = (setup[2].toInt() and 0xFF) or ((setup[3].toInt() and 0xFF) shl 8)
        val wLength = (setup[6].toInt() and 0xFF) or ((setup[7].toInt() and 0xFF) shl 8)
        val isIn = (bmRequestType and 0x80) != 0
        val requestType = (bmRequestType shr 5) and 0x03

        var response = when {
            requestType == TYPE_STANDARD && request == REQ_GET_DESCRIPTOR ->
                getDescriptor(wValue, wLength)

            requestType == TYPE_STANDARD && request == REQ_SET_CONFIGURATION -> {
                configuration = wValue and 0xFF
                ControlResponse.ok()
            }

            requestType == TYPE_STANDARD && request == REQ_GET_CONFIGURATION ->
                ControlResponse.ok(byteArrayOf(configuration.toByte()))

            requestType == TYPE_STANDARD && request == REQ_GET_STATUS ->
                ControlResponse.ok(byteArrayOf(0x00, 0x00))

            requestType == TYPE_STANDARD &&
                    (request == REQ_CLEAR_FEATURE || request == REQ_SET_FEATURE) ->
                // ENDPOINT_HALT 之类的特性位：本设备没有会停住的端点，一律当清除成功
                ControlResponse.ok()

            requestType == TYPE_STANDARD &&
                    (request == REQ_SET_ADDRESS ||
                            request == REQ_SET_INTERFACE ||
                            request == REQ_SET_DESCRIPTOR) -> ControlResponse.ok()

            requestType == TYPE_STANDARD && request == REQ_GET_INTERFACE ->
                ControlResponse.ok(byteArrayOf(0x00))

            /**
             * HID 类请求不按收件人区分。
             *
             * 规范上它们是「接口收件人」（`bmRequestType=0xA1` / `0x21`，Windows 也确实这么发），
             * 但这里不额外校验位 0~4：类请求本来就只有 HID 一种解释，多拦一层只会把
             * 某个驱动版本发来的变体挡在外面 —— 那种失败现场表现为「枚举完了但读不到 Feature 报告」，
             * 极难判断。
             */
            requestType == TYPE_CLASS && request == REQ_HID_GET_REPORT ->
                onGetReport(type = (wValue shr 8) and 0xFF, id = wValue and 0xFF, wLength = wLength)

            requestType == TYPE_CLASS && request == REQ_HID_SET_REPORT ->
                onSetReport(type = (wValue shr 8) and 0xFF, id = wValue and 0xFF, data = outData)

            // SET_IDLE / SET_PROTOCOL / SET_INTERFACE 这类「知道了」型请求
            requestType == TYPE_CLASS &&
                    (request == REQ_HID_SET_IDLE || request == REQ_HID_SET_PROTOCOL) ->
                ControlResponse.ok()

            requestType == TYPE_CLASS && request == REQ_HID_GET_IDLE ->
                ControlResponse.ok(byteArrayOf(0x00))

            /**
             * 始终回「报告协议」。
             *
             * 本设备的报告描述符带报告 ID，主机也只在 Boot 协议下才会来问这个；
             * 真被切到 Boot 协议时报告格式要跟着变（去掉报告 ID），那是另一套实现，
             * 这里宁可让主机拿不到 Boot 协议、继续用报告协议。
             */
            requestType == TYPE_CLASS && request == REQ_HID_GET_PROTOCOL ->
                ControlResponse.ok(byteArrayOf(0x01))

            else -> {
                log(
                    "Control 未支持的请求 bmRequestType=0x%02x request=0x%02x value=0x%03x -> STALL"
                        .format(bmRequestType, request, wValue)
                )
                ControlResponse(UsbIpProtocol.STATUS_STALL, EMPTY)
            }
        }

        // 长度截断：主机要多少给多少，多了会溢出主机的缓冲区
        if (isIn && response.data.size > wLength) {
            response = response.copy(data = response.data.copyOf(wLength))
        }

        log(
            "Control bmRequestType=0x%02x request=0x%02x value=0x%03x length=%d -> status=%d, data=%d%s"
                .format(
                    bmRequestType,
                    request,
                    wValue,
                    wLength,
                    response.status,
                    response.data.size,
                    if (response.data.isEmpty()) "" else " hex=${response.data.toHex(16)}",
                )
        )
        return response
    }

    // ─────────────────────────────────────────────
    // 描述符
    // ─────────────────────────────────────────────

    private fun getDescriptor(wValue: Int, wLength: Int): ControlResponse {
        val descriptorType = (wValue shr 8) and 0xFF
        val index = wValue and 0xFF
        return when (descriptorType) {
            DESC_DEVICE -> ControlResponse.ok(UsbHidDescriptors.deviceDescriptor)

            DESC_CONFIGURATION -> ControlResponse.ok(UsbHidDescriptors.configurationDescriptor)

            DESC_STRING -> UsbHidDescriptors.stringDescriptor(index)
                ?.let { ControlResponse.ok(it) }
                ?: ControlResponse(UsbIpProtocol.STATUS_STALL, EMPTY)

            // HID 描述符：类描述符，但走的是标准请求 + 接口收件人（bmRequestType=0x81）
            DESC_HID -> ControlResponse.ok(
                UsbHidDescriptors.configurationDescriptor
                    .copyOfRange(CONFIG_HID_OFFSET, CONFIG_HID_OFFSET + 9)
            )

            DESC_REPORT -> ControlResponse.ok(UsbHidDescriptors.reportDescriptor)

            /**
             * 设备限定符（Device Qualifier）：**全速设备必须回 STALL**。
             *
             * 回了内容（哪怕全是 0）等于告诉主机「我还能跑高速」，主机会按高速重新走一遍枚举，
             * 而本设备只按全速实现，那一遍必然失败。
             */
            DESC_DEVICE_QUALIFIER -> ControlResponse(UsbIpProtocol.STATUS_STALL, EMPTY)

            else -> {
                log("Get descriptor 未支持的类型 0x%02x（index=%d）".format(descriptorType, index))
                ControlResponse(UsbIpProtocol.STATUS_STALL, EMPTY)
            }
        }
    }

    // ─────────────────────────────────────────────
    // 报告
    // ─────────────────────────────────────────────

    /**
     * 主机读 Feature / 输入报告。
     *
     * `type` 沿用 Android 蓝牙 HID 的编号：1 = 输入、2 = 输出、3 = Feature ——
     * 现场日志与蓝牙那条链路可以直接并排比对。
     *
     * ## 应答的字节里必须带上报告 ID
     *
     * 描述符一旦用报告 ID，**类请求的数据阶段就是「报告 ID + 报告负荷」**，
     * `wValue` 里的那个 ID 只是告诉主机「我要哪一条」，不会自动出现在数据里：
     *
     * | 方向 | 主机发的长度 | 字节 |
     * | --- | --- | --- |
     * | 主机 → 设备（`SET_REPORT`，键盘灯） | 2 = 1 负荷 + 1 | `02 01`（ID=2、负荷=0x01） |
     * | 设备 → 主机（`GET_REPORT`，报告 5） | 主机按最大 Feature 报告申请 257 = 256 + 1 | 我们应回 `05 15` |
     *
     * 主机→设备那一侧的 `length=2 / hex=02 01` 是现场日志里直接看到的证据：
     * 如果 `hidusb` 负责插 ID，它就没必要多申请一个字节。设备→主机这一侧同理，
     * 少这个字节主机就会把负荷当报告 ID 读，能力读取失败 → PTP 初始化停在第一步
     * （现象：键盘能用、触摸板完全不动，且主机此后再不读 PTPHQA）。
     *
     * 蓝牙那条链路没有这一层：报告 ID 由 HIDP 控制通道的响应头承载，
     * 所以 `replyReport(device, type, id, data)` 的 `data` 里只有负荷（1 / 256 字节）。
     */
    private fun onGetReport(type: Int, id: Int, wLength: Int): ControlResponse {
        val payload = when (type) {
            /**
             * 输入报告理论上走中断端点的轮询，但主机偶尔会用控制传输把它读回去
             * （诊断工具、驱动初始化时都可能）。还没发出过这一条报告时回一份等长的零值，
             * 而不是 STALL：STALL 会被主机当成「这条报告不存在」，进而怀疑整份描述符。
             */
            REPORT_TYPE_INPUT -> lastInputReports[id] ?: zeroReportOf(id)

            REPORT_TYPE_FEATURE -> when (id) {
                /**
                 * Byte 0：低 4 位 = Contact Count Maximum（5），高 4 位 = Pad Type
                 * （1 = ClickPad，即整块触摸板可按压）。与蓝牙那条链路**逐位相同**，
                 * 这样两边若行为不同，原因就不在这条报告的内容里。
                 */
                HidReportDescriptors.REPORT_ID_MAX_COUNT -> byteArrayOf(0x15)

                // PTPHQA：256 字节供应商自定义数据，全 0 在 Windows 上是有效值
                HidReportDescriptors.REPORT_ID_PTPHQA -> ByteArray(256)

                else -> null
            }

            else -> null
        }

        if (payload == null) {
            log(
                "Get report: type=$type, id=$id, bufferSize=$wLength → Stalled：描述符里没有这条报告"
            )
            return ControlResponse(UsbIpProtocol.STATUS_STALL, EMPTY)
        }

        // 数据阶段 = 报告 ID + 负荷，与主机→设备的 SET_REPORT 同构
        val wire = byteArrayOf(id.toByte()) + payload

        // 主机给的缓冲区比这次传输短时按主机长度截断（主机按最大 Feature 报告申请，正常不会发生）
        val capped = if (wLength in 1 until wire.size) wire.copyOf(wLength) else wire

        // size 记的是**报告负荷**长度（蓝牙那边的日志口径，报告 5=1 / 报告 6=256），
        // wire 记的是这次控制传输真正发出去的字节数（含报告 ID 那个字节）
        log(
            "Get report: type=$type, id=$id, bufferSize=$wLength → " +
                    "Replied report: id=$id, size=${(capped.size - 1).coerceAtLeast(0)}, wire=${capped.size}"
        )
        return ControlResponse.ok(capped)
    }

    /** 按报告 ID 给出该输入报告的**负荷**长度（全 0）—— 报告 ID 字节由 [onGetReport] 统一补 */
    private fun zeroReportOf(id: Int): ByteArray? = when (id) {
        HidReportDescriptors.REPORT_ID_KEYBOARD -> ByteArray(HidReportDescriptors.KEYBOARD_REPORT_SIZE)
        HidReportDescriptors.REPORT_ID_TOUCHPAD -> ByteArray(HidReportDescriptors.TOUCHPAD_REPORT_SIZE)
        else -> null
    }

    private fun onSetReport(type: Int, id: Int, data: ByteArray?): ControlResponse {
        log("Set report: type=$type, id=$id, size=${data?.size ?: 0} hex=${data?.toHex(8) ?: ""}")

        // 键盘灯走 Output Report（报告 ID 2，负荷 1 字节）
        if (type == REPORT_TYPE_OUTPUT && id == HidReportDescriptors.REPORT_ID_KEYBOARD && data != null) {
            onKeyboardLedReport(data)
            return ControlResponse.ok()
        }

        /**
         * 描述符里没声明的 Output / Feature 报告：**回成功而不是 STALL**。
         *
         * 主机（尤其 Windows 的 HID 类驱动）把 STALL 当成「请求重试」的信号，
         * 一条没被声明的报告被 STALL 掉会让它反复来问；回成功是更省事的收尾方式。
         */
        return ControlResponse.ok()
    }

    /** 控制传输的应答：usbip 的 status（负数 errno）+ 数据阶段 */
    data class ControlResponse(val status: Int, val data: ByteArray) {
        companion object {
            fun ok(data: ByteArray = EMPTY): ControlResponse =
                ControlResponse(UsbIpProtocol.STATUS_OK, data)
        }
    }

    companion object {
        private val EMPTY = ByteArray(0)

        // bmRequestType 拆解后的三个维度
        private const val TYPE_STANDARD = 0
        private const val TYPE_CLASS = 1

        // 标准请求
        private const val REQ_GET_STATUS = 0x00
        private const val REQ_CLEAR_FEATURE = 0x01
        private const val REQ_SET_FEATURE = 0x03
        private const val REQ_SET_ADDRESS = 0x05
        private const val REQ_GET_DESCRIPTOR = 0x06
        private const val REQ_SET_DESCRIPTOR = 0x07
        private const val REQ_GET_CONFIGURATION = 0x08
        private const val REQ_SET_CONFIGURATION = 0x09
        private const val REQ_GET_INTERFACE = 0x0A
        private const val REQ_SET_INTERFACE = 0x0B

        // HID 类请求
        private const val REQ_HID_GET_REPORT = 0x01
        private const val REQ_HID_GET_IDLE = 0x02
        private const val REQ_HID_GET_PROTOCOL = 0x03
        private const val REQ_HID_SET_REPORT = 0x09
        private const val REQ_HID_SET_IDLE = 0x0A
        private const val REQ_HID_SET_PROTOCOL = 0x0B

        // 描述符类型
        private const val DESC_DEVICE = 0x01
        private const val DESC_CONFIGURATION = 0x02
        private const val DESC_STRING = 0x03
        private const val DESC_DEVICE_QUALIFIER = 0x06
        private const val DESC_HID = 0x21
        private const val DESC_REPORT = 0x22

        // 报告类型（与 Android BluetoothHidDevice 的编号一致，便于两边日志对比）
        private const val REPORT_TYPE_INPUT = 1
        private const val REPORT_TYPE_OUTPUT = 2
        private const val REPORT_TYPE_FEATURE = 3

        /** HID 描述符在配置描述符里的偏移：配置 9 + 接口 9 */
        private const val CONFIG_HID_OFFSET = 18
    }
}
