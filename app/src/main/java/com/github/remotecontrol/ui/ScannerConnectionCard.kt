package com.github.remotecontrol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.remotecontrol.R
import com.github.remotecontrol.ui.theme.ConnStatus
import com.github.remotecontrol.ui.theme.ElevatedCard
import com.github.remotecontrol.ui.theme.LocalAppDimens
import com.github.remotecontrol.ui.theme.LocalAppTokens
import com.github.remotecontrol.ui.theme.PrimaryButton
import com.github.remotecontrol.ui.theme.SecondaryButton
import com.github.remotecontrol.ui.theme.StatusDot
import com.github.remotecontrol.ui.theme.TintLevel
import com.github.remotecontrol.viewmodel.HidState

/**
 * 扫描页顶部的「正在保持的连接」卡片。
 *
 * 后退只退出界面之后，用户回到这里时连接其实还在（由前台服务与常驻通知承载），
 * 列表却看不出这件事 —— 于是「再点一次那台设备」和「其实已经连着」在界面上长得一样。
 * 这张卡片把连接状态摆到列表最上面（「已保存的设备」之前）：一眼看到连着谁，
 * 下一条动作是「回到控制页」还是「断开连接」也就明确了。
 *
 * 只有链路真的活跃时（[HidState.hasActiveLink]）才画，由调用方判断。
 */
@Composable
fun ScannerConnectionCard(
    deviceName: String,
    state: HidState,
    onOpenControls: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    val dimens = LocalAppDimens.current

    // 与顶栏的语义色圆点同一口径：绿=已连接，琥珀=过渡中（连接中 / 断开中）
    val (status, statusLabel) = when (state) {
        HidState.Connected -> ConnStatus.OK to stringResource(id = R.string.connected)
        HidState.Disconnecting -> ConnStatus.WARN to stringResource(id = R.string.disconnecting)
        else -> ConnStatus.WARN to stringResource(id = R.string.connecting)
    }

    ElevatedCard(
        modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 4.dp),
        level = TintLevel.SURFACE,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Default.Bluetooth,
                contentDescription = stringResource(id = R.string.bluetooth_device),
                tint = tokens.accent,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = R.string.connection_kept_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                StatusDot(status = status, label = statusLabel)
            }
        }

        Spacer(modifier = Modifier.height(dimens.sectionGap))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 主操作是「回去继续用」；断开是破坏性的那一个，按次按钮处理
            PrimaryButton(
                text = stringResource(id = R.string.notification_action_open),
                onClick = onOpenControls,
            )
            SecondaryButton(
                text = stringResource(id = R.string.disconnect),
                onClick = onDisconnect,
            )
        }
    }
}
