package com.github.remotecontrol.usbip

import com.github.remotecontrol.util.HidReportDescriptors
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * USB 侧的描述符：设备 / 配置 / 接口 / 端点 / HID，以及字符串。
 *
 * ## 与经典蓝牙那份的关系
 * USB 这边除报告描述符外还需要设备 / 配置 / 接口 / HID / 端点描述符 —— 这些在蓝牙侧根本不存在
 * （BT 由 SDP 记录描述设备），所以只能另写。**报告描述符也不再共用**：本链路是
 * [HidReportDescriptors.touchpadCombinedDescriptor]（触摸板 + 键盘，227 B），
 * 经典蓝牙那条已改成鼠标 + 键盘（`HidReportDescriptors.mouseCombinedDescriptor`）。
 * 两条链路各持一份的理由见 [HidReportDescriptors] 的类注释。
 *
 * ## 形态
 * 单接口、单端点（中断 IN，`0x81`）。
 * | 报告 ID | 方向 | 长度 | 用途 |
 * | --- | --- | --- | --- |
 * | 2 | 输入（中断 IN）/ 输出 | 8 / 1 | 键盘按键 / 键盘灯 |
 * | 4 | 输入（中断 IN） | 9 | 触摸板 |
 * | 5 | Feature | 1 | Contact Count Maximum + Pad Type |
 * | 6 | Feature | 256 | PTPHQA |
 *
 * 端点声明为 `0x81` 而不是「IN + OUT 两条」：键盘灯走控制传输（`SET_REPORT`）就够了，
 * 多一条 OUT 端点只会多一种主机侧行为差异。
 */
object UsbHidDescriptors {

    /** usbip 握手里的设备记录要与这里保持一致，改 VID/PID 只需要改这一处 */
    const val VID = 0x1209
    const val PID = 0x0001
    const val BCD_DEVICE = 0x0100

    const val BCD_USB = 0x0200
    const val BCD_HID = 0x0111

    /** 控制端点的最大包（全速设备固定 8/16/32/64，取 64） */
    const val MAX_PACKET_SIZE_0 = 64

    /** 中断 IN 端点的地址 */
    const val INTERRUPT_IN_ENDPOINT = 0x81

    /**
     * 中断端点的最大包：够放下最长的输入报告（触摸板 = 1 字节报告 ID + 9 字节负荷）。
     *
     * 取 16 而不是刚好 10：主机与设备对「一条 URB 能装几帧」的理解都靠它，
     * 留点余量比卡在临界值上安全。
     */
    const val INTERRUPT_PACKET_SIZE = 16

    /**
     * 中断端点的轮询间隔（毫秒，全速设备以 1ms 帧计）。
     *
     * 取 1 意味着主机几乎一直在挂 URB 等报告 —— 这正是触摸板要的（200Hz 的报告流不该被
     * 轮询间隔截断）。代价是 TCP 上每秒上千条小报文，实测可以接受。
     */
    const val INTERRUPT_INTERVAL_MS = 1

    /** 配置值：只有 1 这一套配置 */
    const val CONFIGURATION_VALUE = 1

    /** 配置描述符总长：配置 9 + 接口 9 + HID 9 + 端点 7 */
    const val CONFIGURATION_TOTAL_LENGTH = 34

    /** 总线供电 + 支持远程唤醒；100mA */
    private const val CONFIG_ATTRIBUTES = 0xA0
    private const val MAX_POWER_100mA = 50

    /**
     * HID 报告描述符：触摸板 + 键盘，**只属于局域网链路**。
     *
     * 经典蓝牙那条链路已改用「鼠标 + 键盘」（`HidReportDescriptors.mouseCombinedDescriptor`），
     * 两条链路各持一份组合、互不共用 —— 描述符会被主机缓存，共用时改一边就得连带另一边重配。
     *
     * 注意 USB 侧**不受**经典蓝牙那条「单个描述符 ≤ 256 字节（SDP 限制）」约束，
     * 所以以后要在这条链路上扩展，直接在这里拼接即可。
     */
    val reportDescriptor: ByteArray = HidReportDescriptors.touchpadCombinedDescriptor

    val deviceDescriptor: ByteArray = ByteBuffer.allocate(18)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(18)                                  // bLength
        .put(0x01)                                // bDescriptorType = DEVICE
        .putShort(BCD_USB.toShort())              // bcdUSB
        .put(0x00)                                // bDeviceClass：类在接口上
        .put(0x00)                                // bDeviceSubClass
        .put(0x00)                                // bDeviceProtocol
        .put(MAX_PACKET_SIZE_0.toByte())          // bMaxPacketSize0
        .putShort(VID.toShort())                  // idVendor
        .putShort(PID.toShort())                  // idProduct
        .putShort(BCD_DEVICE.toShort())           // bcdDevice
        .put(1)                                   // iManufacturer
        .put(2)                                   // iProduct
        .put(3)                                   // iSerialNumber
        .put(1)                                   // bNumConfigurations
        .array()

