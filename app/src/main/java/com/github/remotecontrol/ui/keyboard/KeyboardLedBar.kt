package com.github.remotecontrol.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import com.github.remotecontrol.ui.theme.LocalAppDimens
import com.github.remotecontrol.ui.theme.LocalAppTokens
import com.github.remotecontrol.util.KeyboardConstants

/**
 * 键盘灯条：Num / Caps / Scroll 三个**按钮**，夹在分页标签与键盘之间。
 *
 * 位置固定在这里的理由与固定修饰键区同源 —— 它跟着键盘待在原地，不随翻页跑来跑去，
 * 所以翻页时也能一眼看到三盏灯、也能直接切换（横竖屏位置一致：标签下方、键盘上方那一条）。
 *
 * ## 为什么是按钮
 * 灯态的唯一来源是主机，但**切换**这个动作在设备侧：点一下就是把对应的锁键（Caps 0x39 /
 * ScrLk 0x47 / NumLk 0x53）按一下发给主机，主机的锁定状态随之翻转、再把新的灯态推回来。
 * 于是同一个控件同时承担「看」和「改」两件事，不必为了切 Caps 再跑去键盘页找那颗键。
 *
 * ## 为什么有三种画法
 * 灯是主机 → 设备的单向信息，设备侧没有查询手段（见 [KeyboardConstants.KeyboardLedsState]），
 * 所以「主机还没说过」必须能表达出来：
 *
 * | 画法 | 含义 |
 * | --- | --- |
 * | 实心橙点 / 实心暗点 | 主机确认过：亮 / 灭 |
 * | 空心橙圈 / 空心灰圈 | 推测（主机还没确认过），亮 / 灭 |
 *
 * 「亮」取的是 **警告色（异色）**，不是错误色：灯亮只说明锁键生效，不是出错。
 * 两者同色的话，三盏灯亮着会像整页都在报错，而错误色得留着说真正出错的事。
 *
 * 键盘页一打开就会主动去问一次主机（见 `HidSession.syncKeyboardLeds`），
 * 所以空心圈一般只出现在「刚进页面」到「主机回话」之间那一瞬间；主机要是连 Num Lock 都不理
 * （`HidSession.numpadWarning`），空心圈就会一直留着，界面会另外
 * 提示一句「连接的设备可能不支持小键盘」。
 */
@Composable
fun KeyboardLedBar(
    state: KeyboardConstants.KeyboardLedsState,
    onToggle: (KeyboardConstants.Key) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalAppDimens.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.keyContentPadding, vertical = dimens.keySpacing)
            .height(dimens.keyboardLedBarHeight),
        horizontalArrangement = Arrangement.spacedBy(dimens.keySpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ledKeys.forEach { key ->
            KeyboardLedButton(
                key = key,
                on = state.leds.lampFor(key.value) == true,
                confirmed = state.confirmed,
                onClick = { onToggle(key) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

/**
 * 三个灯位，同时也是三个按钮。
 *
 * 顺序按实体键盘右上角的习惯（Num / Caps / Scroll）；标签取键位自己的 displayName，
 * 与键盘页上印的字一致（不翻译的实体键缩写，与 [KeyboardConstants.Keys] 同一个口径）。
 */
private val ledKeys = listOf(
    KeyboardConstants.Keys.NUM_LOCK,
    KeyboardConstants.Keys.CAPS_LOCK,
    KeyboardConstants.Keys.SCROLL_LOCK,
)

/**
 * 单个灯位按钮：键位同款容器（panel 底 + 描边）里放一个圆点 + 灯名。
 *
 * 键位配色与 `KeyboardCanvas` 的普通按键、以及修饰键区保持一致，三处看起来是同一套键。
 */
@Composable
private fun KeyboardLedButton(
    key: KeyboardConstants.Key,
    on: Boolean,
    confirmed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalAppDimens.current
    val tokens = LocalAppTokens.current
    val shape = remember(dimens.innerCorner) { RoundedCornerShape(dimens.innerCorner) }

    Row(
        modifier = modifier
            .clip(shape)
            .background(tokens.panel)
            .border(dimens.strokeWidth, tokens.tintLine, shape)
            // toggleable 而不是裸 clickable：读屏会照着系统文案播报「开 / 关」，
            // 语义也正是「按一下翻转」；实际值仍以主机推回来的为准，所以忽略这里的新值
            .toggleable(value = on, role = Role.Switch, onValueChange = { onClick() }),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(dimens.keyboardLedDotSize)
                .clip(CircleShape)
                .background(
                    when {
                        // 未确认：留空心圈，不填色
                        !confirmed -> Color.Transparent
                        // 点亮：取警告色当指示灯的亮色，笔记本上的小橙点就是这个观感
                        on -> tokens.warn
                        else -> tokens.line
                    }
                )
                .border(
                    width = dimens.strokeWidth,
                    // 未确认时描边按亮灭上色（亮橙圈 / 灭灰圈），确认过就不需要描边
                    color = when {
                        !confirmed && on -> tokens.warn
                        !confirmed -> tokens.line
                        else -> Color.Transparent
                    },
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.width(dimens.keySpacing))
        Text(
            text = key.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.onVar,
            maxLines = 1,
        )
    }
}
