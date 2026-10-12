package com.github.remotecontrol.ui.keyboard

import com.github.remotecontrol.util.KeyboardConstants
import com.github.remotecontrol.util.KeyboardConstants.Keys
import com.github.remotecontrol.util.KeyboardLayoutCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键盘行定义的不变量校验。
 *
 * 这些断言对应的是「重写键盘界面」时定下的几条硬约束：修饰键必须全部移出键盘页、
 * 竖屏两半与横屏主键盘的键集必须一致、每一页都不能重复出现同一个键。
 */
class KeyboardLayoutsTest {

    private val portraitHalfKeys =
        (KeyboardLayouts.portraitLeftHalfRows + KeyboardLayouts.portraitRightHalfRows).flatten()

    private val landscapeKeys = KeyboardLayouts.landscapeMainRows.flatten()

    /** 功能键区里过滤掉布局占位：占位会在多行里重复出现，且不是真的按键 */
    private val functionKeys = KeyboardLayouts.functionRows.flatten().filterNot { it.isSpacer }

    /** 小键盘区过滤掉布局占位后剩下的真按键（占位会在多行里重复出现） */
    private val numpadKeys = KeyboardLayouts.numpadRows.flatten().filterNot { it.isSpacer }

    @Test
    fun `no layout contains modifier keys`() {
        // Ctrl / Shift / Alt / Win 已移到固定修饰键区，键盘页里再出现就是回归
        val allKeys = portraitHalfKeys + landscapeKeys + functionKeys + numpadKeys
        val modifiers = allKeys.filter { it.isModifier }
        assertTrue("键盘页里不应出现修饰键，实际出现：$modifiers", modifiers.isEmpty())
    }

    @Test
    fun `portrait halves cover the same key set as the landscape main keyboard`() {
        // 两种方向的差别只应在 ENTER 的位置上，键集必须完全一致
        assertEquals(portraitHalfKeys.toSet(), landscapeKeys.toSet())
    }

    @Test
    fun `no key appears twice within a layout`() {
        assertNoDuplicates("竖屏两半", portraitHalfKeys)
        assertNoDuplicates("横屏主键盘", landscapeKeys)
        assertNoDuplicates("功能键区", functionKeys)
        assertNoDuplicates("小键盘区", numpadKeys)
    }

    @Test
    fun `pages do not share keys`() {
        // 主键盘 / 功能键 / 小键盘三页各管一段，重复会让同一个键出现在两个页面上
        val mainKeys = portraitHalfKeys.toSet() + landscapeKeys.toSet()
        assertTrue("功能键区与主键盘重复：${mainKeys intersect functionKeys.toSet()}",
            (mainKeys intersect functionKeys.toSet()).isEmpty())
        assertTrue("小键盘区与主键盘重复：${mainKeys intersect numpadKeys.toSet()}",
            (mainKeys intersect numpadKeys.toSet()).isEmpty())
    }

    @Test
    fun `half keyboards are six rows each and end with space and enter`() {
        // 两半行数相同才能行高对齐；末行的宽键让右半第 6 行不至于空着
        assertEquals(6, KeyboardLayouts.portraitLeftHalfRows.size)
        assertEquals(6, KeyboardLayouts.portraitRightHalfRows.size)
        assertEquals(listOf(Keys.SPACE), KeyboardLayouts.portraitLeftHalfRows.last())
        assertEquals(listOf(Keys.ENTER), KeyboardLayouts.portraitRightHalfRows.last())
    }

    @Test
    fun `function and numpad pages keep their row counts`() {
        // 功能键区 3 行功能键 + 2 行方向键（反 T 形）；小键盘 5 行（含跨行的 + / Enter）
        assertEquals(5, KeyboardLayouts.functionRows.size)
        assertEquals(5, KeyboardLayouts.numpadRows.size)
        assertEquals(6, KeyboardLayouts.landscapeMainRows.size)
    }

    @Test
    fun `numpad mimics a physical number pad`() {
        val rows = KeyboardLayouts.numpadRows

        // 每行总权重一致（各 4 格），列才会对齐
        rows.forEach { row ->
            assertEquals("小键盘每行总权重应一致", 4.0, row.sumOf { it.weight.toDouble() }, 0.001)
        }

        // 首行左侧是 Num Lock（与实体数字区同位置），/ * - 正好落在 789 上方
        assertEquals(Keys.NUM_LOCK, rows[0].first())
        assertEquals(listOf(Keys.KP_DIVIDE, Keys.KP_MULTIPLY, Keys.KP_MINUS), rows[0].drop(1))

        // KP+ 与 KP Enter 各跨两行，各自落在最右一列，下一行同列用占位让出位置
        assertEquals(Keys.KP_PLUS.value, rows[1].last().value)
        assertEquals(2, rows[1].last().rowSpan)
        assertTrue("KP+ 下半行同列应为占位", rows[2].last().isSpacer)

        assertEquals(Keys.KP_ENTER.value, rows[3].last().value)
        assertEquals(2, rows[3].last().rowSpan)
        assertTrue("Enter 下半行同列应为占位", rows[4].last().isSpacer)

        // 末行：KP0 两格宽 + KP. 一格 + 占位一格
        assertEquals(Keys.KP_NUM_0.value, rows[4].first().value)
        assertEquals(2f, rows[4].first().weight, 0.001f)
        assertEquals(Keys.KP_DOT.value, rows[4][1].value)
    }

