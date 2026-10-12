package com.github.remotecontrol.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import android.view.MotionEvent
import com.github.remotecontrol.R
import com.github.remotecontrol.ui.theme.LocalAppDimens
import com.github.remotecontrol.ui.theme.LocalAppTokens
import com.github.remotecontrol.ui.theme.SectionTitle
import com.github.remotecontrol.util.HidReportDescriptors
import com.github.remotecontrol.util.TouchpadConstants
import com.github.remotecontrol.util.TouchpadReport
import com.github.remotecontrol.viewmodel.BluetoothHidViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.experimental.or
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

/**
 * 单根手指在某个历史时刻的坐标
 */
data class HistoryFinger(val contactId: Int, val x: Int, val y: Int)

/**
 * 一个历史批次：同一时刻（相同 scantime）所有手指的数据（最多 3 根）
 */
data class HistoryBatch(val scanTime: Int, val fingerCount: Int, val fingers: List<HistoryFinger>)

/**
 * 触摸板模式界面
 * 按照des.txt的触摸板描述符实现绝对坐标发送
 */
@Composable
fun TouchpadModeScreen(
    viewModel: BluetoothHidViewModel,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false
) {
    // contactMap: key = contactId (PointerId), value = TouchpadReport（含 contactId/x/y/tipSwitch=1）
    var contactMap by remember { mutableStateOf(mapOf<Int, TouchpadReport>()) }
    val isTouching = contactMap.isNotEmpty()
    var pressedButtons by remember { mutableStateOf(0.toByte()) }

    // 历史队列：ACTION_MOVE 中的历史采样点，由 TouchpadAbsoluteArea 推送，协程循环消费
    val historyChannel = remember { Channel<HistoryBatch>(Channel.UNLIMITED) }

    // 200Hz 定时发送协程，isTouching 变为 false 时自动取消
    LaunchedEffect(isTouching) {
        if (isTouching) {
            // conflated channel：仅保留最新 reports 列表，发送快时不积压，发送慢时自动丢中间帧
            val reportChannel = Channel<List<TouchpadReport>>(Channel.CONFLATED)

            // 单一后台消费者：在 IO 线程持续取 reports 列表并逐个发送
            launch(Dispatchers.IO) {
                for (reports in reportChannel) {
                    for (report in reports) {

                        sendTouchpadReport(viewModel, report)
                    }
                }
            }

            // 生产者：200Hz 定时循环，非阻塞写入 channel
            while (true) {
                // 1. 消费历史队列
                drainHistory@ while (true) {
                    val batch = historyChannel.tryReceive().getOrNull() ?: break@drainHistory
                    val contacts = contactMap
                    val reports = batch.fingers.mapIndexed { index, finger ->
                        if (contacts.containsKey(finger.contactId)) {
                            TouchpadReport(
                                tipSwitch = 1,
                                contactId = finger.contactId,
                                x = finger.x,
                                y = finger.y,
                                scanTime = batch.scanTime,
                                contactCount = if (index == 0) batch.fingerCount else 0,
                                buttons = pressedButtons
                            )
                        } else null
                    }.filterNotNull()
                    if (reports.isNotEmpty()) {
                        reportChannel.trySend(reports)
                    }
                }

                val st = (System.currentTimeMillis() * 10 and 0xFFFF).toInt()

                // 2. 构造本轮全部报告，统一一次 trySend
                val contactsBefore = contactMap
                val lifted = contactsBefore.filter { (_, r) -> r.tipSwitch == 0 }
                val allReports = buildList {
                    if (lifted.isNotEmpty()) {
                        val oldSize = contactsBefore.size
                        // 抬起帧：当前所有接触点（抬起的有 tipSwitch=0）
                        contactsBefore.entries.forEachIndexed { index, (_, report) ->
                            add(report.copy(
                                scanTime = st,
                                contactCount = if (index == 0) oldSize else 0,
                                buttons = pressedButtons
                            ))
                        }
                        // 从 map 中移除抬起的手指
                        val afterRemove = contactsBefore.toMutableMap()
                        lifted.keys.forEach { afterRemove.remove(it) }
                        contactMap = afterRemove
                        // 全部抬起 → 追加空帧（contactCount → 0）
                        if (afterRemove.isEmpty()) {
                            val last = lifted.entries.last().value
                            add(TouchpadReport(
                                tipSwitch = 0, contactId = last.contactId,
                                x = last.x, y = last.y,
                                scanTime = st, contactCount = 0, buttons = pressedButtons
                            ))
                        }
                    } else if (contactsBefore.isNotEmpty()) {
                        // 普通：无手指抬起，正常发送当前帧
                        contactsBefore.entries.forEachIndexed { index, (_, report) ->
                            add(report.copy(
                                scanTime = st,
                                contactCount = if (index == 0) contactsBefore.size else 0,
                                buttons = pressedButtons
                            ))
                        }
                    }
                }
                if (allReports.isNotEmpty()) {
                    reportChannel.trySend(allReports)
                }
                delay(5.milliseconds) // 5ms = 200Hz
            }
        }
    }

    if (isLandscape) {
        Row(
            modifier = modifier
                .padding(8.dp)
                .fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 触摸板区域
            TouchpadAbsoluteArea(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight(),
                isTouching = isTouching,
                onPointerDown = { contactId, absX, absY ->
                    val newMap = contactMap.toMutableMap()
                    newMap[contactId] = TouchpadReport(
                        tipSwitch = 1, contactId = contactId, x = absX, y = absY
                    )
                    contactMap = newMap
                },
                onActionMove = { historyBatches, currentFingers ->
                    historyBatches.forEach { historyChannel.trySend(it) }
                    for ((contactId, x, y) in currentFingers) {
                        if (contactMap.containsKey(contactId)) {
                            val newMap = contactMap.toMutableMap()
                            newMap[contactId] = newMap[contactId]!!.copy(x = x, y = y)
                            contactMap = newMap
                        }
                    }
                },
                onPointerUp = { contactId ->
                    if (contactMap.containsKey(contactId)) {
                        val newMap = contactMap.toMutableMap()
                        newMap[contactId] = newMap[contactId]!!.copy(tipSwitch = 0)
                        contactMap = newMap
                    }
                },
                onClear = {
                    val st = (System.currentTimeMillis() * 10 and 0xFFFF).toInt()
                    if (contactMap.isNotEmpty()) {
                        sendTouchpadReport(viewModel, TouchpadReport(
                            tipSwitch = 0, contactId = 0, x = 0, y = 0,
                            scanTime = st, contactCount = contactMap.size, buttons = pressedButtons
                        ))
                        sendTouchpadReport(viewModel, TouchpadReport(
                            tipSwitch = 0, contactId = 0, x = 0, y = 0,
                            scanTime = st, contactCount = 0, buttons = pressedButtons
                        ))
                    }
                    contactMap = emptyMap()
                }
            )

            // 竖排按钮面板：宽度按文案的固有宽度自适应（IntrinsicSize.Max = 文案单行宽度），
            // 不再用固定比例 —— 英文 "Left Button" 在 10% 宽度里连单词都放不下，
            // 会被逐字符折断成三行
            TouchpadButtonsPanel(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .fillMaxHeight(),
                vertical = true,
                onButtonDown = { button ->
                    pressedButtons = (pressedButtons or button)
                    val contacts = contactMap
                    if (contacts.isEmpty()) {
                        val st = (System.currentTimeMillis() * 10 and 0xFFFF).toInt()
                        sendTouchpadReport(viewModel, TouchpadReport(
                            tipSwitch = 0, contactId = 0, x = 0, y = 0,
                            scanTime = st, contactCount = 0, buttons = pressedButtons
                        ))
                    }
                },
                onButtonUp = { button ->
                    pressedButtons = (pressedButtons.toInt() and button.toInt().inv()).toByte()
                    val contacts = contactMap
                    if (contacts.isEmpty()) {
                        val st = (System.currentTimeMillis() * 10 and 0xFFFF).toInt()
                        sendTouchpadReport(viewModel, TouchpadReport(
                            tipSwitch = 0, contactId = 0, x = 0, y = 0,
                            scanTime = st, contactCount = 0, buttons = pressedButtons
                        ))
                    }
                }
            )
        }
    } else {
        Column(
            modifier = modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 触摸板区域
            TouchpadAbsoluteArea(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                isTouching = isTouching,
                onPointerDown = { contactId, absX, absY ->
                    val newMap = contactMap.toMutableMap()
                    newMap[contactId] = TouchpadReport(
                        tipSwitch = 1, contactId = contactId, x = absX, y = absY
                    )
                    contactMap = newMap
                },
                onActionMove = { historyBatches, currentFingers ->
                    historyBatches.forEach { historyChannel.trySend(it) }
                    for ((contactId, x, y) in currentFingers) {
                        if (contactMap.containsKey(contactId)) {
                            val newMap = contactMap.toMutableMap()
                            newMap[contactId] = newMap[contactId]!!.copy(x = x, y = y)
                            contactMap = newMap
                        }
                    }
                },
                onPointerUp = { contactId ->
                    if (contactMap.containsKey(contactId)) {
                        val newMap = contactMap.toMutableMap()
                        newMap[contactId] = newMap[contactId]!!.copy(tipSwitch = 0)
                        contactMap = newMap
                    }
                },
                onClear = {
                    val st = (System.currentTimeMillis() * 10 and 0xFFFF).toInt()
                    if (contactMap.isNotEmpty()) {
                        sendTouchpadReport(viewModel, TouchpadReport(
                            tipSwitch = 0, contactId = 0, x = 0, y = 0,
                            scanTime = st, contactCount = contactMap.size, buttons = pressedButtons
                        ))
                        sendTouchpadReport(viewModel, TouchpadReport(
                            tipSwitch = 0, contactId = 0, x = 0, y = 0,
                            scanTime = st, contactCount = 0, buttons = pressedButtons
                        ))
                    }
                    contactMap = emptyMap()
                }
            )

            // 按钮面板
            TouchpadButtonsPanel(
                onButtonDown = { button ->
                    pressedButtons = (pressedButtons or button)
                    val contacts = contactMap
                    if (contacts.isEmpty()) {
                        val st = (System.currentTimeMillis() * 10 and 0xFFFF).toInt()
                        sendTouchpadReport(viewModel, TouchpadReport(
                            tipSwitch = 0, contactId = 0, x = 0, y = 0,
                            scanTime = st, contactCount = 0, buttons = pressedButtons
                        ))
                    }
                },
                onButtonUp = { button ->
                    pressedButtons = (pressedButtons.toInt() and button.toInt().inv()).toByte()
                    val contacts = contactMap
                    if (contacts.isEmpty()) {
                        val st = (System.currentTimeMillis() * 10 and 0xFFFF).toInt()
                        sendTouchpadReport(viewModel, TouchpadReport(
                            tipSwitch = 0, contactId = 0, x = 0, y = 0,
                            scanTime = st, contactCount = 0, buttons = pressedButtons
                        ))
                    }
                }
            )
        }
    }
}

