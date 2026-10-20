package com.github.remotecontrol.util

/**
 * HID 报告描述符：键盘 + 指针，指针有两种形态。
 *
 * **两条链路各取一份组合，互不共用**：
 * | 链路 | 组合 | 指针形态 |
 * | --- | --- | --- |
 * | 经典蓝牙 | [mouseCombinedDescriptor]（鼠标 + 键盘，约 129 B） | 相对位移，带滚轮与 5 个按钮位 |
 * | 局域网（USB/IP） | [touchpadCombinedDescriptor]（触摸板 + 键盘，227 B） | 绝对坐标 PTP，多指 + Feature 5/6 |
 *
 * 分开的理由不只是「两边要的形态不同」：描述符在配对 / 导入时被主机**固化缓存**，
 * 任一条链路改描述符都要在主机端删掉设备重来，所以不该让两条链路共享同一份字节。
 */
object
HidReportDescriptors {

    /** 鼠标输入报告（经典蓝牙链路）：buttons / dx / dy / wheel 各 1 字节 */
    const val REPORT_ID_MOUSE = 1

    const val REPORT_ID_KEYBOARD = 2
    const val REPORT_ID_TOUCHPAD = 4
    const val REPORT_ID_MAX_COUNT = 5
    const val REPORT_ID_PTPHQA = 6

    /** 鼠标输入报告长度（字节），格式见 [MouseConstants] */
    const val MOUSE_REPORT_SIZE = 4

    /** 触摸板输入报告长度（字节），格式见 [TouchpadConstants] */
    const val TOUCHPAD_REPORT_SIZE = 9

    /** 键盘输入报告长度（字节）：1 修饰键位 + 1 保留位 + 6 键码 */
    const val KEYBOARD_REPORT_SIZE = 8

    /**
     * 经典链路应答 Feature 6（PTPHQA）时发出的载荷长度。
     *
     * **当前没有调用点**：经典蓝牙已改用鼠标描述符（[mouseCombinedDescriptor]），
     * 不再声明 Feature 5/6；PTP 那套只剩局域网链路（它走端点 0，不受下面这个 MTU 限制）。
     * 结论保留在这里，是因为将来若再把经典链路改回触摸板，这条「整包不能超过 62 B」
     * 的真机对照仍然成立 —— 别重新试一遍。
     *
     * PTP 规范里这条报告是 256 字节，但 HIDP 上一次应答的**整包**（HIDP 头 1 B +
     * 报告 ID 1 B + 载荷）受手机侧 HID 通道的 L2CAP MTU 限制 —— 抓包里手机在两条 HID
     * 通道上申请的都是 **64**，所以载荷最多 62 B。同一台手机、同一台电脑上逐档对照过：
     *
     * | 载荷 | 整包 | `replyReport` 返回值 | 抓包里有没有上线 |
     * | --- | --- | --- | --- |
     * | 256 B（规范值） | 258 B | false | 没有 |
     * | 254 B | 256 B | true（**只代表「栈收下了」**） | 没有 |
     * | 60 B | 62 B | true | **有**（`a3 06` + 60 B 的 DATA，主机照收） |
     *
     * 故取实测通过的 60 B（理论上限 62 B，留 2 字节余量）。主机侧不校验这条报告的内容
     * —— 历史上连「一帧都没有」都照样能用触摸板 —— 所以少几个字节没有副作用。
     *
     * **只用于经典链路**：BLE 走 GATT（ATT MTU 517）、USB/IP 走端点 0，都不受这个限制，
     * 那两条链路仍回完整的 256 字节。
     */
    const val PTPHQA_REPLY_BYTES = 60

    /**
     * 鼠标描述符（64 B）：5 个按钮位 + 相对位移 −127..127 + 滚轮。
     *
     * 只给经典蓝牙链路用。相对位移意味着指针靠「每帧增量」推进，
     * 单帧幅度受 ±127 限制（界面侧按余量累加分摊，见 `ui/MouseModeScreen.kt`）。
     */
    val mouseDescriptor = byteArrayOf(
        // 鼠标部分
        0x05.toByte(), 0x01.toByte(), // 用途页：通用输入设备
        0x09.toByte(), 0x02.toByte(), // 用途：鼠标
        0xA1.toByte(), 0x01.toByte(), // 集合：应用
        0x85.toByte(), REPORT_ID_MOUSE.toByte(), // 报告ID：1
        0x09.toByte(), 0x01.toByte(), // 用途：指针
        0xA1.toByte(), 0x00.toByte(), // 集合：物理
        0x05.toByte(), 0x09.toByte(), // 用途页：按钮
        0x19.toByte(), 0x01.toByte(), // 用途最小：1
        0x29.toByte(), 0x05.toByte(), // 用途最大：5
        0x15.toByte(), 0x00.toByte(), // 逻辑最小：0
        0x25.toByte(), 0x01.toByte(), // 逻辑最大：1
        0x95.toByte(), 0x05.toByte(), // 报告计数：5
        0x75.toByte(), 0x01.toByte(), // 报告大小：1
        0x81.toByte(), 0x02.toByte(), // 输入：数据、变量、绝对值
        0x95.toByte(), 0x01.toByte(), // 报告计数：1
        0x75.toByte(), 0x03.toByte(), // 报告大小：3
        0x81.toByte(), 0x03.toByte(), // 输入：常量、变量、绝对值
        0x05.toByte(), 0x01.toByte(), // 用途页：通用输入设备
        0x09.toByte(), 0x30.toByte(), // 用途：X坐标
        0x09.toByte(), 0x31.toByte(), // 用途：Y坐标
        0x15.toByte(), 0x81.toByte(), // 逻辑最小：-127
        0x25.toByte(), 0x7F.toByte(), // 逻辑最大：127
        0x75.toByte(), 0x08.toByte(), // 报告大小：8
        0x95.toByte(), 0x02.toByte(), // 报告计数：2
        0x81.toByte(), 0x06.toByte(), // 输入：数据、变量、相对值
        0x09.toByte(), 0x38.toByte(), // 用途：滚轮
        0x15.toByte(), 0x81.toByte(), // 逻辑最小：-127
        0x25.toByte(), 0x7F.toByte(), // 逻辑最大：127
        0x75.toByte(), 0x08.toByte(), // 报告大小：8
        0x95.toByte(), 0x01.toByte(), // 报告计数：1
        0x81.toByte(), 0x06.toByte(), // 输入：数据、变量、相对值
        0xC0.toByte(), // 结束集合
        0xC0.toByte(), // 结束集合
    )

    val keyboardDescriptor = byteArrayOf(
        // 键盘部分
        0x05.toByte(), 0x01.toByte(), // 用途页：通用输入设备
        0x09.toByte(), 0x06.toByte(), // 用途：键盘
        0xA1.toByte(), 0x01.toByte(), // 集合：应用
        0x85.toByte(), REPORT_ID_KEYBOARD.toByte(), // 报告ID：2
        0x05.toByte(), 0x07.toByte(), // 用途页：键盘/按键
        0x19.toByte(), 0xE0.toByte(), // 用途最小：左控制键
        0x29.toByte(), 0xE7.toByte(), // 用途最大：右GUI键
        0x15.toByte(), 0x00.toByte(), // 逻辑最小：0
        0x25.toByte(), 0x01.toByte(), // 逻辑最大：1
        0x95.toByte(), 0x08.toByte(), // 报告计数：8
        0x75.toByte(), 0x01.toByte(), // 报告大小：1
        0x81.toByte(), 0x02.toByte(), // 输入：数据、变量、绝对值
        0x95.toByte(), 0x01.toByte(), // 报告计数：1
        0x75.toByte(), 0x08.toByte(), // 报告大小：8
        0x81.toByte(), 0x03.toByte(), // 输入：常量、变量、绝对值
        0x95.toByte(), 0x06.toByte(), // 报告计数：6
        0x75.toByte(), 0x08.toByte(), // 报告大小：8
        0x15.toByte(), 0x00.toByte(), // 逻辑最小：0
        0x25.toByte(), 0x65.toByte(), // 逻辑最大：101
        0x05.toByte(), 0x07.toByte(), // 用途页：键盘/按键
        0x19.toByte(), 0x00.toByte(), // 用途最小：无按键
        0x29.toByte(), 0x65.toByte(), // 用途最大：键盘左键
        0x81.toByte(), 0x00.toByte(), // 输入：数据、数组、绝对值
        // ── 键盘灯（LED Output Report，18 字节）──
        // 主机就是通过这一条 output 报告把 Num / Caps / Scroll 的状态回传给设备的；
        // 不含它的话主机没有任何通道告诉设备「现在大写锁定是开着的」，界面上的灯就永远是未知态。
        // 与键盘共用 REPORT_ID 2，负荷 1 字节，bit0/1/2 依次是 Num / Caps / Scroll。
        0x05.toByte(), 0x08.toByte(), // 用途页：LED
        0x19.toByte(), 0x01.toByte(), // 用途最小：Num Lock
        0x29.toByte(), 0x05.toByte(), // 用途最大：Kana
        0x95.toByte(), 0x05.toByte(), // 报告计数：5（Num/Caps/Scroll/Compose/Kana）
        0x75.toByte(), 0x01.toByte(), // 报告大小：1
        0x91.toByte(), 0x02.toByte(), // 输出：数据、变量、绝对值
        0x95.toByte(), 0x01.toByte(), // 报告计数：1
        0x75.toByte(), 0x03.toByte(), // 报告大小：3（把 5 位补足到一个字节）
        0x91.toByte(), 0x03.toByte(), // 输出：常量、变量、绝对值
        0xC0.toByte(), // 结束集合
    )

    val touchpadDescriptor = byteArrayOf(
        // 触摸板部分（基于 Digitizers Touch Pad 描述符）
        0x05.toByte(), 0x0d.toByte(),                         // USAGE_PAGE (Digitizers)
        0x09.toByte(), 0x05.toByte(),                         // USAGE (Touch Pad)
        0xa1.toByte(), 0x01.toByte(),                         // COLLECTION (Application)
        0x85.toByte(), REPORT_ID_TOUCHPAD.toByte(),           // REPORT_ID (Touch pad)
        0x09.toByte(), 0x22.toByte(),                         // USAGE (Finger)
        0xa1.toByte(), 0x02.toByte(),                         // COLLECTION (Logical)
        0x15.toByte(), 0x00.toByte(),                         // LOGICAL_MINIMUM (0)
        0x25.toByte(), 0x01.toByte(),                         // LOGICAL_MAXIMUM (1)
        0x09.toByte(), 0x47.toByte(),                         // USAGE (Confidence)
        0x09.toByte(), 0x42.toByte(),                         // USAGE (Tip switch)
        0x95.toByte(), 0x02.toByte(),                         // REPORT_COUNT (2)
        0x75.toByte(), 0x01.toByte(),                         // REPORT_SIZE (1)
        0x81.toByte(), 0x02.toByte(),                         // INPUT (Data,Var,Abs)
        0x95.toByte(), 0x01.toByte(),                         // REPORT_COUNT (1)
        0x75.toByte(), 0x02.toByte(),                         // REPORT_SIZE (2)
        0x25.toByte(), 0x02.toByte(),                         // LOGICAL_MAXIMUM (2)
        0x09.toByte(), 0x51.toByte(),                         // USAGE (Contact Identifier)
        0x81.toByte(), 0x02.toByte(),                         // INPUT (Data,Var,Abs)
        0x75.toByte(), 0x01.toByte(),                         // REPORT_SIZE (1)
        0x95.toByte(), 0x04.toByte(),                         // REPORT_COUNT (4)
        0x81.toByte(), 0x03.toByte(),                         // INPUT (Cnst,Var,Abs)
        0x05.toByte(), 0x01.toByte(),                         // USAGE_PAGE (Generic Desk..)
        0x15.toByte(), 0x00.toByte(),                         // LOGICAL_MINIMUM (0)
        0x26.toByte(), 0xff.toByte(), 0x0f.toByte(),          // LOGICAL_MAXIMUM (4095)
        0x75.toByte(), 0x10.toByte(),                         // REPORT_SIZE (16)
        0x55.toByte(), 0x0e.toByte(),                         // UNIT_EXPONENT (-2)
        0x65.toByte(), 0x13.toByte(),                         // UNIT(Inch,EngLinear)
        0x09.toByte(), 0x30.toByte(),                         // USAGE (X)
        0x35.toByte(), 0x00.toByte(),                         // PHYSICAL_MINIMUM (0)
        0x46.toByte(), 0x90.toByte(), 0x01.toByte(),          // PHYSICAL_MAXIMUM (400)
        0x95.toByte(), 0x01.toByte(),                         // REPORT_COUNT (1)
        0x81.toByte(), 0x02.toByte(),                         // INPUT (Data,Var,Abs)
        0x09.toByte(), 0x31.toByte(),                         // USAGE (Y)
        0x81.toByte(), 0x02.toByte(),                         // INPUT (Data,Var,Abs)
        0xc0.toByte(),                                         // END_COLLECTION
        0x55.toByte(), 0x0C.toByte(),                         // UNIT_EXPONENT (-4)
        0x66.toByte(), 0x01.toByte(), 0x10.toByte(),          // UNIT (Seconds)
        0x47.toByte(), 0xff.toByte(), 0xff.toByte(), 0x00.toByte(), 0x00.toByte(), // PHYSICAL_MAXIMUM (65535)
        0x27.toByte(), 0xff.toByte(), 0xff.toByte(), 0x00.toByte(), 0x00.toByte(), // LOGICAL_MAXIMUM (65535)
        0x75.toByte(), 0x10.toByte(),                         // REPORT_SIZE (16)
        0x95.toByte(), 0x01.toByte(),                         // REPORT_COUNT (1)
        0x05.toByte(), 0x0d.toByte(),                         // USAGE_PAGE (Digitizers)
        0x09.toByte(), 0x56.toByte(),                         // USAGE (Scan Time)
        0x81.toByte(), 0x02.toByte(),                         // INPUT (Data,Var,Abs)
        0x09.toByte(), 0x54.toByte(),                         // USAGE (Contact count)
        0x25.toByte(), 0x7f.toByte(),                         // LOGICAL_MAXIMUM (127)
        0x95.toByte(), 0x01.toByte(),                         // REPORT_COUNT (1)
        0x75.toByte(), 0x08.toByte(),                         // REPORT_SIZE (8)
        0x81.toByte(), 0x02.toByte(),                         // INPUT (Data,Var,Abs)
        0x05.toByte(), 0x09.toByte(),                         // USAGE_PAGE (Button)
        0x09.toByte(), 0x01.toByte(),                         // USAGE_(Button 1)
        0x09.toByte(), 0x02.toByte(),                         // USAGE_(Button 2)
        0x09.toByte(), 0x03.toByte(),                         // USAGE_(Button 3)
        0x25.toByte(), 0x01.toByte(),                         // LOGICAL_MAXIMUM (1)
        0x75.toByte(), 0x01.toByte(),                         // REPORT_SIZE (1)
        0x95.toByte(), 0x03.toByte(),                         // REPORT_COUNT (3)
        0x81.toByte(), 0x02.toByte(),                         // INPUT (Data,Var,Abs)
        0x95.toByte(), 0x05.toByte(),                         // REPORT_COUNT (5)
        0x81.toByte(), 0x03.toByte(),                         // INPUT (Cnst,Var,Abs)
        0x05.toByte(), 0x0d.toByte(),                         // USAGE_PAGE (Digitizer)
        0x85.toByte(), REPORT_ID_MAX_COUNT.toByte(),          // REPORT_ID (Feature)
        0x09.toByte(), 0x55.toByte(),                         // USAGE (Contact Count Maximum)
        0x09.toByte(), 0x59.toByte(),                         // USAGE (Pad TYpe)
        0x75.toByte(), 0x04.toByte(),                         // REPORT_SIZE (4)
        0x95.toByte(), 0x02.toByte(),                         // REPORT_COUNT (2)
        0x25.toByte(), 0x0f.toByte(),                         // LOGICAL_MAXIMUM (15)
        0xb1.toByte(), 0x02.toByte(),                         // FEATURE (Data,Var,Abs)
        0x06.toByte(), 0x00.toByte(), 0xff.toByte(),          // USAGE_PAGE (Vendor Defined)
        0x85.toByte(), REPORT_ID_PTPHQA.toByte(),             // REPORT_ID (PTPHQA)
        0x09.toByte(), 0xC5.toByte(),                         // USAGE (Vendor Usage 0xC5)
        0x15.toByte(), 0x00.toByte(),                         // LOGICAL_MINIMUM (0)
        0x26.toByte(), 0xff.toByte(), 0x00.toByte(),          // LOGICAL_MAXIMUM (0xff)
        0x75.toByte(), 0x08.toByte(),                         // REPORT_SIZE (8)
        0x96.toByte(), 0x00.toByte(), 0x01.toByte(),          // REPORT_COUNT (0x100 (256))
        0xb1.toByte(), 0x02.toByte(),                         // FEATURE (Data,Var,Abs)
        0xc0.toByte(),                                     // END_COLLECTION
    )

    /**
     * 经典蓝牙链路注册用的组合描述符：mouseDescriptor + keyboardDescriptor。
     *
     * 当前 129 字节（鼠标 64 + 键盘 65，其中键盘含 18 字节的 LED Output Report），
     * 远低于 256 字节的 SDP 限制。**这条组合只属于经典蓝牙链路**。
     */
    val mouseCombinedDescriptor: ByteArray by lazy {
        mouseDescriptor + keyboardDescriptor
    }

    /**
     * 局域网（USB/IP）链路注册用的组合描述符：touchpadDescriptor + keyboardDescriptor。
     *
     * 当前 227 字节（触摸板 162 + 键盘 65），在 256 字节的 SDP 限制内只剩 29 字节余量 ——
     * SDP 限制只约束经典蓝牙，但这份组合**只属于局域网链路**，不与鼠标组合共用；
     * USB 侧本来也不受 256 字节约束，要扩展直接在这里拼即可。
     */
    val touchpadCombinedDescriptor: ByteArray by lazy {
        touchpadDescriptor + keyboardDescriptor
    }
}