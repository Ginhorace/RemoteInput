package com.github.remotecontrol.ui.template

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
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
import com.github.remotecontrol.ui.theme.LocalAppDimens
import com.github.remotecontrol.ui.theme.LocalAppTokens
import com.github.remotecontrol.ui.theme.MonoMedium
import com.github.remotecontrol.ui.theme.SectionTitle
import com.github.remotecontrol.util.AppPreferences
import com.github.remotecontrol.util.HostLayoutNotice
import com.github.remotecontrol.util.KeyboardLayoutCatalog
import com.github.remotecontrol.util.LocaleHelper
import com.github.remotecontrol.util.template.MacroTemplateFile
import com.github.remotecontrol.util.template.MacroTemplateLoadError
import com.github.remotecontrol.util.template.MacroTemplateParser
import com.github.remotecontrol.util.template.MacroTemplatePreset
import com.github.remotecontrol.util.template.MacroTemplatePresets
import com.github.remotecontrol.util.template.MacroTemplateStore
import com.github.remotecontrol.hid.HidSession
import java.io.File

@Composable
fun TemplateModeScreen(
    viewModel: HidSession,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false
) {
    val context = LocalContext.current
    // 偏好必须排在 store 之前取：用户自定义的字符集是在 AppPreferences 初始化时登记进
    // CharsetCatalog 的，而 store 一建好就会把已有模板全解析一遍，那时清单得已经是齐的
    val prefs = remember(context) { AppPreferences.getInstance(context) }
    val store = remember(context) { MacroTemplateStore.getInstance(context) }
    val templates by store.templates.collectAsState()

    // 模板一律按**美式键位**发出：界面语言与宿主布局设置**各判一次**，两处都不是美式就给两条。
    // 读的是设置本身而不是宿主状态（宿主按 Win+Space 切换布局不会通知设备），
    // 所以「跟随界面语言」时语言设置同样算一处 —— 详见 KeyboardLayoutCatalog.noticesFor
    val hostLayoutNotices = remember(prefs.keyboardLayoutMode) {
        KeyboardLayoutCatalog.noticesFor(prefs.keyboardLayoutMode, LocaleHelper.currentLocale(context))
    }

    var draft by remember { mutableStateOf<TemplateDraft?>(null) }
    var pendingDelete by remember { mutableStateOf<MacroTemplateFile?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showPresets by remember { mutableStateOf(false) }

    // 加载即校验：文件即使放在应用私有目录，也可能被 root / adb 备份还原改坏，
    // 所以进入模板模式时先把坏模板告知一次。
    // takeStartupLoadErrors() 进程内只返回一次，之后靠卡片标记 / 诊断列表 / 发送门禁兜底。
    var startupErrors by remember { mutableStateOf<List<MacroTemplateLoadError>>(emptyList()) }
    LaunchedEffect(Unit) { startupErrors = store.takeStartupLoadErrors() }

    val currentDraft = draft

    /**
     * 打开一个模板文件进编辑器。
     *
     * 解析结果里的诊断整份带进草稿（见 [TemplateDraft.fileDiagnostics]）：其中「模型表达不出来」的
     * 那几条（`radix = 8`、`keyHold = abc` …）是编辑器唯一能看到它们的机会。
     */
    fun openTemplate(file: File, fallbackName: String) {
        val parsed = MacroTemplateParser.parse(store.readText(file))
        draft = TemplateDraft(
            file = file,
            fallbackName = fallbackName,
            header = parsed.template.header.copy(name = parsed.template.header.name ?: fallbackName),
            steps = parsed.template.steps,
            fileDiagnostics = parsed.diagnostics,
        )
    }

    if (currentDraft == null) {
        TemplateListPane(
            templates = templates,
            store = store,
            hostLayoutNotices = hostLayoutNotices,
            modifier = modifier,
            onNew = { showPresets = true },
            onOpen = { item -> openTemplate(item.file, item.name) },
            onCopy = { item ->
                // 复制 = 落盘一个副本文件，并直接进入副本的编辑/使用界面
                val copyName = store.uniqueName(context.getString(R.string.template_copy_name, item.name))
                store.save(null, copyName, store.readText(item.file))
                    .onSuccess { saved -> openTemplate(saved, copyName) }
                    .onFailure { errorText = it.message }
            },
            onDelete = { pendingDelete = it },
        )
    } else {
        TemplateEditorPane(
            viewModel = viewModel,
            draft = currentDraft,
            isLandscape = isLandscape,
            modifier = modifier,
            onHeaderChange = { newHeader ->
                draft?.let { d ->
                    // 改过的头部字段，旧的文件诊断不再成立；没动的字段照旧留着（用户可能只改了名称）
                    val stale = changedFields(d.header, newHeader)
                    draft = d.copy(
                        header = newHeader,
                        fileDiagnostics = d.fileDiagnostics.filterNot { it.field in stale },
                    )
                }
            },
            onStepsChange = { draft = draft?.copy(steps = it) },
            onCancel = { draft = null },
            onSave = {
                draft?.let { d ->
                    val name = d.header.name?.takeIf { it.isNotBlank() } ?: d.fallbackName
                    val content = MacroTemplateParser.format(d.header, d.steps)
                    store.save(d.file, name, content)
                        .onSuccess { draft = null }
                        .onFailure { errorText = it.message }
                }
            },
        )
    }

    if (showPresets) {
        // 预设正文分两种：两条 Alt Code 带字符集占位符，要在这一步换成界面语言对应的宿主码页
        // （OEM 版取 OEMCP、ANSI 版取 ACP，由预设自己声明，见 MacroTemplatePresets.resolveCharset），
        // 换完对话框摘要里显示的就是真实字符集；其余预设写死了字符集（十六进制那几条走
        // Unicode 码位，与宿主码页无关）。说明则一律按当前界面语言补进正文。
        // 顺序固定：先 resolveCharset 再 localize —— 带占位符的正文解析出来会报
        // UNKNOWN_CHARSET，localize 见到错误就原样返回了。
        // LocalContext 拿到的正是 attachBaseContext 包过界面语言的 Activity Context；
        // 预设是常量，remember 一次即可，不必每次重组都 parse + format 一遍。
        val presets = remember(context) {
            val locale = LocaleHelper.currentLocale(context)
            MacroTemplatePresets.ALL.map { preset ->
                MacroTemplatePresets.localize(
                    context,
                    MacroTemplatePresets.resolveCharset(preset, locale),
                )
            }
        }
        TemplatePresetDialog(
            presets = presets,
            onPick = { preset ->
                showPresets = false
                // 预设名可能已被占用，落盘前先取一个不冲突的名字
                val name = store.uniqueName(preset.name)
                val parsed = MacroTemplateParser.parse(preset.content)
                draft = TemplateDraft(
                    file = null,
                    fallbackName = name,
                    header = parsed.template.header.copy(name = name),
                    steps = parsed.template.steps,
                )
            },
            onDismiss = { showPresets = false },
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.template_delete)) },
            text = { Text(stringResource(R.string.template_delete_confirm, item.name)) },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(item.file)
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

    errorText?.let { message ->
        AlertDialog(
            onDismissRequest = { errorText = null },
            title = { Text(stringResource(R.string.dialog_title_tip)) },
            text = { Text(stringResource(R.string.template_save_failed) + "\n" + message) },
            confirmButton = {
                TextButton(onClick = { errorText = null }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }

    // 只在列表态弹：编辑器里本来就有完整的诊断列表，盖在它上面反而碍事
    if (startupErrors.isNotEmpty() && draft == null) {
        AlertDialog(
            onDismissRequest = { startupErrors = emptyList() },
            title = { Text(stringResource(R.string.template_load_error_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.template_load_error_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    startupErrors.forEach { item ->
                        // 点一条直接进那个模板的编辑器：弹窗只说「有几个错」，
                        // 看具体是哪个参数错了必须进编辑器（因此这里点得动就有意义）
                        Text(
                            text = stringResource(
                                R.string.template_load_error_item,
                                item.name,
                                item.errorCount,
                            ),
                            style = MonoMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    startupErrors = emptyList()
                                    openTemplate(item.file, item.name)
                                }
                                .padding(vertical = 2.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { startupErrors = emptyList() }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }
}

// ─────────────────────────────────────────────
// 模板列表
// ─────────────────────────────────────────────

@Composable
private fun TemplateListPane(
    templates: List<MacroTemplateFile>,
    store: MacroTemplateStore,
    hostLayoutNotices: List<HostLayoutNotice>,
    onNew: () -> Unit,
    onOpen: (MacroTemplateFile) -> Unit,
    onCopy: (MacroTemplateFile) -> Unit,
    onDelete: (MacroTemplateFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalAppDimens.current

    Column(modifier = modifier.fillMaxSize()) {
        SectionTitle(
            text = stringResource(R.string.template_title),
            modifier = Modifier.padding(
                start = dimens.screenPadding,
                end = dimens.screenPadding,
                top = dimens.sectionGap
            ),
            trailing = {
                TextButton(onClick = onNew) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.template_new))
                }
            }
        )

        if (templates.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.template_empty_list),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    horizontal = dimens.screenPadding,
                    vertical = dimens.sectionGap
                ),
                verticalArrangement = Arrangement.spacedBy(dimens.sectionGap)
            ) {
                items(templates, key = { it.file.absolutePath }) { item ->
                    TemplateCard(
                        item = item,
                        store = store,
                        onOpen = { onOpen(item) },
                        onCopy = { onCopy(item) },
                        onDelete = { onDelete(item) },
                    )
                }
            }
        }

        // 挂在这一页的最下方（与「请求蓝牙权限」页那段说明同一形态）：它讲的是模板之外的
        // 前提条件，不随某一张模板的编辑状态变化，所以不放进编辑器
        HostLayoutNoticeCard(notices = hostLayoutNotices)
    }
}

@Composable
private fun TemplateCard(
    item: MacroTemplateFile,
    store: MacroTemplateStore,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    // 只在文件变化时重新解析，避免列表滚动时反复读文件
    val parseResult = remember(item.file.absolutePath, item.updatedAt) {
        MacroTemplateParser.parse(store.readText(item.file))
    }

    Card(
        // 整张卡片可点击：直接进入该模板（编辑参数 + 用 TextInputPanel 使用模板）
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(LocalAppDimens.current.cardCorner),
        colors = CardDefaults.cardColors(containerColor = LocalAppTokens.current.card),
        // 列表项描边同样走主题第 3 档，而不是中性的 line
        border = BorderStroke(LocalAppDimens.current.strokeWidth, LocalAppTokens.current.tintLine)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (parseResult.hasError) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Badge(containerColor = MaterialTheme.colorScheme.error) {
                            Text(
                                text = stringResource(R.string.template_status_error, parseResult.errorCount),
                                color = MaterialTheme.colorScheme.onError,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.template_card_summary,
                        parseResult.template.header.charset,
                        parseResult.template.header.radix,
                        parseResult.template.steps.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onCopy) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.template_copy),
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.template_delete),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 「新建」时的预设选择。
 *
 * 默认只落盘「OEM Alt Code」一条，其余方案（ANSI Alt Code、Windows / macOS 十六进制、Linux、
 * RichEdit Alt+X）依赖宿主系统的额外设置或只对特定软件生效，因此放在这里按需选用；
 * macOS 那条没有设备可测，名字里标了 `unverified`。
 *
 * [presets] 传进来的是**已补好说明**的那一份（按当前界面语言，见调用点），
 * 所以选中之后进编辑器时说明已经就位、可以改。
 */
@Composable
private fun TemplatePresetDialog(
    presets: List<MacroTemplatePreset>,
    onPick: (MacroTemplatePreset) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.template_preset_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                presets.forEach { preset ->
                    val parsed = remember(preset) { MacroTemplateParser.parse(preset.content) }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(preset) },
                        shape = RoundedCornerShape(LocalAppDimens.current.innerCorner),
                        color = LocalAppTokens.current.tint,
                        border = BorderStroke(
                            LocalAppDimens.current.strokeWidth,
                            LocalAppTokens.current.tintLine
                        ),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                text = preset.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(
                                    R.string.template_card_summary,
                                    parsed.template.header.charset,
                                    parsed.template.header.radix,
                                    parsed.template.steps.size
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
