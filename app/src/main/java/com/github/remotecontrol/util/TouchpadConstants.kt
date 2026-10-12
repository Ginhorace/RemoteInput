package com.github.remotecontrol.util

/**
 * 触摸板HID报告常量
 * 对应des.txt中的触摸板描述符
 *
 * 报告格式（共9字节）：
 * Byte 0: [Padding(4)][ContactId(2)][TipSwitch(1)][Confidence(1)]
 * Bytes 1-2: X坐标 (16位, 0-4095)
 * Bytes 3-4: Y坐标 (16位, 0-4095)
 * Bytes 5-6: Scan Time (16位, 0-65535)
 * Byte 7: Contact Count (8位, 0-127)
 * Byte 8: [Padding(5)][Button3(1)][Button2(1)][Button1(1)]
 */
object TouchpadConstants {

    /** 位掩码：Bit 0 - Confidence */
    const val BIT_CONFIDENCE = 0x01
    /** 位掩码：Bit 1 - Tip Switch */
    const val BIT_TIP_SWITCH = 0x02
    /** 位掩码：Bits 2-3 - Contact Identifier */
    const val MASK_CONTACT_ID = 0x0C
    /** 移位：Contact Identifier 左移位数 */
    const val SHIFT_CONTACT_ID = 2

    /** 触摸板X/Y坐标最大值（描述符中定义的逻辑最大值） */
    const val ABSOLUTE_MAX = 4095

    /** 扫描时间最大值（描述符中定义） */
    const val SCAN_TIME_MAX = 65535

    /** 接触数量最大值（描述符中定义） */
    const val CONTACT_COUNT_MAX = 127

    /** 默认接触ID */
    const val CONTACT_ID_DEFAULT = 0

    /**
     * 触摸板按钮常量，这是 Windows Precision Touchpad (PTP) 驱动的按钮映射规则与标准鼠标不同导致的。
     * 对应Byte 8中的位掩码
     */
    object Buttons {
        /** 左键 - Bit 0 */
        const val LEFT: Byte = 0x01.toByte()
        /** 右键 - Bit 2 */
        const val RIGHT: Byte = 0x04.toByte()
    }
}

/**
 * 触摸板HID报告数据类
 */
data class TouchpadReport(
    /** 是否确认触摸（有接触） */
    val confidence: Int = 1,
    /** 是否触尖按下 */
    val tipSwitch: Int = 0,
    /** 接触标识 */
    val contactId: Int = TouchpadConstants.CONTACT_ID_DEFAULT,
    /** 绝对X坐标 (0-4095) */
    val x: Int = 0,
    /** 绝对Y坐标 (0-4095) */
    val y: Int = 0,
    /** 扫描时间 (0-65535) */
    val scanTime: Int = 0,
    /** 接触数量 */
    val contactCount: Int = 1,
    /** 按钮位掩码 */
    val buttons: Byte = 0
) {

    /**
     * 转换为HID报告的字节数组（9字节）
     */
    fun toByteArray(): ByteArray {
        // Byte 0: [padding(4)][contactId(2)][tipSwitch(1)][confidence(1)]
        val byte0 = (confidence and 0x01) or
                ((tipSwitch and 0x01) shl 1) or
                ((contactId and 0x03) shl 2)

        // Bytes 1-2: X (16位大端)
        val xLow = (x and 0xFF).toByte()
        val xHigh = ((x shr 8) and 0xFF).toByte()

        // Bytes 3-4: Y (16位大端)
        val yLow = (y and 0xFF).toByte()
        val yHigh = ((y shr 8) and 0xFF).toByte()

        // Bytes 5-6: Scan Time (16位大端)
        val scanLow = (scanTime and 0xFF).toByte()
        val scanHigh = ((scanTime shr 8) and 0xFF).toByte()

        // Byte 7: Contact Count
        val contactByte = (contactCount and 0x7F).toByte()

        // Byte 8: Buttons (低3位为按钮，高5位为0)
        val buttonsByte = (buttons.toInt() and 0x07).toByte()

        return byteArrayOf(
            byte0.toByte(),
            xLow, xHigh,
            yLow, yHigh,
            scanLow, scanHigh,
            contactByte,
            buttonsByte
        )
    }

    override fun toString(): String {
        return "TouchpadReport(confidence=$confidence, tipSwitch=$tipSwitch, contactId=$contactId, x=$x, y=$y, scanTime=$scanTime, contactCount=$contactCount, buttons=$buttons)"
    }


}
