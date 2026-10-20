package com.github.remotecontrol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.remotecontrol.R
import com.github.remotecontrol.ui.theme.ElevatedCard
import com.github.remotecontrol.ui.theme.LocalAppDimens
import com.github.remotecontrol.ui.theme.SectionTitle
import com.github.remotecontrol.ui.theme.TintLevel
import com.github.remotecontrol.util.SettingConstants

/**
 * 鼠标设置：只有「长按判定时长」一项。
 *
 * 它**只影响经典蓝牙链路的鼠标触摸区**（短按左键 / 长按右键）——
 * 局域网链路是 PTP 触摸板，单击 / 长按由主机侧的精密触摸板驱动处理，不读这个值。
 * 说明文字里必须点明这一点，否则会被当成「触摸板也吃这个设置」。
 *
 * 从 `ui/SettingsScreen.kt` 单独拆出来：那个文件加上这一节会越过 700 行的拆分线，
 * 而设置分区本来就是一整块独立区域。
 */
@Composable
fun MouseSettingsSection(
    longPressDuration: Int,
    onLongPressDurationChange: (Int) -> Unit,
) {
    val dimens = LocalAppDimens.current

    Column(modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 0.dp)) {
        ElevatedCard(level = TintLevel.SURFACE) {
            SectionTitle(text = stringResource(R.string.mouse_settings), icon = Icons.Default.Mouse)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.long_press_duration),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.long_press_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onLongPressDurationChange(
                                longPressDuration - SettingConstants.LONG_PRESS_DURATION_STEP
                            )
                        },
                        enabled = longPressDuration > SettingConstants.LONG_PRESS_DURATION_MIN,
                        modifier = Modifier.size(40.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("-")
                    }
                    Text(
                        text = stringResource(R.string.long_press_duration_format, longPressDuration),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(74.dp)
                    )
                    Button(
                        onClick = {
                            onLongPressDurationChange(
                                longPressDuration + SettingConstants.LONG_PRESS_DURATION_STEP
                            )
                        },
                        enabled = longPressDuration < SettingConstants.LONG_PRESS_DURATION_MAX,
                        modifier = Modifier.size(40.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("+")
                    }
                }
            }
        }
    }
}
