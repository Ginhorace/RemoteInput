package com.github.remotecontrol.hid

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * **三条链路二选一**：经典蓝牙 / BLE / 局域网（USB/IP）同一时刻只用一条。
 *
 * ## 语义
 * 启动某条链路时（蓝牙点「连接」、BLE 点「开始广播」、局域网点「开始监听」），
 * 其它还占着的链路让位：蓝牙让位 = `cleanup()`（注销 HID 应用、拆链），
 * BLE 让位 = `stopBroadcast()`（停广播、关 GATT 服务），局域网让位 = `stopListening()`
 * （停止监听，电脑那边看到设备被拔出）。**后启动的那条赢**，符合用户当下的意图。
 *
 * ## 为什么做成登记式的协调器，而不是让链路互相调用
 * - **不产生包级循环依赖**：这里只认 [Link] 这个抽象（`engaged` + `yield()`），
 *   各条链路在 `init` 里登记自己，因此 `hid/` 不反向依赖 `bluetooth/` / `ble/` / `usbip/`，
 *   链路之间也互不认识；
 * - **不散在界面里**：「点按钮时顺手关掉另一条」会漏掉主机主动发起连接的路径
 *   （蓝牙主机重连、电脑自己 attach），链路规则应该只写一处。
 *
 * ## 触发口径
 * 只在 `engaged` **跳变为 true** 时触发一次：某条链路中途刷新状态（蓝牙 `Connecting → Connected`）
 * 不会再触发一轮。让位方只会把自己置为空闲，因此**不会互相收摊成环**。
 *
 * 唯一的竞态是多条链路几乎同时跳变为 true（用户手速极快、或在极短窗口内多处都动）：
 * 那时可能两边互相让位、最后都空闲。这一步只会「什么都没连着」，不会留下半死不活的链路，
 * 用户重按一次即可 —— 代价远小于为此引入一把全局锁。
 *
 * 登记常驻：各条链路都是进程级单例，正常路径下不会 [unregister]；这个方法留给以后
 * 需要「某条链路在运行期彻底退出」的场景。
 */
object LinkCoordinator {

    /**
     * 一条链路的自我声明。
     *
     * 由各链路的 ViewModel 实现并登记，协调器从此不再了解任何链路细节。
     */
    interface Link {

        /** 这条链路现在「占住」了吗；见 [HidState.hasActiveLink] 与 [HidState.isListening] */
        val engaged: StateFlow<Boolean>

        /** 让位：把自己收掉（蓝牙 / BLE `cleanup()`，局域网 `stopListening()`） */
        fun yield()
    }

    /** 协调器自己的作用域：进程级常驻，没有可取消它的时机 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 登记表。只在主线程读写（各 ViewModel 的 `init` 与收集协程都在主线程） */
    private val entries = LinkedHashMap<String, Entry>()

    private class Entry(val name: String, val link: Link, var job: Job? = null)

    /**
     * 登记一条链路。
     *
     * 从这一刻起它既有「让位给别人」的义务，也有「把别人赶走」的权利。
     * 同一个 [name] 重复登记直接忽略（幂等）。
     */
    fun register(name: String, link: Link) {
        if (entries.containsKey(name)) return
        val entry = Entry(name, link)
        entries[name] = entry
        // StateFlow 只在值真的变化时发射，所以这里天然就是「跳变为 true 才触发一次」
        entry.job = scope.launch {
            link.engaged.collect { engaged ->
                if (engaged) releaseOthers(name)
            }
        }
    }

    /** 注销一条链路（正常路径用不到，见类注释） */
    fun unregister(name: String) {
        entries.remove(name)?.job?.cancel()
    }

    private fun releaseOthers(name: String) {
        entries.values.forEach { other ->
            if (other.name == name) return@forEach
            if (other.link.engaged.value) {
                Log.d(TAG, "$name engaged, releasing ${other.name}")
                other.link.yield()
            }
        }
    }

    private const val TAG = "HidLink"
}
