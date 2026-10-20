package com.github.remotecontrol.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.remotecontrol.R
import com.github.remotecontrol.hid.HidError
import com.github.remotecontrol.hid.HidSession
import com.github.remotecontrol.hid.HidState
import com.github.remotecontrol.hid.PointerKind
import com.github.remotecontrol.hid.hasActiveLink
import com.github.remotecontrol.ui.keyboard.KeyboardModeScreen
import com.github.remotecontrol.ui.template.TemplateModeScreen
import com.github.remotecontrol.ui.theme.ConnStatus
import com.github.remotecontrol.ui.theme.LocalAppDimens
import com.github.remotecontrol.ui.theme.LocalAppTokens
import com.github.remotecontrol.ui.theme.StatusDot
import com.github.remotecontrol.ui.theme.TintLevel
import com.github.remotecontrol.ui.theme.tintStyle
import com.github.remotecontrol.util.ControlMode
import com.github.remotecontrol.util.HostKeyboardLayout
import com.github.remotecontrol.util.KeyboardLayoutCatalog
import com.github.remotecontrol.util.KeyboardLayoutMode
import com.github.remotecontrol.util.LocaleHelper

/**
 * 控制页界面：键盘 / 触摸板 / 模板三种模式 + 横竖屏布局 + 错误 / 提示对话框。
 *
 * **两条链路共用同一份**（经典蓝牙 / 局域网）：它只认 [HidSession] —— 即「一条链路对界面暴露什么」，
 * 因此这里既不知道也不该知道报告最终走蓝牙还是走 USB/IP。
 *
 * 指针那一页是唯一的例外：报告结构由链路的 [PointerKind] 决定，
 * 所以 `ControlMode.TOUCHPAD` 这个槽位在鼠标链路上渲染 `MouseModeScreen`、
 * 在触摸板链路上渲染 `TouchpadModeScreen`，而不是让同一个页面去猜。
 *
 * 蓝牙专有的那几件「界面一挂上就做的事」（初始化 HID 应用、按地址发起连接、申请通知权限）
 * 不在本文件里，见 `bluetooth/BluetoothLinkEffects.kt` —— 它由蓝牙控制页在**同一段 `setContent`**
 * 里与本函数并列调用；局域网控制页没有对应的东西，什么都不传。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlScreen(
    viewModel: HidSession,
    deviceName: String,
    // Configuration.ORIENTATION_LANDSCAPE / PORTRAIT。
    // 不用 LocalConfiguration.current：控制页拦截了 orientation 配置变更（旋转不重建），
    // LocalConfiguration 不会自动刷新，方向只能由 Activity 的 onConfigurationChanged 显式传入。
    orientation: Int,
    keyboardLayoutMode: KeyboardLayoutMode,
    // 后退：只退出界面，连接留在后台（由常驻通知承载）
    onBack: () -> Unit,
    // 断开连接：显式动作，确认之后才走
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
    val state by viewModel.state.collectAsState()
    // 指针形态由链路决定，运行期不变（经典蓝牙 = 鼠标，局域网 = 触摸板）
    val pointerKind = viewModel.pointerKind

    // 「主机不理 Num Lock」的提示。触发点在键盘页那次问灯态，但它说的是**这台设备**的事
    // （模板的 Alt Code 输入靠小键盘），所以挂在 ControlScreen 而不是键盘页：
    // 用户等回话时切到别的页面，照样看得到
    val numpadWarning by viewModel.numpadWarning.collectAsState()

    // 「跟随界面语言」那一档要查当前界面语言，而界面语言只能从偏好读
    // （applicationContext 的 configuration 永远是系统语言，见 LocaleHelper）
    val keyboardLayout = remember(keyboardLayoutMode) {
        KeyboardLayoutCatalog.forMode(keyboardLayoutMode, LocaleHelper.currentLocale(context))
    }

    // 两类失败都直接由状态承载，不需要额外的一次性事件通道
    val stateError = when (val current = state) {
        is HidState.ConnectFailed -> current.error
        is HidState.RegisterFailed -> current.error
        else -> null
    }

    var currentMode by rememberSaveable { mutableStateOf(ControlMode.TOUCHPAD) }
    var showDisconnectDialog by remember { mutableStateOf(false) }

    // 断开入口只有这一个按钮。链路本来就没建起来时不必弹确认框 ——
    // 那时候「断开」和「退出」是同一件事（这段判断原来挂在后退键上）
    val requestDisconnect = {
        if (state.hasActiveLink) showDisconnectDialog = true else onDisconnect()
    }

    // 切模式前补一帧空报告：被切走的界面不一定来得及为按住的键发出抬起报告
    // （键盘画布只在手势被取消时补 keyUp，修饰键的按住状态更是随界面一起丢），
    // 不补的话主机侧会一直认为那个键还按着，表现为连发停不下来。
    // 聚焦在切换入口一处，而不是每个界面各挂一个 DisposableEffect。
    val switchMode: (ControlMode) -> Unit = { mode ->
        if (mode != currentMode) {
            viewModel.sendNeutralReports()
            currentMode = mode
        }
    }

    // 后退只退出界面：连接留在后台，由常驻通知承载（见 ControlActivity.exitControlScreen）
    BackHandler(enabled = true) { onBack() }

    // 横屏没有 Scaffold，于是 Scaffold 顺带提供的那两样东西都没了：
    // 1. 页面底色 —— 浅色的 windowBackground（Theme.RemoteInput 继承 Light 主题）会直接透出来，
    //    深色模式下横屏就变成白底；
    // 2. 内容色 —— LocalContentColor 退回默认的 Color.Black，
    //    所有没显式写 color 的 Text / 输入框在深色下就成了黑字（模板编辑器的输入框就是这种）。
    // 这里统一用 Surface 补齐，横竖屏都在，新增页面也不会再踩同一个坑。
    // 横竖屏是两棵不同的布局树（横屏 Row + 侧栏 / 竖屏 Scaffold），而旋转时 Activity 并不重建
    // （configChanges 已拦截，见 AndroidManifest）。内容区若直接写在分支里，方向一变它就换了位置，
    // Compose 会按新位置重新组合整棵子树 —— 内部所有 remember 全丢，表现就是
    // 「编辑模板时一旋转就退回模板列表」（步骤、启用状态、输入框里的文本一起没）。
    // movableContentOf 专为这种场景而设：位置换了，状态跟着走。
    val modeContent = remember {
        movableContentOf<ControlMode, Boolean, HostKeyboardLayout> { mode, landscape, layout ->
            ModeContentArea(
                viewModel = viewModel,
                currentMode = mode,
                isLandscape = landscape,
                keyboardLayout = layout,
                // 捕获的是本页会话的形态：同一控制页里链路不会中途更换，所以不放进 key
                pointerKind = pointerKind,
            )
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        if (isLandscape) {
            LandscapeLayout(
                currentMode = currentMode,
                pointerKind = pointerKind,
                keyboardLayout = keyboardLayout,
                onModeSelected = switchMode,
                onBack = onBack,
                onDisconnect = requestDisconnect,
                content = modeContent,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            PortraitLayout(
                deviceName = deviceName,
                state = state,
                currentMode = currentMode,
                pointerKind = pointerKind,
                keyboardLayout = keyboardLayout,
                onModeSelected = switchMode,
                onBack = onBack,
                onDisconnect = requestDisconnect,
                content = modeContent,
            )
        }
    }

    // 错误对话框。
    // 注册失败是终态、没有可回退的状态：点「确定」直接退回扫描页，
    // 否则就成了一个按了没反应的弹窗
    ErrorDialog(
        error = stateError,
        exitOnConfirm = state is HidState.RegisterFailed,
        onDismiss = { viewModel.dismissError() },
        onExit = onBack
    )

    // 小键盘提示（见 numpadWarning：主机对 Num Lock 的敲击一声不吭）
    NumpadWarningDialog(
        show = numpadWarning,
        onDismiss = { viewModel.dismissNumpadWarning() }
    )

    // 断开连接确认对话框：原先是挂在后退键上的，现在后退只退出界面，它归断开按钮
    DisconnectDialog(
        show = showDisconnectDialog,
        onDismiss = { showDisconnectDialog = false },
        onConfirm = onDisconnect
    )
}

// ─────────────────────────────────────────────
// 横屏布局
// ─────────────────────────────────────────────

@Composable
private fun LandscapeLayout(
    currentMode: ControlMode,
    pointerKind: PointerKind,
    keyboardLayout: HostKeyboardLayout,
    onModeSelected: (ControlMode) -> Unit,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    // 内容区由外部以 movable content 传入：横竖屏共用同一份状态（见 ControlScreen）
    content: @Composable (ControlMode, Boolean, HostKeyboardLayout) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAppTokens.current

    Row(modifier = modifier) {
        // 侧边导航栏 —— 与顶栏同色（第 1 档），横竖屏切换时形成同一个 L 形"外壳"
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(52.dp)
                .background(tokens.appBar),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(id = R.string.back),
                    // 与 MainActivity 的设置图标同样走 accent，而不是中性 onSurface
                    tint = tokens.accent
                )
            }
            // 断开连接紧挨后退键 —— 竖屏顶栏里它在后退右边，这里就落在后退下面，位置语义一致。
            // 用错误色与「后退」区分开：那是导航（连接留在后台），这是破坏性动作
            IconButton(onClick = onDisconnect) {
                Icon(
                    Icons.Default.LinkOff,
                    contentDescription = stringResource(id = R.string.disconnect),
                    tint = tokens.error
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = tokens.tintLine
            )
            ModeButtonGroup(
                currentMode = currentMode,
                pointerKind = pointerKind,
                onModeSelected = onModeSelected
            )
            Spacer(modifier = Modifier.weight(1f))
        }

        // 主控制区域
        Box(modifier = Modifier.weight(1f)) {
            content(currentMode, true, keyboardLayout)
        }
    }
}

// ─────────────────────────────────────────────
// 竖屏布局
// ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortraitLayout(
    deviceName: String,
    state: HidState,
    currentMode: ControlMode,
    pointerKind: PointerKind,
    keyboardLayout: HostKeyboardLayout,
    onModeSelected: (ControlMode) -> Unit,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    // 内容区由外部以 movable content 传入：横竖屏共用同一份状态（见 ControlScreen）
    content: @Composable (ControlMode, Boolean, HostKeyboardLayout) -> Unit,
) {
    val tokens = LocalAppTokens.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = deviceName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // 连接状态用语义色圆点表示：
                        // 绿=已连接 / 琥珀=过渡中 / 红=初始化失败 / 灰=未连接
                        val (status, statusLabel) = when (state) {
                            HidState.Connected ->
                                ConnStatus.OK to stringResource(id = R.string.connected)

                            HidState.Connecting ->
                                ConnStatus.WARN to stringResource(id = R.string.connecting)

                            HidState.Disconnecting ->
                                ConnStatus.WARN to stringResource(id = R.string.disconnecting)

                            is HidState.RegisterFailed ->
                                ConnStatus.BAD to stringResource(id = R.string.init_failed)

                            HidState.Initializing ->
                                ConnStatus.IDLE to stringResource(id = R.string.initializing)

                            HidState.Registering ->
                                ConnStatus.IDLE to stringResource(id = R.string.registering)

                            // Idle / Ready / ConnectFailed 都是「当前没连上」，统一显示未连接
                            HidState.Idle, HidState.Ready, is HidState.ConnectFailed ->
                                ConnStatus.IDLE to stringResource(id = R.string.disconnected)
                        }
                        StatusDot(status = status, label = statusLabel)
                    }
                },
                navigationIcon = {
                    // 后退与断开并排：后退贴边、断开紧跟其后。
                    // 两者语义差得远 —— 前者只退出界面、连接留在后台，后者真把链路拆掉 ——
                    // 所以断开单独用错误色，不跟后退共用主题色
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(id = R.string.back)
                            )
                        }
                        IconButton(onClick = onDisconnect) {
                            Icon(
                                Icons.Default.LinkOff,
                                contentDescription = stringResource(id = R.string.disconnect),
                                tint = tokens.error
                            )
                        }
                    }
                },
                actions = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        ModeButtonGroup(
                            currentMode = currentMode,
                            pointerKind = pointerKind,
                            onModeSelected = onModeSelected
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    // 顶栏第 1 档：与横屏侧栏同色，也和 MainActivity / SettingsActivity 对齐
                    containerColor = LocalAppTokens.current.appBar,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    // 返回箭头与模式切换图标都带主题色 —— 和 MainActivity 设置图标的处理一致；
                    // 用中性 onSurface 的话这几个图标在顶栏上会显得"没上色"。
                    // 断开图标自带 tint（错误色），不受这两个值影响
                    navigationIconContentColor = LocalAppTokens.current.accent,
                    actionIconContentColor = LocalAppTokens.current.accent
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            content(currentMode, false, keyboardLayout)
        }
    }
}

// ─────────────────────────────────────────────
// 模式内容区域（Touchpad / Keyboard / Template）
// ─────────────────────────────────────────────

@Composable
private fun ModeContentArea(
    viewModel: HidSession,
    currentMode: ControlMode,
    isLandscape: Boolean,
    keyboardLayout: HostKeyboardLayout,
    pointerKind: PointerKind,
) {
    when (currentMode) {
        ControlMode.KEYBOARD -> {
            KeyboardModeScreen(viewModel, keyboardLayout, Modifier.fillMaxSize(), isLandscape)
        }
        // ControlMode.TOUCHPAD 是「指针模式」这个槽位：具体页面由链路的指针形态决定 ——
        // 经典蓝牙注册的是鼠标描述符，只能发 4 字节相对位移；局域网是 PTP 触摸板
        ControlMode.TOUCHPAD -> {
            when (pointerKind) {
                PointerKind.MOUSE ->
                    MouseModeScreen(viewModel, Modifier.fillMaxSize(), isLandscape)

                PointerKind.TOUCHPAD ->
                    TouchpadModeScreen(viewModel, Modifier.fillMaxSize(), isLandscape)
            }
        }
        ControlMode.TEMPLATE -> {
            TemplateModeScreen(viewModel, Modifier.fillMaxSize(), isLandscape)
        }
    }
}

// ─────────────────────────────────────────────
// 模式切换按钮组
// ─────────────────────────────────────────────

@Composable
private fun ModeButtonGroup(
    currentMode: ControlMode,
    pointerKind: PointerKind,
    onModeSelected: (ControlMode) -> Unit
) {
    SideRailModeButton(
        mode = ControlMode.TOUCHPAD,
        pointerKind = pointerKind,
        isSelected = currentMode == ControlMode.TOUCHPAD,
        onClick = { onModeSelected(ControlMode.TOUCHPAD) }
    )
    SideRailModeButton(
        mode = ControlMode.KEYBOARD,
        pointerKind = pointerKind,
        isSelected = currentMode == ControlMode.KEYBOARD,
        onClick = { onModeSelected(ControlMode.KEYBOARD) }
    )
    SideRailModeButton(
        mode = ControlMode.TEMPLATE,
        pointerKind = pointerKind,
        isSelected = currentMode == ControlMode.TEMPLATE,
        onClick = { onModeSelected(ControlMode.TEMPLATE) }
    )
}

// ─────────────────────────────────────────────
// 对话框
// ─────────────────────────────────────────────

/**
 * 失败弹窗。
 *
 * @param exitOnConfirm 终态失败（注册失败）没有可回退的状态，「确定」直接退出页面；
 *   可恢复的失败（连接失败）则是关掉弹窗、状态回到 [HidState.Ready]
 */
