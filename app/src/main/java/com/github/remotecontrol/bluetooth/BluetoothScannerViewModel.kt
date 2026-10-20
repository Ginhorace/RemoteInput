package com.github.remotecontrol.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 蓝牙相关 API 均要求 BLUETOOTH_CONNECT / BLUETOOTH_SCAN 运行时权限。
 * 权限由本页 UI 申请（见 BluetoothScannerScreen），结果经 [updatePermissions] /
 * [checkPermissions] 写入 [hasBluetoothPermission]，所有调用点均已由该状态守卫，
 * 但 Lint 无法跨函数推断，故在类级别统一抑制 MissingPermission。
 *
 * 用 [AndroidViewModel] 而不是 [androidx.lifecycle.ViewModel]：需要在构造期就拿到
 * Context 把权限 / 蓝牙状态读一遍，好让首帧就画对（见 init 的说明）。
 */
@SuppressLint("MissingPermission")
class BluetoothScannerViewModel(application: Application) : AndroidViewModel(application) {

    // 状态管理
    private val _hasBluetoothPermission = MutableStateFlow(false)
    val hasBluetoothPermission: StateFlow<Boolean> = _hasBluetoothPermission

    private val _isBluetoothEnabled = MutableStateFlow(false)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _scannedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BluetoothDevice>> = _scannedDevices

    private val _savedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val savedDevices: StateFlow<List<BluetoothDevice>> = _savedDevices

    private var bluetoothAdapter: BluetoothAdapter? = null

    init {
        // 权限与蓝牙状态必须在首帧之前读出来：它们直接决定首帧画「申请权限」还是「开始扫描」。
        // 放在 UI 的 DisposableEffect 里查就晚了一帧 —— 组合提交后 effect 才执行，
        // 于是已授权的用户每次打开都会先看到权限按钮闪一下。ViewModel 是在组合过程中
        // 创建的，init 里读到的值就是首帧读到的值，不会再有一帧的错位。
        // （读 adapter 与查权限都不需要 Activity，用 Application 即可，也不持有 Activity 引用）
        bluetoothAdapter = getBluetoothAdapter(application)
        refreshState(application)
    }

    // 蓝牙状态广播接收器
    val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                _isBluetoothEnabled.value = state == BluetoothAdapter.STATE_ON

