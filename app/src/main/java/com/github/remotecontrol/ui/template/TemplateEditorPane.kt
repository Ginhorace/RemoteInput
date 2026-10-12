package com.github.remotecontrol.ui.template

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.remotecontrol.R
import com.github.remotecontrol.ui.TextInputPanel
import com.github.remotecontrol.ui.theme.LocalAppDimens
import com.github.remotecontrol.ui.theme.LocalAppTokens
import com.github.remotecontrol.ui.theme.MonoMedium
import com.github.remotecontrol.ui.theme.PrimaryButton
import com.github.remotecontrol.ui.theme.SecondaryButton
import com.github.remotecontrol.util.AppPreferences
import com.github.remotecontrol.util.CharsetCatalog
import com.github.remotecontrol.util.template.MacroTemplate
import com.github.remotecontrol.util.template.MacroTemplateHeader
import com.github.remotecontrol.util.template.MacroTemplateParser
import com.github.remotecontrol.util.template.MacroTemplateStep
import com.github.remotecontrol.util.template.TemplateField
import com.github.remotecontrol.util.template.TemplateSeverity
import com.github.remotecontrol.util.template.hasActiveCode
import com.github.remotecontrol.viewmodel.BluetoothHidViewModel

// ─────────────────────────────────────────────
// 模板编辑器（鼠标宏风格）
// ─────────────────────────────────────────────

