package com.github.remotecontrol.ui.template

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.remotecontrol.R
import com.github.remotecontrol.util.template.MacroTemplateKeys
import com.github.remotecontrol.util.template.MacroTemplateParser
import com.github.remotecontrol.util.template.MacroTemplateStep
import com.github.remotecontrol.util.template.split

// ─────────────────────────────────────────────
// 步骤编辑 / 删除 / 拆分
// ─────────────────────────────────────────────

@Composable
internal fun StepEditDialog(
    step: MacroTemplateStep,
    onConfirm: (List<MacroTemplateStep>) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (step) {
        is MacroTemplateStep.KeyDown -> KeyStepDialog(
            typeLabel = "down",
            initialKey = step.keyName,
            splitTo = null,
            build = { MacroTemplateStep.KeyDown(it) },
            onConfirm = onConfirm,
            onDelete = onDelete,
            onDismiss = onDismiss,
        )

        is MacroTemplateStep.KeyUp -> KeyStepDialog(
            typeLabel = "up",
            initialKey = step.keyName,
            splitTo = null,
            build = { MacroTemplateStep.KeyUp(it) },
            onConfirm = onConfirm,
            onDelete = onDelete,
            onDismiss = onDismiss,
        )

        is MacroTemplateStep.KeyTap -> KeyStepDialog(
            typeLabel = "tap",
            initialKey = step.keyName,
            // tap 可以拆成「按下 + 松开」两步
            splitTo = { listOf(MacroTemplateStep.KeyDown(it), MacroTemplateStep.KeyUp(it)) },
            build = { MacroTemplateStep.KeyTap(it) },
            onConfirm = onConfirm,
            onDelete = onDelete,
            onDismiss = onDismiss,
        )

        is MacroTemplateStep.Delay -> DelayStepDialog(
            initialMs = step.ms,
            onConfirm = { onConfirm(listOf(MacroTemplateStep.Delay(it))) },
            onDelete = onDelete,
            onDismiss = onDismiss,
        )

        is MacroTemplateStep.ChordTap -> ChordStepDialog(
            step = step,
            onConfirm = onConfirm,
            onDelete = onDelete,
            onDismiss = onDismiss,
        )

        is MacroTemplateStep.CodeDigits -> CodeStepDialog(
            step = step,
            onConfirm = { onConfirm(listOf(it)) },
            onDelete = onDelete,
            onDismiss = onDismiss,
        )

        is MacroTemplateStep.Unknown -> UnknownStepDialog(
            raw = step.raw,
            onConfirm = { onConfirm(listOf(it)) },
            onDelete = onDelete,
            onDismiss = onDismiss,
        )
    }
}

/**
 * 「认不出来的一行」的对话框：把这一行重新写对。
 *
 * 与别的步骤对话框不同，这里编辑的是**整行文本**（而不是某一类步骤的参数）—— 这一行之所以出现在
 * 列表里，正是因为它的类型当时认不出来（`slee 20`、`tap Ctrl+`、没闭合的 `<code`）。
 * 改对了就换成解析出的那条步骤，改不对则禁用「确定」并说明原因：不静默丢弃，也不猜着当成某类步骤。
 */
@Composable
private fun UnknownStepDialog(
    raw: String,
    onConfirm: (MacroTemplateStep) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(raw) }
    val parsed = remember(input) { MacroTemplateParser.parseLine(input) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.template_step_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.template_step_unknown_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.template_step_line), style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    isError = !parsed.isUsable,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!parsed.isUsable) {
                    val diagnostic = parsed.diagnostics.firstOrNull()
                    Text(
                        // 有具体诊断（如 `<code pad=x>` 的无效值）就照它说；只是认不出关键字就报认不出
                        text = if (diagnostic != null) {
                            diagnosticText(diagnostic)
                        } else {
                            stringResource(R.string.template_error_unknown_step, input.trim())
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            StepDialogButtons(
                onDelete = onDelete,
                onConfirm = { parsed.step?.let(onConfirm) },
                onCancel = onDismiss,
                confirmEnabled = parsed.isUsable,
            )
        },
    )
}

/**
 * 步骤对话框底部的按钮行：左端「删除」，右端「取消」「确定」。
 *
 * 与展开预览里的「测试 / 确定」同一套摆法：AlertDialog 的按钮行只认一个 end 对齐的横向容器，
 * 且 confirmButton 是必填槽，所以整行都装在这一个 Row 里用 SpaceBetween 摊开。
 * 「取消」必须跟着进这个 Row —— 交给 `dismissButton` 槽的话，会被这个全宽 Row 挤到下一行去。
 *
 * 「删除」用与「确定」同规格的 TextButton（不再套 labelSmall），摆在对话框底部更好点；
 * [onCancel] 给 null 时只有「删除 / 确定」（只读的对话框没有要丢弃的改动）。
 */