    val configurationDescriptor: ByteArray = ByteBuffer.allocate(CONFIGURATION_TOTAL_LENGTH)
        .order(ByteOrder.LITTLE_ENDIAN)
        // ── 配置 ──
        .put(9)                                        // bLength
        .put(0x02)                                     // bDescriptorType = CONFIGURATION
        .putShort(CONFIGURATION_TOTAL_LENGTH.toShort())// wTotalLength
        .put(1)                                        // bNumInterfaces
        .put(CONFIGURATION_VALUE.toByte())             // bConfigurationValue
        .put(0)                                        // iConfiguration
        .put(CONFIG_ATTRIBUTES.toByte())               // bmAttributes
        .put(MAX_POWER_100mA.toByte())                 // bMaxPower
        // ── 接口 ──
        .put(9)                                        // bLength
        .put(0x04)                                     // bDescriptorType = INTERFACE
        .put(0)                                        // bInterfaceNumber
        .put(0)                                        // bAlternateSetting
        .put(1)                                        // bNumEndpoints
        .put(0x03)                                     // bInterfaceClass = HID
        .put(0x00)                                     // bInterfaceSubClass：0 = 非 Boot
        .put(0x00)                                     // bInterfaceProtocol：0 = 不用 Boot 协议
        .put(0)                                        // iInterface
        // ── HID ──
        .put(9)                                        // bLength
        .put(0x21)                                     // bDescriptorType = HID
        .putShort(BCD_HID.toShort())                   // bcdHID
        .put(0)                                        // bCountryCode
        .put(1)                                        // bNumDescriptors
        .put(0x22)                                     // bDescriptorType = REPORT
        .putShort(reportDescriptor.size.toShort())     // wDescriptorLength
        // ── 端点：中断 IN ──
        .put(7)                                        // bLength
        .put(0x05)                                     // bDescriptorType = ENDPOINT
        .put(INTERRUPT_IN_ENDPOINT.toByte())           // bEndpointAddress
        .put(0x03)                                     // bmAttributes = Interrupt
        .putShort(INTERRUPT_PACKET_SIZE.toShort())     // wMaxPacketSize
        .put(INTERRUPT_INTERVAL_MS.toByte())           // bInterval
        .array()

    /** 语言 ID 列表（只要 en-US 一条） */
    private val langIdDescriptor = byteArrayOf(
        0x04, 0x03.toByte(), 0x09, 0x04,
    )

    /**
     * 字符串描述符表：键是 `wValue` 的低字节（0 是语言 ID 列表）。
     *
     * 内容刻意保持中性：它只是主机设备列表里显示的名字，不含任何个人信息。
     */
    private val stringDescriptors: Map<Int, ByteArray> = mapOf(
        0 to langIdDescriptor,
        1 to utf16Descriptor("Remote Control"),
        2 to utf16Descriptor("Remote Control Device"),
        3 to utf16Descriptor("0001"),
    )

    /** 取字符串描述符；不存在时返回 null（调用方回 STALL） */
    fun stringDescriptor(index: Int): ByteArray? = stringDescriptors[index]

    /** UTF-16LE 字符串描述符：2 字节头（长度 + 0x03）+ 字符体 */
    private fun utf16Descriptor(text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_16LE)
        val out = ByteArray(body.size + 2)
        out[0] = out.size.toByte()
        out[1] = 0x03
        body.copyInto(out, destinationOffset = 2)
        return out
    }

    /** `usbip_usb_device`（312 字节）里的设备信息，握手与 DEVLIST 都用它 */
    fun buildDeviceRecord(): ByteArray {
        val record = ByteBuffer.allocate(UsbIpProtocol.DEVICE_RECORD_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
        // path[256]：Linux 侧显示的 sysfs 路径，这里填一个形似的字符串即可
        record.put(UsbIpProtocol.nulPadded("/sys/devices/usbip/${UsbIpProtocol.BUS_ID}", 256))
        // busid[32]
        record.put(UsbIpProtocol.nulPadded(UsbIpProtocol.BUS_ID, UsbIpProtocol.BUSID_SIZE))
        record.putInt(1)                                    // busnum
        record.putInt(1)                                    // devnum
        record.putInt(UsbIpProtocol.SPEED_FULL)             // speed
        record.putShort(VID.toShort())                      // idVendor
        record.putShort(PID.toShort())                      // idProduct
        record.putShort(BCD_DEVICE.toShort())               // bcdDevice
        record.put(0x00)                                    // bDeviceClass
        record.put(0x00)                                    // bDeviceSubClass
        record.put(0x00)                                    // bDeviceProtocol
        record.put(CONFIGURATION_VALUE.toByte())            // bConfigurationValue
        record.put(1)                                       // bNumConfigurations
        record.put(1)                                       // bNumInterfaces
        return record.array()
    }

    /** `usbip_usb_interface`（4 字节）：接口的类 / 子类 / 协议，让主机在导入前就能认出这是 HID */
    fun buildInterfaceRecord(): ByteArray = byteArrayOf(
        0x03,   // bInterfaceClass = HID
        0x00,   // bInterfaceSubClass
        0x00,   // bInterfaceProtocol
        0x00,   // padding
    )
}
