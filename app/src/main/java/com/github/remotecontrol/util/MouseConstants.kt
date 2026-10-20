package com.github.remotecontrol.util

/**
 * 鼠标报告的按钮与滚轮常量，对应 [HidReportDescriptors.mouseDescriptor]。
 *
 * 与触摸板（PTP）那套的区别：鼠标按钮就是标准 HID 按钮位（右键 = Bit1），
 * 而不像 PTP 那样右键落在 Button3 上 —— 两套语义不能互相套用。
 */
object MouseConstants {

    /** 鼠标按钮位掩码，对应报告第 1 字节 */
    object Buttons {
        const val LEFT: Byte = 0x01.toByte()
        const val RIGHT: Byte = 0x02.toByte()
        const val MIDDLE: Byte = 0x04.toByte()
        const val BUTTON_4: Byte = 0x08.toByte()
        const val BUTTON_5: Byte = 0x10.toByte()
    }

    /** 滚轮方向，对应报告第 4 字节（有符号，正为向上） */
    object Wheel {
        const val UP: Byte = 0x01.toByte()
        const val DOWN: Byte = (-1).toByte()
    }

    /** 相对位移的单帧上下限（描述符里逻辑范围是 −127..127） */
    const val DELTA_MAX = 127
    const val DELTA_MIN = -127
}

/**
 * 鼠标输入报告（4 字节）：buttons / dx / dy / wheel。
 *
 * [dx] 与 [dy] 是**相对位移**，不是坐标；[wheel] 一帧一格，0 表示不滚。
 */
data class MouseReport(
    val buttons: Byte = 0,
    val x: Byte = 0,
    val y: Byte = 0,
    val wheel: Byte = 0,
) {

    fun toByteArray(): ByteArray = byteArrayOf(buttons, x, y, wheel)

    override fun toString(): String {
        return "MouseReport(buttons=$buttons, x=$x, y=$y, wheel=$wheel)"
    }
}
