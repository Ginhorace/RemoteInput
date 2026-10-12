package com.github.remotecontrol.ui.template

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.remotecontrol.R
import com.github.remotecontrol.ui.theme.LocalAppDimens
import com.github.remotecontrol.ui.theme.LocalAppTokens
import com.github.remotecontrol.ui.theme.SecondaryButton
import com.github.remotecontrol.ui.theme.TintLevel
import com.github.remotecontrol.ui.theme.tintStyle
import com.github.remotecontrol.util.template.MacroTemplateKeys
import com.github.remotecontrol.util.template.MacroTemplateStep

// ─────────────────────────────────────────────
// 添加步骤：4 个动作按钮常显，键盘按键区按需展开
// ─────────────────────────────────────────────

/**
 * 「添加步骤」面板。
 *
 * ## 为什么拆成 4 个按钮
 * 旧实现是一个「添加步骤」折叠按钮 → 展开一整套面板（修饰键 + 6 组共 90 个键位按钮 +
 * 延时 / 编码占位符 / 自定义按键）。想加一条延时也得先把整块键盘展开，再从 90 个按钮里
 * 找回那三个按钮。现在四类动作直接摆在常显区：延时与编码占位符点一下即成，
 * 自定义按键弹输入框，只有「键盘按键」才展开键位网格 —— 面板不会再一开就铺满屏幕。
 *
 * ## 修饰键为什么跟着键盘区走
 * 修饰键是「接下来那一条的包装」。它只在**键盘按键区展开时**可见：键盘键、以及展开态里
 * 点到的编码占位符都会带上它。选择状态由 [AddStepPanel] 持有而不是展开区自己持有，
 * 所以收起再展开时勾选还在，不会因为误触收起就静默丢掉 Alt。
 *
 * ## 键位网格：一次只组合一组、五行全展示
 * 6 组摊平是 90 个 `FilledTonalButton`，展开一次就要建 90 个 M3 按钮、90 个点击 lambda。
 * 改成组页签之后任一时刻只有一组的按钮存在（最多 26 个 = 6 列 5 行），
 * 高度上放得下，于是连内嵌滚动一起取消 —— 嵌套滚动在编辑器外层那个 `verticalScroll`
 * 里既别扭又容易和手势打架。
 *
 * 回调也做了稳定化，两处最容易重组的路径都掐掉了：
 * 1. 加一条步骤时 [AddStepPanel] 整体可被跳过（外层 [AddStepBar] 用 [rememberUpdatedState]
 *    挡住了随 `steps` 变化的新 lambda）；
 * 2. 面板内部状态变化（选修饰键 / 切组 / 展开收起）时，键位网格可被跳过。
 */
