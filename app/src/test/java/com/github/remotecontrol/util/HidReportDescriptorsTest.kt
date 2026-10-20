package com.github.remotecontrol.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 报告描述符的三条硬约束。
 *
 * 1. **每条组合各自 ≤ 256 字节**：单个 HID 报告描述符受 SDP 限制，超了主机直接拒绝注册。
 *    这个错误只在配对时才暴露，改一次还得在主机端删除配对重来，所以必须靠单测盯住；
 * 2. **两条链路各用一份组合**：经典蓝牙 = 鼠标 + 键盘，局域网 = 触摸板 + 键盘；
 * 3. **键盘灯必须声明 Output Report**：少这一段，主机就没有任何通道回传
 *    Num / Caps / Scroll，界面上的灯会永远停在未知态（功能看起来「没坏但没用」）。
 */
class HidReportDescriptorsTest {

    @Test
    fun `touchpadCombinedDescriptorFitsSdpLimit`() {
        val size = HidReportDescriptors.touchpadCombinedDescriptor.size
        assertTrue("触摸板组合描述符 $size 字节，超过 256 字节的 SDP 上限", size <= 256)
    }

    @Test
    fun `mouseCombinedDescriptorFitsSdpLimit`() {
        val size = HidReportDescriptors.mouseCombinedDescriptor.size
        assertTrue("鼠标组合描述符 $size 字节，超过 256 字节的 SDP 上限", size <= 256)
    }

    /** 鼠标描述符按历史实现固定 64 字节；变了说明集合结构被动过，要同步核对按钮位与滚轮 */
    @Test
    fun `mouseDescriptorHasExpectedSize`() {
        assertEquals(64, HidReportDescriptors.mouseDescriptor.size)
    }

    /**
     * 两条链路取的是不同的组合：鼠标组合里不该出现触摸板的 Feature 报告，
     * 触摸板组合里也不该出现鼠标集合。
     */
    @Test
    fun `linkSpecificCombinedDescriptorsDoNotBleed`() {
        val mouseCombined = HidReportDescriptors.mouseCombinedDescriptor
        val touchpadCombined = HidReportDescriptors.touchpadCombinedDescriptor

        assertTrue(
            "鼠标组合描述符里出现了触摸板 ID ${HidReportDescriptors.REPORT_ID_TOUCHPAD}",
            !mouseCombined.containsSequence(
                intArrayOf(0x85, HidReportDescriptors.REPORT_ID_TOUCHPAD)
            ),
        )
        assertTrue(
            "触摸板组合描述符里出现了鼠标 ID ${HidReportDescriptors.REPORT_ID_MOUSE}",
            !touchpadCombined.containsSequence(
                intArrayOf(0x85, HidReportDescriptors.REPORT_ID_MOUSE)
            ),
        )
    }

    /** 报告 ID 必须两两不同，否则主机会把两条报告当成同一条来解析 */
    @Test
    fun `reportIdsAreDistinct`() {
        val ids = listOf(
            HidReportDescriptors.REPORT_ID_MOUSE,
            HidReportDescriptors.REPORT_ID_KEYBOARD,
            HidReportDescriptors.REPORT_ID_TOUCHPAD,
            HidReportDescriptors.REPORT_ID_MAX_COUNT,
            HidReportDescriptors.REPORT_ID_PTPHQA,
        )
        assertEquals("报告 ID 有重复：$ids", ids.size, ids.toSet().size)
    }

    @Test
    fun `keyboardDescriptorDeclaresLedOutputReport`() {
        assertTrue(
            "键盘描述符里找不到 LED Output Report 块",
            HidReportDescriptors.keyboardDescriptor.containsSequence(LedOutputBlock),
        )
    }

    /** `Usage Page (LED)` 到 `Output (Data,Var,Abs)` 这一段，共 12 字节 */
    private val LedOutputBlock = intArrayOf(
        0x05, 0x08, // Usage Page (LED)
        0x19, 0x01, // Usage Minimum (Num Lock)
        0x29, 0x05, // Usage Maximum (Kana)
        0x95, 0x05, // Report Count (5)
        0x75, 0x01, // Report Size (1)
        0x91, 0x02, // Output (Data, Var, Abs)
    )

    /** [needle] 是否作为连续片段出现在本数组里（描述符是字节流，只能按片段找） */
    private fun ByteArray.containsSequence(needle: IntArray): Boolean =
        indices.any { start ->
            start + needle.size <= size && needle.indices.all { offset ->
                (this[start + offset].toInt() and 0xFF) == needle[offset]
            }
        }
}
