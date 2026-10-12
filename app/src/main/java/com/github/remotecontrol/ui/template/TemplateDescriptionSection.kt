package com.github.remotecontrol.ui.template

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.github.remotecontrol.R
import com.github.remotecontrol.ui.theme.LocalAppTokens
import com.github.remotecontrol.ui.theme.PanelSurface

// ─────────────────────────────────────────────
// 模板说明（「添加步骤」与步骤列表之间）
// ─────────────────────────────────────────────

/**
 * 卡片里最多铺几行（含「描述：」在内），多出来的部分由行尾的省略号提示「点进去看全」。
 *
 * 只给两行是因为它挤走的正是步骤列表的位置：说明是**入口**，全文在对话框里。
 */
private const val DESCRIPTION_PREVIEW_LINES = 2

/**
 * 说明卡片：点它打开 [TemplateDescriptionDialog]。
 *
 * 位置选在「添加步骤」与步骤列表之间，而不是跟字符集、间隔一起收进模板参数卡里：
 * 说明讲的是这张模板**整体**在干什么（「按住 Alt 敲小键盘十进制；小于 1000 的码位会走
 * OEM 页」），读它的时机正是「看着步骤猜这行是干嘛的」之前。收进参数卡就得先展开卡片，
 * 而卡片里全是机器参数，说明混在中间反而不显眼。
 *
 * 底用 [PanelSurface]：它正是步骤行那一套（innerCorner + tint 底 + 第 3 档描边），
 * 说明与步骤是同一个「模板正文」的两半，长得一样才像一回事。
 *
 * 文案不走「标题一行、正文一行」的两段式，而是**「描述：」后面直接接正文**：
 * 卡片上只有两行预算，标题另占一行就只剩一行能看到内容。字号抬到 `bodyLarge`（16sp），
 * 比编辑器标题的 `titleSmall`（14sp）还大一档 —— 这一块要靠字号而不是靠位置争注意力。
 *
 * @param description 说明正文；空白按**没写**处理，显示成一句提示
 */
@Composable
internal fun DescriptionCard(
    description: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppTokens.current
    val text = description.orEmpty()
    val empty = text.isBlank()

    PanelSurface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            // 图标跟着两行正文竖向居中；正文只有一行时也自然对齐
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Notes,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = descriptionPreview(text, empty),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = DESCRIPTION_PREVIEW_LINES,
                // 超出的部分收成行尾的「…」——「点得进去」这件事由它提示，
                // 右侧那支铅笔只是补一个「这是编辑入口」的说法
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                Icons.Default.Edit,
                contentDescription = stringResource(R.string.template_edit),
                tint = tokens.accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 卡片上那行字：「描述：」+ 正文，拼成**一段**而不是两个 Text。
 *
 * 拼成一段是为了让换行接着「描述：」往下流（第二行顶格），而不是缩在正文那一列的框里；
 * 分隔符也才能按语言给（法语是「描述 :」、中日是「描述：」，见 strings 里的
 * `template_description_label`）。
 *
 * 没写说明时整句压暗：那是一句「点击添加」的提示，不该和写好的说明抢注意力。
 */
@Composable
private fun descriptionPreview(content: String, empty: Boolean): AnnotatedString {
    val scheme = MaterialTheme.colorScheme
    // 文案先在 composable 里取好：`withStyle` 的块是普通 lambda，里面不方便调 @Composable
    val label = stringResource(R.string.template_description_label)
    val body = if (empty) stringResource(R.string.template_description_empty) else content
    return buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = scheme.onSurfaceVariant)) {
            append(label)
        }
        withStyle(SpanStyle(color = if (empty) scheme.onSurfaceVariant else scheme.onSurface)) {
            append(body)
        }
    }
}

/**
 * 说明编辑对话框。
 *
 * 与「展开预览」同一个思路：说明可以有好几行，就地展开会把步骤列表整个挤下去，
 * 所以给一个独立窗口 —— 它也才有地方让输入框随内容长高。
 *
 * 改的是副本，点「确定」才写回（与步骤对话框同一口径）：说明是自由文本，
 * 误删一段没法撤销，留一条「取消」比「边打边写」踏实。
 *
 * @param onConfirm 确认后回传新正文；空串照旧表示没写，由 [MacroTemplateParser] 落盘时丢掉那一行
 */
@Composable
internal fun TemplateDescriptionDialog(
    description: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(description) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.template_description)) },
        text = {
            // 上限取**对话框窗口**可用高度的一半，而不是 LocalConfiguration 里的屏幕高度：
            // ControlActivity 拦截了方向配置变更（旋转不重建），那边拿到的宽高旋转后不刷新
            // （见 ControlActivity 的 onConfigurationChanged）。这里的高度是布局时量出来的，
            // 旋转、键盘挤占都会跟着变，含义也正是「半个屏幕」。
            BoxWithConstraints {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.template_description_hint)) },
                    // 高度随内容长：起步 3 行，长到半个屏幕为止，再多就在框内滚动
                    minLines = 3,
                    colors = fieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxHeight * DIALOG_MAX_HEIGHT_RATIO),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** 输入框最高占对话框可用高度的多少 —— 一半，即「半个屏幕」 */
private const val DIALOG_MAX_HEIGHT_RATIO = 0.5f
