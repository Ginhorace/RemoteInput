package com.github.remotecontrol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.remotecontrol.R
import com.github.remotecontrol.ui.theme.ElevatedCard
import com.github.remotecontrol.ui.theme.LocalAppDimens
import com.github.remotecontrol.ui.theme.LocalAppTokens
import com.github.remotecontrol.ui.theme.MonoMedium
import com.github.remotecontrol.ui.theme.SectionTitle
import com.github.remotecontrol.ui.theme.TintLevel
import com.github.remotecontrol.ui.theme.hueDotColor
import com.github.remotecontrol.util.CharsetAddResult
import com.github.remotecontrol.util.CharsetCatalog
import com.github.remotecontrol.util.HueScheme
import com.github.remotecontrol.util.KeyboardLayoutMode
import com.github.remotecontrol.util.LanguageMode
import com.github.remotecontrol.util.PrefGroup
import com.github.remotecontrol.util.SettingConstants
import com.github.remotecontrol.util.ThemeMode

/**
 * 设置页统一使用的下拉菜单。
 *
 * 关键点：`expanded` 状态收拢在这个独立的可组合项里，因此点击展开时
 * 只有本组件（TextField + 菜单）重组，不会像之前那样让整个 Section
 * （标题、说明文字、6 个色相行……）跟着重组。
 *
 * 选项直接取自 [PrefGroup]：列表在 PrefGroup 构造时就已算好、引用恒定，
 * 选中文案走的是 Map 查表，都足够廉价，因此这里不需要任何 remember 缓存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T : Enum<T>> SettingsExposedDropdown(
    label: String,
    group: PrefGroup<T>,
    selectedValue: T,
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val selectedLabelRes = group.getDisplayNameOrDefault(selectedValue)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        // 提前锁定宽度，减少菜单弹出时因测量锚点宽度而多出来的一次布局/重组
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = stringResource(selectedLabelRes),
            onValueChange = {},
            readOnly = true,
            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(LocalAppDimens.current.innerCorner),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = LocalAppTokens.current.tint,
                unfocusedContainerColor = LocalAppTokens.current.tint,
                focusedBorderColor = LocalAppTokens.current.accent,
                unfocusedBorderColor = LocalAppTokens.current.tintLine,
            ),
            modifier = Modifier
                .fillMaxWidth()
                // PrimaryNotEditable：只读锚点，点击时不会触发软键盘/焦点动画，
                // 省掉 PrimaryEditable 那套 IME 相关的额外开销
                .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            group.valueOptions.forEach { (value, labelRes) ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    onClick = {
                        onValueChange(value)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    hueScheme: HueScheme,
    onHueSchemeChange: (HueScheme) -> Unit,
    languageMode: LanguageMode,
    onLanguageModeChange: (LanguageMode) -> Unit,
    keyboardLayoutMode: KeyboardLayoutMode,
    onKeyboardLayoutModeChange: (KeyboardLayoutMode) -> Unit,
    disabledCharsets: Set<String>,
    customCharsets: List<String>,
    onCharsetEnabledChange: (String, Boolean) -> Unit,
    onAddCharset: (String) -> CharsetAddResult,
    onRemoveCharset: (String) -> Unit,
    usbipPort: Int,
    onUsbipPortChange: (Int) -> Unit,
    longPressDuration: Int,
    onLongPressDurationChange: (Int) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    // 与 MainActivity / ControlActivity 顶栏统一到第 1 档
                    containerColor = LocalAppTokens.current.appBar,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        val dimens = LocalAppDimens.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            ThemeSettingsSection(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                hueScheme = hueScheme,
                onHueSchemeChange = onHueSchemeChange
            )

            Spacer(modifier = Modifier.height(dimens.sectionGap))

            LanguageSettingsSection(
                languageMode = languageMode,
                onLanguageModeChange = onLanguageModeChange
            )

            Spacer(modifier = Modifier.height(dimens.sectionGap))

            KeyboardSettingsSection(
                keyboardLayoutMode = keyboardLayoutMode,
                onKeyboardLayoutModeChange = onKeyboardLayoutModeChange
            )

            Spacer(modifier = Modifier.height(dimens.sectionGap))

            MouseSettingsSection(
                longPressDuration = longPressDuration,
                onLongPressDurationChange = onLongPressDurationChange,
            )

            Spacer(modifier = Modifier.height(dimens.sectionGap))

            // 端口排在字符集**之前**：字符集那一节很长（14 项内置 + 自定义），
            // 端口这项既短又只跟局域网有关，不该被压在长长的字符集列表下面
            LanSettingsSection(
                usbipPort = usbipPort,
                onUsbipPortChange = onUsbipPortChange,
            )

            Spacer(modifier = Modifier.height(dimens.sectionGap))

            CharsetSettingsSection(
                disabledCharsets = disabledCharsets,
                customCharsets = customCharsets,
                onCharsetEnabledChange = onCharsetEnabledChange,
                onAddCharset = onAddCharset,
                onRemoveCharset = onRemoveCharset,
            )

            Spacer(modifier = Modifier.height(dimens.sectionGap))
        }
    }
}

@Composable
fun ThemeSettingsSection(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    hueScheme: HueScheme,
    onHueSchemeChange: (HueScheme) -> Unit
) {
    val tokens = LocalAppTokens.current
    val dimens = LocalAppDimens.current

    // 选择器里的色块要按"当前实际生效的明暗"来上色，所以不能用 isSystemInDarkTheme()
    // 直接判断 —— 用户在主题模式里可能显式选了深色/浅色
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    Column(modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = dimens.sectionGap)) {
        ElevatedCard(level = TintLevel.SURFACE) {
            SectionTitle(text = stringResource(R.string.theme_settings), icon = Icons.Default.Palette)

            SettingsExposedDropdown(
                label = stringResource(R.string.theme_mode),
                group = SettingConstants.THEME_MODE,
                selectedValue = themeMode,
                onValueChange = onThemeModeChange
            )

            Spacer(modifier = Modifier.height(dimens.sectionGap))

            Text(
                text = stringResource(R.string.hue_scheme),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            // 色相选择器：圆形色块 + 名称 + radio。
            // 选中的行套第 2 档底 + 第 3 档描边，和"由浅入深"保持同一套语言。
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SettingConstants.HUE_SCHEME.valueOptions.forEach { (hue, nameRes) ->
                    val selected = hue == hueScheme
                    val shape = RoundedCornerShape(dimens.innerCorner)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .then(
                                if (selected) {
                                    Modifier
                                        .background(tokens.tint)
                                        .border(dimens.strokeWidth, tokens.tintLine, shape)
                                } else Modifier
                            )
                            .selectable(
                                selected = selected,
                                onClick = { onHueSchemeChange(hue) },
                                role = Role.RadioButton
                            )
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(hueDotColor(hue, darkTheme))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(nameRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        RadioButton(selected = selected, onClick = null)
                    }
                }
            }
        }
    }
}

@Composable
fun LanguageSettingsSection(
    languageMode: LanguageMode,
    onLanguageModeChange: (LanguageMode) -> Unit
) {
    val dimens = LocalAppDimens.current

    Column(modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 0.dp)) {
        ElevatedCard(level = TintLevel.SURFACE) {
            SectionTitle(text = stringResource(R.string.language_settings), icon = Icons.Default.Language)

            SettingsExposedDropdown(
                label = stringResource(R.string.language_mode),
                group = SettingConstants.LANGUAGE_MODE,
                selectedValue = languageMode,
                onValueChange = onLanguageModeChange
            )
        }
    }
}

/**
 * 键盘设置：宿主键盘布局。
 *
 * 说明文字是必要的 —— 这一项看起来像是「本该自动探测」的，但宿主在 Windows 上按
 * `Win+Space` 切换布局时不会通过 HID 通知设备，应用拿不到，只能由用户手选；
 * 选错的后果是「按 A 出 Q」这类静默错字，不说明就会变成玄学 bug。
 */
