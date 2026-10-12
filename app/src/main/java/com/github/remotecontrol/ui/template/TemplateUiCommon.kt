package com.github.remotecontrol.ui.template

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.github.remotecontrol.R
import com.github.remotecontrol.ui.theme.ElevatedCard
import com.github.remotecontrol.ui.theme.LocalAppDimens
import com.github.remotecontrol.ui.theme.LocalAppTokens
import com.github.remotecontrol.ui.theme.TintLevel
import com.github.remotecontrol.util.HostLayoutNotice
import com.github.remotecontrol.util.template.MacroTemplateDiagnostic
import com.github.remotecontrol.util.template.MacroTemplateHeader
import com.github.remotecontrol.util.template.MacroTemplateIssue
import com.github.remotecontrol.util.template.MacroTemplateStep
import com.github.remotecontrol.util.template.TemplateField
import com.github.remotecontrol.util.template.TemplateSeverity
import java.io.File

internal val STEP_ROW_HEIGHT = 52.dp
internal const val KEYS_PER_ROW = 6

/** 数字框两侧 ± 按钮的步进：延时按 5 ms 走，「补零位数」这种计数按 1 走 */
internal const val STEP_DELAY_MS = 5L
internal const val STEP_UNIT = 1L

/** 编辑中的模板草稿：保存前不落盘，取消即丢弃 */
internal data class TemplateDraft(
    val file: File?,
    /** 没有填名字时回退使用的名字（新建时是新模板名，编辑时是原文件名） */
    val fallbackName: String,
    val header: MacroTemplateHeader,
    val steps: List<MacroTemplateStep>,
    /**
     * 打开文件时解析出来的**全部**诊断，原样留着。
     *
     * 取用时交给 [MacroTemplateParser.editorDiagnostics] 过滤：只有「模型表达不出来」的那几条
     * （`radix` 是 `Int`、间隔是 `Long`，文件里写坏的 `radix = 8` / `keyHold = abc` 在解析时就被
     * 回退成默认值了）才会与模型校验的结果合并。
     *
     * 其余一律不合并，由模型校验每次重算 —— 字符集原样留在模型里；认不出来的那一行是一条
     * [MacroTemplateStep.Unknown]（见 [MacroTemplateParser]）而不是凭空消失。
     * 两处都报的话，用户会看到「同一个错误显示两条」。
     */
    val fileDiagnostics: List<MacroTemplateDiagnostic> = emptyList(),
)

/** 两个头部之间有哪些字段变了，用来丢掉已经不适用的头部诊断 */
internal fun changedFields(
    old: MacroTemplateHeader,
    new: MacroTemplateHeader,
): Set<TemplateField> = buildSet {
    if (old.charset != new.charset) add(TemplateField.CHARSET)
    if (old.radix != new.radix) add(TemplateField.RADIX)
    if (old.templateGapMs != new.templateGapMs) add(TemplateField.TEMPLATE_GAP)
    if (old.keyHoldMs != new.keyHoldMs) add(TemplateField.KEY_HOLD)
    if (old.keyGapMs != new.keyGapMs) add(TemplateField.KEY_GAP)
}

/** 按键选择器里的一项：模板里的规范写法 + 按钮上的短标签 */
internal data class KeyChoice(val name: String, val label: String = name)

internal val MODIFIER_KEYS = listOf("Ctrl", "Shift", "Alt", "Win")

/**
 * 字母键写**小写**：模板里的键名不隐含 Shift，`a` 就是不按 Shift 的 A 键（输出 `a`），
 * 要打大写得先用修饰键行勾上 `Shift` —— 以前这里给的是 `A`，存进文件就成了「悄悄按住 Shift」。
 */
