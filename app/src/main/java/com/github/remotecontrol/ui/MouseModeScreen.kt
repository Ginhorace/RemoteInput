package com.github.remotecontrol.ui

import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.remotecontrol.R
import com.github.remotecontrol.hid.HidSession
import com.github.remotecontrol.ui.theme.LocalAppDimens
import com.github.remotecontrol.ui.theme.LocalAppTokens
import com.github.remotecontrol.ui.theme.SectionTitle
import com.github.remotecontrol.util.AppPreferences
import com.github.remotecontrol.util.HidReportDescriptors
import com.github.remotecontrol.util.MouseConstants
import com.github.remotecontrol.util.MouseReport
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.experimental.or
import kotlin.time.Duration.Companion.milliseconds

/**
 * 鼠标模式界面（**只由经典蓝牙链路使用**；局域网链路走 `TouchpadModeScreen`）。
 *
 * ## 与触摸板页的根本差别
 * 触摸板发的是**绝对坐标**（0–4095），界面按 200Hz 定时把自己的状态推给主机；
 * 鼠标发的是**相对位移**（每帧 ±127），只在该动的时候发一帧 —— 所以这里没有定时循环，
 * 也没有 Channel，触摸移动、按钮按下 / 松开、滚轮各发一帧 4 字节报告。
 *
 * ## 位移为什么要在界面侧累加
 * 报告里的 dx / dy 是整数，而触摸事件给的是浮点位移。直接 `toInt()` 会把慢速拖动
 * （每帧 0.3 px）全部截成 0，表现为「手指动了、指针不动」。所以按 [MouseConstants.DELTA_MIN]
 * ~ [MouseConstants.DELTA_MAX] 分发：取整发出、小数余量留到下一帧。
 *
 * ## 长按时间
 * 长按阈值来自设置页（[AppPreferences.longPressDuration]），只在触摸区按下时生效：
 * 单击（短于阈值且未移动）= 左键，长按不动 = 右键。
 *
 * ## 布局
 * - 竖屏：上排 = 侧键列（4 / 5 / 滚轮上 / 中键 / 滚轮下）**紧贴**指针区；
 *   下排 = 左右键卡片，与触摸板页那张按钮卡片同一种形态；
 * - 横屏：四列 —— 4/5 | 滚轮上 + 中键 + 滚轮下 | 指针区 | 左右键，
 *   三列按键统一宽度（竖屏按钮高度 × 2），文字全部旋转 90°（列宽不随译文长短变化）。
 *
 * 中键夹在两个滚轮键之间（对应实物「滚轮可按」的位置），且不与左右键同区：
 * 它用得少，和主键挤在一起只会把左右键的尺寸压小。
 * 侧键列宽固定为 `dimens.buttonHeight`（与主键按钮同高），40 dp 放不下横向文案，
 * 所以文字旋转 90°（见 [SideButton]）；竖屏那一列不套卡片、按钮铺满列宽，
 * 免得 40 dp 里再被内边距吃掉一圈。
 */
