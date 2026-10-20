package com.github.remotecontrol.usbip

import com.github.remotecontrol.util.HidReportDescriptors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * USB/IP 这一层的三条硬约束。
 *
 * 这三件事都**只能靠单测盯住**：它们出错时现场看到的现象全是「电脑那边没反应」，
 * 而真机复现一次要把 `usbip attach` / 拔插 / 重新枚举跑一遍，改一行重试的成本很高。
 *
 * 1. **字节序**：USB/IP 报文全是大端。version 按小端读会得到 `0x1101`，
 *    于是 `usbip list -r` 一条设备都列不出来；
 * 2. **描述符自洽**：配置描述符里的 `wDescriptorLength` 必须等于真正要回的报告描述符长度，
 *    端点最大包必须装得下最长的输入报告（含报告 ID）；
 * 3. **Feature 报告 5 / 6 答得对**：触摸板在主机眼里是不是精密触摸板就靠这两条。
 *    两件事都不能错：
 *    - 数据阶段必须**以报告 ID 字节开头**（报告 5 → `05 15`）。主机按「ID + 负荷」的
 *      惯例读它，少了这个字节主机就把负荷当成 ID，能力读取判失败，PTP 初始化直接停在第一步
 *      （现场表现：键盘能用、触摸板完全不动，且主机此后再不读 PTPHQA）；
 *    - 长度按主机给的缓冲区截断：主机按最大 Feature 报告申请 257 = 256 + 1，回满 257 是
 *      正确的（报告 6），但若主机给得更短就必须截断，不能溢出。
 */
class UsbIpProtocolTest {

    // ─────────────────────────────────────────────
    // 1. 字节序
    // ─────────────────────────────────────────────

    @Test
    fun `opCommonIsBigEndian`() {
        val bytes = OpCommon(UsbIpProtocol.VERSION, UsbIpProtocol.OP_REQ_DEVLIST, 0).toBytes()
        assertArrayEquals(
            "牵手报文的 version 必须写成大端 01 11（按小端写就成了 11 01）",
            byteArrayOf(0x01, 0x11, 0x80.toByte(), 0x05, 0, 0, 0, 0),
            bytes,
        )
    }

    @Test
    fun `opCommonRoundTrips`() {
        val parsed = OpCommon.parse(
            OpCommon(UsbIpProtocol.VERSION, UsbIpProtocol.OP_REP_IMPORT, 1).toBytes()
        )
        assertEquals(UsbIpProtocol.VERSION, parsed.version)
        assertEquals(UsbIpProtocol.OP_REP_IMPORT, parsed.code)
        assertEquals(1, parsed.status)
    }

    @Test
    fun `pduHeaderFieldsFollowBigEndianLayout`() {
        val pdu = UsbIpPdu.parse(submitHeader(seqnum = 0x11223344, ep = 0x81, length = 16))

        assertEquals(UsbIpProtocol.OP_CMD_SUBMIT, pdu.command)
        assertEquals(0x11223344, pdu.seqnum)
        assertEquals(UsbIpProtocol.DIR_IN, pdu.direction)
        assertEquals(0x81, pdu.ep)
        assertEquals(16, pdu.transferBufferLength)
        assertTrue(pdu.isInterruptIn)
    }

    @Test
    fun `retSubmitEchoesRequestIdentity`() {
        val request = UsbIpPdu.parse(submitHeader(seqnum = 7, ep = 0, length = 257))
        val reply = UsbIpPdu.buildRetSubmit(request, UsbIpProtocol.STATUS_OK, 256)
        val buf = ByteBuffer.wrap(reply).order(ByteOrder.BIG_ENDIAN)

        assertEquals(UsbIpProtocol.HEADER_SIZE, reply.size)
        assertEquals(UsbIpProtocol.OP_RET_SUBMIT, buf.getInt(0))
        assertEquals("seqnum 必须原样回抄，主机靠它把应答配回 URB", 7, buf.getInt(4))
        assertEquals(UsbIpProtocol.STATUS_OK, buf.getInt(20))
        assertEquals(256, buf.getInt(24))
    }

    @Test
    fun `unlinkTargetSeqnumSitsAtUnionOffset`() {
        // unlink 的目标 seqnum 在基础头（20 字节）之后的联合体里
        val header = ByteBuffer.allocate(UsbIpProtocol.HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(UsbIpProtocol.OP_CMD_UNLINK)
            .putInt(99)
            .putInt(1)
            .putInt(UsbIpProtocol.DIR_OUT)
            .putInt(0)
            .putInt(42)
            .putInt(0).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0)
            .array()

        assertEquals(42, ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getInt(20))
    }

    // ─────────────────────────────────────────────
    // 2. 描述符
    // ─────────────────────────────────────────────