@Composable
internal fun AddStepBar(
    onAdd: (List<MacroTemplateStep>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 外层每轮重组传进来的 onAdd 都捕获了最新的 steps（新 lambda 实例），直接往下传会让
    // AddStepPanel 的重组无法被跳过 —— 每加一条步骤就要重建整个面板。这里换成稳定的 State。
    val latestOnAdd by rememberUpdatedState(onAdd)
    AddStepPanel(onAdd = { latestOnAdd(it) }, modifier = modifier)
}

@Composable
private fun AddStepPanel(
    onAdd: (List<MacroTemplateStep>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedModifiers by remember { mutableStateOf(emptySet<String>()) }
    var showCustomKey by remember { mutableStateOf(false) }
    var keyboardExpanded by remember { mutableStateOf(false) }
    var groupIndex by remember { mutableIntStateOf(0) }

    /** 选中的修饰键，按 Ctrl / Shift / Alt / Win 的顺序（也就是按下与拆分的顺序） */
    fun orderedModifiers(): List<String> = MODIFIER_KEYS.filter { it in selectedModifiers }

    // 修饰键是一次性的：取走就清空选择，不然连着加两条会各自被包一遍
    fun takeModifiers(): List<String> {
        val ordered = orderedModifiers()
        selectedModifiers = emptySet()
        return ordered
    }

    // 点按键 = 一步组合键（`tap Ctrl+Shift+u`）；没选修饰键就是原来的一条 `tap`
    fun addKey(keyName: String) {
        val modifiers = takeModifiers()
        onAdd(
            if (modifiers.isEmpty()) {
                listOf(MacroTemplateStep.KeyTap(keyName))
            } else {
                listOf(MacroTemplateStep.ChordTap(modifiers + keyName))
            }
        )
    }

    // 编码占位符：带修饰键时正是 Alt Code 的写法（down Alt → <code> → up Alt）。
    // 编码位夹在按下与松开之间，写不成一条组合键，所以这里仍旧摊成多步
    fun addCodePlaceholder() {
        onAdd(wrapWithModifiers(selectedModifiers, listOf(MacroTemplateStep.CodeDigits())))
        selectedModifiers = emptySet()
    }

    // 只有修饰键也是一个组合：`tap Ctrl+Shift`（切输入法 / 键盘布局那种）
    fun addModifierChord() {
        val modifiers = takeModifiers()
        if (modifiers.isEmpty()) return
        onAdd(listOf(MacroTemplateStep.ChordTap(modifiers)))
    }

    // 网格与修饰键行只认「最新的回调」：面板内部状态一变就重建二十多个键位按钮没有必要
    val currentAddKey by rememberUpdatedState<(String) -> Unit>({ addKey(it) })

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
            // ── 常显行：四类动作，等宽排一行 ──
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ActionButton(
                    text = stringResource(R.string.template_add_delay),
                    onClick = { onAdd(listOf(MacroTemplateStep.Delay(50))) },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    // 节奏取头部参数，所以这里不需要任何数值
                    text = stringResource(R.string.template_add_code),
                    onClick = { addCodePlaceholder() },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    text = stringResource(R.string.template_add_custom),
                    onClick = { showCustomKey = true },
                    modifier = Modifier.weight(1f),
                )
                // 唯一的展开入口：展开时描边走强调色，与下方真正出现的内容对应。
                // 收起时把修饰键勾选一并清掉 —— 勾选只在展开态可见，留着会让下一次添加
                // 带上一个看不见的修饰键
                ActionButton(
                    text = stringResource(R.string.template_add_keyboard_key),
                    onClick = {
                        keyboardExpanded = !keyboardExpanded
                        if (!keyboardExpanded) selectedModifiers = emptySet()
                    },
                    selected = keyboardExpanded,
                    modifier = Modifier.weight(1f),
                )
            }

            // ── 展开区：分隔线 → 修饰键 → 描述 → 组页签 → 键位网格 ──
            if (keyboardExpanded) {
                HorizontalDivider()

                ModifierRow(
                    selectedModifiers = selectedModifiers,
                    onToggle = { name ->
                        selectedModifiers = if (name in selectedModifiers) {
                            selectedModifiers - name
                        } else {
                            selectedModifiers + name
                        }
                    },
                    onAddChord = { addModifierChord() },
                )

                // 两行短说明：长句在窄屏与长语言下会占四五行的位置，
                // 而修饰键行与页签本身已经把结构说清楚了
                SectionLabel(stringResource(R.string.template_modifier_section))
                SectionLabel(stringResource(R.string.template_key_section))

                KeyGroupTabs(
                    selectedIndex = groupIndex,
                    onSelect = { groupIndex = it },
                )

                KeyGrid(
                    choices = KEY_CHOICE_GROUPS[groupIndex].choices,
                    onPick = { currentAddKey(it) },
                )
            }
        }
    }

    if (showCustomKey) {
        CustomKeyDialog(
            onConfirm = {
                addKey(it)
                showCustomKey = false
            },
            onDismiss = { showCustomKey = false },
        )
    }
}

