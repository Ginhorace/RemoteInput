package com.github.remotecontrol.ui.keyboard

import com.github.remotecontrol.util.KeyboardConstants
import com.github.remotecontrol.util.KeyboardConstants.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildKeyboardGeometry] 的纯函数单测。
 *
 * 这里覆盖的是键盘布局里最容易写错、又最难靠肉眼发现的部分：
 * 行内权重分配、键间距、自适应键高的 clamp、垂直居中偏移。
 */
class KeyboardCanvasGeometryTest {

    private val unitRow = listOf(listOf(Keys.A))

    @Test
    fun `keys fill row width and respect weight`() {
        val geometry = buildGeometry(
            rows = listOf(listOf(Keys.A, Keys.SPACE)),
            width = 200f,
            keyHeightPx = 10f,
        )

        val (first, second) = geometry.hitAreas
        // Keys.A 权重 1，Keys.SPACE 权重 3 → 宽度 50 / 150，右边界正好落在容器右边缘
        assertEquals(Keys.A, first.key)
        assertEquals(Keys.SPACE, second.key)
        assertEquals(50f, first.rect.width, TOLERANCE)
        assertEquals(150f, second.rect.width, TOLERANCE)
        assertEquals(0f, first.rect.left, TOLERANCE)
        assertEquals(50f, second.rect.left, TOLERANCE)
        assertEquals(200f, second.rect.right, TOLERANCE)
    }

    @Test
    fun `key spacing is subtracted from available width`() {
        val geometry = buildGeometry(
            rows = listOf(listOf(Keys.A, Keys.B)),
            width = 100f,
            keyHeightPx = 10f,
            keySpacingPx = 10f,
        )

        val (first, second) = geometry.hitAreas
        // 可用宽 100 - 1 个间距 10 = 90，两个等权键各 45
        assertEquals(45f, first.rect.width, TOLERANCE)
        assertEquals(45f, second.rect.width, TOLERANCE)
        assertEquals(55f, second.rect.left, TOLERANCE)
        assertEquals(100f, second.rect.right, TOLERANCE)
    }

    @Test
    fun `auto key height is clamped to max and content is centered`() {
        val geometry = buildGeometry(
            rows = List(4) { unitRow.first() },
            width = 300f,
            height = 1000f,
            contentPaddingPx = 10f,
            rowSpacingPx = 4f,
            minKeyHeightPx = 20f,
            maxKeyHeightPx = 40f,
            centerVertically = true,
        )

        geometry.hitAreas.forEach { assertEquals(40f, it.rect.height, TOLERANCE) }
        // 内容高 4*40 + 3*4 + 2*10 = 192 → 垂直居中偏移 (1000-192)/2 = 404，再叠加内边距 10
        assertEquals(414f, geometry.hitAreas.first().rect.top, TOLERANCE)
        // 第二行紧跟前一行一个行距
        assertEquals(458f, geometry.hitAreas[1].rect.top, TOLERANCE)
    }

    @Test
    fun `auto key height is clamped to min and never pushed off the top`() {
        val geometry = buildGeometry(
            rows = List(6) { unitRow.first() },
            width = 300f,
            height = 100f,
            contentPaddingPx = 4f,
            rowSpacingPx = 2f,
            minKeyHeightPx = 20f,
            maxKeyHeightPx = 40f,
        )

        // 82 / 6 = 13.7 px 低于下限 → 取 20；内容高 138 > 容器高 100，偏移必须夹到 0
        geometry.hitAreas.forEach { assertEquals(20f, it.rect.height, TOLERANCE) }
        assertEquals(4f, geometry.hitAreas.first().rect.top, TOLERANCE)
    }

    @Test
    fun `explicit key height wins over auto sizing`() {
        val geometry = buildGeometry(
            rows = List(4) { unitRow.first() },
            width = 300f,
            height = 1000f,
            keyHeightPx = 30f,
            minKeyHeightPx = 20f,
            maxKeyHeightPx = 40f,
        )

        geometry.hitAreas.forEach { assertEquals(30f, it.rect.height, TOLERANCE) }
    }

    @Test
    fun `spacer keeps its width but produces no hit area`() {
        val spacer = KeyboardConstants.Key(displayName = "", value = 0, isSpacer = true)
        val geometry = buildGeometry(
            rows = listOf(listOf(spacer, Keys.ARROW_UP, spacer)),
            width = 300f,
            keyHeightPx = 20f,
        )

        // 三列等宽：占位只把 ↑ 顶到中间一列（100..200），两侧留白且不可点
        assertEquals(1, geometry.hitAreas.size)
        assertEquals(Keys.ARROW_UP, geometry.hitAreas.single().key)
        assertEquals(100f, geometry.hitAreas.single().rect.left, TOLERANCE)
        assertEquals(200f, geometry.hitAreas.single().rect.right, TOLERANCE)
    }

    @Test
    fun `row span key is two rows tall and lines up with the next row bottom`() {
        val spacer = KeyboardConstants.Key(displayName = "", value = 0, isSpacer = true)
        val tallKey = Keys.KP_PLUS.copy(rowSpan = 2)
        val geometry = buildGeometry(
            rows = listOf(
                listOf(Keys.KP_NUM_7, tallKey),
                listOf(Keys.KP_NUM_4, spacer),
            ),
            width = 200f,
            keyHeightPx = 20f,
            rowSpacingPx = 4f,
        )

        // 跨行键高 = 2×20 + 1 个行距 4 = 44，正好顶到下一行的底边
        val tall = geometry.hitAreas.first { it.key == tallKey }
        assertEquals(0f, tall.rect.top, TOLERANCE)
        assertEquals(44f, tall.rect.bottom, TOLERANCE)

        val nextRowKey = geometry.hitAreas.first { it.key == Keys.KP_NUM_4 }
        assertEquals(24f, nextRowKey.rect.top, TOLERANCE)
        assertEquals(tall.rect.bottom, nextRowKey.rect.bottom, TOLERANCE)

        // 占位键不进命中区域：两行共 4 个条目，其中 1 个占位 → 3 个键位
        assertEquals(3, geometry.hitAreas.size)
    }

    @Test
    fun `no areas for empty rows or unmeasured container`() {
        assertTrue(buildGeometry(rows = emptyList(), width = 200f, keyHeightPx = 10f).hitAreas.isEmpty())
        assertTrue(buildGeometry(rows = unitRow, width = 0f, keyHeightPx = 10f).hitAreas.isEmpty())
        assertTrue(buildGeometry(rows = unitRow, width = 200f, height = 0f, keyHeightPx = 10f).hitAreas.isEmpty())
    }

    private fun buildGeometry(
        rows: List<List<KeyboardConstants.Key>>,
        width: Float,
        height: Float = 100f,
        keyHeightPx: Float? = null,
        minKeyHeightPx: Float = 1f,
        maxKeyHeightPx: Float = 100f,
        rowSpacingPx: Float = 0f,
        keySpacingPx: Float = 0f,
        contentPaddingPx: Float = 0f,
        centerVertically: Boolean = false,
    ) = buildKeyboardGeometry(
        width = width,
        height = height,
        rows = rows,
        keyHeightPx = keyHeightPx,
        minKeyHeightPx = minKeyHeightPx,
        maxKeyHeightPx = maxKeyHeightPx,
        rowSpacingPx = rowSpacingPx,
        keySpacingPx = keySpacingPx,
        contentPaddingPx = contentPaddingPx,
        centerVertically = centerVertically,
    )

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