    @Test
    fun `arrow keys form an inverted T like a physical keyboard`() {
        val rows = KeyboardLayouts.functionRows
        val upRow = rows[rows.size - 2]
        val bottomRow = rows.last()

        // ↑ 单独一行且居中（两侧必须是占位），下面一行是 ← ↓ →，与三列功能键对齐
        assertEquals(Keys.ARROW_UP, upRow[1])
        assertTrue("↑ 两侧应为占位键，实际是 ${upRow.map { it.displayName }}",
            upRow[0].isSpacer && upRow[2].isSpacer)
        assertEquals(listOf(Keys.ARROW_LEFT, Keys.ARROW_DOWN, Keys.ARROW_RIGHT), bottomRow)
    }

    @Test
    fun `spacer keys carry no HID code`() {
        val spacers = KeyboardLayouts.functionRows.flatten().filter { it.isSpacer }
        assertTrue("方向键区应当有占位键", spacers.isNotEmpty())
        // 占位不是真按键：它既不该被绘制，也不该发出任何键码
        assertTrue("占位键不能带 HID 编码", spacers.all { it.value.toInt() == 0 })
    }

    @Test
    fun `US layout reuses the very same list instances`() {
        // KeyboardCanvas 拿 rows 当 remember 的 key：US 必须拿回上面那几个单例引用，
        // 否则每次组合都会重算整张键盘的几何与逐键文本测量
        val rows = KeyboardLayouts.mainRows(KeyboardLayoutCatalog.US)
        assertSame(KeyboardLayouts.portraitLeftHalfRows, rows.portraitLeftHalf)
        assertSame(KeyboardLayouts.portraitRightHalfRows, rows.portraitRightHalf)
        assertSame(KeyboardLayouts.landscapeMainRows, rows.landscape)
    }

    @Test
    fun `every layout keeps the original invariants`() {
        for (layout in KeyboardLayoutCatalog.ALL) {
            val rows = KeyboardLayouts.mainRows(layout)
            val halves = (rows.portraitLeftHalf + rows.portraitRightHalf).flatten()
            val landscape = rows.landscape.flatten()

            // 两半行数相同才能行高对齐；横屏整块也是 6 行
            assertEquals(layout.id, 6, rows.portraitLeftHalf.size)
            assertEquals(layout.id, 6, rows.portraitRightHalf.size)
            assertEquals(layout.id, 6, rows.landscape.size)

            // 两种方向的差别仍然只应在 ENTER 的位置上，键集必须完全一致
            assertEquals(layout.id, halves.toSet(), landscape.toSet())

            // 主键盘里不出现修饰键，也不出现重复键
            assertTrue(layout.id, halves.none { it.isModifier })
            assertNoDuplicates("${layout.id} 竖屏两半", halves)
            assertNoDuplicates("${layout.id} 横屏主键盘", landscape)
        }
    }

    @Test
    fun `extra keys sit immediately left of enter`() {
        for (layout in KeyboardLayoutCatalog.ALL) {
            if (layout.extraKeys.isEmpty()) continue
            val rows = KeyboardLayouts.mainRows(layout)

            // 横屏：主行行尾是「新键… ENTER」
            val landscapeHomeRow = rows.landscape[3]
            val enterIndex = landscapeHomeRow.indexOf(Keys.ENTER)
            assertTrue(layout.id, enterIndex > 0)
            assertEquals(
                layout.id,
                layout.extraKeys,
                landscapeHomeRow.subList(enterIndex - layout.extraKeys.size, enterIndex),
            )

            // 竖屏：右半末行原本只有 ENTER，新键同样插在它左边（行数不变）
            val portraitLastRow = rows.portraitRightHalf.last()
            assertEquals(layout.id, Keys.ENTER, portraitLastRow.last())
            assertEquals(layout.id, layout.extraKeys, portraitLastRow.dropLast(1))
        }
    }

    private fun assertNoDuplicates(layoutName: String, keys: List<KeyboardConstants.Key>) {
        val duplicates = keys.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue("$layoutName 里出现了重复键：$duplicates", duplicates.isEmpty())
    }
}