                // 当蓝牙状态变化时，重新获取已配对设备
                if (state == BluetoothAdapter.STATE_ON && _hasBluetoothPermission.value) {
                    updateSavedDevices()
                }
            }
        }
    }

    // 蓝牙扫描广播接收器
    val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let {
                        if (device.type != BluetoothDevice.DEVICE_TYPE_LE &&
                            !_scannedDevices.value.contains(it) && !_savedDevices.value.contains(it)
                        ) {
                            _scannedDevices.value += it
                        }
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isScanning.value = false
                }
            }
        }
    }

    /**
     * UI 每次重新注册广播接收器时调用：补取 adapter（首次必然已在 init 里取到），
     * 并把权限、蓝牙状态、已保存设备重新读一遍。
     */
    fun initialize(context: Context) {
        if (bluetoothAdapter == null) {
            bluetoothAdapter = getBluetoothAdapter(context)
        }
        refreshState(context)
    }

    /** 权限 → 蓝牙状态 → 已保存设备，一次刷完；每一步都自带权限守卫。 */
    private fun refreshState(context: Context) {
        checkPermissions(context)
        refreshBluetoothState()
        if (_hasBluetoothPermission.value && _isBluetoothEnabled.value) {
            updateSavedDevices()
        }
    }

    /**
     * 重新读一遍蓝牙权限。
     *
     * **不含 POST_NOTIFICATIONS**：通知权限只影响连接期间那条常驻通知（Android 13+），
     * 被拒时连接照旧在后台保持。把它算进来会让用户「不想看通知」就直接失去扫描页的所有功能，
     * 它的申请时机另在控制页「连接成功」那一刻（见 ControlScreen），同样不做门禁。
     */
    fun checkPermissions(context: Context) {
        _hasBluetoothPermission.value =
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 重读蓝牙开关状态。
     *
     * Android 12+ 上 `BluetoothAdapter.isEnabled` / `bondedDevices` 都要求 BLUETOOTH_CONNECT：
     * 蓝牙关闭时适配器服务还没起来，这两个调用会直接返回 false / 空列表而不查权限；
     * 蓝牙已开就会走到 Binder，无权限时抛 SecurityException。
     * 于是「第一次打开、还没授予权限、而蓝牙已经是开的」正好踩在会查权限的这条路径上 ——
     * 首次启动（initialize）和从其他页面返回（refreshOnResume）都会闪退。
     *
     * 因此没权限时不去问状态：此时 UI 只显示「申请权限」按钮，本就用不上这个状态；
     * 有权限时仍包一层 runCatching，兜住个别 ROM 的差异化实现与权限被中途收回的情况。
     */
    private fun refreshBluetoothState() {
        val adapter = bluetoothAdapter
        if (adapter == null || !_hasBluetoothPermission.value) {
            _isBluetoothEnabled.value = false
            return
        }
        _isBluetoothEnabled.value = runCatching { adapter.isEnabled }.getOrDefault(false)
    }

    fun updatePermissions(granted: Boolean) {
        _hasBluetoothPermission.value = granted
        if (!granted) {
            // 权限被拒：清掉只能在有权限时读出来的列表，别留下上一轮的残留
            _isBluetoothEnabled.value = false
            _savedDevices.value = emptyList()
            _scannedDevices.value = emptyList()
            return
        }
        // 刚拿到权限，之前没权限时读到的蓝牙状态不可信，要重读一次，
        // 否则蓝牙明明开着，界面却停在「打开蓝牙」按钮上
        refreshBluetoothState()
        if (_isBluetoothEnabled.value) {
            updateSavedDevices()
        }
    }

    private fun updateSavedDevices() {
        if (!_hasBluetoothPermission.value) return
        val adapter = bluetoothAdapter ?: return
        _savedDevices.value = runCatching { adapter.bondedDevices.toList() }.getOrDefault(emptyList())
    }

    /**
     * 回到扫描界面（含从控制界面返回、从设置页返回、从后台回前台）时调用。
     *
     * 三件事要一起做，页面才会处在「干净的一次」状态：
     * 1. 已保存设备可能刚多了一个刚配对成功的设备，必须重新读一次 `bondedDevices`；
     * 2. 附近设备是上一次扫描的残留，没必要留着；
     * 3. 若不停掉扫描，残留列表会被仍在运行的服务发现立刻重新填满，看起来像「没清掉」。
     *
     * 另外权限可能在别处被改（系统设置里关掉再回来），所以顺带重查一次权限再决定是否读设备。
     */
    fun refreshOnResume(context: Context) {
        // 首次组合时本回调可能先于 initialize() 执行，此时还没拿到 adapter，刷新没有意义
        if (bluetoothAdapter == null) return
        refreshState(context)
        stopScan()
        _scannedDevices.value = emptyList()
    }

    fun toggleScan() {
        viewModelScope.launch {
            if (_isScanning.value) {
                stopScan()
            } else {
                startScan()
            }
        }
    }

    private fun startScan() {
        val adapter = bluetoothAdapter ?: return
        if (!_hasBluetoothPermission.value || !_isBluetoothEnabled.value) return
        _scannedDevices.value = emptyList()
        // 发起失败就不置为扫描中：否则等不到 ACTION_DISCOVERY_FINISHED，按钮一直停在「停止扫描」
        if (runCatching { adapter.startDiscovery() }.getOrDefault(false)) {
            _isScanning.value = true
        }
    }

    fun stopScan() {
        bluetoothAdapter?.let { adapter ->
            if (_hasBluetoothPermission.value && _isBluetoothEnabled.value) {
                runCatching { adapter.cancelDiscovery() }
            }
        }
        _isScanning.value = false
    }

    override fun onCleared() {
//        super.onCleared()
        // 清理资源
        stopScan()
        // 注意：广播接收器需要在 Activity/Fragment 中手动注销
    }

    private fun getBluetoothAdapter(context: Context): BluetoothAdapter? {
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        return bluetoothManager.adapter
    }
}