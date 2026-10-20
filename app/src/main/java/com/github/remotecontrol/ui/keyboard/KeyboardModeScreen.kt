package com.github.remotecontrol.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.github.remotecontrol.R
import com.github.remotecontrol.ui.theme.LocalAppDimens
import com.github.remotecontrol.ui.theme.TintLevel
import com.github.remotecontrol.ui.theme.tintStyle
import com.github.remotecontrol.util.HidReportDescriptors
import com.github.remotecontrol.util.HostKeyboardLayout
import com.github.remotecontrol.util.KeyboardConstants
import com.github.remotecontrol.hid.HidSession
import com.github.remotecontrol.hid.HidState
import kotlinx.coroutines.launch

/**
 * 键盘模式界面。
 *
 * 整体是三层结构（横竖屏排布一致，横屏把修饰键区挪到右侧竖条）：
 *
 * ```
 * ┌─ 分页指示器（可点击）───────────────┐
 * ├─ 键盘灯条（Num / Caps / Scroll）────┤
 * ├─ 分页键盘区（点页签切换）───────────┤
 * │  横竖屏都是：主键盘 / 功能键 + 小键盘 │
 * └─ 固定修饰键区（Ctrl/Shift/Alt/Win）──┘
 * ```
 *
 * 修饰键之所以要独立于分页之外：键盘区会翻页，而修饰键必须在翻页时待在原地、
 * 且状态（按住 / 锁定）不受翻页影响。灯条同理 —— 它是键盘的附属信息，也不该随翻页移动。
 *
 * **翻页不做滑动**：键盘画布铺满整页、且不消费按下事件（见 [KeyboardCanvas]），
 * 起始点落在键位上的滑动会同时敲一个键并翻页，而画布之外没有可作"滑动把手"的空白；
 * 所以 Pager 关掉手势滚动，页签加高到与键位同高（[com.github.remotecontrol.ui.theme.AppDimens.keyboardPageIndicatorHeight]），
 * 翻页统一走点页签。
 *
 * ## 报告规则（本次重写的关键变化）
 * | 时机 | 报告 |
 * | --- | --- |
 * | 键位按下 | `modifier = held ∪ locked`，`keyCode = 按下的键` |
 * | 键位抬起 | `modifier = held ∪ locked`，`keyCode = 0` |
 * | 修饰键状态变化 | `modifier = 新状态`，`keyCode = 0` |
 *
 * 抬起**不能**再发全零报告：修饰键改成「按住生效」之后，全零报告会把正按着的 Ctrl
 * 一起清掉（旧实现就是这么写的）。修饰键状态变化时补发一次无按键报告，
 * 是为了让主机不必等到下一次按键才知道修饰位变了。
 */
@Composable
fun KeyboardModeScreen(
    viewModel: HidSession,
    layout: HostKeyboardLayout,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false,
) {
    // 修饰键状态放在分页容器之外 —— 翻页、换页都不会重置它
    var modifierState by remember { mutableStateOf(ModifierUiState()) }

    // 键盘灯由主机经 LED Output Report 回传；主机没下发过时给出的是本地推断值，见 KeyboardLedBar
    val ledState by viewModel.keyboardLeds.collectAsState()

    // 「键盘页可见」+「链路已连接」两件事谁后到都算数：先停在本页、之后才连上同样会触发。
    // 灯态的同步刻意只挂在本页 —— 别的页面不看灯，没必要为了几个灯去打扰主机
    // （同步动作会碰一下主机的锁键，见 HidSession.syncKeyboardLeds）
    val hidState by viewModel.state.collectAsState()
    LaunchedEffect(hidState is HidState.Connected) {
        if (hidState is HidState.Connected) viewModel.syncKeyboardLeds()
    }

    // 灯位按钮：点一下 = 把对应的锁键按一次发给主机（主机翻转锁定状态后会把新灯态推回来）。
    // 刻意不带修饰位：它是一个独立的锁定开关，不该跟正锁着的 Ctrl 之类绑在一起。
    val onLedToggle: (KeyboardConstants.Key) -> Unit = { key ->
        sendKeyboardReport(viewModel, KeyboardConstants.KeyboardReport(keyCode = key.value))
        sendKeyboardReport(viewModel, KeyboardConstants.KeyboardReport())
    }

    val onKeyDown: (Byte) -> Unit = { keyCode ->
        sendKeyboardReport(
            viewModel = viewModel,
            report = KeyboardConstants.KeyboardReport(
                modifier = modifierState.reportByte,
                keyCode = keyCode,
            ),
        )
    }

    val onKeyUp: () -> Unit = {
        sendKeyboardReport(
            viewModel = viewModel,
            report = KeyboardConstants.KeyboardReport(modifier = modifierState.reportByte),
        )
    }

    val onModifierStateChange: (ModifierUiState) -> Unit = { newState ->
        modifierState = newState
        sendKeyboardReport(
            viewModel = viewModel,
            report = KeyboardConstants.KeyboardReport(modifier = newState.reportByte),
        )
    }

    if (isLandscape) {
        LandscapeKeyboardContent(
            modifierState = modifierState,
            onModifierStateChange = onModifierStateChange,
            onKeyDown = onKeyDown,
            onKeyUp = onKeyUp,
            layout = layout,
            ledState = ledState,
            onLedToggle = onLedToggle,
            modifier = modifier,
        )
    } else {
        PortraitKeyboardContent(
            modifierState = modifierState,
            onModifierStateChange = onModifierStateChange,
            onKeyDown = onKeyDown,
            onKeyUp = onKeyUp,
            layout = layout,
            ledState = ledState,
            onLedToggle = onLedToggle,
            modifier = modifier,
        )
    }
}

