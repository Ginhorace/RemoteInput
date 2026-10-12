package com.github.remotecontrol.util

import com.github.remotecontrol.util.KeyboardConstants.Key
import com.github.remotecontrol.util.KeyboardConstants.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 宿主键盘布局清单的校验。
 *
 * 守两类东西：
 *
 * 1. **一致性**：界面语言 → 布局、设置项 [KeyboardLayoutMode] → 布局、[KeyboardLayoutCatalog.ALL]
 *    三者必须对得上，新增语言或新增布局时先在这里红；
 * 2. **结构正确性**：图例的键必须是真实存在的物理位置，额外键必须是真按键、且用的是 US 矩阵
 *    没有的位置 —— 写错码位是这张表最容易犯的错。
 *
 * **字面正确性覆盖不到**：AZERTY 的 0x1E 到底该印 `&` 还是别的、葡萄牙语 ABNT2 与日语 JIS
 * 的符号落点，只能靠真机核对（见本轮文档的遗留事项）。
 */
class KeyboardLayoutCatalogTest {

    /** US 参考矩阵里真实存在的位置 */
    private val usUsages: Set<Int> = buildSet {
        for (field in KeyboardConstants.Keys::class.java.declaredFields) {
            if (field.type != Key::class.java) continue
            field.isAccessible = true
            add(((field.get(KeyboardConstants.Keys) as Key).value.toInt() and 0xFF))
        }
    }

    private val usKeys: List<Key> = buildList {
        for (field in KeyboardConstants.Keys::class.java.declaredFields) {
            if (field.type != Key::class.java) continue
            field.isAccessible = true
            add(field.get(KeyboardConstants.Keys) as Key)
        }
    }

    /** 日语表里用到的假名：用来断言其它布局不会误带假名角标 */
    private val kana = listOf(
        "た", "て", "い", "す", "か", "ん", "な", "に", "ら", "せ",
        "ち", "と", "し", "は", "き", "く", "ま", "の", "り",
        "つ", "さ", "そ", "ひ", "こ", "み", "も",
        "ぬ", "ふ", "あ", "う", "え", "お", "や", "ゆ", "よ", "わ",
        "ほ", "へ", "れ", "け", "ね", "る", "め", "む", "゛", "゜",
    )

    private fun locale(tag: String): Locale = Locale.forLanguageTag(tag)

    @Test
    fun `every selectable language maps to a layout in the catalog`() {
        val expected = mapOf(
            "zh-CN" to "us-qwerty",
            "zh-Hant" to "us-qwerty",
            "en" to "us-qwerty",
            "hi" to "us-qwerty",
            "es" to "es",
            "ar" to "ar-101",
            "ru" to "ru",
            "fr" to "fr-azerty",
            "pt" to "pt-abnt2",
            "de" to "de-qwertz",
            "ja" to "ja-jis",
            "ko" to "ko-dubeolsik",
        )
        // 这张表必须正好覆盖 12 种界面语言：新增语言时这里先红，提醒同步补布局
        assertEquals(LanguageCatalog.UI_LANGUAGES.map { it.tag }.toSet(), expected.keys)

        for ((tag, layoutId) in expected) {
            assertEquals(tag, layoutId, KeyboardLayoutCatalog.forLocale(locale(tag)).id)
        }
    }

    /**
     * 「用美式键位发送前提醒什么」的分类（[HostLayoutNotice]）。
     *
     * 这是**人工判断**的结果，推不出来，所以在这里写死：德语只差 `Y`/`Z` 互换、对十六进制根本没有
     * 影响，却是最容易被当成「没问题」的一种（用户看不出来），所以照样进 [HostLayoutNotice.US_LAYOUT]；
     * 朝鲜语 / 日语则是字母位被换掉、但英文模式下与美式一致，只需要切模式。改分类必须过这一关。
     */
    @Test
    fun `every layout declares what to tell the user before sending`() {
        val expected = mapOf(
            "us-qwerty" to HostLayoutNotice.NONE,
            "es" to HostLayoutNotice.NONE,
            "pt-abnt2" to HostLayoutNotice.NONE,
            "fr-azerty" to HostLayoutNotice.US_LAYOUT,
            "de-qwertz" to HostLayoutNotice.US_LAYOUT,
            "ru" to HostLayoutNotice.US_LAYOUT,
            "ar-101" to HostLayoutNotice.US_LAYOUT,
            "ko-dubeolsik" to HostLayoutNotice.ENGLISH_MODE,
            "ja-jis" to HostLayoutNotice.ENGLISH_MODE,
        )
        assertEquals(KeyboardLayoutCatalog.ALL.map { it.id }.toSet(), expected.keys)

        for ((id, notice) in expected) {
            assertEquals(id, notice, KeyboardLayoutCatalog.of(id)?.notice)
        }
    }

