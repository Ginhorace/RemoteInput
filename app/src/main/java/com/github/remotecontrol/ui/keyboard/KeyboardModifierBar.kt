package com.github.remotecontrol.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import com.github.remotecontrol.ui.theme.LocalAppDimens
import com.github.remotecontrol.ui.theme.LocalAppTokens
import com.github.remotecontrol.util.KeyboardConstants
import com.github.remotecontrol.util.KeyboardConstants.Keys
import com.github.remotecontrol.util.KeyboardConstants.ModifierKeyType
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 固定修饰键区的状态。
 *
 * 修饰键有两条互相独立的生效路径，所以状态也是两份：
 *
 * | 集合 | 含义 | 何时增减 |
 * | --- | --- | --- |
 * | [held] | **按下生效**：手指按住期间有效 | 按下时加入，抬起时移除 |
 * | [locked] | **常驻锁定**：长按切换，不随手指抬起消失 | 长按加入；再点一下（或再长按）移除 |
 *
 * 送给主机的修饰位是两者的并集（[reportByte]），因此「按住 Ctrl 再点 C」和
 * 「锁定 Ctrl 再点 C」得到的是同一份报告。
 */
@Immutable
data class ModifierUiState(
    val held: Set<ModifierKeyType> = emptySet(),
    val locked: Set<ModifierKeyType> = emptySet(),
) {

    /**
     * 键盘报告第 0 字节（修饰位掩码）= held ∪ locked。
     *
     * 合并逻辑复用 [KeyboardConstants.ModifierKeys.toByte]，与模板模式等其它发送路径
     * 共用同一套位定义，避免这里再写一遍 0x01/0x02… 的映射。
     */
    val reportByte: Byte
        get() = (held + locked)
            .fold(KeyboardConstants.ModifierKeys()) { acc, type -> acc.withFlag(type, pressed = true) }
            .toByte()

    /** 该修饰键当前是否生效（按住或已锁定） */
    fun isActive(type: ModifierKeyType): Boolean = type in held || type in locked

    /** 该修饰键是否处于常驻锁定 */
    fun isLocked(type: ModifierKeyType): Boolean = type in locked

    /** 手指按下：进入临时按下态 */
    internal fun press(type: ModifierKeyType): ModifierUiState = copy(held = held + type)

    /**
     * 长按到阈值：切换常驻锁定，同时清掉临时按下态 ——
     * 手指仍按着但 locked 已经是更强的状态，held 留着只会让状态含义变模糊。
     */
    internal fun lock(type: ModifierKeyType): ModifierUiState = copy(
        held = held - type,
        locked = if (type in locked) locked - type else locked + type,
    )

    /**
     * 手指抬起（或被上层取消）。
     *
     * @param wasLockedAtPress 按下瞬间该键是否已锁定。锁定态下的一次**点按**要能解锁，
     *   单看抬起这一刻的 [locked] 分不清「本来就锁着」还是「刚刚长按锁上的」
    */
    internal fun release(type: ModifierKeyType, wasLockedAtPress: Boolean): ModifierUiState = copy(
        held = held - type,
        locked = if (wasLockedAtPress) locked - type else locked,
    )
}

/** 修饰键区里的四个键：取左侧变体（HID 0xE0–0xE3） */
private val modifierBarKeys: List<KeyboardConstants.Key> = listOf(
    Keys.LEFT_CTRL,
    Keys.LEFT_SHIFT,
    Keys.LEFT_ALT,
    Keys.LEFT_WIN,
)

/**
 * 固定修饰键区。
 *
 * 键盘页会翻页（竖屏上下、横屏左右），而修饰键必须在翻页时待在原地，所以它们被抽到这里，
 * 由界面挂在分页容器之外：竖屏是底部横条，横屏是右侧竖条（`vertical = true`）。
 *
 * 交互（三态）：
 * - **点按** → 临时按下，手指抬起即失效；
 * - **长按**（阈值取系统 `ViewConfiguration.longPressTimeoutMillis`，默认约 400ms）→ 转常驻锁定；
 * - **锁定态点按** → 解锁。
 *
 * 组件只负责状态迁移，不自己发 HID 报告：状态变化通过 [onStateChange] 交给界面，
 * 由界面统一组装并发送报告（保持「报告出口只有一个」）。
 * 这样也把「修饰位怎么合并」变成纯函数，可以直接单测（见 ModifierUiStateTest）。
 *
 * @param state 当前状态（由界面持有，翻页 / 旋转都不受影响）
 * @param onStateChange 状态迁移回调；界面据此更新状态并发送带新修饰位的报告
 * @param vertical 横屏竖条布局（4 键纵向排布），默认是竖屏的底部横条
 */