@Composable
fun MouseModeScreen(
    viewModel: HidSession,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false,
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences.getInstance(context) }
    // 设置页改完回到控制页时立刻生效（StateFlow），不必重进控制页
    val longPressDuration by prefs.longPressDuration.collectAsState()
    val scope = rememberCoroutineScope()

    // 当前按住的鼠标按钮位掩码。移动时带上它，才能「按住左键拖动 = 拖选」
    var pressedButtons by remember { mutableStateOf(0.toByte()) }

    fun press(button: Byte) {
        pressedButtons = pressedButtons or button
        sendMouseReport(viewModel, MouseReport(buttons = pressedButtons))
    }

    fun releaseAll() {
        pressedButtons = 0
        sendMouseReport(viewModel, MouseReport())
    }

    fun click(button: Byte) {
        // 点击是瞬时的：按下帧 + 50 ms 后的松开帧，不进入 pressedButtons 的持续状态
        sendMouseReport(viewModel, MouseReport(buttons = button))
        scope.launch {
            delay(50.milliseconds)
            sendMouseReport(viewModel, MouseReport())
        }
    }

    val onDrag: (Int, Int) -> Unit = { dx, dy ->
        sendMouseReport(
            viewModel,
            MouseReport(buttons = pressedButtons, x = dx.toByte(), y = dy.toByte()),
        )
    }

    val dimens = LocalAppDimens.current
    val onWheel: (Byte) -> Unit = { direction ->
        sendMouseReport(viewModel, MouseReport(buttons = pressedButtons, wheel = direction))
    }

    if (isLandscape) {
        // 横屏 = 四列：4/5 | 中键 + 滚轮 | 指针区 | 主键列（左 / 右）
        Row(
            modifier = modifier
                .padding(16.dp)
                .fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MouseSideButtons(
                modifier = Modifier.fillMaxHeight(),
                split = true,
                onButtonDown = ::press,
                onButtonUp = ::releaseAll,
                onWheel = onWheel,
            )

            MousePointerArea(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                longPressDuration = longPressDuration.toLong(),
                pressedButtons = pressedButtons,
                onDrag = onDrag,
                onLeftClick = { click(MouseConstants.Buttons.LEFT) },
                onRightClick = { click(MouseConstants.Buttons.RIGHT) },
            )

            // 与左侧两列同宽（竖屏按钮高度 × 2）：文案旋转 90°，不再由文案长度决定列宽
            MousePrimaryButtons(
                modifier = Modifier
                    .width(dimens.buttonHeight * 2)
                    .fillMaxHeight(),
                vertical = true,
                onButtonDown = ::press,
                onButtonUp = ::releaseAll,
            )
        }
    } else {
        // 竖屏 = 上：侧键列（4 / 5 / 中键 / 滚轮）紧贴指针区；下：左右键卡片独占一行
        Column(
            modifier = modifier
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                // 侧键列与指针区**紧贴**：窄列按钮本身就不宽，再留 16 dp 空隙显得两侧都在浪费
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                MouseSideButtons(
                    modifier = Modifier
                        .width(dimens.buttonHeight)
                        .fillMaxHeight(),
                    onButtonDown = ::press,
                    onButtonUp = ::releaseAll,
                    onWheel = onWheel,
                )

                MousePointerArea(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    longPressDuration = longPressDuration.toLong(),
                    pressedButtons = pressedButtons,
                    onDrag = onDrag,
                    onLeftClick = { click(MouseConstants.Buttons.LEFT) },
                    onRightClick = { click(MouseConstants.Buttons.RIGHT) },
                )
            }

            MousePrimaryButtons(
                modifier = Modifier.fillMaxWidth(),
                vertical = false,
                onButtonDown = ::press,
                onButtonUp = ::releaseAll,
            )
        }
    }
}

private fun sendMouseReport(viewModel: HidSession, report: MouseReport) {
    viewModel.sendReport(HidReportDescriptors.REPORT_ID_MOUSE, report.toByteArray())
}

/** 单个触摸事件里最多补发几帧 —— 快速滑动时一帧只装得下 ±127 */
private const val MAX_DRAG_FRAMES_PER_EVENT = 8



/**
 * 鼠标触摸区：单指滑动 = 相对位移，单击 = 左键，长按不动 = 右键。
 *
 * 按住鼠标按钮时触摸区不产生位移（与旧实现一致）：那种手势是「拖选 / 拖拽」，
 * 位移由按钮按下后的滑动承担，这里若再叠加一次就会拖两倍距离。
 */
