package com.github.remotecontrol.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.remotecontrol.R

/**
 * 断开连接确认框。
 *
 * 控制页顶栏的断开按钮与扫描页连接卡片上的断开按钮共用：两处都是「破坏性动作先确认」
 * 这同一件事，文案与按钮顺序不该各写一份。
 */
@Composable
fun DisconnectDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.disconnect)) },
        text = { Text(stringResource(id = R.string.confirm_disconnect)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) {
                Text(stringResource(id = R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.cancel))
            }
        }
    )
}