private fun sendTouchpadReport(viewModel: BluetoothHidViewModel, report: TouchpadReport) {
//    Log.w("BluetoothHid", "$report")
    viewModel.sendReport(HidReportDescriptors.REPORT_ID_TOUCHPAD, report.toByteArray())
}

/**
 * 将像素坐标转换为绝对坐标 (0~4095)，使用宽高最长边保持 1:1 比例
 * @param pixelX 触摸点在 Canvas 上的像素 x 坐标
 * @param pixelY 触摸点在 Canvas 上的像素 y 坐标
 * @param maxDim  Canvas 宽高（像素）
 * @return Pair(absX, absY) 0~4095 的绝对坐标
 */
private fun pixelsToAbsolute(pixelX: Float, pixelY: Float, maxDim: Int): Pair<Int, Int> {
    val absX = ((pixelX / maxDim) * TouchpadConstants.ABSOLUTE_MAX).toInt()
        .coerceIn(0, TouchpadConstants.ABSOLUTE_MAX)
    val absY = ((pixelY / maxDim) * TouchpadConstants.ABSOLUTE_MAX).toInt()
        .coerceIn(0, TouchpadConstants.ABSOLUTE_MAX)
    return Pair(absX, absY)
}

/**
 * 触摸板区域 - 使用绝对坐标
 * 多指模式下，每个 contactId（由 getPointerId 派生）对应一对 xy 坐标
 */