@Composable
fun MousePointerArea(
    modifier: Modifier = Modifier,
    longPressDuration: Long = 500,
    pressedButtons: Byte = 0,
    onDrag: (Int, Int) -> Unit,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    val dimens = LocalAppDimens.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var isTouching by remember { mutableStateOf(false) }
    var isLongPress by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    // 本次手势里出现过第二根手指：这种手势不判单击（是拖动或误触，不该点出左键）
    var hadSecondPointer by remember { mutableStateOf(false) }
    // 主指针（第一根按下的手指）的 pointerId：鼠标只认它，多指落下时不抢、不打断它的移动
    var primaryPointerId by remember { mutableIntStateOf(MotionEvent.INVALID_POINTER_ID) }
    var pressStartTime by remember { mutableLongStateOf(0L) }
    var lastX by remember { mutableFloatStateOf(0f) }
    var lastY by remember { mutableFloatStateOf(0f) }
    var residualX by remember { mutableFloatStateOf(0f) }
    var residualY by remember { mutableFloatStateOf(0f) }
    var longPressJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val outerShape = RoundedCornerShape(dimens.cardCorner)
    val innerShape = RoundedCornerShape(dimens.innerCorner)
    val bgColor = if (isTouching) tokens.active else tokens.tint
    val strokeColor = if (isTouching) tokens.activeLine else tokens.tintLine

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
                .padding(dimens.innerCorner),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(innerShape)
                    .background(bgColor)
                    .border(dimens.strokeWidth, strokeColor, innerShape)
                    .pointerInteropFilter { event ->
                        view.requestUnbufferedDispatch(event)
                        when (event.action and MotionEvent.ACTION_MASK) {
                            MotionEvent.ACTION_DOWN -> {
                                if (pressedButtons != 0.toByte()) {
                                    // 按住按钮时触摸区只管「不响应」，避免位移叠加
                                    return@pointerInteropFilter true
                                }
                                isTouching = true
                                isLongPress = false
                                isDragging = false
                                hadSecondPointer = false
                                primaryPointerId = event.getPointerId(0)
                                pressStartTime = System.currentTimeMillis()
                                lastX = event.getX(0)
                                lastY = event.getY(0)
                                residualX = 0f
                                residualY = 0f
                                longPressJob = scope.launch {
                                    delay(longPressDuration.milliseconds)
                                    if (!isDragging && !isLongPress) {
                                        isLongPress = true
                                        onRightClick()
                                    }
                                }
                                true
                            }

                            MotionEvent.ACTION_MOVE -> {
                                if (pressedButtons != 0.toByte()) return@pointerInteropFilter true
                                // 始终按**主指针**取坐标：多指落下时 index 0 可能已经是另一根手指，
                                // 「按 index 0 取」正是旧实现跳变的原因
                                val index = event.findPointerIndex(primaryPointerId)
                                if (index < 0) return@pointerInteropFilter true
                                val x = event.getX(index)
                                val y = event.getY(index)
                                val dx = x - lastX
                                val dy = y - lastY
                                lastX = x
                                lastY = y
                                residualX += dx
                                residualY += dy

                                var frames = 0
                                while (frames < MAX_DRAG_FRAMES_PER_EVENT) {
                                    val ix = residualX.toInt().coerceIn(
                                        MouseConstants.DELTA_MIN,
                                        MouseConstants.DELTA_MAX,
                                    )
                                    val iy = residualY.toInt().coerceIn(
                                        MouseConstants.DELTA_MIN,
                                        MouseConstants.DELTA_MAX,
                                    )
                                    if (ix == 0 && iy == 0) break
                                    residualX -= ix
                                    residualY -= iy
                                    onDrag(ix, iy)
                                    frames++
                                }
                                // 只有真的推出了位移才算拖动：系统会因为手指微颤发 MOVE，
                                // 若见 MOVE 就置 true，单击会被判成拖动、左键永远发不出去
                                if (frames > 0) isDragging = true
                                true
                            }

                            // 次指针落下：主指针不换人，只把基准点重取一次（多指期间主指仍在移动）
                            MotionEvent.ACTION_POINTER_DOWN -> {
                                hadSecondPointer = true
                                val index = event.findPointerIndex(primaryPointerId)
                                    .takeIf { it >= 0 } ?: event.actionIndex
                                lastX = event.getX(index)
                                lastY = event.getY(index)
                                residualX = 0f
                                residualY = 0f
                                true
                            }

                            MotionEvent.ACTION_POINTER_UP -> {
                                // 抬起的是主指针：把手势交接给剩下的一根。
                                // 交接时必须重取基准，否则下一帧 MOVE 会按两根手指的距离跳一下。
                                // 抬起的是次指针则什么都不用做 —— 主指针本来就是基准。
                                if (event.getPointerId(event.actionIndex) == primaryPointerId) {
                                    longPressJob?.cancel()
                                    longPressJob = null
                                    val nextIndex = if (event.actionIndex == 0) 1 else 0
                                    primaryPointerId = event.getPointerId(nextIndex)
                                    lastX = event.getX(nextIndex)
                                    lastY = event.getY(nextIndex)
                                    residualX = 0f
                                    residualY = 0f
                                }
                                true
                            }

                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                longPressJob?.cancel()
                                longPressJob = null
                                isTouching = false
                                val pressDuration = System.currentTimeMillis() - pressStartTime
                                // 没有建立主指针（按下时正按着鼠标按钮、被 DOWN 挡掉了）不算单击；
                                // 多指起步的手势同样不算 —— 那是拖动或误触，不该点出左键
                                val hasPrimary = primaryPointerId != MotionEvent.INVALID_POINTER_ID
                                if (hasPrimary && !hadSecondPointer && !isDragging && !isLongPress &&
                                    pressDuration < longPressDuration
                                ) {
                                    onLeftClick()
                                }
                                isDragging = false
                                isLongPress = false
                                hadSecondPointer = false
                                primaryPointerId = MotionEvent.INVALID_POINTER_ID
                                residualX = 0f
                                residualY = 0f
                                true
                            }

                            else -> false
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Mouse,
                        contentDescription = stringResource(id = R.string.mouse_area),
                        modifier = Modifier.size(56.dp),
                        tint = if (isTouching) tokens.accent else tokens.onVar,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (pressedButtons != 0.toByte()) {
                            stringResource(id = R.string.mouse_only_move)
                        } else {
                            stringResource(id = R.string.mouse_area_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.onVar,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * 侧键区：按键 4 / 5、中键与滚轮上下。
 *
 * 顺序固定为 **4、5、滚轮上、中键、滚轮下** —— 中键夹在两个滚轮键之间，对应鼠标实物
 * 「滚轮可按」的位置；横屏拆列也按这个顺序切：4/5 一列，上 / 中 / 下 一列。
 *
 * 中键不与左右键同处一区：它用得少，聚到这一列里，主键区就能保持「左右两键」的形态。
 *
 * @param split true = 横屏拆成两列（4/5 | 滚轮上 + 中键 + 滚轮下，列间保留间距）；
 *        false = 竖屏一列（5 个按钮，**不套卡片**：40 dp 宽的列再套一层内边距，
 *        按钮只剩 32 dp、两侧全是空的；直接铺满列宽并紧贴指针区）
 */
@Composable
fun MouseSideButtons(
    modifier: Modifier = Modifier,
    split: Boolean = false,
    onButtonDown: (Byte) -> Unit,
    onButtonUp: () -> Unit,
    onWheel: (Byte) -> Unit,
) {
    // 横屏三列按键统一宽度：竖屏按钮高度的两倍（40 dp → 80 dp）。再宽会把中间指针区压窄
    val dimens = LocalAppDimens.current
    val splitColumnWidth = dimens.buttonHeight * 2

    val sideKeys = listOf(SideButtonKind.BUTTON_4, SideButtonKind.BUTTON_5)
    val wheelGroup = listOf(
        SideButtonKind.WHEEL_UP,
        SideButtonKind.MIDDLE,
        SideButtonKind.WHEEL_DOWN,
    )

    if (split) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SideButtonsColumn(
                modifier = Modifier.width(splitColumnWidth).fillMaxHeight(),
                card = true,
                kinds = sideKeys,
                onButtonDown = onButtonDown,
                onButtonUp = onButtonUp,
                onWheel = onWheel,
            )
            SideButtonsColumn(
                modifier = Modifier.width(splitColumnWidth).fillMaxHeight(),
                card = true,
                kinds = wheelGroup,
                onButtonDown = onButtonDown,
                onButtonUp = onButtonUp,
                onWheel = onWheel,
            )
        }
    } else {
        SideButtonsColumn(
            modifier = modifier,
            card = false,
            kinds = sideKeys + wheelGroup,
            onButtonDown = onButtonDown,
            onButtonUp = onButtonUp,
            onWheel = onWheel,
        )
    }
}

/** 侧键列里的按钮种类；枚举顺序即竖屏的渲染顺序 */
private enum class SideButtonKind { BUTTON_4, BUTTON_5, WHEEL_UP, MIDDLE, WHEEL_DOWN }

/**
 * 一列窄按钮，按 [kinds] 的顺序渲染。
 *
 * [card] = true 时套一张卡片（横屏，与指针区分开）；false 时直接铺在页面上（竖屏，
 * 与指针区紧贴，按钮铺满列宽）。
 */
@Composable
private fun SideButtonsColumn(
    modifier: Modifier = Modifier,
    card: Boolean,
    kinds: List<SideButtonKind>,
    onButtonDown: (Byte) -> Unit,
    onButtonUp: () -> Unit,
    onWheel: (Byte) -> Unit,
) {
    val dimens = LocalAppDimens.current
    val tokens = LocalAppTokens.current
    val shape = RoundedCornerShape(dimens.cardCorner)

    val content: @Composable ColumnScope.() -> Unit = {
        kinds.forEach { kind ->
            when (kind) {
                SideButtonKind.BUTTON_4 -> SideButton(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    text = stringResource(id = R.string.button_4),
                    onDown = { onButtonDown(MouseConstants.Buttons.BUTTON_4) },
                    onUp = onButtonUp,
                )

                SideButtonKind.BUTTON_5 -> SideButton(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    text = stringResource(id = R.string.button_5),
                    onDown = { onButtonDown(MouseConstants.Buttons.BUTTON_5) },
                    onUp = onButtonUp,
                )

                SideButtonKind.MIDDLE -> SideButton(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    text = stringResource(id = R.string.middle_button),
                    onDown = { onButtonDown(MouseConstants.Buttons.MIDDLE) },
                    onUp = onButtonUp,
                )

                SideButtonKind.WHEEL_UP -> WheelButton(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    icon = Icons.Default.ArrowUpward,
                    contentDescription = stringResource(id = R.string.scroll_up),
                    compact = true,
                    onWheel = { onWheel(MouseConstants.Wheel.UP) },
                )

                SideButtonKind.WHEEL_DOWN -> WheelButton(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    icon = Icons.Default.ArrowDownward,
                    contentDescription = stringResource(id = R.string.scroll_down),
                    compact = true,
                    onWheel = { onWheel(MouseConstants.Wheel.DOWN) },
                )
            }
        }
    }

    if (card) {
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
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    } else {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/**
 * 主键区：左 / 右键（与触摸板页那张卡片同一种形态）。
 *
 * 中键不在这里 —— 它用得少，和 4/5、滚轮一起放进侧键区（见 [MouseSideButtons]），
 * 主键区只留最常用的两键，尺寸也不会被挤小。
 *
 * @param vertical true = 横屏右列竖排（无标题，按钮均分高度，文字旋转 90° 与左侧三列对齐）；
 *        false = 竖屏横排（带标题，与触摸板页一致）
 */
@Composable
fun MousePrimaryButtons(
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    onButtonDown: (Byte) -> Unit,
    onButtonUp: () -> Unit,
) {
    val dimens = LocalAppDimens.current
    val tokens = LocalAppTokens.current
    val shape = RoundedCornerShape(dimens.cardCorner)

    Card(
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = tokens.card),
        border = BorderStroke(dimens.strokeWidth, tokens.line),
        elevation = CardDefaults.cardElevation(defaultElevation = dimens.cardElevation),
    ) {
        if (vertical) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SideButton(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    text = stringResource(id = R.string.left_button),
                    onDown = { onButtonDown(MouseConstants.Buttons.LEFT) },
                    onUp = onButtonUp,
                )
                SideButton(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    text = stringResource(id = R.string.right_button),
                    onDown = { onButtonDown(MouseConstants.Buttons.RIGHT) },
                    onUp = onButtonUp,
                )
            }
        } else {
            Column(
                modifier = Modifier.padding(dimens.cardPadding),
                verticalArrangement = Arrangement.spacedBy(dimens.sectionGap),
            ) {
                SectionTitle(text = stringResource(id = R.string.mouse_control))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MouseButton(
                        modifier = Modifier.weight(1f),
                        text = stringResource(id = R.string.left_button),
                        onDown = { onButtonDown(MouseConstants.Buttons.LEFT) },
                        onUp = onButtonUp,
                    )
                    MouseButton(
                        modifier = Modifier.weight(1f),
                        text = stringResource(id = R.string.right_button),
                        onDown = { onButtonDown(MouseConstants.Buttons.RIGHT) },
                        onUp = onButtonUp,
                    )
                }
            }
        }
    }
}

