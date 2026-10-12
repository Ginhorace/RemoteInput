package com.github.remotecontrol.util

import com.github.remotecontrol.util.KeyboardConstants.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 键盘 LED 的解析与「哪颗键挂哪盏灯」。
 *
 * 主机给的 `data` 形态不止一种（见 [KeyboardConstants.parseKeyboardLeds] 的表格），三种都要认；
 * 位定义必须与描述符里 `Usage Min 0x01 (Num Lock) …` 的顺序一致，
 * 错一位就会出现「按 Caps 亮 Num」这种错位 —— 只有真机能看出来，所以钉在单测里。
 */
class KeyboardLedsTest {

    @Test
    fun `reportProtocolPayloadWithoutReportId`() {
        assertEquals(
            "0x00：三个灯都灭",
            KeyboardConstants.KeyboardLeds(),
            KeyboardConstants.parseKeyboardLeds(byteArrayOf(0x00)),
        )
        assertEquals(
            "0x02：只有 Caps",
            KeyboardConstants.KeyboardLeds(capsLock = true),
            KeyboardConstants.parseKeyboardLeds(byteArrayOf(0x02)),
        )
        assertEquals(
            "0x07：三个灯全亮",
            KeyboardConstants.KeyboardLeds(numLock = true, capsLock = true, scrollLock = true),
            KeyboardConstants.parseKeyboardLeds(byteArrayOf(0x07)),
        )
    }

    @Test
    fun `reportProtocolPayloadWithReportId`() {
        // 首字节是键盘报告 ID（2）时，位图在第二个字节
        assertEquals(
            KeyboardConstants.KeyboardLeds(capsLock = true),
            KeyboardConstants.parseKeyboardLeds(byteArrayOf(0x02, 0x02)),
        )
        assertEquals(
            KeyboardConstants.KeyboardLeds(numLock = true),
            KeyboardConstants.parseKeyboardLeds(byteArrayOf(0x02, 0x01)),
        )
    }

    @Test
    fun `bootProtocolSingleByte`() {
        // Boot 协议报告 ID 为 0，负荷只有 1 字节；单字节与「含报告 ID」不会撞车
        assertEquals(
            KeyboardConstants.KeyboardLeds(numLock = true, scrollLock = true),
            KeyboardConstants.parseKeyboardLeds(byteArrayOf(0x05)),
        )
    }

    @Test
    fun `highBitsDoNotLeakIntoTheThreeLamps`() {
        // 0x80 是 Byte 的符号位（对应 HID 的 Compose 位之后），不能被算成某个灯亮
        assertEquals(
            KeyboardConstants.KeyboardLeds(),
            KeyboardConstants.parseKeyboardLeds(byteArrayOf(0x80.toByte())),
        )
    }

    @Test
    fun `emptyDataIsNotAReport`() {
        assertNull(KeyboardConstants.parseKeyboardLeds(null))
        assertNull(KeyboardConstants.parseKeyboardLeds(ByteArray(0)))
    }

    @Test
    fun `numLockUsesTheStandardUsageCode`() {
        // 0x53 = Keypad Num Lock and Clear（Keyboard/Keypad 用途页）。
        // 描述符早已把按键用途范围声明成 0x00–0x65，所以这颗键一个描述符字节都不占
        assertEquals(0x53.toByte(), Keys.NUM_LOCK.value)
    }

    @Test
    fun `only the three lock keys carry a lamp`() {
        val allOn = KeyboardConstants.KeyboardLeds(
            numLock = true,
            capsLock = true,
            scrollLock = true,
        )

        assertEquals("Caps 挂灯", true, allOn.lampFor(Keys.CAPS_LOCK.value))
        assertEquals("ScrLk 挂灯", true, allOn.lampFor(Keys.SCROLL_LOCK.value))
        assertEquals("NumLk 挂灯", true, allOn.lampFor(Keys.NUM_LOCK.value))

        // 其余键必须返回 null（不是 false）：返回 false 等于在说「这颗键的灯是灭的」，
        // 那是画布没画点、但语义已经错了
        assertNull("字母键不挂灯", allOn.lampFor(Keys.A.value))
        assertNull("小键盘数字键不挂灯", allOn.lampFor(Keys.KP_NUM_1.value))
    }

    @Test
    fun `each lamp follows its own key`() {
        val onlyCaps = KeyboardConstants.KeyboardLeds(capsLock = true)

        assertEquals(true, onlyCaps.lampFor(Keys.CAPS_LOCK.value))
        assertEquals(false, onlyCaps.lampFor(Keys.NUM_LOCK.value))
        assertEquals(false, onlyCaps.lampFor(Keys.SCROLL_LOCK.value))
    }

    @Test
    fun `lock keys toggle their own lamp`() {
        val off = KeyboardConstants.KeyboardLeds()

        assertEquals(KeyboardConstants.KeyboardLeds(capsLock = true), off.toggledBy(Keys.CAPS_LOCK.value))
        assertEquals(KeyboardConstants.KeyboardLeds(numLock = true), off.toggledBy(Keys.NUM_LOCK.value))
        assertEquals(KeyboardConstants.KeyboardLeds(scrollLock = true), off.toggledBy(Keys.SCROLL_LOCK.value))

        // 锁键是翻转语义：再按一次翻回来
        assertEquals(off, KeyboardConstants.KeyboardLeds(capsLock = true).toggledBy(Keys.CAPS_LOCK.value))

        // 不是锁键一律返回 null —— 含抬起报文与空报告（它们的 keyCode 是 0），
        // 返回 null 才能保证键盘上随便敲一个字母不会把某个灯翻反
        assertNull("普通键不翻灯", off.toggledBy(Keys.A.value))
        assertNull("keyCode = 0 是抬起 / 空报告", off.toggledBy(0))
    }

    @Test
    fun `press inference only applies before the host confirms`() {
        val unconfirmed = KeyboardConstants.KeyboardLedsState()
        assertEquals(
            KeyboardConstants.KeyboardLedsState(KeyboardConstants.KeyboardLeds(capsLock = true)),
            unconfirmed.inferredAfterPress(Keys.CAPS_LOCK.value),
        )

        // 主机确认过就交给主机：它自己会把新灯态推回来，本地抢先改只会先闪一下错的
        val confirmed = KeyboardConstants.KeyboardLedsState(
            leds = KeyboardConstants.KeyboardLeds(capsLock = true),
            confirmed = true,
        )
        assertEquals(confirmed, confirmed.inferredAfterPress(Keys.NUM_LOCK.value))

        // 非锁键原样返回
        assertEquals(unconfirmed, unconfirmed.inferredAfterPress(Keys.A.value))
    }

    @Test
    fun `demote keeps the value but drops the confirmation`() {
        val confirmed = KeyboardConstants.KeyboardLedsState(
            leds = KeyboardConstants.KeyboardLeds(numLock = true),
            confirmed = true,
        )
        val demoted = confirmed.demoted()

        assertEquals("值要留着 —— 它是下次连接唯一的初值", KeyboardConstants.KeyboardLeds(numLock = true), demoted.leds)
        assertEquals("但不能再冒充主机确认过的值", false, demoted.confirmed)
        assertEquals("已经是推测态就原样返回", demoted, demoted.demoted())
    }
}