    /**
     * [HostLayoutNotice.NONE] 的布局必须真的「什么都不用管」：`A`–`F`（0x04–0x09，十六进制字母位）
     * 与主键盘数字行（0x1E–0x27，`keys=main` 的数字位）的主档都得与 US 同字 ——
     * 这两处正是模板会按到的位置，其它位置（标点、`Y`/`Z`）错不错与模板无关。
     */
    @Test
    fun `layouts without a notice keep hex letters and digit row on US`() {
        val checkedUsages = (0x04..0x09) + (0x1E..0x27)

        for (layout in KeyboardLayoutCatalog.ALL.filter { it.notice == HostLayoutNotice.NONE }) {
            for (usage in checkedUsages) {
                val key = usKeys.first { (it.value.toInt() and 0xFF) == usage }
                assertEquals(
                    "${layout.id} 声明了不用提示，却把 0x${usage.toString(16)} 换成了别的字",
                    KeyboardLayoutCatalog.US.legendOf(key).label,
                    layout.legendOf(key).label,
                )
            }
        }
    }

    /**
     * 「界面语言 或 宿主布局设置」任一不是美式就提醒，两处都非美式时**两条都给**
     * （[KeyboardLayoutCatalog.noticesFor]）。
     *
     * 两个来源是两处互不相干的手动设置，各自都可能是把宿主带离美式的那一处：与其替用户猜哪一处
     * 算数，不如两边都摆出来。同一档位只出一条，重的排前面；两边都干净才是空列表。
     */
    @Test
    fun `notice comes from either the UI language or the layout setting`() {
        // 语言单独触发
        assertEquals(
            listOf(HostLayoutNotice.US_LAYOUT),
            KeyboardLayoutCatalog.noticesFor(KeyboardLayoutMode.US_QWERTY, locale("fr")),
        )
        // 布局单独触发：界面语言是中文也一样
        assertEquals(
            listOf(HostLayoutNotice.US_LAYOUT),
            KeyboardLayoutCatalog.noticesFor(KeyboardLayoutMode.FRENCH, locale("zh-CN")),
        )
        // 两边各给一档：两条都给，重的在前
        assertEquals(
            listOf(HostLayoutNotice.US_LAYOUT, HostLayoutNotice.ENGLISH_MODE),
            KeyboardLayoutCatalog.noticesFor(KeyboardLayoutMode.KOREAN, locale("fr")),
        )
        // 两边落在同一档：只出一条
        assertEquals(
            listOf(HostLayoutNotice.US_LAYOUT),
            KeyboardLayoutCatalog.noticesFor(KeyboardLayoutMode.FRENCH, locale("fr")),
        )
        assertEquals(
            listOf(HostLayoutNotice.ENGLISH_MODE),
            KeyboardLayoutCatalog.noticesFor(KeyboardLayoutMode.KOREAN, locale("ja")),
        )
        // 跟随界面语言：语言说是什么就是什么，设置这一边没有独立信息
        assertEquals(
            listOf(HostLayoutNotice.ENGLISH_MODE),
            KeyboardLayoutCatalog.noticesFor(KeyboardLayoutMode.FOLLOW_UI, locale("ja")),
        )
        // 两边都干净才没有提示；清单外的语言回落到 US，同样没有提示
        assertEquals(
            emptyList<HostLayoutNotice>(),
            KeyboardLayoutCatalog.noticesFor(KeyboardLayoutMode.US_QWERTY, locale("zh-CN")),
        )
        assertEquals(
            emptyList<HostLayoutNotice>(),
            KeyboardLayoutCatalog.noticesFor(KeyboardLayoutMode.FOLLOW_UI, locale("en")),
        )
        assertEquals(
            emptyList<HostLayoutNotice>(),
            KeyboardLayoutCatalog.noticesFor(KeyboardLayoutMode.FOLLOW_UI, locale("sw")),
        )
    }

