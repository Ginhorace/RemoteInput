package com.github.remotecontrol.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 鼠标报告的字节布局（4 字节：buttons / dx / dy / wheel）。
 *
 * 布局错了不会崩，只会表现为「点了没反应 / 往反方向跑」，主机侧极难看出原因，
 * 所以固定在这里。
 */
class MouseReportTest {

    @Test
    fun `emptyReportIsFourZeroBytes`() {
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0),
            MouseReport().toByteArray(),
        )
    }

    @Test
    fun `layoutIsButtonsDeltaXDeltaYWheel`() {
        val report = MouseReport(
            buttons = MouseConstants.Buttons.RIGHT,
            x = 5,
            y = (-3).toByte(),
            wheel = MouseConstants.Wheel.DOWN,
        )
        assertArrayEquals(
            byteArrayOf(MouseConstants.Buttons.RIGHT, 5, (-3).toByte(), (-1).toByte()),
            report.toByteArray(),
        )
    }

    @Test
    fun `buttonsAreIndependentBits`() {
        val combined = (
            MouseConstants.Buttons.LEFT.toInt() or
                MouseConstants.Buttons.MIDDLE.toInt() or
                MouseConstants.Buttons.BUTTON_5.toInt()
            ).toByte()
        assertEquals(0x15, combined.toInt())
    }

    @Test
    fun `wheelDirectionsAreSigned`() {
        assertEquals(1, MouseConstants.Wheel.UP.toInt())
        assertEquals(-1, MouseConstants.Wheel.DOWN.toInt())
    }

    @Test
    fun `deltaRangeMatchesDescriptor`() {
        assertEquals(127, MouseConstants.DELTA_MAX)
        assertEquals(-127, MouseConstants.DELTA_MIN)
    }
}
