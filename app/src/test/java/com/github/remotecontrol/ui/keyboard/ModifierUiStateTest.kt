package com.github.remotecontrol.ui.keyboard

import com.github.remotecontrol.util.KeyboardConstants.ModifierKeyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 修饰键状态迁移与修饰位合并的单测。
 *
 * 对应需求里的两条交互约定：**按下生效**（按住期间有效）与**长按锁定**（常驻有效，
 * 锁定态下点按解锁）。修饰位必须始终是 held ∪ locked 的并集。
 */
class ModifierUiStateTest {

    private val ctrl = ModifierKeyType.LeftControl
    private val shift = ModifierKeyType.LeftShift
    private val alt = ModifierKeyType.LeftAlt
    private val win = ModifierKeyType.LeftGUI

    @Test
    fun `empty state reports a clear modifier byte`() {
        assertEquals(0, ModifierUiState().reportByte.toInt())
    }

    @Test
    fun `held and locked are merged into the modifier byte`() {
        val state = ModifierUiState(held = setOf(ctrl), locked = setOf(shift, alt))

        // 0x01(左Ctrl) | 0x02(左Shift) | 0x04(左Alt) = 0x07
        assertEquals(0x07, state.reportByte.toInt())
        assertTrue(state.isActive(ctrl))
        assertTrue(state.isActive(shift))
        assertFalse(state.isLocked(ctrl))
        assertTrue(state.isLocked(shift))
    }

    @Test
    fun `all eight modifier types map to their own bit`() {
        val expected = mapOf(
            ModifierKeyType.LeftControl to 0x01,
            ModifierKeyType.LeftShift to 0x02,
            ModifierKeyType.LeftAlt to 0x04,
            ModifierKeyType.LeftGUI to 0x08,
            ModifierKeyType.RightControl to 0x10,
            ModifierKeyType.RightShift to 0x20,
            ModifierKeyType.RightAlt to 0x40,
            ModifierKeyType.RightGUI to 0x80,
        )

        expected.forEach { (type, bit) ->
            // Byte 是有符号的：0x80（右 GUI）读出来是 -128，所以按无符号位比较
            val actual = ModifierUiState(held = setOf(type)).reportByte.toInt() and 0xFF
            assertEquals("$type 的位定义不对", bit, actual)
        }
    }

    @Test
    fun `press is momentary and release drops it`() {
        val pressed = ModifierUiState().press(ctrl)
        assertTrue(pressed.isActive(ctrl))
        assertEquals(0x01, pressed.reportByte.toInt())

        val released = pressed.release(ctrl, wasLockedAtPress = false)
        assertFalse(released.isActive(ctrl))
        assertEquals(0, released.reportByte.toInt())
    }

    @Test
    fun `long press locks the modifier and clears the momentary hold`() {
        val locked = ModifierUiState().press(shift).lock(shift)

        assertTrue(locked.isLocked(shift))
        assertTrue(locked.held.isEmpty())
        assertEquals(0x02, locked.reportByte.toInt())
    }

    @Test
    fun `long pressing a locked modifier unlocks it`() {
        val unlocked = ModifierUiState(locked = setOf(alt)).press(alt).lock(alt)

        assertFalse(unlocked.isActive(alt))
        assertEquals(0, unlocked.reportByte.toInt())
    }

    @Test
    fun `tapping a locked modifier unlocks it on release`() {
        val state = ModifierUiState(locked = setOf(win))

        // 点按期间仍生效（held 加入后并集不变），抬起时才解锁
        val pressed = state.press(win)
        assertTrue(pressed.isActive(win))
        assertEquals(0x08, pressed.reportByte.toInt())

        val released = pressed.release(win, wasLockedAtPress = true)
        assertFalse(released.isActive(win))
        assertEquals(0, released.reportByte.toInt())
    }

    @Test
    fun `multiple modifiers can be held and locked at the same time`() {
        val state = ModifierUiState()
            .press(ctrl)
            .press(shift)
            .lock(alt)

        assertTrue(state.held.containsAll(listOf(ctrl, shift)))
        assertTrue(state.isLocked(alt))
        assertEquals(0x07, state.reportByte.toInt())

        val released = state.release(ctrl, wasLockedAtPress = false)
        assertEquals(0x06, released.reportByte.toInt())
    }
}