@Composable
private fun ErrorDialog(
    error: HidError?,
    exitOnConfirm: Boolean,
    onDismiss: () -> Unit,
    onExit: () -> Unit
) {
    if (error == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.error_title)) },
        text = { Text(errorText(error)) },
        confirmButton = {
            TextButton(onClick = if (exitOnConfirm) onExit else onDismiss) {
                Text(stringResource(id = R.string.ok))
            }
        }
    )
}

/**
 * 「连接的设备可能不支持小键盘」提示。
 *
 * 它不是失败 —— 链路是好的，只是主机对 Num Lock 的敲击一声不吭（见
 * `HidSession.numpadWarning`）。之所以值得打断一下：模板的 `Alt Code` 输入
 * 全靠小键盘，主机没有小键盘这一路时，用户会在模板里按半天却什么都打不出来，
 * 而且现场看不出原因。标题用「提示」而不是「错误」，也是这个意思。
 */
@Composable
private fun NumpadWarningDialog(show: Boolean, onDismiss: () -> Unit) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.dialog_title_tip)) },
        text = { Text(stringResource(id = R.string.keyboard_numpad_warning)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.ok))
            }
        }
    )
}

/**
 * 错误类型 → 字符串资源。
 *
 * ViewModel 只描述「出了什么事」，具体文案统一在这里落地，
 * 这样多语言跟着 Activity 走，ViewModel 也不必持有 Context。
 */
