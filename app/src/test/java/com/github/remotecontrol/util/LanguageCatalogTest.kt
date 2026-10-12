package com.github.remotecontrol.util

import com.github.remotecontrol.util.template.MacroTemplateExpander
import com.github.remotecontrol.util.template.MacroTemplateHeader
import com.github.remotecontrol.util.template.MacroTemplateIssue
import com.github.remotecontrol.util.template.MacroTemplateParser
import com.github.remotecontrol.util.template.MacroTemplatePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.util.Locale

/**
 * 语言清单、语言枚举、字符集清单三者的一致性。
 *
 * 守两条线：
 *
 * 1. **界面语言**：下拉里能选到的、[LanguageCatalog] 里有条目的、[LanguageMode] 枚举能解析的，
 *    三者必须对得上，新增语言时先在这里红；
 * 2. **字符集**：清单里写出来的名字（含别名）在运行时必须真的能解析 —— 界面上展示的别名也算
 *    「支持」，一旦列了个系统其实不认的写法，就会出现「照着提示写却被判 UNKNOWN_CHARSET」
 *    的自相矛盾。**唯一例外**是标了 [CharsetOption.androidOnly] 的那几个（只有 `IBM720`）：
 *    Android 走 ICU 能解析，桌面 JVM 的注册表里没有，所以这里跳过并反向断言它确实不可用。
 */
class LanguageCatalogTest {

    @Test
    fun languagePickerMatchesCatalog() {
        val options = SettingConstants.LANGUAGE_MODE.allOptions

        assertEquals("下拉第一项应该是「跟随系统」", LanguageMode.SYSTEM, options.first().value)
        assertEquals(
            "下拉项数应该是「跟随系统」+ 已提供译文的语言",
            LanguageCatalog.UI_LANGUAGES.size + 1,
            options.size
        )
        // 语言名也取自清单的原生名，两处分开写必然走叉
        assertEquals(
            "下拉里的语言名应与清单的原生名一一对应",
            LanguageCatalog.UI_LANGUAGES.map { it.nativeName },
            options.drop(1).map { it.displayName }
        )
    }

    @Test
    fun everySelectableLanguageHasACatalogEntry() {
        LanguageMode.entries.forEach { mode ->
            if (mode == LanguageMode.SYSTEM) {
                assertNull("「跟随系统」不该有语言标签", mode.tag)
                return@forEach
            }
            val tag = mode.tag
            assertNotNull("$mode 缺少 BCP-47 标签", tag)

            val option = LanguageCatalog.of(tag!!)
            assertNotNull("$mode 的标签 $tag 在语言清单里没有条目", option)
            assertTrue("$mode 对应的 $tag 还没有译文，不应出现在下拉里", option!!.uiTranslated)
        }
    }

    /**
     * 别名会原样展示在「字符集与别名」对话框里，而 `isSupported` 也认别名；
     * 一旦某个名字系统其实解析不了，就会出现「照着提示写却被判成运行时错误」的自相矛盾。
     *
     * `androidOnly` 的例外反向断言：如果哪天 JDK 也支持了它，这里会红，
     * 提醒把 [CharsetOption.androidOnly] 标记删掉（那时它就不再是「只在 Android 可用」了）。
     */
    @Test
    fun charsetNamesAndAliasesResolve() {
        CharsetCatalog.OPTIONS.forEach { option ->
            val names = listOf(option.name) + option.aliases
            names.forEach { name ->
                if (option.androidOnly) {
                    assertFalse(
                        "$name 标了 androidOnly，但这台 JVM 已经能解析它了 —— 该删掉这个标记",
                        Charset.isSupported(name)
                    )
                } else {
                    assertTrue("$name 无法解析成 charset", Charset.isSupported(name))
                }
            }
        }
        assertTrue(
            "androidOnly 是给「Android 有、JDK 没有」的码页用的，清单里应当至少有一个",
            CharsetCatalog.OPTIONS.any { it.androidOnly }
        )
    }