    @Test
    fun `deviceRecordMatchesUsbipLayout`() {
        val record = UsbHidDescriptors.buildDeviceRecord()
        val buf = ByteBuffer.wrap(record).order(ByteOrder.BIG_ENDIAN)

        assertEquals(UsbIpProtocol.DEVICE_RECORD_SIZE, record.size)
        assertEquals(
            "busid",
            UsbIpProtocol.BUS_ID,
            UsbIpProtocol.readNulTerminated(record.copyOfRange(256, 288)),
        )
        assertEquals(1, buf.getInt(288))                                    // busnum
        assertEquals(UsbIpProtocol.SPEED_FULL, buf.getInt(296))             // speed
        assertEquals(0x1209, buf.getShort(300).toInt() and 0xFFFF)          // idVendor
        assertEquals(0x0001, buf.getShort(302).toInt() and 0xFFFF)          // idProduct
        assertEquals(
            UsbHidDescriptors.CONFIGURATION_VALUE,
            record[309].toInt() and 0xFF,
        )                                                                   // bConfigurationValue
        assertEquals(1, record[311].toInt())                                // bNumInterfaces
    }

    @Test
    fun `configurationDescriptorIsSelfConsistent`() {
        val config = UsbHidDescriptors.configurationDescriptor
        val buf = ByteBuffer.wrap(config).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(UsbHidDescriptors.CONFIGURATION_TOTAL_LENGTH, config.size)
        assertEquals(config.size, buf.getShort(2).toInt() and 0xFFFF)   // wTotalLength
        assertEquals(1, config[4].toInt())                              // bNumInterfaces

        // 接口：HID 类、一个端点
        assertEquals(9, config[9].toInt())                              // bLength
        assertEquals(0x04, config[10].toInt())                          // bDescriptorType = INTERFACE
        assertEquals(1, config[13].toInt())                             // bNumEndpoints
        assertEquals(0x03, config[14].toInt())                          // bInterfaceClass = HID

        // HID 描述符：报告描述符长度必须等于真正要回的长度
        assertEquals(0x21, config[19].toInt())                          // bDescriptorType = HID
        assertEquals(0x0111, buf.getShort(20).toInt() and 0xFFFF)        // bcdHID
        assertEquals(0x22, config[24].toInt())                          // 报告描述符
        assertEquals(
            "wDescriptorLength 与实际报告描述符长度不一致时，主机读到一半就停",
            UsbHidDescriptors.reportDescriptor.size,
            buf.getShort(25).toInt() and 0xFFFF,
        )

        // 端点：中断 IN
        assertEquals(0x05, config[28].toInt())                          // bDescriptorType = ENDPOINT
        assertEquals(UsbHidDescriptors.INTERRUPT_IN_ENDPOINT, config[29].toInt() and 0xFF)
        assertEquals(0x03, config[30].toInt())                          // bmAttributes = Interrupt
        assertEquals(UsbHidDescriptors.INTERRUPT_PACKET_SIZE, buf.getShort(31).toInt() and 0xFFFF)
    }

    @Test
    fun `interruptPacketFitsLongestInputReport`() {
        val touchpad = HidReportDescriptors.TOUCHPAD_REPORT_SIZE + 1   // + 报告 ID
        val keyboard = HidReportDescriptors.KEYBOARD_REPORT_SIZE + 1

        assertTrue(
            "最长输入报告 $touchpad 字节（触摸板 + 报告 ID）装不进端点最大包",
            touchpad <= UsbHidDescriptors.INTERRUPT_PACKET_SIZE,
        )
        assertTrue(keyboard <= UsbHidDescriptors.INTERRUPT_PACKET_SIZE)
        assertEquals(
            "报告描述符长度变了但常量没跟着变",
            UsbHidDescriptors.reportDescriptor.size,
            HidReportDescriptors.touchpadDescriptor.size + HidReportDescriptors.keyboardDescriptor.size,
        )
    }

    // ─────────────────────────────────────────────
    // 3. 控制传输（Feature 报告）
    // ─────────────────────────────────────────────

    @Test
    fun `deviceQualifierIsStalled`() {
        val device = newDevice()
        val response = device.handleControl(setup(0x80, 0x06, 0x0600, 0), null)
        assertEquals(
            "全速设备回了设备限定符，主机会按高速重新枚举一遍",
            UsbIpProtocol.STATUS_STALL,
            response.status,
        )
    }

