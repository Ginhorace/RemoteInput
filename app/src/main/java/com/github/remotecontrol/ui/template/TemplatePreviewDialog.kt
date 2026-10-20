package com.github.remotecontrol.ui.template

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.remotecontrol.R
import com.github.remotecontrol.ui.rememberTemplateSender
import com.github.remotecontrol.ui.theme.MonoSmall
import com.github.remotecontrol.util.CharsetCatalog
import com.github.remotecontrol.util.template.MacroTemplate
import com.github.remotecontrol.util.template.MacroTemplateExpander
import com.github.remotecontrol.util.template.MacroTemplateHeader
import com.github.remotecontrol.util.template.MacroTemplateStep
import com.github.remotecontrol.hid.HidSession

// ─────────────────────────────────────────────
// 展开预览
// ─────────────────────────────────────────────

@Composable
internal fun TemplatePreviewDialog(
    viewModel: HidSession,
    header: MacroTemplateHeader,
    steps: List<MacroTemplateStep>,
    canSend: Boolean,
    onDismiss: () -> Unit,
) {
    // 打开就把该字符集的测试文本填好（参考表总表口径），省得手动敲样本；
    // 某个字符集在总表里还是「待定」（没有 sampleText）就留空，不自作主张填一个
    var sample by remember { mutableStateOf(CharsetCatalog.of(header.charset)?.sampleText.orEmpty()) }
    val template = remember(header, steps) { MacroTemplate(header, steps) }
    // 「测试」= 拿当前模板把文本框里的样本真发一遍；发完不清空，可以连着发。
    // 编不出来的字符这里不拦（下面那一行常驻提示已经说了），所以关掉发送前那道确认框
    val send = rememberTemplateSender(viewModel, template, checkEncodable = false)
    val frames = remember(template, sample) {
        MacroTemplateExpander.expand(template, sample.take(MAX_SAMPLE_LENGTH))
    }
    // 示例里第一个编不出来的字符（与发送前那道检查同一个函数）：展开器遇到它是**跳过**，
    // 不提示的话预览里只是「少了一格」，看不出是字符集的问题。判定范围与展开一致（只取前几位）
    val unencodable = remember(header, sample) {
        MacroTemplateExpander.firstUnencodable(header, sample.take(MAX_SAMPLE_LENGTH))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.template_preview)) },
        text = {
            Column(modifier = Modifier.height(360.dp)) {
                OutlinedTextField(
                    value = sample,
                    onValueChange = { sample = it },
                    label = { Text(stringResource(R.string.template_preview_sample), style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (unencodable != null) {
                    Text(
                        text = stringResource(
                            R.string.template_preview_unencodable,
                            unencodable.text,
                            unencodable.codePoint.toString(16).uppercase(),
                            header.charset,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (frames.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.template_preview_empty),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        text = stringResource(
                            R.string.template_expand_summary,
                            steps.size,
                            frames.size,
                            MacroTemplateExpander.totalDurationMs(frames)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        PreviewHeader(stringResource(R.string.template_preview_header_action), Modifier.weight(1f))
                        PreviewHeader(stringResource(R.string.template_preview_header_report), Modifier.width(88.dp))
                        PreviewHeader(stringResource(R.string.template_preview_header_duration), Modifier.width(52.dp))
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(frames.size) { index -> PreviewFrameRow(index + 1, frames[index]) }
                    }
                }
            }
        },
        // 「测试」与「确定」对称分居按钮行两端。
        //
        // AlertDialog 的按钮行只有一个 end 对齐的横向容器，且 confirmButton 是必填槽，
        // 所以两个按钮一起放进它的 Row 里用 SpaceBetween 摊开 —— 拆成 confirm / dismiss
        // 两个槽的话，两个按钮会一起挤在右边，不是「对称」。
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 模板有 ERROR 就只给看、不给发 —— 与文本面板同一个门禁：展开器对未知键是
                // 「跳过」，带着错误发送不会崩，但会静默发错字符
                TextButton(
                    onClick = { send(sample) },
                    enabled = canSend && sample.isNotBlank(),
                ) {
                    Text(stringResource(R.string.template_preview_test))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            }
        },
    )
}

@Composable
private fun PreviewHeader(text: String, modifier: Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = modifier
    )
}

@Composable
private fun PreviewFrameRow(index: Int, frame: MacroTemplateExpander.Frame) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = index.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = secondary,
            modifier = Modifier.width(24.dp)
        )
        Text(
            text = frame.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (frame.isDelay) secondary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (frame.isDelay) "—" else formatReport(frame),
            style = MonoSmall,
            color = secondary,
            modifier = Modifier.width(88.dp)
        )
        Text(
            text = "${frame.durationMs}ms",
            style = MaterialTheme.typography.labelSmall,
            color = secondary,
            modifier = Modifier.width(52.dp)
        )
    }
}

private fun formatReport(frame: MacroTemplateExpander.Frame): String {
    val modifier = "0x%02X".format(frame.modifier)
    val key = if (frame.keyCode == 0) "--" else "0x%02X".format(frame.keyCode)
    return "$modifier / $key"
}

private const val MAX_SAMPLE_LENGTH = 8