/**
 * 窄列里的文字按钮：文字旋转 90° 沿列方向排列。
 *
 * 为什么不用 `Button + rotate`：Button 会先按「按钮宽度」（40 dp 减去内边距）布局文字，
 * 「Button 4」在旋转之前就已经被折成几行了。这里用 [BoxWithConstraints] 拿到按钮高度，
 * 先让文字按这个长度排成一行（`requiredWidth` 忽略父宽度约束），再整体旋转 90° ——
 * 旋转后的视觉宽度只有一行字高，正好落进 40 dp 的列里。
 */
@Composable
private fun SideButton(
    modifier: Modifier = Modifier,
    text: String,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isPressed) {
        if (!isPressed) return@LaunchedEffect
        onDown()
        try {
            awaitCancellation()
        } finally {
            onUp()
        }
    }

    val tokens = LocalAppTokens.current
    val dimens = LocalAppDimens.current
    val shape = RoundedCornerShape(dimens.innerCorner)

    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .background(if (isPressed) tokens.active else tokens.accent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            maxLines = 1,
            softWrap = false,
            style = MaterialTheme.typography.labelLarge,
            color = tokens.onAccent,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .rotate(90f)
                .requiredWidth(maxHeight),
        )
    }
}

/**
 * 一个「按住即生效」的鼠标按钮。
 *
 * 按下 / 松开写在 `LaunchedEffect(isPressed)` 里：协程在松手或离开组合时被取消，
 * `finally` 保证一定回调 [onUp] —— 少了这一步，快速滑出按钮就会让主机一直按着不放。
 */