@Composable
fun KeyboardSettingsSection(
    keyboardLayoutMode: KeyboardLayoutMode,
    onKeyboardLayoutModeChange: (KeyboardLayoutMode) -> Unit,
) {
    val dimens = LocalAppDimens.current

    Column(modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 0.dp)) {
        ElevatedCard(level = TintLevel.SURFACE) {
            SectionTitle(text = stringResource(R.string.keyboard_settings), icon = Icons.Default.Keyboard)

            SettingsExposedDropdown(
                label = stringResource(R.string.keyboard_layout),
                group = SettingConstants.KEYBOARD_LAYOUT,
                selectedValue = keyboardLayoutMode,
                onValueChange = onKeyboardLayoutModeChange
            )

            Text(
                text = stringResource(R.string.keyboard_layout_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            // 第二段讲「发送模板时要注意什么」：模板一律按美式键位发出，与上一条说明是一件事的
            // 两个面（上面说「选错会出错字」，这里说「错在哪、怎么改回来」）
            Text(
                text = stringResource(R.string.keyboard_layout_send_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/**
 * 字符集设置：启用 / 禁用清单里的字符集，新增自定义字符集，以及删除自定义的那些。
 *
 * 三处刻意的取舍：
 *
 * 1. **禁用只写进偏好，不改 [CharsetCatalog]** —— 被禁用的字符集照样能解析、能展开、能发送，
 *    只是不再出现在模板编辑器的下拉里。「关掉一个选项」不该弄坏已经在用的模板；
 * 2. **删除改的正是 [CharsetCatalog]** —— 名字一离开清单，用它写过的模板就会报
 *    `UNKNOWN_CHARSET`。这是删除与禁用的唯一区别，也是它要走确认对话框的原因；
 * 3. **新增走 [SettingsViewModel.addCharset] 的校验**，只有「运行时认这个名字」且「不与已有
 *    规范名 / 别名重复」才落盘。Android 上同一张码页的几种写法就是同一个 `Charset`、
 *    映射也一致，所以只需要按名字判断，不必像桌面 JDK 那样再比一遍编码表。
 *
 * 列表里**自定义条目铺一层警告色底**（[AppTokens.warnContainer]），行内文字与开关维持原样，
 * 只多一个删除按钮。
 *
 * 这块标记试过多版，结论记在这里免得再来回：
 *
 * 1. 中性 `panel` 底色 —— 灰度与整套色相家族不搭；但「只有底色稍微不一致」这个**力度**是对的，
 *    现在这版沿用；
 * 2. 第 3 档 `tintLine`、第 4 档 `activeLine` 描边 —— 前者与卡片自己那道描边同色（等于没画），
 *    后者是「选中 / 拖拽」的档位，都不属于设置项；
 * 3. 低浓度铺底看不出效果 —— 原因不在浓度，而在**混错了层**：底要按它实际落上去的卡片底算
 *    （见 [AppTokens.warnContainer]）；
 * 4. 实心 `warn` + `onWarn` 内容 —— 太沉，浅色下整行字都翻白，与周围不像同一张列表；
 * 5. 现在这版：`warnContainer` 轻铺一层，文字、开关、删除图标各按各的语义色走。
 *
 * **开关与文字不参与这套标记**：同一屏里的开关必须长得一样，正文也不该因为「这一项是自定义的」
 * 就换颜色 —— 标记只落在底色上。
 */
@Composable
fun CharsetSettingsSection(
    disabledCharsets: Set<String>,
    customCharsets: List<String>,
    onCharsetEnabledChange: (String, Boolean) -> Unit,
    onAddCharset: (String) -> CharsetAddResult,
    onRemoveCharset: (String) -> Unit,
) {
    val tokens = LocalAppTokens.current
    val dimens = LocalAppDimens.current

    // CharsetCatalog.ALL 是即时读出来的，但只有「读到一个会变的状态」才会触发重组：
    // customCharsets 就是这个状态（新增 / 删除后它变，这里才跟着刷新）
    val options = remember(customCharsets) { CharsetCatalog.ALL }

    var input by remember { mutableStateOf("") }
    var addResult by remember { mutableStateOf<CharsetAddResult?>(null) }

    // 待确认删除的那个自定义字符集。删除会牵动正在用它的模板，所以不给「点一下就没了」的机会
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    // 自定义条目的铺底形状（一行一块底，与卡片同一档圆角）
    val customShape = remember(dimens.innerCorner) { RoundedCornerShape(dimens.innerCorner) }

    Column(modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 0.dp)) {
        ElevatedCard(level = TintLevel.SURFACE) {
            SectionTitle(
                text = stringResource(R.string.charset_settings),
                icon = Icons.Default.Translate
            )

            Text(
                text = stringResource(R.string.charset_settings_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    // 改动输入即清掉上一次的结果提示，免得「成功」挂在一个已被改过的名字上
                    onValueChange = {
                        input = it
                        addResult = null
                    },
                    label = { Text(stringResource(R.string.charset_add_label), style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(dimens.innerCorner),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = tokens.tint,
                        unfocusedContainerColor = tokens.tint,
                        focusedBorderColor = tokens.accent,
                        unfocusedBorderColor = tokens.tintLine,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val result = onAddCharset(input)
                        addResult = result
                        if (result is CharsetAddResult.Ok) input = ""
                    },
                    enabled = input.isNotBlank(),
                ) {
                    Text(stringResource(R.string.charset_add))
                }
            }

            CharsetAddMessage(addResult)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            options.forEachIndexed { index, option ->
                if (index > 0) {
                    HorizontalDivider(color = tokens.line)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (option.isCustom) {
                                Modifier
                                    .clip(customShape)
                                    .background(tokens.warnContainer)
                            } else Modifier
                        )
                        .padding(
                            start = if (option.isCustom) 8.dp else 0.dp,
                            end = 0.dp,
                            top = 4.dp,
                            bottom = 4.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = option.name,
                            style = MonoMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (option.aliases.isNotEmpty()) {
                            Text(
                                text = option.aliases.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // 删除按钮只给自定义条目：内置的那些删不掉（也不该删），不想用就禁用
                    if (option.isCustom) {
                        IconButton(onClick = { pendingDelete = option.name }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.charset_delete),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    // 开关一律用默认配色：同一屏里的开关必须长得一样，
                    // 自定义行铺了警告底也不例外（见上面那段说明）
                    Switch(
                        checked = option.name !in disabledCharsets,
                        onCheckedChange = { onCharsetEnabledChange(option.name, it) },
                    )
                }
            }
        }
    }

    pendingDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.charset_delete)) },
            text = { Text(stringResource(R.string.charset_delete_confirm, name)) },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveCharset(name)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** 新增字符集的结果提示：成功走强调色，失败走错误色 */
@Composable
private fun CharsetAddMessage(result: CharsetAddResult?) {
    if (result == null) return

    val text = when (result) {
        is CharsetAddResult.Ok -> stringResource(R.string.charset_add_ok, result.name)
        is CharsetAddResult.Duplicate -> stringResource(R.string.charset_add_duplicate, result.existing)
        is CharsetAddResult.NotFound -> stringResource(R.string.charset_add_not_found, result.name)
        CharsetAddResult.Blank -> stringResource(R.string.charset_add_blank)
    }
    val color = if (result is CharsetAddResult.Ok) {
        LocalAppTokens.current.accent
    } else {
        MaterialTheme.colorScheme.error
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * 局域网（USB/IP）设置：监听端口。
 *
 * 做成设置项的理由通常只有一个 —— 3240 被别的程序占了。两个细节：
 *
 * 1. **只有合法端口才回写偏好**：输入框自己管一份文本，用户敲到一半的 `80` 不该被当成一次改动
 *    （也免得落盘一个永远绑不上的值）；越界时输入框标红、说明文字转错误色；
 * 2. **改了会把正在跑的监听停掉**（见 `UsbIpInputViewModel`）：服务端绑的还是旧端口，
 *    而卡片显示的是新端口，不停的话界面就开始骗人了。
 */
@Composable
fun LanSettingsSection(
    usbipPort: Int,
    onUsbipPortChange: (Int) -> Unit,
) {
    val dimens = LocalAppDimens.current

    // key 用 usbipPort：合法值一落盘就跟着复位（把「用户敲到一半的残留」丢掉），
    // 也让旋转 / 重建之后输入框回到已生效的那个值上
    var text by rememberSaveable(usbipPort) { mutableStateOf(usbipPort.toString()) }

    val valid = text.toIntOrNull()
        ?.let { it in SettingConstants.USBIP_PORT_MIN..SettingConstants.USBIP_PORT_MAX }
        ?: false

    Column(modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 0.dp)) {
        ElevatedCard(level = TintLevel.SURFACE) {
            SectionTitle(text = stringResource(R.string.lan_title), icon = Icons.Default.Wifi)

            Text(
                text = stringResource(R.string.lan_port),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    // 只留数字、最多 5 位；中间态不动偏好，合法了才落盘
                    val digits = raw.filter { it.isDigit() }.take(5)
                    text = digits
                    digits.toIntOrNull()
                        ?.takeIf { it in SettingConstants.USBIP_PORT_MIN..SettingConstants.USBIP_PORT_MAX }
                        ?.let(onUsbipPortChange)
                },
                isError = !valid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = stringResource(R.string.lan_port_hint),
                style = MaterialTheme.typography.bodySmall,
                color = if (valid) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    LocalAppTokens.current.error
                },
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