    @Test
    fun `chinese variants all share the US layout`() {
        // zh-TW / zh-HK / zh-MO 在 LanguageCatalog 里归到 zh-Hant，这边也必须落到 US
        for (tag in listOf("zh-CN", "zh-SG", "zh-TW", "zh-HK", "zh-MO", "zh-Hant")) {
            assertEquals(tag, "us-qwerty", KeyboardLayoutCatalog.forLocale(locale(tag)).id)
        }
    }

    @Test
    fun `languages outside the catalog fall back to US`() {
        assertEquals("us-qwerty", KeyboardLayoutCatalog.forLocale(locale("th")).id)
        assertEquals("us-qwerty", KeyboardLayoutCatalog.forLocale(locale("it")).id)
    }

    @Test
    fun `every layout mode resolves and follow-ui carries no id`() {
        assertNull(KeyboardLayoutMode.FOLLOW_UI.layoutId)

        val modes = KeyboardLayoutMode.entries.filter { it != KeyboardLayoutMode.FOLLOW_UI }
        for (mode in modes) {
            assertNotNull(mode.name, KeyboardLayoutCatalog.of(mode.layoutId!!))
        }
        // 双向一致：下拉里每一项都能查到，清单里也不该有查不到的布局
        assertEquals(modes.size, KeyboardLayoutCatalog.ALL.size)
        assertEquals(modes.size, modes.map { it.layoutId }.distinct().size)

        // 「跟随界面语言」走界面语言兜底
        assertEquals(
            "de-qwertz",
            KeyboardLayoutCatalog.forMode(KeyboardLayoutMode.FOLLOW_UI, locale("de")).id,
        )
        assertEquals(
            "fr-azerty",
            KeyboardLayoutCatalog.forMode(KeyboardLayoutMode.FRENCH, locale("de")).id,
        )
    }

    @Test
    fun `every legend points at a real physical position`() {
        for (layout in KeyboardLayoutCatalog.ALL) {
            val extraUsages = layout.extraKeys.map { it.value.toInt() and 0xFF }.toSet()
            for (usage in layout.legends.keys) {
                assertTrue(
                    "${layout.id} 的图例位置 0x${usage.toString(16)} 既不是 US 矩阵里的键，" +
                        "也不在该布局的 extraKeys 里",
                    usage in usUsages || usage in extraUsages,
                )
            }
        }
    }

    @Test
    fun `only layouts with extra keys declare them`() {
        val expected = setOf("fr-azerty", "de-qwertz", "es", "pt-abnt2", "ja-jis")
        for (layout in KeyboardLayoutCatalog.ALL) {
            assertEquals(layout.id, layout.id in expected, layout.extraKeys.isNotEmpty())
        }
    }

    @Test
    fun `extra keys are plain keys on positions the US matrix lacks`() {
        val allExtras = KeyboardLayoutCatalog.ALL.flatMap { it.extraKeys }
        for (key in allExtras) {
            // 它们只承载图例与位置，绝不参与 charToKeyMap / 模板键名（见 InternationalKeys 的 KDoc）
            assertNull(key.displayName, key.char)
            assertNull(key.displayName, key.shiftedChar)
            assertTrue(key.displayName, !key.isModifier)
            assertTrue(key.displayName, !key.isSpacer)
            assertTrue(key.displayName, key.value.toInt() != 0)
            assertTrue(
                "${key.displayName} 用了 US 矩阵里已有的位置，应当直接写图例而不是新增键",
                (key.value.toInt() and 0xFF) !in usUsages,
            )
        }
        // 只要求同一个布局内不重复：`ISO_EXTRA` 是同一种键，被法/德/西/葡四个布局共用是刻意的
        for (layout in KeyboardLayoutCatalog.ALL) {
            assertEquals(
                layout.id,
                layout.extraKeys.size,
                layout.extraKeys.map { it.value }.distinct().size,
            )
        }
        assertTrue("清单里应当至少有一份额外键", allExtras.isNotEmpty())
    }