@Composable
fun TouchpadAbsoluteArea(
    modifier: Modifier = Modifier,
    isTouching: Boolean,
    onPointerDown: (contactId: Int, absX: Int, absY: Int) -> Unit,
    onActionMove: (historyBatches: List<HistoryBatch>, currentFingers: List<HistoryFinger>) -> Unit,
    onPointerUp: (contactId: Int) -> Unit,
    onClear: () -> Unit
) {
    val view = LocalView.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // 在 Composable 作用域内获取颜色值，避免在 Canvas draw scope 中引用 MaterialTheme
    val tokens = LocalAppTokens.current
    val dimens = LocalAppDimens.current
    // 未按下 = 第 2 档，按下 = 第 4 档，走的是同一条"由浅入深"的浓度阶梯
    val bgColor = if (isTouching) tokens.active else tokens.tint
    val strokeColor = if (isTouching) tokens.activeLine else tokens.tintLine
    val outerShape = RoundedCornerShape(dimens.cardCorner)
    val innerShape = RoundedCornerShape(dimens.innerCorner)

    Card(
        modifier = modifier.clip(outerShape),
        shape = outerShape,
        colors = CardDefaults.cardColors(containerColor = tokens.card),
        border = BorderStroke(dimens.strokeWidth, tokens.line),
        elevation = CardDefaults.cardElevation(defaultElevation = dimens.cardElevation),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(dimens.innerCorner)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(innerShape)
                    .background(bgColor)
                    .border(dimens.strokeWidth, strokeColor, innerShape)
                    .onSizeChanged { canvasSize = it }
                    .pointerInteropFilter(
                        onTouchEvent = touchpadPointerInteropFilter(
                            view = view,
                            canvasSize = canvasSize,
                            onPointerDown = onPointerDown,
                            onActionMove = onActionMove,
                            onPointerUp = onPointerUp,
                            onClear = onClear
                        )
                    )
            ) {
                // Canvas draw scope
            }
        }
    }
}