@Composable
fun MouseButton(
    modifier: Modifier = Modifier,
    text: String,
    compact: Boolean = false,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isPressed) {
        if (!isPressed) return@LaunchedEffect
        onDown()
        try {
            awaitCancellation()
        } finally {
            onUp()
        }
    }

    val tokens = LocalAppTokens.current
    val dimens = LocalAppDimens.current

    Button(
        onClick = {},
        interactionSource = interactionSource,
        modifier = modifier.heightIn(min = dimens.buttonHeight),
        shape = RoundedCornerShape(dimens.innerCorner),
        colors = ButtonDefaults.buttonColors(
            containerColor = tokens.accent,
            contentColor = tokens.onAccent,
        ),
        contentPadding = PaddingValues(horizontal = if (compact) 8.dp else 16.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/** 滚轮按钮：按下先滚一格，按住 400 ms 后每 80 ms 连发一格 */
@Composable
fun WheelButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    contentDescription: String,
    compact: Boolean = false,
    onWheel: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isPressed) {
        if (!isPressed) return@LaunchedEffect
        onWheel()
        delay(400.milliseconds)
        while (true) {
            onWheel()
            delay(80.milliseconds)
        }
    }

    val tokens = LocalAppTokens.current
    val dimens = LocalAppDimens.current

    Button(
        onClick = {},
        interactionSource = interactionSource,
        modifier = modifier.heightIn(min = dimens.buttonHeight),
        shape = RoundedCornerShape(dimens.innerCorner),
        colors = ButtonDefaults.buttonColors(
            containerColor = tokens.panel,
            contentColor = tokens.accent,
        ),
        contentPadding = PaddingValues(horizontal = if (compact) 8.dp else 16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
        )
    }
}
