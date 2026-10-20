package com.github.remotecontrol.usbip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.remotecontrol.R
import com.github.remotecontrol.hid.HidState
import com.github.remotecontrol.hid.isListening
import com.github.remotecontrol.ui.theme.ConnStatus
import com.github.remotecontrol.ui.theme.ElevatedCard
import com.github.remotecontrol.ui.theme.LocalAppDimens
import com.github.remotecontrol.ui.theme.LocalAppTokens
import com.github.remotecontrol.ui.theme.PrimaryButton
import com.github.remotecontrol.ui.theme.SectionTitle
import com.github.remotecontrol.ui.theme.StatusDot
import com.github.remotecontrol.ui.theme.TintLevel

/**
 * 首屏（设备列表）下方的局域网卡片：开始 / 停止监听、显示本机地址、显示电脑是否已导入。
 *
 * ## 三个状态，一眼就能分清下一步
 * | 状态 | 圆点 | 用户要做什么 |
 * | --- | --- | --- |
 * | 未监听 | 灰 | 点「开始监听」 |
 * | 正在监听 | 琥珀 | 在电脑上执行卡片给出的 `usbip attach` 命令 |
 * | 电脑已连接 | 绿 | 点卡片进控制页（或直接等它自己进来） |
 *
 * ## 为什么主机一连上就自动进控制页
 * 电脑 `attach` 成功之后，用户下一步必然是打字 / 划触摸板；停在首屏对着一个绿点没有意义。
 * 只在**这一次会话**自动进一次（断开后重连会再来一次），用户退回来也不会被反复弹走。
 *
 * ## 端口被占时
 * 卡片把失败原因直接写在状态行里（不弹窗）：这个失败只有一个出口 —— 再点一次开始监听，
 * 而按钮就在旁边。
 */
@Composable
fun LanConnectionCard(
    modifier: Modifier = Modifier,
    onOpenControls: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel = remember { UsbIpInputViewModel.getInstance(context) }
    val state by viewModel.state.collectAsState()
    val address by viewModel.localAddress.collectAsState()
    // 端口是设置项：用户在设置里改完返回时，这里的 `地址:端口` 要跟着变
    val port by viewModel.port.collectAsState()

    val dimens = LocalAppDimens.current
    val tokens = LocalAppTokens.current
    val listening = state.isListening
    val connected = state is HidState.Connected

    // 主机导入成功 → 自动进控制页一次
    var enteredForThisSession by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (connected && !enteredForThisSession) {
            enteredForThisSession = true
            onOpenControls()
        } else if (!connected) {
            enteredForThisSession = false
        }
    }

    val addressText = address
        ?: stringResource(id = R.string.lan_address_unknown)
    val endpoint = "$addressText:$port"

    val (status, statusLabel) = when (val current = state) {
        HidState.Connected ->
            ConnStatus.OK to stringResource(id = R.string.lan_connected)

        HidState.Ready, HidState.Connecting ->
            ConnStatus.WARN to stringResource(id = R.string.lan_listening, endpoint)

        is HidState.RegisterFailed ->
            ConnStatus.BAD to stringResource(id = R.string.lan_failed, port)

        else ->
            ConnStatus.IDLE to stringResource(id = R.string.lan_idle)
    }

    ElevatedCard(
        modifier = modifier
            .padding(horizontal = dimens.screenPadding, vertical = 4.dp),
        // 已经挂上时整卡可点（进控制页）；没挂上时卡片不是可点目标，别给用户一个点不动的错觉
        onClick = if (connected) onOpenControls else null,
        level = if (connected) TintLevel.SOLID else TintLevel.SURFACE,
    ) {
        SectionTitle(text = stringResource(id = R.string.lan_title))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.sectionGap),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                StatusDot(
                    status = status,
                    label = statusLabel,
                    // 满浓度底（已连接）上只能用 onAccent，与扫描页那张连接卡片同一口径
                    textColor = if (connected) {
                        tokens.onAccent
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (connected) {
                    Text(
                        text = stringResource(id = R.string.lan_open_controls),
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.onAccent,
                    )
                } else if (listening) {
                    // 电脑那条命令直接写出来：地址、busid 都在上面，用户不用去别处找
                    Text(
                        text = stringResource(id = R.string.lan_attach_hint, addressText),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            PrimaryButton(
                text = if (listening) {
                    stringResource(id = R.string.lan_stop)
                } else {
                    stringResource(id = R.string.lan_start)
                },
                onClick = { viewModel.toggleListening(context) },
            )
        }
    }
}
