package com.github.remotecontrol.ui.template

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.github.remotecontrol.R
import com.github.remotecontrol.ui.theme.LocalAppDimens
import com.github.remotecontrol.ui.theme.LocalAppTokens
import com.github.remotecontrol.ui.theme.MonoMedium
import com.github.remotecontrol.ui.theme.MonoSmall
import com.github.remotecontrol.util.template.MacroTemplateKeys
import com.github.remotecontrol.util.template.MacroTemplateStep
import com.github.remotecontrol.util.template.modifierNames
import com.github.remotecontrol.util.template.plainKeyNames
import com.github.remotecontrol.util.template.shiftResultChar
import kotlin.math.roundToInt

// ─────────────────────────────────────────────
// 步骤列表：可上下拖动排序，点击编辑
// ─────────────────────────────────────────────

/** 步骤行间距：列里 `spacedBy` 与拖动时的行步距必须取同一个值，否则被拖的行会与手指错开 */
private val STEP_ROW_SPACING = 4.dp

/** 拖动时离上下边缘这么近就开始自动滚动 */
private val AUTO_SCROLL_EDGE = 24.dp

/** 自动滚动每帧最多滚多少（越深入边缘区越接近它，停在边缘不动时取满速） */
private val AUTO_SCROLL_STEP = 6.dp