@Composable
fun KeyboardModifierBar(
    state: ModifierUiState,
    onStateChange: (ModifierUiState) -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    val dimens = LocalAppDimens.current
    val tokens = LocalAppTokens.current
    val density = LocalDensity.current
    val strokePx = with(density) { dimens.strokeWidth.toPx() }

    // 与分页区之间的 1dp 分层线：竖屏画在顶边，横屏画在左边
    val barModifier = modifier
        .then(
            if (vertical) Modifier.width(dimens.modifierBarWidth).fillMaxHeight()
            else Modifier.fillMaxWidth().height(dimens.modifierBarHeight)
        )
        .drawBehind {
            val lineColor = tokens.tintLine
            if (vertical) {
                drawRect(color = lineColor, topLeft = Offset.Zero, size = Size(strokePx, size.height))
            } else {
                drawRect(color = lineColor, topLeft = Offset.Zero, size = Size(size.width, strokePx))
            }
        }

    if (vertical) {
        Column(
            modifier = barModifier.padding(dimens.modifierBarPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.keySpacing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            modifierBarKeys.forEach { key ->
                ModifierKeyButton(
                    key = key,
                    state = state,
                    onStateChange = onStateChange,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }
    } else {
        Row(
            modifier = barModifier.padding(dimens.modifierBarPadding),
            horizontalArrangement = Arrangement.spacedBy(dimens.keySpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            modifierBarKeys.forEach { key ->
                ModifierKeyButton(
                    key = key,
                    state = state,
                    onStateChange = onStateChange,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * 单枚修饰键。
 *
 * 键位配色与 `KeyboardCanvas` 的普通按键保持一致（panel 底 + 描边 + 主文字色），
 * 这样固定区和键盘区看起来是同一套键；两个额外状态是：
 * 临时按下 = 第 4 档 [com.github.remotecontrol.ui.theme.AppTokens.active]，
 * 常驻锁定 = 满浓度 [com.github.remotecontrol.ui.theme.AppTokens.accent] + 反白文字
 * （与模式按钮的「选中 = 满浓度」语义一致），另在文字下方加一条细色条强化锁定感。
 */
@Composable
private fun ModifierKeyButton(
    key: KeyboardConstants.Key,
    state: ModifierUiState,
    onStateChange: (ModifierUiState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = requireNotNull(key.modifierType) {
        "修饰键区只接受带 modifierType 的按键，收到的是 ${key.displayName}"
    }
    val dimens = LocalAppDimens.current
    val tokens = LocalAppTokens.current

    val isLocked = state.isLocked(type)
    val isHeld = type in state.held
    val containerColor = when {
        isLocked -> tokens.accent
        isHeld -> tokens.active
        else -> tokens.panel
    }
    val borderColor = when {
        isLocked -> tokens.accent
        isHeld -> tokens.activeLine
        else -> tokens.tintLine
    }
    val contentColor = when {
        isLocked -> tokens.onAccent
        isHeld -> tokens.on
        else -> MaterialTheme.colorScheme.onSurface
    }
    val shape = remember(dimens.innerCorner) { RoundedCornerShape(dimens.innerCorner) }
    val indicatorShape = remember(dimens.strokeWidth) { RoundedCornerShape(dimens.strokeWidth) }

    // 手势闭包只挂一次（pointerInput 的 key 是键位类型），所以状态与回调都要读最新值
    val currentState by rememberUpdatedState(state)
    val currentOnStateChange by rememberUpdatedState(onStateChange)

    Box(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .border(dimens.strokeWidth, borderColor, shape)
            // 无障碍语义：读屏用户没法"一边按住 Ctrl 一边点别的键"，所以语义动作只提供
            // 常驻锁定/解锁（等价长按），并按 Switch 播报锁定状态；触摸探索会拦掉触摸，
            // 与下面的手势循环不会双触发
            .semantics {
                role = Role.Switch
                contentDescription = key.displayName
                toggleableState = if (isLocked) ToggleableState.On else ToggleableState.Off
                onClick {
                    currentOnStateChange(currentState.lock(type))
                    true
                }
            }
            .pointerInput(type) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 按下瞬间是否已锁定 —— 决定这一次是「解锁」还是「临时按下」
                    val wasLocked = currentState.isLocked(type)
                    currentOnStateChange(currentState.press(type))

                    var released = false
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation()
                        released = true
                    }

                    if (released) {
                        currentOnStateChange(currentState.release(type, wasLockedAtPress = wasLocked))
                    } else {
                        // 超时仍按住 → 转常驻锁定。手指还在按着，但 locked 已经是最强状态，
                        // 不必再等抬起；awaitEachGesture 会在所有手指离开后开启下一轮
                        currentOnStateChange(currentState.lock(type))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = key.displayName,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                softWrap = false,
            )
            // 锁定指示条：非锁定态与底色同色，占位但不显形，避免状态切换时布局跳动
            Spacer(Modifier.height(dimens.strokeWidth))
            Box(
                modifier = Modifier
                    .size(width = dimens.modifierLockIndicatorSize, height = dimens.strokeWidth)
                    .clip(indicatorShape)
                    .background(if (isLocked) contentColor else containerColor)
            )
        }
    }
}

/** 按修饰键类型翻转 [KeyboardConstants.ModifierKeys] 里的对应位 */
private fun KeyboardConstants.ModifierKeys.withFlag(
    type: ModifierKeyType,
    pressed: Boolean,
): KeyboardConstants.ModifierKeys = when (type) {
    ModifierKeyType.LeftControl -> copy(leftControl = pressed)
    ModifierKeyType.LeftShift -> copy(leftShift = pressed)
    ModifierKeyType.LeftAlt -> copy(leftAlt = pressed)
    ModifierKeyType.LeftGUI -> copy(leftGUI = pressed)
    ModifierKeyType.RightControl -> copy(rightControl = pressed)
    ModifierKeyType.RightShift -> copy(rightShift = pressed)
    ModifierKeyType.RightAlt -> copy(rightAlt = pressed)
    ModifierKeyType.RightGUI -> copy(rightGUI = pressed)
}