/**
 * 常显区的动作按钮。
 *
 * 复用 [SecondaryButton]（第 2 档底 + 描边 + 强调色文字）：这四个是"点了就会加东西"的操作，
 * 需要一眼看出是按钮，而不是一个带框的文字标签。
 *
 * 等宽 `weight(1f)` 排在窄格里，所以三处都要让一让：内边距收到 4dp、高度放到
 * [ACTION_BUTTON_HEIGHT] 容下两行、文案允许折成两行 —— 英文的 "Code placeholder"
 * 在单行 + 固定 40dp 下只能显示成省略号。
 */
@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    SecondaryButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        maxLines = 2,
        height = ACTION_BUTTON_HEIGHT,
        contentPadding = PaddingValues(horizontal = 4.dp),
        selected = selected,
    )
}

/** 修饰键行：Ctrl / Shift / Alt / Win + 只由修饰键组成的组合键 */
@Composable
private fun ModifierRow(
    selectedModifiers: Set<String>,
    onToggle: (String) -> Unit,
    onAddChord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        MODIFIER_KEYS.forEach { name ->
            FilterChip(
                selected = name in selectedModifiers,
                onClick = { onToggle(name) },
                label = { Text(name, style = MaterialTheme.typography.labelSmall) },
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = onAddChord,
            enabled = selectedModifiers.isNotEmpty(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(stringResource(R.string.template_add))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 键位分组页签。
 *
 * 与键盘页的分页标签同一套「选中」语义（[TintLevel.SOLID] / [TintLevel.SURFACE]），
 * `selectable(role = Role.Tab)` 让读屏能播报当前组。标签是**不翻译的键盘缩写**，
 * 理由见 [KeyChoiceGroup]。
 */
@Composable
private fun KeyGroupTabs(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalAppDimens.current
    val shape = remember(dimens.innerCorner) { RoundedCornerShape(dimens.innerCorner) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        KEY_CHOICE_GROUPS.forEachIndexed { index, group ->
            val selected = index == selectedIndex
            val style = tintStyle(if (selected) TintLevel.SOLID else TintLevel.SURFACE)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(KEY_GROUP_TAB_HEIGHT)
                    .clip(shape)
                    .background(style.container)
                    .border(dimens.strokeWidth, style.border, shape)
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = group.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = style.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 一组的键位网格。
 *
 * 每个按钮 `weight(1f)` 而不是固定 52dp：固定宽度在窄屏上会让一整行溢出卡片。
 * 末行不足 [KEYS_PER_ROW] 个时补等宽占位，键宽才与上面各行对齐。
 *
 * 不加高度上限：最多的一组是 26 个键（6 列 5 行），放得下就别再套一层滚动。
 */
@Composable
private fun KeyGrid(
    choices: List<KeyChoice>,
    onPick: (String) -> Unit,
) {
    val rows = remember(choices) { choices.chunked(KEYS_PER_ROW) }
    // 网格自身只认最新回调：点击回调里捕获的是 State，于是按钮在面板重组时能被跳过
    val currentOnPick by rememberUpdatedState(onPick)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEach { choice ->
                    FilledTonalButton(
                        onClick = { currentOnPick(choice.name) },
                        modifier = Modifier
                            .weight(1f)
                            .height(KEY_BUTTON_HEIGHT),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(
                            text = choice.label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
                repeat(KEYS_PER_ROW - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CustomKeyDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val valid = MacroTemplateKeys.resolve(input) != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.template_custom_key_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.template_step_key), style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    isError = input.isNotBlank() && !valid,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (input.isNotBlank() && !valid) {
                    Text(
                        text = stringResource(R.string.template_error_unknown_key, input),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(input.trim()) }, enabled = valid) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** 动作按钮高度：容得下两行 labelLarge 文案（单行时四个按钮也等高） */
private val ACTION_BUTTON_HEIGHT = 48.dp

/** 键位按钮高度（比动作按钮矮一档，26 个键才排得下 5 行） */
private val KEY_BUTTON_HEIGHT = 32.dp

/** 组页签高度：比键位再矮一档，一行小字刚好 */
private val KEY_GROUP_TAB_HEIGHT = 30.dp
