package com.github.remotecontrol.ui.keyboard

import com.github.remotecontrol.util.HostKeyboardLayout
import com.github.remotecontrol.util.HostLayoutNotice
import com.github.remotecontrol.util.KeyLegend
import com.github.remotecontrol.util.KeyboardConstants
import com.github.remotecontrol.util.KeyboardConstants.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildKeyboardSemantics] 的纯函数单测。
 *
 * 无障碍路径在本机没法真机验证（设备没有 TTS，TalkBack 起不来），所以把「几何 + 图例 →
 * 语义节点」这一段下沉成纯函数，用单测守住最容易错的几件事：节点数量与顺序、位置原样搬运、
 * 播报用图例而不是 Usage ID、占位键不产生节点。
 */
class KeyboardSemanticsTest {

    @Test
    fun `every hit area becomes one item in the same order`() {
        val rows = listOf(listOf(Keys.A, Keys.B), listOf(Keys.C))
        val geometry = geometryOf(rows)

        val items = buildKeyboardSemantics(geometry.hitAreas, legendsOf(rows))

        assertEquals(3, items.size)
        assertEquals(
            listOf(Keys.A.value, Keys.B.value, Keys.C.value),
            items.map { it.value },
        )
        // 位置原样搬运：语义层与绘制层读的是同一份矩形
        assertEquals(geometry.hitAreas.map { it.rect }, items.map { it.rect })
    }

    @Test
    fun `description is the keycap legend so non-US layouts read what is printed`() {
        // AZERTY：物理位置 0x04（US 的 A）键帽印的是 q，角标 a
        val layout = HostKeyboardLayout(
            id = "azerty",
            legends = mapOf(0x04 to KeyLegend(label = "q", subLabel = "a")),
            notice = HostLayoutNotice.NONE,
        )
        val rows = listOf(listOf(Keys.A))
        val geometry = geometryOf(rows)

        val item = buildKeyboardSemantics(geometry.hitAreas, legendsOf(rows, layout)).single()

        assertEquals("q", item.description)
        // 播报图例，但送出去的仍是该物理位置的 Usage ID
        assertEquals(Keys.A.value, item.value)
    }

    @Test
    fun `description falls back to the key name without a legend entry`() {
        val rows = listOf(listOf(Keys.ESC))
        val geometry = geometryOf(rows)

        val item = buildKeyboardSemantics(geometry.hitAreas, emptyMap()).single()

        assertEquals(Keys.ESC.displayName, item.description)
    }

    @Test
    fun `spacer produces no semantic node`() {
        val spacer = KeyboardConstants.Key(displayName = "", value = 0, isSpacer = true)
        val rows = listOf(listOf(spacer, Keys.ARROW_UP, spacer))
        val geometry = geometryOf(rows)

        val items = buildKeyboardSemantics(geometry.hitAreas, legendsOf(rows))

        // 方向键反 T 形两侧的留白不可点，也就不该被读屏念到
        assertEquals(listOf(Keys.ARROW_UP.value), items.map { it.value })
    }

    @Test
    fun `unmeasured container produces no items`() {
        assertTrue(buildKeyboardSemantics(emptyList(), emptyMap()).isEmpty())
    }

    /** 几何走与绘制层相同的入口，语义层的输入因此就是绘制层看到的那一份 */
    private fun geometryOf(rows: List<List<KeyboardConstants.Key>>) = buildKeyboardGeometry(
        width = 300f,
        height = 100f,
        rows = rows,
        keyHeightPx = 20f,
        minKeyHeightPx = 1f,
        maxKeyHeightPx = 100f,
        rowSpacingPx = 0f,
        keySpacingPx = 0f,
        contentPaddingPx = 0f,
        centerVertically = false,
    )

    /** 复刻 `KeyboardCanvas` 取图例的方式；占位键没有图例，天然被排除 */
    private fun legendsOf(
        rows: List<List<KeyboardConstants.Key>>,
        layout: HostKeyboardLayout = HostKeyboardLayout(id = "us", notice = HostLayoutNotice.NONE),
    ): Map<KeyboardConstants.Key, KeyLegend> =
        rows.flatten().filterNot { it.isSpacer }.associateWith { layout.legendOf(it) }
}