    @Test
    fun `deviceAndConfigurationDescriptorsAreServed`() {
        val device = newDevice()

        val deviceDescriptor = device.handleControl(setup(0x80, 0x06, 0x0100, 64), null)
        assertEquals(UsbIpProtocol.STATUS_OK, deviceDescriptor.status)
        assertEquals(18, deviceDescriptor.data.size)

        // 主机先读 9 字节配置头（拿 wTotalLength），再读整份
        val header = device.handleControl(setup(0x80, 0x06, 0x0200, 9), null)
        assertEquals(9, header.data.size)
        val full = device.handleControl(setup(0x80, 0x06, 0x0200, 34), null)
        assertEquals(UsbHidDescriptors.CONFIGURATION_TOTAL_LENGTH, full.data.size)

        // 报告描述符走标准请求 + 接口收件人
        val report = device.handleControl(setup(0x81, 0x06, 0x2200, 256), null)
        assertEquals(UsbHidDescriptors.reportDescriptor.size, report.data.size)
    }

    @Test
    fun `configurationValueIsRememberedAndReset`() {
        val device = newDevice()
        assertEquals(0, device.handleControl(setup(0x80, 0x08, 0, 1), null).data[0].toInt())

        assertEquals(UsbIpProtocol.STATUS_OK, device.handleControl(setup(0x00, 0x09, 1, 0), null).status)
        assertEquals(1, device.handleControl(setup(0x80, 0x08, 0, 1), null).data[0].toInt())

        // 设备对象跨会话复用：不重置的话重连后主机会读到上一次的配置值
        device.reset()
        assertEquals(0, device.handleControl(setup(0x80, 0x08, 0, 1), null).data[0].toInt())
    }

    @Test
    fun `contactCountMaximumFeatureReportCarriesReportIdFirst`() {
        val device = newDevice()
        val response = device.handleControl(setup(0xa1, 0x01, 0x0305, 257), null)

        assertEquals(UsbIpProtocol.STATUS_OK, response.status)
        assertArrayEquals(
            "数据阶段必须是「报告 ID 字节 + 负荷」：报告 5 应回 05 15" +
                    "（低 4 位 = Contact Count Maximum（5），高 4 位 = Pad Type（1））",
            byteArrayOf(0x05, 0x15),
            response.data,
        )
    }

    @Test
    fun `ptphqaFeatureReportCarriesReportIdFirst`() {
        val device = newDevice()
        val response = device.handleControl(setup(0xa1, 0x01, 0x0306, 257), null)

        assertEquals(UsbIpProtocol.STATUS_OK, response.status)
        // 256 字节负荷 + 1 字节报告 ID = 主机的 wLength（256 + 1）刚好装下
        assertEquals(257, response.data.size)
        assertEquals(0x06.toByte(), response.data[0])
        assertArrayEquals(ByteArray(256), response.data.copyOfRange(1, 257))
    }

    @Test
    fun `shortFeatureBufferTruncatesInsteadOfOverflowing`() {
        val device = newDevice()
        val response = device.handleControl(setup(0xa1, 0x01, 0x0306, 100), null)

        assertEquals(
            "主机给的缓冲区更短时必须截断（缓冲区含报告 ID 字节），回满 257 会溢出",
            100,
            response.data.size,
        )
        assertEquals(0x06.toByte(), response.data[0])
    }

    @Test
    fun `setReportCarriesReportIdFirstSoGetReportMustToo`() {
        // 镜像证据：主机→设备的键盘灯报告是 2 字节 `02 01`（ID + 负荷），
        // 说明报告 ID 属于数据阶段的一部分；设备→主机的 Feature 报告必须同样带上
        var leds: ByteArray? = null
        val device = VirtualUsbDevice(log = {}, onKeyboardLedReport = { leds = it })

        device.handleControl(setup(0x21, 0x09, 0x0202, 2), byteArrayOf(0x02, 0x01))

        assertNotNull(leds)
        assertEquals("报告 ID 是数据阶段的第一个字节", 0x02.toByte(), leds!![0])
    }

    @Test
    fun `inputReportIsServedFromLastSentFrame`() {
        val device = newDevice()

        // 还没有发出过这一条输入报告：给等长的零值（含报告 ID 字节）而不是 STALL
        val empty = device.handleControl(setup(0xa1, 0x01, 0x0104, 16), null)
        assertEquals(UsbIpProtocol.STATUS_OK, empty.status)
        assertEquals(HidReportDescriptors.TOUCHPAD_REPORT_SIZE + 1, empty.data.size)
        assertEquals(
            HidReportDescriptors.REPORT_ID_TOUCHPAD.toByte(),
            empty.data[0],
        )

        device.recordInputReport(
            HidReportDescriptors.REPORT_ID_TOUCHPAD,
            byteArrayOf(0x01, 0x02),
        )
        assertArrayEquals(
            byteArrayOf(0x04, 0x01, 0x02),
            device.handleControl(setup(0xa1, 0x01, 0x0104, 16), null).data,
        )
    }

    @Test
    fun `undeclaredFeatureReportIsStalled`() {
        val device = newDevice()
        val response = device.handleControl(setup(0xa1, 0x01, 0x0309, 8), null)

        assertEquals(UsbIpProtocol.STATUS_STALL, response.status)
        assertTrue(response.data.isEmpty())
    }

