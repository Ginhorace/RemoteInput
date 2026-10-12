package com.github.remotecontrol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.remotecontrol.R
import com.github.remotecontrol.ui.theme.ElevatedCard
import com.github.remotecontrol.ui.theme.LocalAppDimens
import com.github.remotecontrol.ui.theme.LocalAppTokens
import com.github.remotecontrol.ui.theme.TintLevel
import com.github.remotecontrol.util.HidReportDescriptors
import com.github.remotecontrol.util.KeyboardConstants
import com.github.remotecontrol.util.template.MacroTemplate
import com.github.remotecontrol.util.template.MacroTemplateExpander
import com.github.remotecontrol.viewmodel.BluetoothHidViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 文本发送面板。
 *
 * @param template 按该模板展开文本发送（模板模式：「所见即所发」，
 *                 与编辑器里的展开预览共用 [MacroTemplateExpander]）。
 * @param enabled false = 当前模板存在错误，禁用输入与发送。
 *                展开器对未知键是「跳过」，带着错误发送不会崩但会静默发错字符，
 *                所以由调用方（编辑器）在出现 ERROR 时关掉它；警告不拦。
 */
@Composable
fun TextInputPanel(
    viewModel: BluetoothHidViewModel,
    modifier: Modifier = Modifier,
    template: MacroTemplate,
    enabled: Boolean = true,
) {
    var inputText by remember { mutableStateOf("") }
    val send = rememberTemplateSender(viewModel, template)

    val tokens = LocalAppTokens.current
    val dimens = LocalAppDimens.current

    // 从旧版的「surface 底 + 8dp 阴影」换成第 2 档浓度底 + 1dp 描边：
    // B 风格全程 elevation = 0，层次由浓度阶梯承担
    ElevatedCard(
        modifier = modifier.padding(
            horizontal = dimens.screenPadding,
            vertical = dimens.sectionGap
        ),
        level = TintLevel.SURFACE,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                enabled = enabled,
                placeholder = {
                    Text(
                        stringResource(
                            id = if (enabled) R.string.input_text else R.string.template_send_blocked
                        )
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(dimens.innerCorner),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = tokens.tint,
                    unfocusedContainerColor = tokens.tint,
                    disabledContainerColor = tokens.panel,
                    focusedBorderColor = tokens.accent,
                    unfocusedBorderColor = tokens.tintLine,
                    disabledBorderColor = tokens.line,
                )
            )
            IconButton(
                onClick = {
                    if (inputText.isBlank()) return@IconButton
                    val textToSend = inputText
                    // 面板是「输入 → 发送」的循环，发完即清空；预览里的「测试」则相反，
                    // 文本留着好连着发（见 rememberTemplateSender）
                    inputText = ""
                    send(textToSend)
                },
                enabled = enabled,
                modifier = Modifier.size(dimens.iconButtonSize),
                shape = RoundedCornerShape(dimens.innerCorner),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = tokens.accent,
                    contentColor = tokens.onAccent,
                    disabledContainerColor = tokens.panel,
                    disabledContentColor = tokens.onVar,
                )
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(id = R.string.send),
                    tint = tokens.onAccent
                )
            }
        }
    }
}

/**
 * 按模板发送文本：把文本的每个字符交给 [MacroTemplateExpander] 展开成报告序列，
 * 逐帧发送并按帧时长停顿，发送结束后补一帧空报告释放所有按键。
 *
 * 与编辑器里的展开预览共用同一份展开逻辑，因此「预览所见」就是「实际所发」。
 */
fun sendTextByTemplate(
    viewModel: BluetoothHidViewModel,
    template: MacroTemplate,
    text: String,
    cancellationCheck: (() -> Unit)? = null
) {
    val frames = MacroTemplateExpander.expand(template, text)
    for (frame in frames) {
        cancellationCheck?.invoke()
        viewModel.sendReport(
            HidReportDescriptors.REPORT_ID_KEYBOARD,
            KeyboardConstants.KeyboardReport(
                modifier = frame.modifier.toByte(),
                keyCode = frame.keyCode.toByte()
            ).toByteArray()
        )
        if (frame.durationMs > 0) {
            Thread.sleep(frame.durationMs)
        }
    }
    // 释放所有按键
    viewModel.sendReport(HidReportDescriptors.REPORT_ID_KEYBOARD, ByteArray(8))
}