@Composable
private fun errorText(error: HidError): String = when (error) {
    HidError.Unsupported -> stringResource(id = R.string.error_unsupported)

    HidError.DeviceNotReady -> stringResource(id = R.string.error_device_not_ready)

    HidError.RegisterFailed -> stringResource(id = R.string.error_register_failed)

    is HidError.RegisterException ->
        stringResource(id = R.string.error_register_exception, error.detail.orEmpty())

    HidError.PairingFailed -> stringResource(id = R.string.error_pairing_failed)

    HidError.ConnectFailed -> stringResource(id = R.string.error_connect_failed)

    is HidError.ConnectException ->
        stringResource(id = R.string.error_connect_exception, error.detail.orEmpty())
}

// ─────────────────────────────────────────────
// 侧边栏 / 顶栏 模式切换图标按钮
// ─────────────────────────────────────────────

@Composable
fun SideRailModeButton(
    mode: ControlMode,
    pointerKind: PointerKind,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val icon = when (mode) {
        ControlMode.KEYBOARD -> Icons.Default.Keyboard
        // 指针槽位的图标随链路形态切换：经典蓝牙是鼠标，局域网是触摸板
        ControlMode.TOUCHPAD -> when (pointerKind) {
            PointerKind.MOUSE -> Icons.Default.Mouse
            PointerKind.TOUCHPAD -> Icons.Default.TouchApp
        }
        ControlMode.TEMPLATE -> Icons.Default.Code
    }
    val contentDescription = when (mode) {
        ControlMode.KEYBOARD -> stringResource(id = R.string.mode_keyboard)
        ControlMode.TOUCHPAD -> when (pointerKind) {
            PointerKind.MOUSE -> stringResource(id = R.string.mode_mouse)
            PointerKind.TOUCHPAD -> stringResource(id = R.string.mode_touchpad)
        }
        ControlMode.TEMPLATE -> stringResource(id = R.string.mode_template)
    }
    val dimens = LocalAppDimens.current
    // 未选中 = 第 2 档（浅色相底 + 第 3 档描边），选中 = 第 5 档（满浓度）
    // 三个模式按钮共用同一个色相，"由浅入深"而不是"三个颜色"来区分状态
    val style = tintStyle(if (isSelected) TintLevel.SOLID else TintLevel.SURFACE)
    val shape = RoundedCornerShape(dimens.innerCorner)

    Box(
        modifier = Modifier
            .size(dimens.iconButtonSize)
            .clip(shape)
            .background(style.container)
            .border(dimens.strokeWidth, style.border, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            // 直接取档位的 content：未选中 = 第 2 档的 accent，选中 = 第 5 档的 onAccent。
            // 之前未选中用 tokens.onVar（中性灰），三个模式图标在侧栏上等于是"没上色"
            tint = style.content,
            modifier = Modifier.size(20.dp)
        )
    }
}
