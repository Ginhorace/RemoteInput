package com.github.remotecontrol.ui.keyboard

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.remotecontrol.ui.theme.LocalAppDimens
import com.github.remotecontrol.ui.theme.LocalAppTokens
import com.github.remotecontrol.util.HostKeyboardLayout
import com.github.remotecontrol.util.KeyboardConstants

/**
 * 自绘键盘画布。
 *
 * 相比旧实现（曾在 KeyboardModeScreen.kt 内以同名私有函数存在），这里把三件昂贵的事
 * 从「每帧」挪到了「每屏一次」：
 *
 * 1. **文本测量**：所有键位的 [TextLayoutResult] 在组合期预测量并按图例文字缓存，绘制期
 *    只查表；按下态与普通态的文字色差异由 `drawText(color = …)` 在绘制时覆盖，不产生
 *    第二份测量结果。旧实现是在 `drawBehind` 里对每个键每帧调一次 `textMeasurer.measure`，
 *    这正是 debug 模式下打开键盘页卡顿的主因。
 * 2. **区域计算**：命中区域在组合期由 [buildKeyboardGeometry] 算出（容器尺寸变化时重算）。
 *    旧实现在 `drawBehind` 里计算并把结果回写到 Compose state —— 在绘制阶段写状态会引发
 *    invalidation 循环，既慢又不正确。
 * 3. **手势循环**：`pointerInput(Unit)` 只挂一次，区域与回调都通过 [rememberUpdatedState]
 *    读取最新值，避免旧实现以区域列表为 key 导致每次布局变化都重启手势循环。
 *
 * 单键高度支持自适应：`keyHeight = null` 时按容器实测高度反推，并 clamp 到
 * [com.github.remotecontrol.ui.theme.AppDimens.keyMinHeight] /
 * [com.github.remotecontrol.ui.theme.AppDimens.keyMaxHeight] —— 竖屏 12 行不溢出、
 * 横屏 6 行不留白，折叠屏 / 平板按同一规则适配。
 *
 * 修饰键（Ctrl/Shift/Alt/Win）不归本组件管，它们位于 KeyboardModifierBar 固定区，
 * 因此这里既不接收也不分发修饰键回调。
 *
 * @param rows 行定义；每行按键按 [KeyboardConstants.Key.weight] 分配行内宽度
 * @param layout 宿主键盘布局，决定每个位置印什么字（图例）与右上角的角标小字。
 *   **必须参与文本测量的缓存 key**，否则切换布局后字不会重新测量
 * @param keyHeight 单键高度；null 表示按容器高度自适应
 * @param centerVertically 行数少的页面（功能键区 / 小键盘区）内容不足容器高度时垂直居中
 * @param onKeyDown 命中键位按下时回调（参数为 HID Usage ID）
 * @param onKeyUp 手指抬起或手势被上层取消时回调
 */
