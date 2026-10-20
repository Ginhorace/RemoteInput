package com.github.remotecontrol.usbip

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.github.remotecontrol.HidControlActivity
import com.github.remotecontrol.R
import com.github.remotecontrol.hid.HidSession

/**
 * 局域网（USB/IP）链路的控制页。
 *
 * 它只交代两件事：**控制哪个会话**（[session]）与**顶栏写什么**（本机地址:端口）。
 * 没有链路专属副作用 —— 这条链路的「连接」由电脑执行 `usbip attach` 发起，
 * 通知权限也由首屏卡片那条路径负责。
 *
 * 界面外壳（主题 / 语言 / 方向 / 系统栏 / 后退与断开两个出口）全在 [HidControlActivity]，
 * 与蓝牙那条链路共用同一份。
 */
class UsbIpControlActivity : HidControlActivity() {

    /**
     * 不用 `by viewModels()`：那是 Activity 作用域，页面一销毁监听就跟着停了。
     */
    private val viewModel: UsbIpInputViewModel = UsbIpInputViewModel.getInstance(this)

    override val session: HidSession get() = viewModel

    @Composable
    override fun controlTitle(): String {
        // 标题是「本机地址:端口」：电脑要 attach 的就是它，一眼能核对。
        // 地址是在开始监听那一刻才取到的，所以跟着 flow 走，而不是在这里读一次
        val address by viewModel.localAddress.collectAsState()
        val port by viewModel.port.collectAsState()
        return if (address.isNullOrEmpty()) {
            stringResource(R.string.lan_title)
        } else {
            "$address:$port"
        }
    }
}