@Composable
internal fun TemplateEditorPane(
    viewModel: BluetoothHidViewModel,
    draft: TemplateDraft,
    isLandscape: Boolean,
    onHeaderChange: (MacroTemplateHeader) -> Unit,
    onStepsChange: (List<MacroTemplateStep>) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 模型校验 + 「模型表达不出来」的文件诊断（合并规则在 editorDiagnostics 里，
    // 免得同一个错误既由模型报一次、又由文件那一份报一次）
    val diagnostics = remember(draft.header, draft.steps, draft.fileDiagnostics) {
        MacroTemplateParser.editorDiagnostics(draft.header, draft.steps, draft.fileDiagnostics)
    }
    // 有错误（警告不算）就关掉发送面板：展开器对未知键是「跳过」，
    // 带着错误发送不会崩，但会静默发错字符
    val hasError = diagnostics.any { it.severity == TemplateSeverity.ERROR }
    // 发送文本比预览多一条要求：模板得真的有**启用的**编码位，否则按字符发出去什么都打不出来。
    // 展开预览里的「测试」不加这条 —— 它就是拿来试半成品的（把 `<code>` 勾掉试 down / up 顺序）
    val canSendText = !hasError && draft.steps.hasActiveCode()

    // 错误落到哪个头部字段 / 哪条步骤上，用来在界面上标红。
    // 步骤的 line 是「第几条」（从 1 起，见 validateModel），头部问题带的是字段名而不是行号
    val errorFields = remember(diagnostics) {
        diagnostics.filter { it.severity == TemplateSeverity.ERROR }
            .mapNotNull { it.field }
            .toSet()
    }
    val errorStepIndexes = remember(diagnostics) {
        diagnostics.filter {
            it.severity == TemplateSeverity.ERROR && it.field == null && it.line > 0
        }.map { it.line - 1 }.toSet()
    }

    // 默认折叠，折叠后按钮上直接显示当前参数，不占额外空间
    var headerExpanded by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableIntStateOf(-1) }
    var showPreview by remember { mutableStateOf(false) }
    var showDescription by remember { mutableStateOf(false) }

    // 步骤列表是**自己**滚动的一格（见下面 StepList 的 heightIn + verticalScroll）：
    // 新加的步骤滚进视野时，只动这一格，Header 与添加步骤区待在原地不动
    val stepListScrollState = rememberScrollState()
    // 基准条数跟着模板文件走：切换模板时重置，免得一打开就滚到底部
    var lastStepCount by remember(draft.file) { mutableIntStateOf(draft.steps.size) }

    // 新加的步骤排在列表末尾，不滚过去的话点完看不到它落在哪一行。
    // 只有「条数变多」才滚：删除、拖动排序、切换模板都不该动滚动位置
    LaunchedEffect(draft.steps.size) {
        val added = draft.steps.size > lastStepCount
        lastStepCount = draft.steps.size
        if (!added) return@LaunchedEffect
        // 新加的步骤还没参与布局时 maxValue 还是旧值，等一帧再滚
        withFrameNanos { }
        stepListScrollState.animateScrollTo(stepListScrollState.maxValue)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 模板名称只在 Header 折叠时显示在标题行，展开时由 Header 里的名称输入框负责
            val title = stringResource(R.string.template_editor_title)
            val name = draft.header.name?.takeIf { it.isNotBlank() }
            Text(
                text = if (!headerExpanded && name != null) "$title · $name" else title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showPreview = true }) {
                Icon(
                    Icons.Default.Visibility,
                    contentDescription = stringResource(R.string.template_preview),
                    modifier = Modifier.size(20.dp)
                )
            }
            // 主/次操作成对出现：保存走第 5 档满浓度主按钮，取消走第 2 档底 + 第 3 档描边的次级按钮
            SecondaryButton(
                text = stringResource(R.string.cancel),
                onClick = onCancel,
            )
            PrimaryButton(
                text = stringResource(R.string.template_save),
                onClick = onSave,
            )
        }

        // 上半区整体可滚动，并且能被键盘挤小。
        //
        // 窗口是 adjustNothing（键盘不改变窗口高度，只盖在上面），所以让位全靠 imePadding。
        // 若这里的子项高度写死，展开「模板参数 / 添加步骤」后总高度会超出可用空间，
        // 排在最后的发送面板就会被顶到键盘后面 —— 所以这里必须 weight + 滚动，
        // 让溢出变成滚动而不是位移。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeaderSection(
                expanded = headerExpanded,
                header = draft.header,
                errorFields = errorFields,
                onToggle = { headerExpanded = !headerExpanded },
                onHeaderChange = onHeaderChange,
            )

            // 说明挨着 Header：它讲的是这张模板「做什么」，排在参数与步骤之间才顺
            DescriptionCard(
                description = draft.header.description,
                onClick = { showDescription = true },
            )

            // 展开状态由面板自己持有：外层只管把「加哪些步骤」这件事接下来
            AddStepBar(onAdd = { onStepsChange(draft.steps + it) })

            // 步骤列表自己滚（外层那个 verticalScroll 只是「展开 Header / 添加步骤后放不下」的兜底）：
            // 加了新步骤只滚这一格，Header 与添加步骤区不会被推走
            StepList(
                steps = draft.steps,
                errorIndexes = errorStepIndexes,
                onMove = { from, to -> onStepsChange(draft.steps.moveItem(from, to)) },
                onClick = { editingIndex = it },
                onToggleEnabled = { index -> onStepsChange(draft.steps.toggleEnabled(index)) },
                scrollState = stepListScrollState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = STEP_LIST_MAX_HEIGHT)
                    .verticalScroll(stepListScrollState),
            )

            TemplateStatusRow(
                diagnostics = diagnostics,
                stepCount = draft.steps.size,
                header = draft.header,
            )

            if (diagnostics.isNotEmpty()) {
                TemplateDiagnostics(
                    diagnostics = diagnostics,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (isLandscape) 140.dp else 96.dp),
                )
            }
        }

        // 界面最下方：直接输入文本并按当前模板发送（使用模板）
        // imePadding 与键盘模式保持一致：窗口不缩放，键盘弹出时只压缩上方区域
        TextInputPanel(
            viewModel = viewModel,
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
            template = MacroTemplate(draft.header, draft.steps),
            enabled = canSendText,
        )
    }

    if (showPreview) {
        TemplatePreviewDialog(
            viewModel = viewModel,
            header = draft.header,
            steps = draft.steps,
            canSend = !hasError,
            onDismiss = { showPreview = false },
        )
    }

    if (showDescription) {
        TemplateDescriptionDialog(
            description = draft.header.description.orEmpty(),
            onConfirm = {
                onHeaderChange(draft.header.copy(description = it))
                showDescription = false
            },
            onDismiss = { showDescription = false },
        )
    }

    if (editingIndex in draft.steps.indices) {
        StepEditDialog(
            step = draft.steps[editingIndex],
            onConfirm = { newSteps ->
                onStepsChange(draft.steps.replaceAt(editingIndex, newSteps))
                editingIndex = -1
            },
            onDelete = {
                onStepsChange(draft.steps.removeAt(editingIndex))
                editingIndex = -1
            },
            onDismiss = { editingIndex = -1 },
        )
    }
}