/**
 * 「按模板发送」的发送态：返回的 `send(text)` 调用即开始发送。
 *
 * 发送期间显示「发送中」弹窗（点取消会中断发送并补一帧空报告释放按键），与文本面板里
 * 那一套完全同源 —— 展开预览的「测试」不是另写一份发送，而是共用这里，行为不会走叉。
 *
 * **发送前先拦一道「编不出来」**：展开器遇到这套字符集编不出的字符是**静默跳过**，
 * 用户只会看到少打了一个字。所以先跑 [MacroTemplateExpander.firstUnencodable]（线性、
 * 一碰到第一个编不出来的就收工，不单独显示进度），命中就弹一个说明 +「仍然发送」的确认框；
 * `Unicode` / `UTF-32` / `UTF-16*` 三个口径直接放行，连检查都不做。
 *
 * **不碰调用方的文本框**：面板是「输入 → 发送」的循环，发完要清空；预览要留着文本连着试，
 * 两边的期望相反，所以清不清由调用方决定。
 *
 * @param checkEncodable 是否做上面那道检查。展开预览的「测试」传 `false`：那一边已经有常驻的
 *   「编不出来」提示（`template_preview_unencodable`），再拦一道确认框就重复了
 */
@Composable
fun rememberTemplateSender(
    viewModel: BluetoothHidViewModel,
    template: MacroTemplate,
    checkEncodable: Boolean = true,
): (String) -> Unit {
    val scope = rememberCoroutineScope()
    var isSending by remember { mutableStateOf(false) }
    var cancelled by remember { mutableStateOf(false) }
    // 被拦下的那一次发送：留着文本，「仍然发送」时原样用
    var blockedUnit by remember { mutableStateOf<MacroTemplateExpander.UnencodableUnit?>(null) }
    var blockedText by remember { mutableStateOf("") }

    /** 真正开始逐帧发送：检查通过、或用户点了「仍然发送」 */
    fun startSending(text: String) {
        cancelled = false
        isSending = true
        scope.launch(Dispatchers.IO) {
            val check = { if (cancelled) throw CancellationException() }
            try {
                sendTextByTemplate(viewModel, template, text, check)
            } catch (_: CancellationException) {
                // 用户取消了发送（或预览被关掉），释放所有按键
                viewModel.sendReport(
                    HidReportDescriptors.REPORT_ID_KEYBOARD,
                    ByteArray(8)
                )
            } finally {
                withContext(Dispatchers.Main) {
                    isSending = false
                }
            }
        }
    }

    if (isSending) {
        AlertDialog(
            onDismissRequest = { cancelled = true },
            title = { Text(stringResource(id = R.string.sending)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(stringResource(id = R.string.sending_text_hint))
                }
            },
            confirmButton = {
                TextButton(onClick = { cancelled = true }) {
                    Text(stringResource(id = R.string.cancel))
                }
            }
        )
    }

    blockedUnit?.let { unit ->
        AlertDialog(
            onDismissRequest = { blockedUnit = null },
            title = { Text(stringResource(id = R.string.template_send_unencodable_title)) },
            text = {
                Text(
                    stringResource(
                        id = R.string.template_send_unencodable_text,
                        unit.index + 1,
                        unit.text,
                        unit.codePoint.toString(16).uppercase(),
                        template.header.charset,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    blockedUnit = null
                    startSending(blockedText)
                }) {
                    Text(stringResource(id = R.string.template_send_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = { blockedUnit = null }) {
                    Text(stringResource(id = R.string.cancel))
                }
            }
        )
    }

    // template 每次编辑都会换新对象，所以这里每次重组重建一个闭包就够（开销可忽略）
    return { text ->
        if (text.isNotBlank() && !isSending && blockedUnit == null) {
            if (!checkEncodable) {
                startSending(text)
            } else {
                blockedText = text
                // 检查放 IO：即便只碰第一个编不出来的字符，几万字符的扫描也不值得占主线程
                scope.launch(Dispatchers.IO) {
                    val unit = MacroTemplateExpander.firstUnencodable(template.header, text)
                    withContext(Dispatchers.Main) {
                        if (unit == null) startSending(text) else blockedUnit = unit
                    }
                }
            }
        }
    }
}