    @Test
    fun `keyboardLedOutputReportReachesCallback`() {
        var leds: ByteArray? = null
        val device = VirtualUsbDevice(log = {}, onKeyboardLedReport = { leds = it })

        val response = device.handleControl(setup(0x21, 0x09, 0x0202, 1), byteArrayOf(0x02))

        assertEquals(UsbIpProtocol.STATUS_OK, response.status)
        assertNotNull("键盘灯的 Output Report 必须被认出来，否则灯永远停在未知态", leds)
        assertArrayEquals(byteArrayOf(0x02), leds)
    }

    // ─────────────────────────────────────────────
    // 4. 中断 IN 的 URB ↔ 报告配对
    // ─────────────────────────────────────────────

    @Test
    fun `reportQueuedWhenNoUrbIsPending`() {
        val queue = UrbQueue(log = {})
        val report = byteArrayOf(0x04, 0x01)

        assertNull("主机还没挂 URB，报告应先排队", queue.onReport(report))

        val pair = queue.onUrb(interruptPdu(seqnum = 1))
        assertNotNull("URB 一到就该把排队的报告配出去", pair)
        assertArrayEquals(report, pair!!.second)
        assertEquals(1, pair.first.seqnum)
    }

    @Test
    fun `urbHeldUntilReportArrives`() {
        val queue = UrbQueue(log = {})

        assertNull("报告还没产生，URB 应挂起（这就是真实设备的 NAK）", queue.onUrb(interruptPdu(seqnum = 2)))

        val report = byteArrayOf(0x02)
        val pair = queue.onReport(report)
        assertNotNull(pair)
        assertEquals(2, pair!!.first.seqnum)
        assertArrayEquals(report, pair.second)
    }

    @Test
    fun `pairsAreKeptInOrder`() {
        val queue = UrbQueue(log = {})
        queue.onReport(byteArrayOf(1))
        queue.onReport(byteArrayOf(2, 3))

        assertArrayEquals(byteArrayOf(1), queue.onUrb(interruptPdu(seqnum = 1))!!.second)
        assertArrayEquals(byteArrayOf(2, 3), queue.onUrb(interruptPdu(seqnum = 2))!!.second)
    }

    @Test
    fun `unlinkRemovesPendingUrb`() {
        val queue = UrbQueue(log = {})
        queue.onUrb(interruptPdu(seqnum = 5))

        assertTrue(queue.unlink(5))
        assertEquals(0, queue.pendingCount)
        assertTrue("摘掉之后不该再配出去", !queue.unlink(5))
    }

    @Test
    fun `overflowDropsOldestReport`() {
        val queue = UrbQueue(capacity = 2, log = {})
        queue.onReport(byteArrayOf(1))
        queue.onReport(byteArrayOf(2))
        queue.onReport(byteArrayOf(3))

        assertArrayEquals("超限时丢最旧的一帧，保留最新的", byteArrayOf(2), queue.onUrb(interruptPdu(1))!!.second)
        assertArrayEquals(byteArrayOf(3), queue.onUrb(interruptPdu(2))!!.second)
    }

    // ─────────────────────────────────────────────
    // 构造工具
    // ─────────────────────────────────────────────

    private fun newDevice(): VirtualUsbDevice = VirtualUsbDevice(log = {}, onKeyboardLedReport = {})

    /** 8 字节 SETUP 包：wValue / wLength 是**小端** */
    private fun setup(bmRequestType: Int, request: Int, value: Int, length: Int): ByteArray =
        byteArrayOf(
            bmRequestType.toByte(),
            request.toByte(),
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            0,
            0,
            (length and 0xFF).toByte(),
            ((length shr 8) and 0xFF).toByte(),
        )

    private fun submitHeader(seqnum: Int, ep: Int, length: Int): ByteArray =
        ByteBuffer.allocate(UsbIpProtocol.HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(UsbIpProtocol.OP_CMD_SUBMIT)
            .putInt(seqnum)
            .putInt(1)                                  // devid
            .putInt(UsbIpProtocol.DIR_IN)
            .putInt(ep)
            .putInt(0)                                  // transfer_flags
            .putInt(length)                             // transfer_buffer_length
            .putInt(0)
            .putInt(0)
            .putInt(1)                                  // interval
            .put(ByteArray(8))                          // setup 包
            .array()

    private fun interruptPdu(seqnum: Int): UsbIpPdu =
        UsbIpPdu.parse(
            submitHeader(
                seqnum = seqnum,
                ep = UsbHidDescriptors.INTERRUPT_IN_ENDPOINT,
                length = UsbHidDescriptors.INTERRUPT_PACKET_SIZE,
            )
        )
}