/**
 * 反转第 [index] 条步骤的启用状态。
 *
 * 只换这一条对象，其余步骤的引用原样留在新列表里，所以「哪一行变了」对 Compose 与
 * 展开预览的缓存都是清楚的。
 */
private fun List<MacroTemplateStep>.toggleEnabled(index: Int): List<MacroTemplateStep> {
    val step = getOrNull(index) ?: return this
    return toMutableList().apply { this[index] = step.withEnabled(!step.enabled) }
}

private fun List<MacroTemplateStep>.moveItem(from: Int, to: Int): List<MacroTemplateStep> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

private fun List<MacroTemplateStep>.replaceAt(
    index: Int,
    newSteps: List<MacroTemplateStep>,
): List<MacroTemplateStep> =
    toMutableList().apply {
        removeAt(index)
        addAll(index, newSteps)
    }

private fun List<MacroTemplateStep>.removeAt(index: Int): List<MacroTemplateStep> =
    toMutableList().apply { removeAt(index) }

/**
 * 步骤列表可见高度的上限（6 条：52dp 行高 + 4dp 间距 = 6 × 52 + 5 × 4）。
 *
 * 有上限，步骤列表才是一格**独立滚动**的区域：不加限制它会一直长高，把 Header 与
 * 添加步骤区顶出屏幕，用户加完一条就得整页滚回去找刚才点的按钮。超出部分在这一格内滚动，
 * 外层滚动因此只在「展开 Header / 添加步骤后实在放不下」时兜底。
 */
private val STEP_LIST_MAX_HEIGHT = 332.dp

// ─────────────────────────────────────────────
// 头部参数：3 × 2 直接修改
// ─────────────────────────────────────────────

@Composable
private fun HeaderSection(
    expanded: Boolean,
    header: MacroTemplateHeader,
    /** 有错误的头部字段，标红用 */
    errorFields: Set<TemplateField>,
    onToggle: () -> Unit,
    onHeaderChange: (MacroTemplateHeader) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(LocalAppDimens.current.innerCorner),
            border = fieldBorder(),
        ) {
            Icon(
                Icons.Default.Tune,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (expanded) {
                    stringResource(R.string.template_header_title)
                } else {
                    headerSummary(header)
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }

        if (expanded) {
            HeaderEditor(
                header = header,
                errorFields = errorFields,
                onHeaderChange = onHeaderChange,
            )
        }
    }
}

/** 折叠后在按钮上直接展示参数内容，避免必须展开才能确认当前配置 */
@Composable
private fun headerSummary(header: MacroTemplateHeader): String = stringResource(
    R.string.template_header_summary,
    header.charset,
    header.radix,
    header.templateGapMs,
    header.keyHoldMs,
    header.keyGapMs,
)