@Composable
fun KeyboardCanvas(
    rows: List<List<KeyboardConstants.Key>>,
    layout: HostKeyboardLayout,
    modifier: Modifier = Modifier,
    keyHeight: Dp? = null,
    rowSpacing: Dp = LocalAppDimens.current.keyRowSpacing,
    keySpacing: Dp = LocalAppDimens.current.keySpacing,
    contentPadding: Dp = LocalAppDimens.current.keyContentPadding,
    centerVertically: Boolean = true,
    onKeyDown: (Byte) -> Unit = {},
    onKeyUp: () -> Unit = {},
) {
    val density = LocalDensity.current
    val dimens = LocalAppDimens.current
    val tokens = LocalAppTokens.current
    val textMeasurer = rememberTextMeasurer()

    val keyDefaultColor = tokens.panel
    val keyPressedColor = tokens.active
    val keyTextColor = MaterialTheme.colorScheme.onSurface
    val keyPressedTextColor = tokens.on
    val keyStrokeColor = tokens.tintLine
    // 角标小字只是提示，不能和主档抢注意力，所以走次要文字色
    val subLabelTextColor = tokens.onVar

    val cornerRadius = CornerRadius(with(density) { dimens.keyCorner.toPx() })
    val strokeWidthPx = with(density) { dimens.strokeWidth.toPx() }
    val subLabelInsetPx = with(density) { SUB_LABEL_INSET.toPx() }

    // ── 文本测量：组合期一次性完成 ──
    // 键面文字（图例）随宿主布局变化，所以缓存的 key 里必须带上 layout；
    // 占位键没有文字，直接排除
    val legends = remember(rows, layout) {
        rows.flatten().filterNot { it.isSpacer }.associateWith { layout.legendOf(it) }
    }
    // 按图例本身去重是安全的：字号完全由图例文字长度决定，同字样的键必然同字号
    val labelLayouts = remember(legends, textMeasurer) {
        legends.values.asSequence().distinct().associateWith { legend ->
            textMeasurer.measure(
                text = AnnotatedString(legend.label),
                style = TextStyle(
                    fontSize = legend.fontSize.sp,
                    // 与加大后的字号配套：半粗在小键位上明显更清楚，也不会糊成一团
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
    // 角标（Shift 档字符，或日语键帽上的假名）字号固定，同样按文字去重
    val subLabelLayouts = remember(legends, textMeasurer) {
        legends.values.asSequence()
            .mapNotNull { it.subLabel }
            .distinct()
            .associateWith { subLabel ->
                textMeasurer.measure(
                    text = AnnotatedString(subLabel),
                    style = TextStyle(
                        fontSize = SUB_LABEL_FONT_SIZE_SP.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
    }

    // ── 区域计算：组合期完成，绘制与手势读同一个值 ──
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val keyHeightPx = keyHeight?.let { with(density) { it.toPx() } }
    val minKeyHeightPx = with(density) { dimens.keyMinHeight.toPx() }
    val maxKeyHeightPx = with(density) { dimens.keyMaxHeight.toPx() }
    val rowSpacingPx = with(density) { rowSpacing.toPx() }
    val keySpacingPx = with(density) { keySpacing.toPx() }
    val contentPaddingPx = with(density) { contentPadding.toPx() }

    val geometry = remember(
        rows,
        containerSize,
        centerVertically,
        keyHeightPx,
        minKeyHeightPx,
        maxKeyHeightPx,
        rowSpacingPx,
        keySpacingPx,
        contentPaddingPx,
    ) {
        buildKeyboardGeometry(
            width = containerSize.width.toFloat(),
            height = containerSize.height.toFloat(),
            rows = rows,
            keyHeightPx = keyHeightPx,
            minKeyHeightPx = minKeyHeightPx,
            maxKeyHeightPx = maxKeyHeightPx,
            rowSpacingPx = rowSpacingPx,
            keySpacingPx = keySpacingPx,
            contentPaddingPx = contentPaddingPx,
            centerVertically = centerVertically,
        )
    }

    val currentHitAreas by rememberUpdatedState(geometry.hitAreas)
    val currentOnKeyDown by rememberUpdatedState(onKeyDown)
    val currentOnKeyUp by rememberUpdatedState(onKeyUp)

    // 行定义变化（换页 / 换方向）时按下态自动失效，避免高亮停在旧索引上
    var pressedIndex by remember(rows) { mutableIntStateOf(-1) }

    // 无障碍语义层只在读屏的触摸探索开启时才建节点（详见 KeyboardSemantics.kt）：
    // 逐键语义节点是"每键一个"的结构，不该让常规操作付这份代价
    val touchExplorationEnabled = rememberTouchExplorationEnabled()
    val semanticItems = remember(geometry, legends) {
        buildKeyboardSemantics(geometry.hitAreas, legends)
    }

    Box(
        modifier = modifier
            .onSizeChanged { containerSize = it }
            .drawBehind {
                for ((index, area) in geometry.hitAreas.withIndex()) {
                    val isPressed = index == pressedIndex
                    val topLeft = Offset(area.rect.left, area.rect.top)
                    val keySize = Size(area.rect.width, area.rect.height)

                    drawRoundRect(
                        color = if (isPressed) keyPressedColor else keyDefaultColor,
                        topLeft = topLeft,
                        size = keySize,
                        cornerRadius = cornerRadius,
                    )
                    drawRoundRect(
                        color = keyStrokeColor,
                        topLeft = topLeft,
                        size = keySize,
                        cornerRadius = cornerRadius,
                        style = Stroke(width = strokeWidthPx),
                    )

                    val legend = legends[area.key] ?: continue
                    val label = labelLayouts[legend] ?: continue
                    drawText(
                        textLayoutResult = label,
                        color = if (isPressed) keyPressedTextColor else keyTextColor,
                        topLeft = Offset(
                            area.rect.center.x - label.size.width / 2f,
                            area.rect.center.y - label.size.height / 2f,
                        ),
                    )

                    // 角标贴右上角，主档保持居中不动：AZERTY 的数字行整档反了，
                    // 不把小字印出来，用户按下去只会觉得「按 1 出了 &」是 bug
                    val subLabel = legend.subLabel?.let { subLabelLayouts[it] }
                    if (subLabel != null) {
                        drawText(
                            textLayoutResult = subLabel,
                            color = if (isPressed) keyPressedTextColor else subLabelTextColor,
                            topLeft = Offset(
                                area.rect.right - subLabel.size.width - subLabelInsetPx,
                                area.rect.top + subLabelInsetPx,
                            ),
                        )
                    }
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val index = currentHitAreas.indexOfFirst { it.rect.contains(down.position) }
                    if (index >= 0) {
                        pressedIndex = index
                        currentOnKeyDown(currentHitAreas[index].key.value)
                    }
                    // 抬起或被上层（分页拖拽）取消：只要按下过就必须补一次 keyUp，
                    // 否则主机侧会一直认为该键处于按下状态
                    waitForUpOrCancellation()
                    if (index >= 0) currentOnKeyUp()
                    pressedIndex = -1
                }
            },
    ) {
        if (touchExplorationEnabled) {
            KeyboardSemanticsLayer(
                items = semanticItems,
                onActivate = { value ->
                    // 读屏的一次双击 = 完整的按下 + 抬起，与自绘层的一次点按等价；
                    // 触摸探索会拦掉触摸，所以这个回调与上面的手势循环不会双触发
                    currentOnKeyDown(value)
                    currentOnKeyUp()
                },
            )
        }
    }
}

/** 单个键位的命中区域（画布坐标系，单位 px） */
internal data class KeyboardHitArea(
    val key: KeyboardConstants.Key,
    val rect: Rect,
)

/** 一次布局算出的全部键位区域 */
internal class KeyboardGeometry(
    val hitAreas: List<KeyboardHitArea>,
)

/**
 * 由容器尺寸算出键位区域。
 *
 * 纯函数、不读任何 Compose 状态，因此可以直接单测（见 KeyboardCanvasGeometryTest）。
 *
 * @param keyHeightPx 指定单键高度；传 null 时按容器高度自适应并 clamp 到
 *   [minKeyHeightPx] / [maxKeyHeightPx]
 * @param centerVertically 内容高度小于容器高度时是否垂直居中
 */
internal fun buildKeyboardGeometry(
    width: Float,
    height: Float,
    rows: List<List<KeyboardConstants.Key>>,
    keyHeightPx: Float?,
    minKeyHeightPx: Float,
    maxKeyHeightPx: Float,
    rowSpacingPx: Float,
    keySpacingPx: Float,
    contentPaddingPx: Float,
    centerVertically: Boolean,
): KeyboardGeometry {
    if (width <= 0f || height <= 0f || rows.isEmpty()) return KeyboardGeometry(emptyList())

    val rowCount = rows.size
    val rowGaps = (rowCount - 1).coerceAtLeast(0)
    val usableHeight = height - 2 * contentPaddingPx - rowGaps * rowSpacingPx
    // 上限写在 token 里时理论上不会出现 min > max，这里仍做一次防御避免 coerceIn 抛异常
    val lowerBound = minKeyHeightPx.coerceAtMost(maxKeyHeightPx)
    val resolvedKeyHeight = keyHeightPx
        ?: (usableHeight / rowCount).coerceIn(lowerBound, maxKeyHeightPx)

    val contentHeight = rowCount * resolvedKeyHeight + rowGaps * rowSpacingPx + 2 * contentPaddingPx
    // 内容装不下时（小屏 + 行数多）不再居中，从顶部开始画，宁可溢出也不把首行顶掉
    val topOffset = if (centerVertically) ((height - contentHeight) / 2f).coerceAtLeast(0f) else 0f
    val usableWidth = width - 2 * contentPaddingPx

    val hitAreas = ArrayList<KeyboardHitArea>(rows.sumOf { it.size })
    var y = topOffset + contentPaddingPx
    for (row in rows) {
        val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
        val availableWidth = usableWidth - keySpacingPx * (row.size - 1).coerceAtLeast(0)
        var x = contentPaddingPx
        for (key in row) {
            // 权重全为 0 时退化成等宽，避免除零
            val fraction = if (totalWeight > 0f) key.weight / totalWeight else 1f / row.size
            val keyWidth = availableWidth * fraction
            // 占位键只推进 x（留出空白），既不绘制也不进入命中区域
            if (!key.isSpacer) {
                // 跨行键（小键盘的 + / Enter）把行距也算进去，底边正好与下一行的底边对齐
                val span = key.rowSpan.coerceAtLeast(1)
                val keyHeight = resolvedKeyHeight * span + rowSpacingPx * (span - 1)
                hitAreas += KeyboardHitArea(key, Rect(x, y, x + keyWidth, y + keyHeight))
            }
            x += keyWidth + keySpacingPx
        }
        y += resolvedKeyHeight + rowSpacingPx
    }
    return KeyboardGeometry(hitAreas)
}

/** 角标小字的字号与右上角内缩：比主档（13 / 15sp）小一档，贴边但不压键框 */
private const val SUB_LABEL_FONT_SIZE_SP = 9
private val SUB_LABEL_INSET = 2.dp