/** 键盘报告的唯一出口：报告 ID 与字节序列都收在这里 */
fun sendKeyboardReport(viewModel: HidSession, report: KeyboardConstants.KeyboardReport) {
    viewModel.sendReport(HidReportDescriptors.REPORT_ID_KEYBOARD, report.toByteArray())
}

// ─────────────────────────────────────────────
// 竖屏：2 页点页签切换 + 底部固定修饰键横条
// ─────────────────────────────────────────────

@Composable
private fun PortraitKeyboardContent(
    modifierState: ModifierUiState,
    onModifierStateChange: (ModifierUiState) -> Unit,
    onKeyDown: (Byte) -> Unit,
    onKeyUp: () -> Unit,
    layout: HostKeyboardLayout,
    ledState: KeyboardConstants.KeyboardLedsState,
    onLedToggle: (KeyboardConstants.Key) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = KeyboardPage.entries
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()

    Column(modifier = modifier) {
        KeyboardPageIndicator(
            labels = pages.map { it.label() },
            currentPage = pagerState.currentPage,
            onPageSelected = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
        )

        KeyboardLedBar(state = ledState, onToggle = onLedToggle)

        HorizontalPager(
            state = pagerState,
            // 只组合可见页：一页有几十个键位，多组合一页就多付一次文本测量
            beyondViewportPageCount = 0,
            // 不做滑动翻页：画布铺满整页且不消费按下事件，落在键位上的滑动会顺带敲一个键；
            // 翻页只走页签（页签已加高到与键位同高）
            userScrollEnabled = false,
            key = { page -> pages[page] },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            KeyboardPageContent(
                page = pages[page],
                isLandscape = false,
                layout = layout,
                onKeyDown = onKeyDown,
                onKeyUp = onKeyUp,
            )
        }

        KeyboardModifierBar(
            state = modifierState,
            onStateChange = onModifierStateChange,
            vertical = false,
        )
    }
}

/**
 * 分页内容：两种方向共用同一套两页结构，只是页内排布不同。
 *
 * | 页 | 竖屏 | 横屏 |
 * | --- | --- | --- |
 * | 主键盘 | 左半在上、右半在下 | 整块键盘 |
 * | 功能键 + 小键盘 | 上下两块（各占半高） | 左右两块（各占半宽） |
 *
 * 主键盘的行定义随宿主布局变化（少数布局在 ENTER 左边多出几颗键）；
 * 功能键区与小键盘区在任何布局下都一样，仍然是布局无关的常量。
 */