    /**
     * 预设的字符集分两类，别混：
     *
     * 1. **两条 Alt Code 带占位符**（`follow_language`）：新建那一刻换成界面语言对应的宿主码页
     *    （OEM 版取 OEMCP、ANSI 版取 ACP，见下两条用例），所以正文里写着的不是最终值 ——
     *    没替换就解析必然报 `UNKNOWN_CHARSET`，这正是「忘了替换」的显式信号；
     * 2. **其余写死**：十六进制那几条走 Unicode 码位（`UTF-16` = 代码单元、`Unicode` = 完整码点），
     *    与宿主码页无关；空白模板是 `UTF-16`。
     */
    @Test
    fun presetsCarryAFixedCharset() {
        val unicodeOrders = setOf(MacroTemplateHeader.DEFAULT_CHARSET, "Unicode")

        assertEquals(
            "带字符集占位符的预设应当是 OEM / ANSI 两条 Alt Code",
            listOf(MacroTemplatePresets.OEM_ALT_CODE.name, MacroTemplatePresets.ANSI_ALT_CODE.name),
            MacroTemplatePresets.ALL
                .filter { MacroTemplatePresets.CHARSET_PLACEHOLDER in it.content }
                .map { it.name },
        )

        MacroTemplatePresets.ALL.forEach { preset ->
            if (MacroTemplatePresets.CHARSET_PLACEHOLDER in preset.content) {
                // 占位符要靠 charsetTarget 才有人替换它，两者必须同时出现
                assertNotNull("${preset.name} 带占位符却没声明 charsetTarget", preset.charsetTarget)
            } else {
                assertNull("${preset.name} 没有占位符，不该声明 charsetTarget", preset.charsetTarget)
                val charset = MacroTemplateParser.parse(preset.content).template.header.charset
                assertTrue(
                    "${preset.name} 的字符集应当是写死的 Unicode 口径（UTF-16 / Unicode），实际是 $charset",
                    charset in unicodeOrders,
                )
            }
        }

        // 走 Unicode 码位的方案必须是十六进制（写十进制会发错数字）
        listOf(
            MacroTemplatePresets.WINDOWS_HEX,
            MacroTemplatePresets.RICHEDIT_ALT_X,
            MacroTemplatePresets.MACOS_HEX,
            MacroTemplatePresets.LINUX_CTRL_SHIFT_U,
        ).forEach { preset ->
            assertTrue(
                "${preset.name} 走的是 Unicode 码位，进制应当是 16",
                MacroTemplateParser.parse(preset.content).template.header.radix == 16
            )
        }

        // 种子模板是在 seedIfFirstLaunch 里替换占位符的，替换之后要能直接解析通过
        //（空白模板的 EMPTY_TEMPLATE 是它的正常状态，不在种子里）
        MacroTemplatePresets.SEEDED.forEach { preset ->
            val seeded = MacroTemplatePresets.resolveCharset(preset, Locale.forLanguageTag("zh-CN"))
            val parsed = MacroTemplateParser.parse(seeded.content)
            assertEquals("${preset.name} 不该有解析错误", 0, parsed.errorCount)
            assertEquals(
                "${preset.name} 不该报 UNKNOWN_CHARSET",
                false,
                parsed.diagnostics.any { it.issue == MacroTemplateIssue.UNKNOWN_CHARSET }
            )
        }
    }