@Composable
private fun StepDialogButtons(
    onDelete: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onDelete) {
            Text(
                text = stringResource(R.string.template_delete),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onCancel != null) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(stringResource(R.string.ok))
            }
        }
    }
}

@Composable
private fun KeyStepDialog(
    typeLabel: String,
    initialKey: String,
    splitTo: ((String) -> List<MacroTemplateStep>)?,
    build: (String) -> MacroTemplateStep,
    onConfirm: (List<MacroTemplateStep>) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var keyName by remember { mutableStateOf(initialKey) }
    val resolved = MacroTemplateKeys.resolve(keyName)
    val suggestion = if (resolved == null) MacroTemplateKeys.suggest(keyName) else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.template_step_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(typeLabel, style = MaterialTheme.typography.labelSmall)
                OutlinedTextField(
                    value = keyName,
                    onValueChange = { keyName = it },
                    label = { Text(stringResource(R.string.template_step_key), style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    isError = resolved == null,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    resolved == null -> Text(
                        text = if (suggestion != null) {
                            stringResource(R.string.template_error_unknown_key_suggest, keyName, suggestion)
                        } else {
                            stringResource(R.string.template_error_unknown_key, keyName)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )

                    resolved.isModifier -> Text(
                        text = stringResource(R.string.template_step_modifier_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (splitTo != null) {
                    Text(
                        text = stringResource(R.string.template_step_split_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (splitTo != null) {
                    TextButton(
                        onClick = { onConfirm(splitTo(keyName.trim())) },
                        enabled = resolved != null,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.template_step_split),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            StepDialogButtons(
                onDelete = onDelete,
                onConfirm = { onConfirm(listOf(build(keyName.trim()))) },
                onCancel = onDismiss,
                confirmEnabled = resolved != null,
            )
        },
    )
}

@Composable
private fun DelayStepDialog(
    initialMs: Long,
    onConfirm: (Long) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var ms by remember { mutableStateOf(initialMs) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.template_step_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("sleep", style = MaterialTheme.typography.labelSmall)
                HeaderNumberField(
                    label = stringResource(R.string.template_step_duration),
                    value = ms,
                    onValueChange = { ms = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            StepDialogButtons(
                onDelete = onDelete,
                onConfirm = { onConfirm(ms) },
                onCancel = onDismiss,
            )
        },
    )
}

/**
 * 组合键步骤的对话框：看这一步、拆成多步、或删掉。
 *
 * 内容只读：组合里的**顺序**有意义（拆分顺序跟着它走），用一组勾选框去改会把顺序抹平；
 * 要改就删了重加，或者直接改文件。
 */
@Composable
private fun ChordStepDialog(
    step: MacroTemplateStep.ChordTap,
    onConfirm: (List<MacroTemplateStep>) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.template_step_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("tap", style = MaterialTheme.typography.labelSmall)
                Text(
                    text = step.keyNames.joinToString(" + "),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.template_step_chord_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { onConfirm(step.split()) },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.template_step_split),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            StepDialogButtons(onDelete = onDelete, onConfirm = onDismiss)
        },
    )
}

@Composable
private fun CodeStepDialog(
    step: MacroTemplateStep.CodeDigits,
    onConfirm: (MacroTemplateStep.CodeDigits) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pad by remember { mutableStateOf(step.padDigits.toLong()) }
    var useNumpad by remember { mutableStateOf(step.useNumpad) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.template_step_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("<code>", style = MaterialTheme.typography.labelSmall)
                Text(
                    text = stringResource(R.string.template_step_code_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HeaderNumberField(
                    label = stringResource(R.string.template_step_pad),
                    value = pad,
                    onValueChange = { pad = it },
                    modifier = Modifier.fillMaxWidth(),
                    step = STEP_UNIT,
                    max = MacroTemplateParser.MAX_PAD_DIGITS.toLong(),
                )
                Text(
                    text = stringResource(R.string.template_step_keys),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = useNumpad,
                        onClick = { useNumpad = true },
                        label = { Text(stringResource(R.string.template_keys_numpad), style = MaterialTheme.typography.labelSmall) },
                    )
                    FilterChip(
                        selected = !useNumpad,
                        onClick = { useNumpad = false },
                        label = { Text(stringResource(R.string.template_keys_main), style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        },
        confirmButton = {
            StepDialogButtons(
                onDelete = onDelete,
                onConfirm = {
                    onConfirm(
                        MacroTemplateStep.CodeDigits(
                            useNumpad = useNumpad,
                            padDigits = pad.toInt(),
                        )
                    )
                },
                onCancel = onDismiss,
            )
        },
    )
}
