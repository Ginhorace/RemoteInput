package com.github.remotecontrol.util

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 报告描述符的两条硬约束。
 *
 * 1. **总长 ≤ 256 字节**：单个 HID 报告描述符受 SDP 限制，超了主机直接拒绝注册。
 *    这个错误只在配对时才暴露，改一次还得在主机端删除配对重来，所以必须靠单测盯住；
 * 2. **键盘灯必须声明 Output Report**：少这一段，主机就没有任何通道回传
 *    Num / Caps / Scroll，界面上的灯会永远停在未知态（功能看起来「没坏但没用」）。
 */
class HidReportDescriptorsTest {

    @Test
    fun `combinedDescriptorFitsSdpLimit`() {
        val size = HidReportDescriptors.touchpadCombinedDescriptor.size
        assertTrue("组合描述符 $size 字节，超过 256 字节的 SDP 上限", size <= 256)
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
