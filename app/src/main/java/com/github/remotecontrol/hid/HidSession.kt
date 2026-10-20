package com.github.remotecontrol.hid

import com.github.remotecontrol.util.KeyboardConstants
import kotlinx.coroutines.flow.StateFlow

/**
 * 一条 HID 链路对**界面**暴露的全部能力。
 *
 * ## 它为什么存在
 * 经典蓝牙 / 局域网（USB/IP）两条链路的差别只在「报告怎么送出去」；键盘 / 触摸板 / 模板三种界面、
 * 键盘灯条、错误弹窗对两条链路是一样的。界面只认这一个接口，各条链路各自实现它，
 * 于是同一份界面能被两条链路复用 —— `usbip/ui/` 那一层「改界面要改两份」的副本由此取消。
 *
 * ## 成员是怎么定下来的
 * 它们**逐字**取自各实现里本来就已经一致的成员（`BluetoothHidViewModel` /
 * `UsbIpInputViewModel`），没有为这个接口新造任何方法。
 * 同名但语义不同的一处是 [dismissError] 与 [cleanup]：接口只保证签名，出口由各链路自己定 ——
 * 蓝牙的注册失败是系统级终态（状态保持不变），局域网的监听失败则回到「没在监听」；
 * 蓝牙的 [cleanup] 要注销 HID 应用并关掉 profile 代理，局域网只是停掉监听。
 *
 * ## 刻意不进接口的东西
 * - **`getInstance()`**：两者都是进程级单例，但构造方式略有不同 —— 局域网那个要多一个 Context
 *   （监听端口是可配置的设置项，得从偏好读），
 *   由调用方（各自的入口）直接取自己那一份，接口不必知道；
 * - **链路专属动作**：`initialize` / `connectToDevice` / `connectedDeviceName`（蓝牙）与
 *   `startListening` / `localAddress` / `port`（局域网）只出现在各自的入口与卡片里，
 *   界面主体用不到。
 *
 * 蓝牙侧那些「界面一挂上就要做的事」（初始化 HID 应用、按设备地址发起连接、申请通知权限）
 * 不属于「界面读什么」，单独放在 `bluetooth/BluetoothLinkEffects.kt`，由蓝牙控制页自己的
 * `setContent` 里调用；局域网那条链路的控制页没有对应的东西。
 */
/** 指针形态，见 [HidSession.pointerKind] */
enum class PointerKind {
    /** 相对位移鼠标（经典蓝牙链路）：4 字节报告，带滚轮与侧键 */
    MOUSE,

    /** 绝对坐标 PTP 触摸板（局域网链路）：9 字节报告，多指 + Feature 5/6 */
    TOUCHPAD,
}

interface HidSession {

    /**
     * 这条链路的指针形态：相对位移的鼠标，还是绝对坐标的触摸板。
     *
     * 界面靠它决定渲染哪一个指针页面（`ui/MouseModeScreen` / `ui/TouchpadModeScreen`），
     * 以及侧栏那个模式按钮的图标与文案 —— 两种形态的报告结构完全不同
     * （4 字节相对位移 vs 9 字节绝对坐标），界面不能只靠同一个页面「猜」。
     *
     * 形态由链路注册的描述符决定，运行期不变：经典蓝牙 = [PointerKind.MOUSE]，
     * 局域网 = [PointerKind.TOUCHPAD]。
     */
    val pointerKind: PointerKind

    /** 链路的唯一真源，取值见 [HidState] */
    val state: StateFlow<HidState>

    /** 键盘灯（Num / Caps / Scroll）与「这份值是不是主机确认过的」 */
    val keyboardLeds: StateFlow<KeyboardConstants.KeyboardLedsState>

    /** 「主机连 Num Lock 都不理」的提示（模板的 Alt Code 输入要靠小键盘） */
    val numpadWarning: StateFlow<Boolean>

    /**
     * 发一帧报告。
     *
     * @param reportId 报告 ID（见 `HidReportDescriptors`）
     * @param data 报告负荷；**报告 ID 不在这里**，各条链路自己按传输层约定补上
     */
    fun sendReport(reportId: Int, data: ByteArray)

    /** 补发一帧「无接触 / 无按键」的空报告（触摸板 + 键盘各一帧），切模式时清残留的按下状态 */
    fun sendNeutralReports()

    /** 主动让主机把三盏灯的真值说出来（只由键盘页调用） */
    fun syncKeyboardLeds()

    /** 用户关掉「小键盘可能不可用」的提示 */
    fun dismissNumpadWarning()

    /** 用户关掉失败弹窗 */
    fun dismissError()

    /** 收摊：拆链 / 停监听 */
    fun cleanup()
}
