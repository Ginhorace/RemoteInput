package com.github.remotecontrol.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.remotecontrol.ControlActivity
import com.github.remotecontrol.R
import com.github.remotecontrol.ui.theme.ConnStatus
import com.github.remotecontrol.ui.theme.ElevatedCard
import com.github.remotecontrol.ui.theme.LocalAppDimens
import com.github.remotecontrol.ui.theme.LocalAppTokens
import com.github.remotecontrol.ui.theme.PrimaryButton
import com.github.remotecontrol.ui.theme.SectionTitle
import com.github.remotecontrol.ui.theme.StatusDot
import com.github.remotecontrol.ui.theme.TintLevel
import com.github.remotecontrol.viewmodel.BluetoothHidViewModel
import com.github.remotecontrol.viewmodel.BluetoothScannerViewModel
import com.github.remotecontrol.viewmodel.hasActiveLink

/**
 * 读取 device.name / 发起 ACTION_REQUEST_ENABLE 需要 BLUETOOTH_CONNECT 权限，
 * 本页已在 requestPermissionLauncher 中申请，并由 hasBluetoothPermission 守卫，
 * 故抑制 Lint 无法跨作用域推断的 MissingPermission 告警。
 */
@SuppressLint("MissingPermission")
@Composable
fun BluetoothScannerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val viewModel: BluetoothScannerViewModel = viewModel()

    // 扫描页不调 initialize()：连接归进程级单例所有，这里只是读一眼它的状态（见「正在保持的连接」卡片）。
    // 真正会绑定 HID profile 的是控制页 —— 用户没打算控制设备时不该把 HID 应用挂上去。
    val hidViewModel = remember { BluetoothHidViewModel.getInstance() }
    val hidState by hidViewModel.state.collectAsState()
    val connectedName by hidViewModel.connectedDeviceName.collectAsState()
    val connectedAddress by hidViewModel.connectedDeviceAddress.collectAsState()

    // 从 ViewModel 获取状态
    val hasBluetoothPermission by viewModel.hasBluetoothPermission.collectAsState()
    val isBluetoothEnabled by viewModel.isBluetoothEnabled.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scannedDevices by viewModel.scannedDevices.collectAsState()
    val savedDevices by viewModel.savedDevices.collectAsState()

    // 「已经连着别人了，要不要换」的待确认目标
    var pendingSwitch by remember { mutableStateOf<BluetoothDevice?>(null) }
    var showHidDisconnectDialog by remember { mutableStateOf(false) }

    val unknownDevice = stringResource(id = R.string.unknown_device)

    // 权限请求
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // 只有蓝牙这两项是功能必需。通知权限被拒只影响那条常驻通知（连接照旧在后台保持），
        // 若把它也算进来，用户在 Android 13+ 上不想看通知就会被锁在「申请权限」这一步。
        viewModel.updatePermissions(
            permissions[Manifest.permission.BLUETOOTH_CONNECT] == true &&
                    permissions[Manifest.permission.BLUETOOTH_SCAN] == true
        )
    }

    // 注册广播接收器
    DisposableEffect(context) {
        // 蓝牙状态变化广播
        val stateFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(viewModel.bluetoothStateReceiver, stateFilter)

        // 蓝牙扫描广播
        val scanFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(viewModel.scanReceiver, scanFilter)

        // 初始化 ViewModel
        viewModel.initialize(context)

        onDispose {
            // 注销广播接收器
            context.unregisterReceiver(viewModel.bluetoothStateReceiver)
            context.unregisterReceiver(viewModel.scanReceiver)

            // 停止扫描
            viewModel.stopScan()
        }
    }

    // 回到本页（从控制界面返回、从设置页返回、从后台回前台）时把列表刷成「干净的一次」：
    // 已保存设备可能刚多了一个刚配对成功的设备，附近设备则是上一次扫描的残留。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshOnResume(context)
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top
    ) {
        val dimens = LocalAppDimens.current

        // 顶部控制区域
        Box(
            modifier = Modifier.fillMaxWidth().padding(dimens.screenPadding),
            contentAlignment = Alignment.Center
        ) {
            when {
                !hasBluetoothPermission -> {
                    PrimaryButton(
                        text = stringResource(id = R.string.request_bluetooth_permission),
                        onClick = {
                            requestPermissionLauncher.launch(requiredPermissions())
                        }
                    )
                }
                !isBluetoothEnabled -> {
                    PrimaryButton(
                        text = stringResource(id = R.string.turn_on_bluetooth),
                        onClick = {
                            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            context.startActivity(enableBtIntent)
                        }
                    )
                }
                else -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // 扫描中用琥珀色、就绪用绿色 —— 顶栏之外的第二处状态语义色
                        StatusDot(
                            status = if (isScanning) ConnStatus.WARN else ConnStatus.OK,
                            label = if (isScanning) {
                                stringResource(id = R.string.scanning)
                            } else {
                                stringResource(id = R.string.bluetooth_ready)
                            },
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(dimens.sectionGap))
                        PrimaryButton(
                            text = if (isScanning) {
                                stringResource(id = R.string.stop_scanning)
                            } else {
                                stringResource(id = R.string.start_scanning)
                            },
                            onClick = { viewModel.toggleScan() },
                            // 只保证最小宽度：文案偏长时按钮跟着变宽，而不是把文字折行
                            modifier = Modifier.widthIn(min = 160.dp)
                        )
                    }
                }
            }
        }

        // 底部显示区域
        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            if (!hasBluetoothPermission) {
                // 应用功能说明
                ElevatedCard(
                    modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 4.dp),
                    level = TintLevel.SURFACE,
                ) {
                    Text(
                        text = stringResource(id = R.string.app_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                // 点设备：目标就是当前这台直接进控制页（那边会复用已有链路）；
                // 连着另一台才需要先问一句 —— 换设备意味着把旧的拆掉
                val openDevice: (BluetoothDevice) -> Unit = { device ->
                    if (hidState.hasActiveLink &&
                        connectedAddress != null &&
                        connectedAddress != device.address
                    ) {
                        pendingSwitch = device
                    } else {
                        startControlActivity(context, device.address, device.name)
                    }
                }

                // 扫描列表
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // 正在保持的连接放在最前面：它是「当前状态」，比「可选项」更该先看到
                    if (hidState.hasActiveLink) {
                        item {
                            ScannerConnectionCard(
                                deviceName = connectedName ?: unknownDevice,
                                state = hidState,
                                onOpenControls = {
                                    startControlActivity(context, connectedAddress, connectedName)
                                },
                                onDisconnect = { showHidDisconnectDialog = true },
                            )
                        }
                    }

                    // 已保存的设备置顶
                    if (savedDevices.isNotEmpty()) {
                        item {
                            SectionTitle(
                                text = stringResource(id = R.string.saved_devices),
                                modifier = Modifier.padding(
                                    start = dimens.screenPadding,
                                    end = dimens.screenPadding,
                                    top = dimens.sectionGap
                                )
                            )
                        }
                        items(savedDevices) { device ->
                            BluetoothDeviceItem(device = device, onClick = openDevice)
                        }
                    }

                    // 扫描到的设备
                    if (scannedDevices.isNotEmpty()) {
                        item {
                            SectionTitle(
                                text = stringResource(id = R.string.nearby_devices),
                                modifier = Modifier.padding(
                                    start = dimens.screenPadding,
                                    end = dimens.screenPadding,
                                    top = dimens.screenPadding
                                )
                            )
                        }
                        items(scannedDevices) { device ->
                            BluetoothDeviceItem(device = device, onClick = openDevice)
                        }
                    } else if (isBluetoothEnabled && !isScanning) {
                        item {
                            Text(
                                text = stringResource(id = R.string.click_to_scan),
                                modifier = Modifier.padding(16.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }

    // 换设备确认：已有保持的连接时，点另一台设备先问一句
    pendingSwitch?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text(stringResource(id = R.string.scanner_switch_title)) },
            text = {
                Text(
                    stringResource(
                        id = R.string.scanner_switch_text,
                        connectedName ?: unknownDevice,
                        target.name ?: unknownDevice,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingSwitch = null
                    startControlActivity(context, target.address, target.name)
                }) {
                    Text(stringResource(id = R.string.scanner_switch_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSwitch = null }) {
                    Text(stringResource(id = R.string.cancel))
                }
            }
        )
    }

    // 卡片上的「断开连接」：与控制页那个按钮同一件事，确认之后才真的拆链
    DisconnectDialog(
        show = showHidDisconnectDialog,
        onDismiss = { showHidDisconnectDialog = false },
        onConfirm = { hidViewModel.cleanup() }
    )
}

/**
 * 本页要申请的运行时权限。
 *
 * 通知权限只在 Android 13+ 存在：在更低版本上把它放进请求数组会被直接判为拒绝，
 * 反而把「蓝牙权限已授予」这个判断带偏。
 */
private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.BLUETOOTH_CONNECT)
    add(Manifest.permission.BLUETOOTH_SCAN)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

/** 进控制页。连接复用 / 重连的判断都在 ViewModel 的 connectToDevice 里 */
private fun startControlActivity(context: Context, address: String?, name: String?) {
    if (address == null) return
    val intent = Intent(context, ControlActivity::class.java).apply {
        putExtra(ControlActivity.EXTRA_DEVICE_ADDRESS, address)
        putExtra(ControlActivity.EXTRA_DEVICE_NAME, name)
    }
    context.startActivity(intent)
}

@SuppressLint("MissingPermission")
@Composable
fun BluetoothDeviceItem(device: BluetoothDevice, onClick: (BluetoothDevice) -> Unit) {
    val tokens = LocalAppTokens.current
    val dimens = LocalAppDimens.current

    ElevatedCard(
        modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 4.dp),
        level = TintLevel.SURFACE,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Default.Bluetooth,
                contentDescription = stringResource(id = R.string.bluetooth_device),
                tint = tokens.accent,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name ?: stringResource(id = R.string.unknown_device),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            PrimaryButton(
                text = stringResource(id = R.string.connect),
                onClick = { onClick(device) },
                // 不设固定宽度：按钮随文案自然撑开（中文「连接」窄、英文 Connect 宽），
                // 后续新增语言也不会被挤成两行
            )
        }
    }
}