@Composable
private fun KeyboardPageContent(
    page: KeyboardPage,
    isLandscape: Boolean,
    layout: HostKeyboardLayout,
    onKeyDown: (Byte) -> Unit,
    onKeyUp: () -> Unit,
) {
    when (page) {
        KeyboardPage.MAIN -> if (isLandscape) {
            KeyboardCanvas(
                rows = KeyboardLayouts.mainRows(layout).landscape,
                layout = layout,
                modifier = Modifier.fillMaxSize(),
                onKeyDown = onKeyDown,
                onKeyUp = onKeyUp,
            )
        } else {
            PortraitSplitKeyboardPage(layout = layout, onKeyDown = onKeyDown, onKeyUp = onKeyUp)
        }

        // 功能键区与小键盘区行数都少，键高会被 clamp 到上限并在各自容器里居中
        KeyboardPage.FUNCTION_AND_NUMPAD -> if (isLandscape) {
            Row(modifier = Modifier.fillMaxSize()) {
                KeyboardCanvas(
                    rows = KeyboardLayouts.functionRows,
                    layout = layout,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    onKeyDown = onKeyDown,
                    onKeyUp = onKeyUp,
                )
                KeyboardCanvas(
                    rows = KeyboardLayouts.numpadRows,
                    layout = layout,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    onKeyDown = onKeyDown,
                    onKeyUp = onKeyUp,
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                KeyboardCanvas(
                    rows = KeyboardLayouts.functionRows,
                    layout = layout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    onKeyDown = onKeyDown,
                    onKeyUp = onKeyUp,
                )
                KeyboardCanvas(
                    rows = KeyboardLayouts.numpadRows,
                    layout = layout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    onKeyDown = onKeyDown,
                    onKeyUp = onKeyUp,
                )
            }
        }
    }
}

/**
 * 竖屏主键盘页：左半在上、右半在下。
 *
 * 两半各占一半高度（`weight(1f)`），行数又都是 6，所以两半的键高会算出同一个值、
 * 行高自然对齐。布局独有的新键插在右半末行的 ENTER 左边，行数不变，对齐关系不受影响。
 */
@Composable
private fun PortraitSplitKeyboardPage(
    layout: HostKeyboardLayout,
    onKeyDown: (Byte) -> Unit,
    onKeyUp: () -> Unit,
) {
    val rows = KeyboardLayouts.mainRows(layout)
    Column(modifier = Modifier.fillMaxSize()) {
        KeyboardCanvas(
            rows = rows.portraitLeftHalf,
            layout = layout,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            onKeyDown = onKeyDown,
            onKeyUp = onKeyUp,
        )
        KeyboardCanvas(
            rows = rows.portraitRightHalf,
            layout = layout,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            onKeyDown = onKeyDown,
            onKeyUp = onKeyUp,
        )
    }
}

// ─────────────────────────────────────────────
// 横屏：2 页点页签切换 + 右侧固定修饰键竖条
// ─────────────────────────────────────────────

@Composable
private fun LandscapeKeyboardContent(
    modifierState: ModifierUiState,
    onModifierStateChange: (ModifierUiState) -> Unit,
    onKeyDown: (Byte) -> Unit,
    onKeyUp: () -> Unit,
    layout: HostKeyboardLayout,
    ledState: KeyboardConstants.KeyboardLedsState,
    onLedToggle: (KeyboardConstants.Key) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = KeyboardPage.entries
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()

    Row(modifier = modifier) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            KeyboardPageIndicator(
                labels = pages.map { it.label() },
                currentPage = pagerState.currentPage,
                onPageSelected = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
            )

            KeyboardLedBar(state = ledState, onToggle = onLedToggle)

            HorizontalPager(
                state = pagerState,
                // 只组合可见页：一页有几十个键位，多组合一页就多付一次文本测量
                beyondViewportPageCount = 0,
                // 不做滑动翻页：画布铺满整页且不消费按下事件，落在键位上的滑动会顺带敲一个键；
                // 翻页只走页签（页签已加高到与键位同高）
                userScrollEnabled = false,
                key = { page -> pages[page] },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                KeyboardPageContent(
                    page = pages[page],
                    isLandscape = true,
                    layout = layout,
                    onKeyDown = onKeyDown,
                    onKeyUp = onKeyUp,
                )
            }
        }

        KeyboardModifierBar(
            state = modifierState,
            onStateChange = onModifierStateChange,
            vertical = true,
        )
    }
}

// ─────────────────────────────────────────────
// 分页指示器
// ─────────────────────────────────────────────

/**
 * 可点击的分页标签条：选中 = 第 5 档满浓度 + 反白文字，未选中 = 第 2 档浅色相底 + 色相文字，
 * 与侧栏模式按钮共用同一套「选中」语义。
 */
@Composable
private fun KeyboardPageIndicator(
    labels: List<String>,
    currentPage: Int,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalAppDimens.current
    val shape = remember(dimens.innerCorner) { RoundedCornerShape(dimens.innerCorner) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.keyContentPadding, vertical = dimens.keySpacing)
            .height(dimens.keyboardPageIndicatorHeight),
        horizontalArrangement = Arrangement.spacedBy(dimens.keySpacing),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == currentPage
            val style = tintStyle(if (selected) TintLevel.SOLID else TintLevel.SURFACE)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(style.container)
                    .border(dimens.strokeWidth, style.border, shape)
                    // selectable 而不是 clickable：读屏能播报"当前页"，语义也正确（页签 = tab）
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onPageSelected(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = style.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 分页标签文案（与各语言 strings.xml 里的 keyboard_page_* 对应）。
 *
 * 第 2 页在两种方向下都是「功能键区 + 小键盘区」，标签用中性分隔符拼接，
 * 不为这个组合再造一条只会出现在一处的字符串。
 */
@Composable
private fun KeyboardPage.label(): String = when (this) {
    KeyboardPage.MAIN -> stringResource(R.string.keyboard_page_main)
    KeyboardPage.FUNCTION_AND_NUMPAD ->
        "${stringResource(R.string.keyboard_page_function)} · ${stringResource(R.string.keyboard_page_numpad)}"
}