    @Test
    fun `US baseline overrides nothing and suppresses case-only shift labels`() {
        assertTrue("US 是基线，不该有覆盖项", KeyboardLayoutCatalog.US.legends.isEmpty())
        // 字母键的 Shift 只是大小写，印到角上是噪音
        assertNull(KeyboardLayoutCatalog.US.legendOf(Keys.A).subLabel)
        assertNull(KeyboardLayoutCatalog.US.legendOf(Keys.Z).subLabel)
        // 数字 / 符号键的另一档才是副标签该表达的东西
        assertEquals(KeyLegend("1", "!"), KeyboardLayoutCatalog.US.legendOf(Keys.NUM_1))
        assertEquals(KeyLegend(";", ":"), KeyboardLayoutCatalog.US.legendOf(Keys.SEMICOLON))
        assertEquals(KeyLegend("-", "_"), KeyboardLayoutCatalog.US.legendOf(Keys.MINUS))
    }

    @Test
    fun `french azerty flips the number row and swaps a q z w`() {
        val fr = KeyboardLayoutCatalog.forLocale(locale("fr"))
        // 数字落在 Shift 档，主档是符号：不印副标签，用户会以为「按 1 出 &」是 bug
        assertEquals(KeyLegend("&", "1"), fr.legendOf(Keys.NUM_1))
        assertEquals(KeyLegend("é", "2"), fr.legendOf(Keys.NUM_2))
        // A/Q 与 Z/W 互换
        assertEquals("A", fr.legendOf(Keys.Q).label)
        assertEquals("Q", fr.legendOf(Keys.A).label)
        assertEquals("Z", fr.legendOf(Keys.W).label)
        assertEquals("W", fr.legendOf(Keys.Z).label)
        // M 搬到 US 分号的位置，0x10 位变成逗号（? 要按 Shift+逗号）
        assertEquals("M", fr.legendOf(Keys.SEMICOLON).label)
        assertEquals(KeyLegend(",", "?"), fr.legendOf(Keys.M))
    }

    @Test
    fun `german qwertz swaps y and z and moves the minus key`() {
        val de = KeyboardLayoutCatalog.forLocale(locale("de"))
        assertEquals("Z", de.legendOf(Keys.Y).label)
        assertEquals("Y", de.legendOf(Keys.Z).label)
        assertEquals("Ö", de.legendOf(Keys.SEMICOLON).label)
        assertEquals("Ä", de.legendOf(Keys.QUOTE).label)
        assertEquals(KeyLegend("ß", "?"), de.legendOf(Keys.MINUS))
        assertEquals(KeyLegend("-", "_"), de.legendOf(Keys.SLASH))
    }

    @Test
    fun `extra keys of german and abnt2 carry the legend their layout gives them`() {
        val de = KeyboardLayoutCatalog.forLocale(locale("de"))
        // 0x32「Non-US # and ~」在德语里是 `#`，Shift 档是 `'`
        val numberSign = de.extraKeys.single { (it.value.toInt() and 0xFF) == 0x32 }
        assertEquals(KeyLegend("#", "'"), de.legendOf(numberSign))

        val pt = KeyboardLayoutCatalog.forLocale(locale("pt"))
        // ABNT2 的左 Shift 旁那颗 ISO 键是 `\`／`|`，不是德/西/法的 `<>`
        val iso = pt.extraKeys.single { (it.value.toInt() and 0xFF) == 0x64 }
        assertEquals(KeyLegend("\\", "|"), pt.legendOf(iso))
        // ABNT2 把 `;`／`:` 放在 US 的 `/` 位上
        assertEquals(KeyLegend(";", ":"), pt.legendOf(Keys.SLASH))
        // `ç`／`Ç` 只有大小写之差，不该印成角标
        assertNull(pt.legendOf(Keys.SEMICOLON).subLabel)

        val es = KeyboardLayoutCatalog.forLocale(locale("es"))
        assertEquals(KeyLegend("Ç"), es.legendOf(Keys.BACKSLASH))
    }

