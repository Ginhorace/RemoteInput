package com.github.remotecontrol.util

/**
 * 蓝牙HID设备报告描述符
 * 包含键盘和触摸板的报告描述符
 */
object HidReportDescriptors {

    const val REPORT_ID_KEYBOARD = 2
    const val REPORT_ID_TOUCHPAD = 4
    const val REPORT_ID_MAX_COUNT = 5
    const val REPORT_ID_PTPHQA = 6

    /** 触摸板输入报告长度（字节），格式见 [TouchpadConstants] */
    const val TOUCHPAD_REPORT_SIZE = 9

    /** 键盘输入报告长度（字节）：1 修饰键位 + 1 保留位 + 6 键码 */
    const val KEYBOARD_REPORT_SIZE = 8

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
     * 注册用的组合描述符：touchpadDescriptor + keyboardDescriptor
     *
     * 注意：单个描述符不能超过 256 字节（SDP 限制），
     * 当前为 227 字节（触摸板 162 + 键盘 65，其中键盘含 18 字节的 LED Output Report），
     * 余量只剩 29 字节，鼠标描述符（64 字节）无法再一并塞进来。
     */
    val touchpadCombinedDescriptor: ByteArray by lazy {
        touchpadDescriptor + keyboardDescriptor
    }
}