@Composable
internal fun StepList(
    steps: List<MacroTemplateStep>,
    /** 有错误的步骤下标（0 起），标红用 */
    errorIndexes: Set<Int>,
    onMove: (Int, Int) -> Unit,
    onClick: (Int) -> Unit,
    onToggleEnabled: (Int) -> Unit,
    /** 列表自己那一格滚动区（见 `TemplateEditorPane`）：拖动到边缘时按帧滚它 */
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    val rowHeightPx = with(density) { STEP_ROW_HEIGHT.toPx() }
    // 被拖的行在内容里是按「行高 + 行距」一格一格排的，算它的位置要用这个步距
    val rowPitchPx = rowHeightPx + with(density) { STEP_ROW_SPACING.toPx() }
    val autoScrollEdgePx = with(density) { AUTO_SCROLL_EDGE.toPx() }
    val autoScrollStepPx = with(density) { AUTO_SCROLL_STEP.toPx() }

    val latestSteps by rememberUpdatedState(steps)
    val latestOnMove by rememberUpdatedState(onMove)

    /**
     * 按累计位移把被拖的行挪到它该在的下标上，余量继续留在 [dragOffsetY]
     * （也就是「手指底下那个位置」），所以行是跟着手指走的。
     *
     * 步距取 [rowPitchPx]（行高 + 行距）而不是单行高：行的位置就是按这个步距排的，
     * 拿行高当阈值的话，每换一行行就会比手指落后一个行距（拖十来行就看得出来）。
     */
    fun settleDrag() {
        val from = draggingIndex
        if (from < 0) return
        val lastIndex = latestSteps.lastIndex
        if (lastIndex < 0) return
        val shift = (dragOffsetY / rowPitchPx).roundToInt()
        val target = (from + shift).coerceIn(0, lastIndex)
        if (target != from) {
            latestOnMove(from, target)
            dragOffsetY -= (target - from) * rowPitchPx
            draggingIndex = target
        }
    }

    fun handleDrag(deltaY: Float) {
        if (draggingIndex < 0) return
        dragOffsetY += deltaY
        settleDrag()
    }

    /**
     * 被拖的行贴到上下边缘（或停在边缘）时，列表跟着滚一帧的量。
     *
     * 两处容易走叉：
     * - **滚多少就把这一行在内容里的位置补多少**（[dragOffsetY] 加上实际滚过的量）：行与手指的
     *   相对位置因此不变，而下标会随滚动继续往后（或往前）走 —— 这就是「拖到边缘能一路拖到底」；
     *   不补的话，内容一滚，行就会自己从手指底下溜走；
     * - 越深入边缘区滚得越快（停在边缘不动时正好取满速），所以拖动速度天然由手指位置控制。
     */
    suspend fun autoScroll() {
        val index = draggingIndex
        val viewport = scrollState.viewportSize
        if (index < 0 || viewport <= 0) return

        val rowTop = index * rowPitchPx + dragOffsetY - scrollState.value
        val rowBottom = rowTop + rowHeightPx
        val upperEdge = autoScrollEdgePx
        val lowerEdge = viewport - autoScrollEdgePx
        val delta = when {
            rowTop < upperEdge -> -((upperEdge - rowTop) / upperEdge).coerceIn(0f, 1f) * autoScrollStepPx
            rowBottom > lowerEdge ->
                ((rowBottom - lowerEdge) / upperEdge).coerceIn(0f, 1f) * autoScrollStepPx
            else -> return
        }

        val before = scrollState.value
        scrollState.scrollTo((before + delta).roundToInt())
        // 滚到顶 / 到底时实际滚过的量会小于要滚的量，这里只认实际那个值
        dragOffsetY += (scrollState.value - before).toFloat()
        settleDrag()
    }

    // 拖动中每帧滚一次：只看 onDrag 的话，手指停在边缘不动就再也没有拖动事件了
    LaunchedEffect(draggingIndex >= 0) {
        while (draggingIndex >= 0) {
            withFrameNanos { }
            autoScroll()
        }
    }

    if (steps.isEmpty()) {
        Box(
            modifier = modifier.padding(vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.template_empty_steps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    // 用普通 Column 而不是 LazyColumn：外层（编辑器上半区）已经是一个 verticalScroll，
    // 同方向嵌套的可滚动组件必须有确定高度，否则会因「高度无界」直接崩。
    // 模板步骤本来就只有几条，这里也不需要用懒加载。
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(STEP_ROW_SPACING),
    ) {
        steps.forEachIndexed { index, step ->
            val latestIndex by rememberUpdatedState(index)
            val isDragging = draggingIndex == index

            StepRow(
                index = index,
                step = step,
                isDragging = isDragging,
                isError = index in errorIndexes,
                dragOffsetY = if (isDragging) dragOffsetY else 0f,
                onClick = { onClick(latestIndex) },
                onToggleEnabled = { onToggleEnabled(latestIndex) },
                dragHandleModifier = Modifier.pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            draggingIndex = latestIndex
                            dragOffsetY = 0f
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            handleDrag(amount.y)
                        },
                        onDragEnd = {
                            draggingIndex = -1
                            dragOffsetY = 0f
                        },
                        onDragCancel = {
                            draggingIndex = -1
                            dragOffsetY = 0f
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun StepRow(
    index: Int,
    step: MacroTemplateStep,
    isDragging: Boolean,
    /** 这一步有错误：整行按错误色标出来，与诊断列表里的那条对应 */
    isError: Boolean,
    dragOffsetY: Float,
    onClick: () -> Unit,
    onToggleEnabled: () -> Unit,
    dragHandleModifier: Modifier,
) {
    val enabled = step.enabled
    // 停用的步骤整行压暗：一眼看出这一步不参与展开与发送
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f)
    // 认不出来的一行不给「启用 / 停用」开关：停用会把它写成注释行，而注释行读回来不再是步骤
    // （[MacroTemplateParser] 里 Unknown.isComplete() = false），一保存一重开它就没了
    val canToggle = step !is MacroTemplateStep.Unknown

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(STEP_ROW_HEIGHT)
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer { translationY = dragOffsetY }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(LocalAppDimens.current.innerCorner),
        // 拖拽中 = 第 4 档（按下），常态 = 第 2 档（分区容器），同一条浓度阶梯
        color = if (isDragging) {
            LocalAppTokens.current.active
        } else {
            LocalAppTokens.current.tint
        },
        // 底色两档差别小，补一圈描边才能把每条步骤分开；出错的那条改用错误色描边
        border = BorderStroke(
            LocalAppDimens.current.strokeWidth,
            when {
                isError -> MaterialTheme.colorScheme.error
                isDragging -> LocalAppTokens.current.activeLine
                else -> LocalAppTokens.current.tintLine
            }
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 勾选框在拖拽把手左边：取消勾选 = 这一步不参与发送（文件里就是注释掉那一行）。
            // 认不出来的一行用同宽的占位替掉，保证各行的把手与编号仍然对齐
            if (canToggle) {
                StepEnabledToggle(enabled = enabled, onToggle = onToggleEnabled)
            } else {
                Spacer(modifier = Modifier.size(28.dp))
            }
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = null,
                tint = muted,
                modifier = Modifier
                    .size(28.dp)
                    .then(dragHandleModifier)
            )
            Text(
                text = (index + 1).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                modifier = Modifier.width(22.dp)
            )
            // 主标签就是文件里那一行；副标签（这一步会打出什么字符）空一格跟在它后面 ——
            // 它是算出来给人看的，文件里没有它，所以小一档、颜色走次要色
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stepLabel(step),
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        isError -> MaterialTheme.colorScheme.error
                        enabled -> LocalContentColor.current
                        else -> muted
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // fill = false：标签短的时候不撑满，副标签才跟得住它
                    modifier = Modifier.weight(1f, fill = false),
                )
                step.shiftResultChar()?.let { produced ->
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "($produced)",
                        style = MonoMedium,
                        color = muted,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = stepDetail(step),
                style = MonoSmall,
                color = muted,
                maxLines = 1
            )
        }
    }
}

/**
 * 步骤行最左边的启用勾选框。
 *
 * 用 `Icon` + `toggleable` 而不是 Material 的 `Checkbox`：后者自带 48dp 的最小点击区，
 * 塞进 52dp 高的步骤行里会把拖拽把手挤开（与 [StepIcon] 同一个理由）。
 * `role = Role.Checkbox` 让读屏能播报勾选状态，不必自己拼状态文案。
 */
@Composable
private fun StepEnabledToggle(
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .toggleable(
                value = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (enabled) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
            contentDescription = stringResource(R.string.template_step_enable),
            tint = if (enabled) LocalAppTokens.current.accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun stepLabel(step: MacroTemplateStep): String = when (step) {
    is MacroTemplateStep.KeyDown -> "down ${step.keyName}"
    is MacroTemplateStep.KeyUp -> "up ${step.keyName}"
    is MacroTemplateStep.KeyTap -> "tap ${step.keyName}"
    is MacroTemplateStep.ChordTap -> "tap ${step.keyNames.joinToString("+")}"
    is MacroTemplateStep.Delay -> "sleep ${step.ms}"
    is MacroTemplateStep.CodeDigits -> "<code>"
    // 认不出来的一行：原文照显示，用户才知道自己写错成什么样
    is MacroTemplateStep.Unknown -> step.raw
}

private fun stepDetail(step: MacroTemplateStep): String = when (step) {
    is MacroTemplateStep.KeyDown -> hexOf(step.keyName)
    is MacroTemplateStep.KeyUp -> hexOf(step.keyName)
    is MacroTemplateStep.KeyTap -> hexOf(step.keyName)
    // 组合键：整组按下时占的修饰位掩码 + 普通键码，与真发出去的报告一致
    is MacroTemplateStep.ChordTap -> chordDetail(step)
    is MacroTemplateStep.Delay -> "${step.ms}ms"
    // 节奏不在这个步骤上，所以只显示它独有的属性
    is MacroTemplateStep.CodeDigits ->
        (if (step.useNumpad) "numpad" else "main") +
            (if (step.padDigits > 0) " pad${step.padDigits}" else "")
    // 没有可算的东西；错在哪由诊断列表说
    is MacroTemplateStep.Unknown -> "?"
}

/** 组合键的摘要：修饰位掩码 + 普通键码（只有修饰键的组合就只显示掩码） */
private fun chordDetail(step: MacroTemplateStep.ChordTap): String {
    val mask = step.modifierNames().fold(0) { acc, name ->
        acc or (MacroTemplateKeys.resolve(name)?.modifierMask ?: 0)
    }
    val key = step.plainKeyNames().firstOrNull()
        ?.let { MacroTemplateKeys.resolve(it)?.key?.value?.toInt()?.and(0xFF) }
    return if (key == null) "mod%02X".format(mask) else "mod%02X 0x%02X".format(mask, key)
}

private fun hexOf(keyName: String): String {
    val resolved = MacroTemplateKeys.resolve(keyName) ?: return "?"
    val mask = "%02X".format(resolved.modifierMask)
    val code = "%02X".format(resolved.key.value.toInt() and 0xFF)
    return if (resolved.modifierMask != 0) "mod$mask" else "0x$code"
}