internal val LETTER_CHOICES = ('a'..'z').map { KeyChoice(it.toString()) }
internal val DIGIT_CHOICES = ('0'..'9').map { KeyChoice(it.toString()) }
internal val SYMBOL_CHOICES = listOf("-", "=", "[", "]", "\\", ";", "'", ",", ".", "/", "`").map { KeyChoice(it) }
internal val NAVIGATION_CHOICES = listOf(
    KeyChoice("Enter"), KeyChoice("Tab"), KeyChoice("Space"), KeyChoice("Esc"),
    KeyChoice("Backspace", "Back"), KeyChoice("Delete", "Del"), KeyChoice("Insert", "Ins"),
    KeyChoice("Home"), KeyChoice("End"), KeyChoice("PgUp"), KeyChoice("PgDn"),
    KeyChoice("Up", "↑"), KeyChoice("Down", "↓"), KeyChoice("Left", "←"), KeyChoice("Right", "→"),
)
internal val FUNCTION_CHOICES = (1..12).map { KeyChoice("F$it") }
internal val NUMPAD_CHOICES = (0..9).map { KeyChoice("Numpad$it", "N$it") } + listOf(
    KeyChoice("NumpadAdd", "N+"), KeyChoice("NumpadSub", "N-"),
    KeyChoice("NumpadMult", "N*"), KeyChoice("NumpadDiv", "N/"),
    KeyChoice("NumpadDot", "N."), KeyChoice("NumpadEnter", "N⏎"),
)

/**
 * 键位选择器的一组：页签上的短标签 + 该组的键。
 *
 * 标签用**不翻译的键盘缩写**（`a-z` / `0-9` / `Nav` / `F1-12` / `Num`）。它们与键帽上印的
 * 字是一类东西（与键盘灯条的 Num / Caps / Scroll 同一个口径），翻译过来反而对不上键帽；
 * 页签只有约 60dp 宽，长译文（「数字与符号」/「Buchstaben」）也会被省略号截掉。
 */
internal data class KeyChoiceGroup(val label: String, val choices: List<KeyChoice>)

/**
 * 按键选择器的分组：一次只显示一组，90 个键位按钮不必同时存在。
 *
 * 分组按「用起来像不像一类」切：字母单列一组，数字与符号合起来（Alt Code 的编码位在数字区，
 * 但符号也是同一片主键区），导航与功能键各自独立，小键盘是唯一有专属前缀（`Numpad`）的一类。
 */
internal val KEY_CHOICE_GROUPS = listOf(
    KeyChoiceGroup("a-z", LETTER_CHOICES),
    KeyChoiceGroup("0-9", DIGIT_CHOICES + SYMBOL_CHOICES),
    KeyChoiceGroup("Nav", NAVIGATION_CHOICES),
    KeyChoiceGroup("F1-12", FUNCTION_CHOICES),
    KeyChoiceGroup("Num", NUMPAD_CHOICES),
)

/**
 * 编辑区内输入框 / 次级按钮统一的描边。
 *
 * 不显式指定的话，`OutlinedTextField` 与 `OutlinedButton` 会落回 M3 默认的
 * outline 灰，和全局浓度阶梯里的主题描边脱节 —— 表现就是"整个编辑区只有这些框是灰的"。
 */
@Composable
internal fun fieldBorder(): BorderStroke =
    BorderStroke(LocalAppDimens.current.strokeWidth, LocalAppTokens.current.tintLine)

