package com.github.remotecontrol.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字符集清单自身的两条不变量 + 「新增字符集」的校验。
 *
 * 1. **不重复**：规范名与别名两两不撞（大小写不敏感）。别名会原样展示在「字符集与别名」
 *    对话框里、也会被 `isSupported` 认，撞了就会出现「同一行能查到两个字符集」的糊涂账；
 * 2. **能解析**：新增校验用运行时判断（`Charset.isSupported`）而不是再比一遍编码表 ——
 *    Android 上同一张码页的几种写法就是同一个 `Charset`、映射也一致（见
 *    `docs/summary/编码参考表.md`），没有桌面 JDK 那种「两张表差几个字符」的问题。
 *
 * 内置条目「在 JVM 上能不能解析」由 `LanguageCatalogTest.charsetNamesAndAliasesResolve`
 * 负责（那边还要处理 `androidOnly` 的反向断言），这里只管清单内部的自洽与新增流程。
 */
class CharsetCatalogTest {

    @After
    fun tearDown() {
        // 自定义清单是进程级状态，跑完清掉，别影响别的测试
        CharsetCatalog.registerCustom(emptyList())
    }

    @Test
    fun `builtInNamesAndAliasesAreUnique`() {
        val seen = mutableMapOf<String, String>()
        CharsetCatalog.OPTIONS.forEach { option ->
            (listOf(option.name) + option.aliases).forEach { name ->
                val key = name.lowercase()
                val owner = seen[key]
                assertNull("「$name」在清单里出现了两次：$owner 与 ${option.name}", owner)
                seen[key] = option.name
            }
        }
    }

    @Test
    fun `addRejectsDuplicateOfNameOrAlias`() {
        assertEquals(
            "规范名本身也算重复",
            CharsetAddResult.Duplicate("GBK"),
            CharsetCatalog.validateAdd("GBK"),
        )
        assertEquals(
            "大小写不敏感",
            CharsetAddResult.Duplicate("GBK"),
            CharsetCatalog.validateAdd("gbk"),
        )
        assertEquals(
            "别名同样算重复：它与规范名指向同一个字符集",
            CharsetAddResult.Duplicate("GBK"),
            CharsetCatalog.validateAdd("windows-936"),
        )
    }

    @Test
    fun `addRejectsBlankAndUnknown`() {
        assertEquals(CharsetAddResult.Blank, CharsetCatalog.validateAdd("   "))
        assertEquals(CharsetAddResult.NotFound("nope"), CharsetCatalog.validateAdd("nope"))
        // 带空格的描述性文字不是字符集名（参考表里那条 `Unicode (LE)` 就是这种）
        assertEquals(
            CharsetAddResult.NotFound("Unicode (LE)"),
            CharsetCatalog.validateAdd("Unicode (LE)"),
        )
    }

    @Test
    fun `addAcceptsAnAvailableCharsetAndRegistersIt`() {
        // 取一个两个运行时都一定有的名字（`ISO-8859-1` 在 JDK 与 ICU 里都是基础字符集），
        // 且它不在内置清单里
        val name = "ISO-8859-1"
        assertEquals(CharsetAddResult.Ok(name), CharsetCatalog.validateAdd(name))

        // 落盘那一步（AppPreferences）会把它登记进来；登记前它必须还没进清单
        assertNull("登记之前不该已经在清单里", CharsetCatalog.of(name))
        CharsetCatalog.registerCustom(listOf(name))

        assertNotNull(CharsetCatalog.of(name))
        assertTrue(CharsetCatalog.isSupported(name.lowercase()))
        assertEquals(listOf(name), CharsetCatalog.customNames())
        assertTrue(
            "自定义条目要出现在 ALL 里（编辑器下拉与设置页清单都取它）",
            CharsetCatalog.ALL.map { it.name }.contains(name),
        )
        // 内置条目的数量不能被它顶掉
        assertEquals(
            CharsetCatalog.OPTIONS.size + 1,
            CharsetCatalog.ALL.size,
        )
    }

    @Test
    fun `onlyCustomEntriesAreFlaggedAsCustom`() {
        CharsetCatalog.registerCustom(listOf("windows-1250"))

        assertEquals(
            "内置条目一个都不该带自定义标记（设置页靠它决定要不要上底色、给不给删除按钮）",
            0,
            CharsetCatalog.OPTIONS.count { it.isCustom },
        )
        assertEquals(
            "ALL 里带标记的应该正好是自定义那些，且顺序与登记顺序一致",
            CharsetCatalog.customNames(),
            CharsetCatalog.ALL.filter { it.isCustom }.map { it.name },
        )
    }

    @Test
    fun `customEntriesAreListedAfterBuiltInsAndCanBeReplaced`() {
        CharsetCatalog.registerCustom(listOf("windows-1250", "windows-1253"))
        assertEquals(listOf("windows-1250", "windows-1253"), CharsetCatalog.customNames())
        assertEquals(
            CharsetCatalog.OPTIONS.map { it.name } + listOf("windows-1250", "windows-1253"),
            CharsetCatalog.ALL.map { it.name },
        )

        // 重新登记 = 覆盖（存盘值变化时由 AppPreferences 调用），不是累加
        CharsetCatalog.registerCustom(listOf("windows-1253"))
        assertEquals(listOf("windows-1253"), CharsetCatalog.customNames())
        assertNull(CharsetCatalog.of("windows-1250"))
    }
}