    /**
     * OEM Alt Code 的默认字符集按**界面语言**给，取的是该语言宿主的 OEM 码页
     * （[LanguageCatalog.HOST_CHARSETS_BY_TAG] 的 `oem` 列）。
     *
     * 三条容易在「顺手统一一下」时被改掉的取值单独钉住：印地语是 `US-ASCII`（天城文没有
     * Windows 页，印地语地区普遍是 US 矩阵键盘）、英语是美国页 `IBM437`、
     * 西欧各语言是多语言页 `IBM850`。清单外的语言（扩展位、跟随系统命中不到）兜底 `IBM850`。
     */
    @Test
    fun oemAltCodeFollowsUiLanguage() {
        val expected = mapOf(
            "zh-CN" to "GBK",
            "zh-Hant" to "Big5",
            "en" to "IBM437",
            "es" to "IBM850",
            "ar" to "IBM720",
            "hi" to "US-ASCII",
            "ru" to "IBM866",
            "fr" to "IBM850",
            "pt" to "IBM850",
            "de" to "IBM850",
            "ja" to "windows-31j",
            "ko" to "windows-949",
        )

        // 12 种可选界面语言与映射表一一对应，且每个取值都是清单里的**规范名**
        //（写别名也能解析，但落进模板的应当是规范名，界面上才不会出现两种写法）
        assertEquals(
            "可选界面语言应当与映射表一一对应",
            LanguageCatalog.UI_LANGUAGES.map { it.tag }.toSet(),
            expected.keys,
        )
        expected.forEach { (tag, charset) ->
            assertTrue("$tag 的默认页 $charset 不在字符集清单里", CharsetCatalog.isSupported(charset))
            assertEquals("$tag 的默认页应当是规范名", charset, CharsetCatalog.of(charset)?.name)
            assertEquals(
                "$tag 的 oem 列应当与预期一致",
                charset,
                LanguageCatalog.HOST_CHARSETS_BY_TAG.getValue(tag).oem,
            )
        }

        // 繁体看 script / 地区，跟随系统时要能把 zh-TW 归到繁中
        assertEquals("Big5", LanguageCatalog.oemCharsetFor(Locale.forLanguageTag("zh-TW")))
        // 英语按地区细分（只有「跟随系统」才拿得到地区）：美国 437、英国 / 国际 850
        assertEquals("IBM437", LanguageCatalog.oemCharsetFor(Locale.forLanguageTag("en-US")))
        assertEquals("IBM850", LanguageCatalog.oemCharsetFor(Locale.forLanguageTag("en-GB")))
        assertEquals("IBM850", LanguageCatalog.oemCharsetFor(Locale.forLanguageTag("en-AU")))
        assertEquals("IBM850", LanguageCatalog.oemCharsetFor(Locale.forLanguageTag("en-001")))
        // 手动选「English (US)」时 locale 没有地区，按表里的 en 行取美国页
        assertEquals("IBM437", LanguageCatalog.oemCharsetFor(Locale.forLanguageTag("en")))
        // 清单外的语言兜底西欧多语言页，而不是美国页
        assertEquals("IBM850", LanguageCatalog.oemCharsetFor(Locale.forLanguageTag("it")))
        assertEquals("IBM850", LanguageCatalog.oemCharsetFor(Locale.forLanguageTag("sw")))

        // 端到端：换进预设之后零错误，头部就是那张页
        expected.forEach { (tag, charset) ->
            val preset = MacroTemplatePresets.resolveCharset(
                MacroTemplatePresets.OEM_ALT_CODE,
                Locale.forLanguageTag(tag),
            )
            val parsed = MacroTemplateParser.parse(preset.content)
            assertEquals("$tag 的 OEM 预设不该有解析错误：${parsed.diagnostics}", 0, parsed.errorCount)
            assertEquals(charset, parsed.template.header.charset)
            assertFalse(
                "$tag 的预设里不该再有占位符",
                MacroTemplatePresets.CHARSET_PLACEHOLDER in preset.content,
            )
        }
    }

    /**
     * ANSI Alt Code 走的是同一套机制，只是取表里的 ACP 那一列
     * （[LanguageCatalog.ansiCharsetFor]）。
     *
     * 与 OEM 的差别集中在这几处：英语与西欧各语言的 ANSI 页都是 `windows-1252`
     * （OEM 侧才分 437 / 850）、俄语是 `windows-1251`（OEM 是 866）、阿拉伯语是 `windows-1256`
     * （OEM 是 720）；简中 / 繁中 / 日语 / 朝鲜语两列相同，印地语两列都是 `US-ASCII`。
     * 清单外语言的兜底页是 `windows-1252`。
     */
    @Test
    fun ansiAltCodeFollowsUiLanguage() {
        val expected = mapOf(
            "zh-CN" to "GBK",
            "zh-Hant" to "Big5",
            "en" to "windows-1252",
            "es" to "windows-1252",
            "ar" to "windows-1256",
            "hi" to "US-ASCII",
            "ru" to "windows-1251",
            "fr" to "windows-1252",
            "pt" to "windows-1252",
            "de" to "windows-1252",
            "ja" to "windows-31j",
            "ko" to "windows-949",
        )

        assertEquals(
            "可选界面语言应当与映射表一一对应",
            LanguageCatalog.UI_LANGUAGES.map { it.tag }.toSet(),
            expected.keys,
        )
        expected.forEach { (tag, charset) ->
            assertTrue("$tag 的默认页 $charset 不在字符集清单里", CharsetCatalog.isSupported(charset))
            assertEquals("$tag 的默认页应当是规范名", charset, CharsetCatalog.of(charset)?.name)
            assertEquals(
                "$tag 的 ansi 列应当与预期一致",
                charset,
                LanguageCatalog.HOST_CHARSETS_BY_TAG.getValue(tag).ansi,
            )
        }

        // 清单外的语言兜底西欧 ANSI 页
        assertEquals("windows-1252", LanguageCatalog.ansiCharsetFor(Locale.forLanguageTag("it")))
        assertEquals("windows-1252", LanguageCatalog.ansiCharsetFor(Locale.forLanguageTag("sw")))

        // 端到端：换进预设之后零错误，头部就是那张页
        expected.forEach { (tag, charset) ->
            val preset = MacroTemplatePresets.resolveCharset(
                MacroTemplatePresets.ANSI_ALT_CODE,
                Locale.forLanguageTag(tag),
            )
            val parsed = MacroTemplateParser.parse(preset.content)
            assertEquals("$tag 的 ANSI 预设不该有解析错误：${parsed.diagnostics}", 0, parsed.errorCount)
            assertEquals(charset, parsed.template.header.charset)
            assertFalse(
                "$tag 的预设里不该再有占位符",
                MacroTemplatePresets.CHARSET_PLACEHOLDER in preset.content,
            )
        }
    }