    @Test
    fun `cyrillic hangul and arabic relabel the same positions`() {
        val ru = KeyboardLayoutCatalog.forLocale(locale("ru"))
        assertEquals("Й", ru.legendOf(Keys.Q).label)
        assertEquals("Ь", ru.legendOf(Keys.M).label)
        // 西里尔字母没有大小写视觉差异，不设副标签
        assertNull(ru.legendOf(Keys.Q).subLabel)

        val ko = KeyboardLayoutCatalog.forLocale(locale("ko"))
        assertEquals("ㅂ", ko.legendOf(Keys.Q).label)
        assertEquals("ㅃ", ko.legendOf(Keys.Q).subLabel)
        assertNull(ko.legendOf(Keys.M).subLabel)

        val ar = KeyboardLayoutCatalog.forLocale(locale("ar"))
        assertEquals("ض", ar.legendOf(Keys.Q).label)
        // 数字行与 Shift 档与 US 一致
        assertEquals(KeyLegend("1", "!"), ar.legendOf(Keys.NUM_1))
    }

    @Test
    fun `japanese jis prints kana on the latin keycaps`() {
        val ja = KeyboardLayoutCatalog.forLocale(locale("ja"))

        // 字母位与 US 相同（JIS 键帽就是这样），区别全在角标上的假名 ——
        // 只改符号位的话，整块键盘看起来与 US 没有区别
        assertEquals("Q", ja.legendOf(Keys.Q).label)
        assertEquals("た", ja.legendOf(Keys.Q).subLabel)
        assertEquals("て", ja.legendOf(Keys.W).subLabel)
        assertEquals("か", ja.legendOf(Keys.T).subLabel)
        assertEquals("も", ja.legendOf(Keys.M).subLabel)
        assertEquals("ち", ja.legendOf(Keys.A).subLabel)

        // 主档一律与 US 一致：位置码送出去交给宿主翻译，图上印的必须就是宿主会打出来的那个字，
        // 否则就是「看着是 @、按下去是 [」的错位（0x2F/0x30/0x31 曾经印成 @ [ ]）
        for (key in usKeys) {
            assertEquals(
                "「${key.displayName}」的主档应当与 US 完全一致",
                KeyboardLayoutCatalog.US.legendOf(key).label,
                ja.legendOf(key).label,
            )
        }

        // 数字行：主档仍是数字（好按），角标是假名
        assertEquals(KeyLegend("1", "ぬ"), ja.legendOf(Keys.NUM_1))
        assertEquals(KeyLegend("0", "わ"), ja.legendOf(Keys.NUM_0))

        // 符号位：假名在角标上，主档还是 US 那颗字
        assertEquals(KeyLegend("=", "へ"), ja.legendOf(Keys.EQUAL))
        assertEquals(KeyLegend("[", "゛"), ja.legendOf(Keys.LEFT_BRACKET))
        assertEquals(KeyLegend("]", "゜"), ja.legendOf(Keys.RIGHT_BRACKET))
        assertEquals(KeyLegend("\\", "む"), ja.legendOf(Keys.BACKSLASH))
        assertEquals(KeyLegend("'", "け"), ja.legendOf(Keys.QUOTE))
        assertEquals(KeyLegend(";", "れ"), ja.legendOf(Keys.SEMICOLON))
        assertEquals(KeyLegend(",", "ね"), ja.legendOf(Keys.COMMA))
        assertEquals(KeyLegend("/", "め"), ja.legendOf(Keys.SLASH))

        // 其余布局不该出现假名角标：这是日语专有的
        for (layout in KeyboardLayoutCatalog.ALL.filter { it.id != "ja-jis" }) {
            for (mark in kana) {
                assertTrue(
                    "${layout.id} 上不该出现假名角标 $mark",
                    layout.legends.values.none { it.subLabel == mark },
                )
            }
        }
    }

    @Test
    fun `every key of the US matrix has a label in every layout`() {
        for (layout in KeyboardLayoutCatalog.ALL) {
            for (key in usKeys) {
                assertTrue(
                    "${layout.id} 下「${key.displayName}」的图例是空的",
                    layout.legendOf(key).label.isNotEmpty(),
                )
            }
        }
    }

    @Test
    fun `extra keys carry a label in their own layout`() {
        for (layout in KeyboardLayoutCatalog.ALL) {
            for (key in layout.extraKeys) {
                assertTrue(
                    "${layout.id} 的额外键「${key.displayName}」没有图例",
                    layout.legendOf(key).label.isNotEmpty(),
                )
            }
        }
    }
}