/** 输入框描边色：聚焦走满浓度 accent，常态走第 3 档主题描边 */
@Composable
internal fun fieldColors(): TextFieldColors {
    val tokens = LocalAppTokens.current
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = tokens.accent,
        unfocusedBorderColor = tokens.tintLine,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> HeaderDropdown(
    label: String,
    value: T,
    options: List<Pair<T, String>>,
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier,
    ) {
        OutlinedTextField(
            // 认不出来的值（例如手写进文件的 charset）取不到选项，就原样显示 ——
            // 配合 isError 标红，用户能直接看到自己写错成了什么
            value = options.firstOrNull { it.first == value }?.second ?: value.toString(),
            onValueChange = {},
            readOnly = true,
            isError = isError,
            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            textStyle = MaterialTheme.typography.bodySmall,
            colors = fieldColors(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (option, text) ->
                DropdownMenuItem(
                    text = { Text(text, style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/**
 * 数字框两侧的步进按钮。
 *
 * 用 [Box] 而不是 `IconButton`：后者有 48dp 的最小触摸目标，
 * 放进 `leadingIcon` / `trailingIcon` 会把 Header 的三列布局撑爆。
 */
@Composable
private fun StepIcon(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            },
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 带 ± 步进按钮的数字输入框。
 *
 * @param step ± 按钮每次的改动量
 * @param min 下限，只约束 ± 按钮
 * @param max 上限，输入与按钮都会受它约束
 */
@Composable
internal fun HeaderNumberField(
    label: String,
    value: Long,
    onValueChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    step: Long = STEP_DELAY_MS,
    min: Long = 0L,
    max: Long = Long.MAX_VALUE,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value.toString(),
        isError = isError,
        onValueChange = { text ->
            val digits = text.filter { it.isDigit() }.take(6)
            // 打字时只钳上限、不钳下限：想输 20 的话，打到「2」就被改成下限 10，
            // 再补一个「0」会变成 100。低于下限的值交给 SMALL_DELAY_HEADER 警告提醒
            // —— 与解析层「不静默钳制」是同一个原则；下限只由两侧按钮约束。
            val parsed = if (digits.isEmpty()) min else digits.toLongOrNull() ?: value
            onValueChange(parsed.coerceIn(0L, max))
        },
        leadingIcon = {
            StepIcon(
                icon = Icons.Default.Remove,
                contentDescription = "$label -",
                enabled = value > min,
                onClick = { onValueChange((value - step).coerceAtLeast(min)) },
            )
        },
        trailingIcon = {
            StepIcon(
                icon = Icons.Default.Add,
                contentDescription = "$label +",
                enabled = value < max,
                onClick = { onValueChange((value + step).coerceAtMost(max)) },
            )
        },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        textStyle = MaterialTheme.typography.bodySmall,
        singleLine = true,
        colors = fieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/**
 * 把一组步骤包进选中的修饰键里：`down Ctrl` / `down Shift` / 步骤 / `up Shift` / `up Ctrl`。
 *
 * 模板里没有「组合键」这种写法（修饰键是**状态**，见 [MacroTemplateStep] 的注释），
 * 「Ctrl+Shift+U」只能展开成这几条步骤 —— 按下按 [MODIFIER_KEYS] 的顺序（也是真手上的顺序），
 * 松开按逆序。列表里选中修饰键与点击顺序无关，生成的步骤总是同一个样子。
 *
 * 没选修饰键时原样返回，于是「点一下加一条 `tap`」的老行为不变。
 */
internal fun wrapWithModifiers(
    selected: Set<String>,
    steps: List<MacroTemplateStep>,
): List<MacroTemplateStep> {
    if (selected.isEmpty()) return steps
    val ordered = MODIFIER_KEYS.filter { it in selected }
    return ordered.map { MacroTemplateStep.KeyDown(it) } +
        steps +
        ordered.reversed().map { MacroTemplateStep.KeyUp(it) }
}

// ─────────────────────────────────────────────
// 状态与诊断
// ─────────────────────────────────────────────

@Composable
internal fun TemplateStatusRow(
    diagnostics: List<MacroTemplateDiagnostic>,
    stepCount: Int,
    header: MacroTemplateHeader,
) {
    val errorCount = diagnostics.count { it.severity == TemplateSeverity.ERROR }
    val warningCount = diagnostics.count { it.severity == TemplateSeverity.WARNING }

    val icon = when {
        errorCount > 0 -> Icons.Default.Error
        warningCount > 0 -> Icons.Default.Warning
        else -> Icons.Default.CheckCircle
    }
    val color = when {
        errorCount > 0 -> MaterialTheme.colorScheme.error
        warningCount > 0 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val statusText = when {
        errorCount > 0 -> stringResource(R.string.template_status_error, errorCount)
        warningCount > 0 -> stringResource(R.string.template_status_warning, warningCount)
        else -> stringResource(R.string.template_status_ok)
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = statusText, style = MaterialTheme.typography.labelMedium, color = color)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(
                R.string.template_step_summary,
                stepCount,
                header.keyHoldMs,
                header.keyGapMs
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 模板列表下方的宿主键盘提醒：模板默认按**美式键位**发送（HID 送的是位置码），布局不对会静默发错字符。
 *
 * 版式照「请求蓝牙权限」页下方那段说明（`ElevatedCard` + `bodyMedium`）：它是常驻的一句说明，
 * 不是出错的告警，所以不与诊断列表共用配色与形态。
 *
 * 刻意**不看模板内容**：法语 AZERTY 连主键盘数字行都反档，`keys=main` 的十进制模板同样会错，
 * 「哪些模板受影响」判不准，索性都提示。档位由 `KeyboardLayoutCatalog.noticesFor` 给出 ——
 * **界面语言与宿主布局设置各判一次**，两处都非美式时两条都出现（它们是两处手动设置，改哪一处
 * 都要用户自己动手）；两边都是美式（含与美式等价的西语、巴西 ABNT2）时整块不出现。
 */
@Composable
internal fun HostLayoutNoticeCard(
    notices: List<HostLayoutNotice>,
    modifier: Modifier = Modifier,
) {
    if (notices.isEmpty()) return
    val dimens = LocalAppDimens.current

    ElevatedCard(
        modifier = modifier.padding(horizontal = dimens.screenPadding, vertical = 4.dp),
        level = TintLevel.SURFACE,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            notices.forEach { notice ->
                val textRes = when (notice) {
                    HostLayoutNotice.US_LAYOUT -> R.string.template_host_layout_us_notice
                    HostLayoutNotice.ENGLISH_MODE -> R.string.template_host_layout_english_notice
                    HostLayoutNotice.NONE -> null
                }
                if (textRes == null) return@forEach
                Text(
                    text = stringResource(textRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
internal fun TemplateDiagnostics(
    diagnostics: List<MacroTemplateDiagnostic>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        diagnostics.forEach { diagnostic ->
            val color = if (diagnostic.severity == TemplateSeverity.ERROR) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.tertiary
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    // 头部问题没有「第几条步骤」可言（带过来的那些 line 是文件行号，
                    // 在这个按字段编辑的界面里没有意义），统一显示成占位符
                    text = if (diagnostic.field == null && diagnostic.line > 0) {
                        diagnostic.line.toString()
                    } else {
                        "—"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    modifier = Modifier.width(24.dp)
                )
                Text(
                    text = diagnosticText(diagnostic),
                    style = MaterialTheme.typography.labelSmall,
                    color = color
                )
            }
        }
    }
}

@Composable
internal fun diagnosticText(diagnostic: MacroTemplateDiagnostic): String {
    val arg = diagnostic.arg.orEmpty()
    val suggestion = diagnostic.suggestion
    val resId = when (diagnostic.issue) {
        MacroTemplateIssue.EMPTY_TEMPLATE -> R.string.template_error_empty
        MacroTemplateIssue.MISSING_CODE -> R.string.template_error_missing_code
        MacroTemplateIssue.CODE_DISABLED -> R.string.template_warn_code_disabled
        MacroTemplateIssue.UNKNOWN_KEY ->
            if (suggestion != null) R.string.template_error_unknown_key_suggest else R.string.template_error_unknown_key
        MacroTemplateIssue.INVALID_NUMBER -> R.string.template_error_invalid_number
        MacroTemplateIssue.INVALID_VALUE -> R.string.template_error_invalid_value
        MacroTemplateIssue.UNCLOSED_PLACEHOLDER -> R.string.template_error_unclosed_placeholder
        MacroTemplateIssue.UNKNOWN_STEP -> R.string.template_error_unknown_step
        MacroTemplateIssue.UNKNOWN_CHARSET -> R.string.template_error_unknown_charset
        MacroTemplateIssue.INVALID_RADIX -> R.string.template_error_invalid_radix
        MacroTemplateIssue.UNBALANCED_KEY -> R.string.template_error_unbalanced_key
        MacroTemplateIssue.UNKNOWN_PARAM -> R.string.template_warn_unknown_param
        MacroTemplateIssue.DUPLICATE_CODE -> R.string.template_warn_duplicate_code
        MacroTemplateIssue.MULTIPLE_HELD_KEYS -> R.string.template_warn_multiple_held_keys
        MacroTemplateIssue.SMALL_DELAY -> R.string.template_warn_small_delay
        MacroTemplateIssue.SMALL_DELAY_HEADER -> R.string.template_warn_small_delay_header
        MacroTemplateIssue.IGNORED_ATTR -> R.string.template_warn_ignored_attr
    }
    return when {
        diagnostic.issue == MacroTemplateIssue.UNKNOWN_KEY && suggestion != null ->
            stringResource(resId, arg, suggestion)

        diagnostic.arg == null -> stringResource(resId)

        else -> stringResource(resId, arg)
    }
}