    /**
     * 说明按界面语言补进预设正文，而且**只动说明**。
     *
     * 说明是唯一能换行的头部参数，落盘要写成引号 + `\n` 的形式，所以连「补完仍零错误、
     * 说明一字不差、`description =` 只占一行、步骤数不变」一起钉住；没有说明资源的那几条
     * 必须原样返回 —— 不做 parse→format 归一化，免得把注释块与写法的现状悄悄改掉。
     */
    @Test
    fun localizedPresetsOnlyGainADescription() {
        val sample = "第一行\n第二行"

        assertTrue(
            "内置预设里应当有带说明的那一条（OEM Alt Code）",
            MacroTemplatePresets.ALL.any { it.descriptionRes != null },
        )

        MacroTemplatePresets.ALL.forEach { preset ->
            // 占位符必须先换掉（与落盘前的调用点同序）：带占位符的正文解析出来有错，
            // localize 见到错误会原样返回，说明就补不进去了
            val resolved = MacroTemplatePresets.resolveCharset(preset, Locale.forLanguageTag("zh-CN"))
            val localized = MacroTemplatePresets.localize(resolved, sample)
            val original = MacroTemplateParser.parse(resolved.content)
            val parsed = MacroTemplateParser.parse(localized.content)

            // 不硬写 0：空白模板本来就带一条 EMPTY_TEMPLATE，那是它的正常状态（见上条用例）
            assertEquals(
                "${preset.name} 补说明不该带来新的诊断",
                original.errorCount,
                parsed.errorCount,
            )
            assertEquals(
                "${preset.name} 的步骤不该被说明改动",
                original.template.steps.size,
                parsed.template.steps.size,
            )

            if (preset.descriptionRes == null) {
                assertEquals(
                    "${preset.name} 没有说明资源，正文应当原样返回",
                    resolved.content,
                    localized.content,
                )
            } else {
                assertEquals("${preset.name} 的说明要一字不差", sample, parsed.template.header.description)
                assertEquals(
                    "${preset.name} 的说明只占一行",
                    1,
                    localized.content.lines().count { it.startsWith("description = ") },
                )
            }
        }
    }

    @Test
    fun localeMapsToLanguage() {
        // 繁体看 script，其次看地区；zh-SG 这类写法要归到简体
        assertEquals("zh-Hant", LanguageCatalog.forLocale(Locale.forLanguageTag("zh-Hant"))?.tag)
        assertEquals("zh-Hant", LanguageCatalog.forLocale(Locale.forLanguageTag("zh-TW"))?.tag)
        assertEquals("zh-Hant", LanguageCatalog.forLocale(Locale.forLanguageTag("zh-HK"))?.tag)
        assertEquals("zh-CN", LanguageCatalog.forLocale(Locale.forLanguageTag("zh-CN"))?.tag)
        assertEquals("zh-CN", LanguageCatalog.forLocale(Locale.forLanguageTag("zh-SG"))?.tag)
        assertEquals("ja", LanguageCatalog.forLocale(Locale.forLanguageTag("ja-JP"))?.tag)
        assertEquals("en", LanguageCatalog.forLocale(Locale.forLanguageTag("en-US"))?.tag)

        assertNull("清单里没有的语言不该硬凑", LanguageCatalog.forLocale(Locale.forLanguageTag("sw")))
    }