@Composable
private fun HeaderEditor(
    header: MacroTemplateHeader,
    errorFields: Set<TemplateField>,
    onHeaderChange: (MacroTemplateHeader) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 下拉里去掉被禁用的项：禁用只是「编辑模板时少个选项」，不影响这张模板自己的 charset
    // 能不能执行 —— 所以当前值即使被禁用了也照样显示（下面 value 取不到就回退原值）
    val context = LocalContext.current
    val prefs = remember(context) { AppPreferences.getInstance(context) }
    val disabledCharsets by prefs.disabledCharsets.collectAsState()
    val customCharsets by prefs.customCharsets.collectAsState()

    val charsetOptions = remember(disabledCharsets, customCharsets) {
        CharsetCatalog.ALL
            .filter { it.name !in disabledCharsets }
            .map { it.name to it.name }
    }
    val radixOptions = remember {
        MacroTemplateHeader.SUPPORTED_RADIX.sorted().map { it to it.toString() }
    }
    var showCharsetHint by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LocalAppDimens.current.cardCorner),
        colors = CardDefaults.cardColors(containerColor = LocalAppTokens.current.card),
        border = BorderStroke(LocalAppDimens.current.strokeWidth, LocalAppTokens.current.line)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 第 1 行：名称单独一行（它是最长的文本，挤在三列里会看不全）
            OutlinedTextField(
                value = header.name.orEmpty(),
                onValueChange = { onHeaderChange(header.copy(name = it)) },
                label = { Text(stringResource(R.string.template_name), style = MaterialTheme.typography.labelSmall) },
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            // 第 2 行：字符集提示 / 字符集 / 进制（提示按钮紧挨它解释的字段）
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { showCharsetHint = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = stringResource(R.string.charset_hint_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                HeaderDropdown(
                    label = stringResource(R.string.template_header_charset),
                    value = header.charset,
                    options = charsetOptions,
                    isError = TemplateField.CHARSET in errorFields,
                    onValueChange = { onHeaderChange(header.copy(charset = it)) },
                    modifier = Modifier.weight(1f),
                )
                HeaderDropdown(
                    label = stringResource(R.string.template_header_radix),
                    value = header.radix,
                    options = radixOptions,
                    isError = TemplateField.RADIX in errorFields,
                    onValueChange = { onHeaderChange(header.copy(radix = it)) },
                    modifier = Modifier.weight(1f),
                )
            }

            // 第 3 行：模板间隔 / 按下保持 / 按键间隔
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HeaderNumberField(
                    label = stringResource(R.string.template_header_template_gap),
                    value = header.templateGapMs,
                    onValueChange = { onHeaderChange(header.copy(templateGapMs = it)) },
                    modifier = Modifier.weight(1f),
                    isError = TemplateField.TEMPLATE_GAP in errorFields,
                )
                HeaderNumberField(
                    label = stringResource(R.string.template_header_key_hold),
                    value = header.keyHoldMs,
                    onValueChange = { onHeaderChange(header.copy(keyHoldMs = it)) },
                    modifier = Modifier.weight(1f),
                    min = MacroTemplateHeader.MIN_KEY_DELAY_MS,
                    isError = TemplateField.KEY_HOLD in errorFields,
                )
                HeaderNumberField(
                    label = stringResource(R.string.template_header_key_gap),
                    value = header.keyGapMs,
                    onValueChange = { onHeaderChange(header.copy(keyGapMs = it)) },
                    modifier = Modifier.weight(1f),
                    min = MacroTemplateHeader.MIN_KEY_DELAY_MS,
                    isError = TemplateField.KEY_GAP in errorFields,
                )
            }
        }
    }

    if (showCharsetHint) {
        CharsetAliasDialog(onDismiss = { showCharsetHint = false })
    }
}