/**
 * 提取的 pointerInteropFilter 处理函数，将 MotionEvent 解析为触摸板事件并分发给回调
 */
private fun touchpadPointerInteropFilter(
    view: android.view.View,
    canvasSize: IntSize,
    onPointerDown: (contactId: Int, absX: Int, absY: Int) -> Unit,
    onActionMove: (historyBatches: List<HistoryBatch>, currentFingers: List<HistoryFinger>) -> Unit,
    onPointerUp: (contactId: Int) -> Unit,
    onClear: () -> Unit
): (MotionEvent) -> Boolean = { event ->
    // 开启无缓冲分发，避免坐标被系统批量合并
    view.requestUnbufferedDispatch(event)

    val maxDim = max(canvasSize.width, canvasSize.height)

    when (event.action and MotionEvent.ACTION_MASK) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
            val idx = event.actionIndex
            val contactId = event.getPointerId(idx)
            val (absX, absY) = pixelsToAbsolute(
                event.getX(idx), event.getY(idx), maxDim
            )
            onPointerDown(contactId, absX, absY)
        }

        MotionEvent.ACTION_MOVE -> {
            val histSize = event.historySize
            val pointerCount = event.pointerCount
            val deltaMs = System.currentTimeMillis() - SystemClock.uptimeMillis()
            val maxFingers = minOf(pointerCount, 3)

            val historyBatches = mutableListOf<HistoryBatch>()
            for (hi in 0 until histSize) {
                val histTime = event.getHistoricalEventTime(hi)
                val scanTime = ((histTime + deltaMs) * 10 and 0xFFFF).toInt()
                val fingers = mutableListOf<HistoryFinger>()
                for (pi in 0 until maxFingers) {
                    val contactId = event.getPointerId(pi)
                    val hx = event.getHistoricalX(pi, hi)
                    val hy = event.getHistoricalY(pi, hi)
                    val (absX, absY) = pixelsToAbsolute(hx, hy, maxDim)
                    fingers.add(HistoryFinger(contactId, absX, absY))
                }
                historyBatches.add(HistoryBatch(scanTime, pointerCount, fingers))
            }

            val currentFingers = mutableListOf<HistoryFinger>()
            for (pi in 0 until pointerCount) {
                val contactId = event.getPointerId(pi)
                val (absX, absY) = pixelsToAbsolute(event.getX(pi), event.getY(pi), maxDim)
                currentFingers.add(HistoryFinger(contactId, absX, absY))
            }

            onActionMove(historyBatches, currentFingers)
        }

        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
            val idx = event.actionIndex
            val contactId = event.getPointerId(idx)
            onPointerUp(contactId)
        }

        MotionEvent.ACTION_CANCEL -> {
            onClear()
        }
    }
    true
}