    /**
     * 展开预览打开时会把 [CharsetOption.sampleText] 预填进样本框，所以样本必须是
     * **这张码页真的编得出来**的字符：编不出来的字符在 [MacroTemplateExpander] 里是「跳过」，
     * 打开预览只会得到一片空白，比没有测试文本更让人困惑。
     *
     * `androidOnly` 的 720 在桌面 JVM 上解析不了，跳过（与名字断言同一个理由）。
     */
    @Test
    fun charsetSampleTextsAreEncodable() {
        CharsetCatalog.OPTIONS.forEach { option ->
            if (option.androidOnly || option.sampleText.isEmpty()) return@forEach

            // 预览对话框会把样本截到 MAX_SAMPLE_LENGTH（TemplatePreviewDialog.kt 里为 8）个字符，
            // 超长的测试文本会被静默截掉尾巴，看着像展开器漏了字符
            assertTrue(
                "${option.name} 的测试文本超过预览样本上限 8 个字符：${option.sampleText}",
                option.sampleText.length <= SAMPLE_TEXT_LIMIT
            )

            // 一个单位在这张表里可能是代码单元（UTF-16）也可能是完整码点（Unicode），
            // 所以按码点遍历，免得把辅助平面字符从代理对中间切开
            option.sampleText.codePoints().forEach { codePoint ->
                assertNotNull(
                    "${option.name} 的测试文本里有编不出来的字符：${String(Character.toChars(codePoint))}",
                    MacroTemplateExpander.encodeValue(codePoint, option.name)
                )
            }
        }
    }

    /**
     * 发送前的「编不出来」检查（[MacroTemplateExpander.firstUnencodable]）。
     *
     * 三件事：码点 / 代码单元口径一律放行（它们取的就是数值本身，没有「编不出来」这回事）；
     * 代码页口径要能指出**第一个**编不出来的字符、它的码位与位置（提示框就靠这三样说明问题）；
     * 都编得出来时返回 null。字符集名字认不出来**不在这里报** —— 那是解析与诊断那条线的事。
     */
    @Test
    fun unencodableCheckPointsAtTheFirstCharacter() {
        // Unicode / UTF-16 / UTF-32 不检查：😀 在 GBK 里编不出来，但这几条路发的是数值本身
        listOf("Unicode", "UTF-16", "UTF-32").forEach { charset ->
            assertNull(
                "$charset 不该做编码检查",
                MacroTemplateExpander.firstUnencodable(MacroTemplateHeader(charset = charset), "中😀"),
            )
        }

        val gbk = MacroTemplateHeader(charset = "GBK")
        // 「中」编得出来、😀 编不出来：要指出后者（辅助平面按完整码点显示，不拆成半个代理项）
        val unit = MacroTemplateExpander.firstUnencodable(gbk, "中a😀")
        assertNotNull("GBK 编不出 😀，应当被指出来", unit)
        assertEquals("😀", unit!!.text)
        assertEquals(0x1F600, unit.codePoint)
        assertEquals("位置应当是 😀 的下标", 2, unit.index)

        assertNull("GBK 编得出「汉语」，不该拦", MacroTemplateExpander.firstUnencodable(gbk, "汉语"))
        assertNull(
            "认不出的字符集名字不在这里报",
            MacroTemplateExpander.firstUnencodable(MacroTemplateHeader(charset = "NoSuchPage"), "中"),
        )
    }

    /**
     * 两种「Unicode 口径」的切法必须分开 —— 这是最容易走叉的一处不变量：
     *
     * - `Unicode` 按**完整码点**切，`😀` 是一次注入（Linux `Ctrl+Shift+U` 的 1~6 位十六进制要它）；
     * - `UTF-16` 按**代码单元**切，`😀` 是两次注入（Windows 十六进制 / Word `Alt+X` /
     *   macOS Hex Input 要的就是逐步补代理对）。
     *
     * 一旦两种口径走成同一种切法，辅助平面就会在其中一条路上发错数字。
     */
    @Test
    fun unicodeAndUtf16SplitInjectionUnitsDifferently() {
        val emoji = "😀"

        assertEquals(listOf(0x1F600), MacroTemplateExpander.injectionUnits("Unicode", emoji))
        assertEquals(listOf(0xD83D, 0xDE00), MacroTemplateExpander.injectionUnits("UTF-16", emoji))
        assertEquals(0x1F600, MacroTemplateExpander.encodeValue(0x1F600, "Unicode"))
    }

    private companion object {
        /** 与 `TemplatePreviewDialog.kt` 里的 `MAX_SAMPLE_LENGTH` 保持一致 */
        const val SAMPLE_TEXT_LIMIT = 8
    }
}