/**
 * 字符集 → 「语言 / 地区 + 页型」的文案。
 *
 * 键是 [CharsetCatalog] 里的规范名。文案属于界面层，所以放在这里而不是 `CharsetCatalog`
 * —— 那边的原则是「只存数据，文案在 UI 层按当前语言解析」。
 *
 * 口径与 `docs/summary/编码参考表.md` 总表的「语言 / 地区」列一致（写 `简体中文（ansi/oem）`
 * 这种形式）：那一列已经把「哪张码页、走哪条 Alt 路径」讲清楚了，比只写语言名更精准。
 * 同一格可以覆盖多种语言：西欧各国共用一套 ANSI 代码页，合并显示反而更好查。
 *
 * 新增字符集时忘了补这里也不会丢行，只是那一行没有语言说明。
 */
private val CHARSET_LANGUAGE_RES: Map<String, Int> = mapOf(
    "GBK" to R.string.charset_lang_gbk,
    "Big5" to R.string.charset_lang_big5,
    "windows-31j" to R.string.charset_lang_windows_31j,
    "windows-949" to R.string.charset_lang_windows_949,
    "windows-1252" to R.string.charset_lang_windows_1252,
    "IBM437" to R.string.charset_lang_ibm437,
    "IBM850" to R.string.charset_lang_ibm850,
    "windows-1251" to R.string.charset_lang_windows_1251,
    "IBM866" to R.string.charset_lang_ibm866,
    "windows-1256" to R.string.charset_lang_windows_1256,
    "IBM720" to R.string.charset_lang_ibm720,
    "Unicode" to R.string.charset_lang_unicode,
    "UTF-16" to R.string.charset_lang_utf16,
    "US-ASCII" to R.string.charset_lang_us_ascii,
)

/**
 * 哪几行还要在左栏多补一句。
 *
 * 目前只有 `Unicode`：Word 的 Alt Code 走的是它自己那套（等于重载了这个机制），
 * 码页那一列在 Word 里不适用 —— 这一条正是 OEM / ANSI 两份预设说明里
 * 「Word 里需要改成 Unicode」那句话的出处，摆在这张表上最容易被查到。
 */
private val CHARSET_ROW_HINT_RES: Map<String, Int> = mapOf(
    "Unicode" to R.string.charset_word_alt_code_hint,
)

/**
 * 字符集与语言对照。
 *
 * 左侧是每种字符集在 Windows 上覆盖的语言（多种语言共用一套代码页时合并成一格），
 * 右侧是写进模板的规范名与它在系统里的等价别名（Windows 代码页写法等）。
 * 代码页与 `UTF-*` 这两类名字 `Charset.forName` 都认；`Unicode` 是项目自定义名，
 * 展开器对它特判、不查注册表（见 [CharsetCatalog] 里那一条的注释）。
 *
 * 遍历的是 [CharsetCatalog.OPTIONS]（**内置**那 14 项）而不是上面那张映射表，所以新增内置字符集
 * 一定会出现在这里；用户自定义的条目不在其中 —— 它们没有「语言 / 地区」可写，
 * 也谈不上 Windows 上的等价别名，列进来只会多出一列空白。
 */
@Composable
private fun CharsetAliasDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.charset_hint_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 两句说明用 bodySmall（表格行是 labelSmall）：说明是成段读的文字，
                // 比表格里的短标签大两档才不费眼
                Text(
                    text = stringResource(R.string.charset_hint_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 这张表的两个前提：多数页收不全 Unicode 的字符；不同页之间同一字符的码位可能不同。
                // 与上面那句分开，是因为它是「这张表不能保证什么」，混在一段里会被当成客套话划过去
                Text(
                    text = stringResource(R.string.charset_hint_limits),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                CharsetCatalog.OPTIONS.forEachIndexed { index, option ->
                    if (index > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (index == 0) 8.dp else 0.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        val languageRes = CHARSET_LANGUAGE_RES[option.name]
                        val rowHintRes = CHARSET_ROW_HINT_RES[option.name]
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (languageRes != null) stringResource(languageRes) else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (rowHintRes != null) {
                                Text(
                                    text = stringResource(rowHintRes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.name,
                                style = MonoMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            if (option.aliases.isNotEmpty()) {
                                Text(
                                    text = option.aliases.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
    )
}