/**
 * 触摸板按钮面板
 * @param vertical true = 横屏竖排（无标题，紧凑布局），false = 竖屏横排（带标题）
 */
@Composable
fun TouchpadButtonsPanel(
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    onButtonDown: (Byte) -> Unit,
    onButtonUp: (Byte) -> Unit
) {
    val tokens = LocalAppTokens.current
    val dimens = LocalAppDimens.current
    val shape = RoundedCornerShape(dimens.cardCorner)

    if (vertical) {
        // 横屏竖排：无标题，按钮紧凑堆积
        Card(
            modifier = modifier,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = tokens.card),
            border = BorderStroke(dimens.strokeWidth, tokens.line),
            elevation = CardDefaults.cardElevation(defaultElevation = dimens.cardElevation),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                TouchpadButton(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    text = stringResource(id = R.string.left_button),
//                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    compact = true,
                    onDown = { onButtonDown(TouchpadConstants.Buttons.LEFT) },
                    onUp = { onButtonUp(TouchpadConstants.Buttons.LEFT) }
                )
                TouchpadButton(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    text = stringResource(id = R.string.right_button),
//                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    compact = true,
                    onDown = { onButtonDown(TouchpadConstants.Buttons.RIGHT) },
                    onUp = { onButtonUp(TouchpadConstants.Buttons.RIGHT) }
                )
            }
        }
    } else {
        Card(
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = tokens.card),
            border = BorderStroke(dimens.strokeWidth, tokens.line),
            elevation = CardDefaults.cardElevation(defaultElevation = dimens.cardElevation),
        ) {
            Column(
                modifier = Modifier.padding(dimens.cardPadding),
                verticalArrangement = Arrangement.spacedBy(dimens.sectionGap)
            ) {
                SectionTitle(text = stringResource(id = R.string.touchpad_buttons))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TouchpadButton(
                        modifier = Modifier.weight(1f),
                        text = stringResource(id = R.string.left_button),
//                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        onDown = { onButtonDown(TouchpadConstants.Buttons.LEFT) },
                        onUp = { onButtonUp(TouchpadConstants.Buttons.LEFT) }
                    )
                    TouchpadButton(
                        modifier = Modifier.weight(1f),
                        text = stringResource(id = R.string.right_button),
//                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        onDown = { onButtonDown(TouchpadConstants.Buttons.RIGHT) },
                        onUp = { onButtonUp(TouchpadConstants.Buttons.RIGHT) }
                    )
                }
            }
        }
    }
}

@Composable
fun TouchpadButton(
    modifier: Modifier = Modifier,
    text: String,
    icon: ImageVector? = null,
    compact: Boolean = false,
    onDown: () -> Unit,
    onUp: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed = interactionSource.collectIsPressedAsState().value
    var wasPressed by remember { mutableStateOf(false) }

    if (isPressed && !wasPressed) {
        onDown()
        wasPressed = true
    } else if (!isPressed && wasPressed) {
        onUp()
        wasPressed = false
    }

    val tokens = LocalAppTokens.current
    val dimens = LocalAppDimens.current

    Button(
        onClick = {},
        interactionSource = interactionSource,
        // 用 heightIn 而非 height：文案偏长的语言按空格换成两行时按钮能跟着变高，
        // 不会把第二行裁掉
        modifier = modifier.heightIn(min = dimens.buttonHeight),
        shape = RoundedCornerShape(dimens.innerCorner),
        colors = ButtonDefaults.buttonColors(
            containerColor = tokens.accent,
            contentColor = tokens.onAccent,
        ),
        // 横屏竖排面板本来就地窄，默认 24dp 的水平内边距会吃掉大半可用宽度
        contentPadding = PaddingValues(horizontal = if (compact) 8.dp else 16.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                modifier = Modifier.size(18.dp)
            )
            if (!compact) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(text, style = MaterialTheme.typography.labelLarge)
            }
        } else {
            // 换行只发生在空格处（Compose 默认按词断行），居中让多行也保持对齐
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}
