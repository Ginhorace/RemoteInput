package com.github.remotecontrol.bluetooth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.github.remotecontrol.ui.theme.SecondaryButton
import com.github.remotecontrol.ui.theme.StatusDot
import com.github.remotecontrol.ui.theme.TintLevel
import com.github.remotecontrol.hid.HidState

/**
 * 扫描页顶部的「正在保持的连接」卡片。
 *
 * 后退只退出界面之后，用户回到这里时连接其实还在（由前台服务与常驻通知承载），
 * 列表却看不出这件事 —— 于是「再点一次那台设备」和「其实已经连着」在界面上长得一样。
 * 这张卡片把连接状态摆到列表最上面（「已保存的设备」之前）：一眼看到连着谁，
 * 也就知道下一步是点进控制页还是断开。
 *
 * 交互分工：**整张卡片就是「回到控制页」**（点断开按钮以外的地方都算），
 * 破坏性的那个动作单独做成右侧的次按钮 —— 不用两个一样重的按钮去分担一件事，
 * 也让「继续用」这个默认意图有整张卡片的点击面积。
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
        modifier = Modifier
            .padding(horizontal = dimens.screenPadding, vertical = 4.dp)
            // 整卡就是「回到控制页」。onClickLabel 是给读屏的动作名：卡片上已经没有
            // 写着「回到控制页」的按钮了，不点明的话读屏只说得出「可点击」
            .clickable(
                onClickLabel = stringResource(id = R.string.open_controls),
                onClick = onOpenControls,
            ),
        // 第 5 档，与「开始扫描」那个主按钮同浓：一列点不开的设备行里，这张卡片必须一眼
        // 看出不是同一种东西；顺带让卡里的「断开连接」次按钮（第 2 档底）也彻底分得开。
        // 代价是卡上内容不能再跟随 onSurface / onSurfaceVariant —— 满浓度底上只能用 onAccent，
        // 所以下面四处颜色都是为这个档位专门指定的（ElevatedCard 只铺底色，不管内容色）
        level = TintLevel.SOLID,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Default.Bluetooth,
                contentDescription = stringResource(id = R.string.bluetooth_device),
                tint = tokens.onAccent,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = R.string.connection_kept_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.onAccent
                )
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.onAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 圆点本身仍是语义色（绿 / 琥珀），只有它旁边的文案跟随 onAccent
                StatusDot(status = status, label = statusLabel, textColor = tokens.onAccent)
            }
            // 断开是破坏性的那一个，按次按钮处理，与设备名同行；点卡片其它地方＝回到控制页
            SecondaryButton(
                text = stringResource(id = R.string.disconnect),
                onClick = onDisconnect,
            )
        }
    }
}
