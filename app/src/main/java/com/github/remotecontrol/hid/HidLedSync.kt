package com.github.remotecontrol.hid

import android.util.Log
import com.github.remotecontrol.util.KeyboardConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 键盘灯（Num / Caps / Scroll）与「这台主机理不理 Num Lock」的判断。
 *
 * 单独成文件是因为它是一整块自洽的事：灯的**值只由主机定**（LED 是主机 → 设备的
 * Output Report，`BluetoothHidDevice` 里没有让设备查询输出报告的请求），应用侧能做的只有
 * 记下主机说过什么，以及主动制造一次变化去换它开口。挂在状态机里会把连接流程淹掉。
 *
 * @param scope 跑「问灯态」那段动作的作用域（由 ViewModel 传 `viewModelScope`）
 * @param state 当前链路状态：既用来判断还能不能发报告，也用来判断回话还有没有意义
 * @param tapKey 按一次某键（按下 + 抬起两条报告，不带修饰位），由 ViewModel 提供 —— 只有那里持有 HID 代理
 */
internal class HidLedSync(
    private val scope: CoroutineScope,
    private val state: StateFlow<HidState>,
    private val tapKey: (KeyboardConstants.Key) -> Unit,
) {

    /**
     * 键盘灯状态（Num / Caps / Scroll）。
     *
     * 初值是「三个灯全灭 + 未确认」：主机不下发时这是界面唯一的初值，
     * 而实体键盘的这三盏灯本来就默认不亮，绝大多数情况下它是对的。
     */
    private val _leds = MutableStateFlow(KeyboardConstants.KeyboardLedsState())

    /**
     * 键盘灯状态（Num / Caps / Scroll）+ 这份值是不是主机确认过的。
     *
     * 主机什么时候下发由它决定（Windows 在 Caps / Num **变化**时下发，连接时不一定），
     * 设备侧也没有任何查询手段，所以「主机还没说过」这件事必须能被表达出来 ——
     * 见 [KeyboardConstants.KeyboardLedsState]。
     */
    val leds: StateFlow<KeyboardConstants.KeyboardLedsState> = _leds

    /**
     * 「连 Num Lock 都敲不动这台主机」的提示，由 [sync] 在没等到 LED 报告时点亮。
     *
     * 界面把它读成「连接的设备可能不支持小键盘」：Num Lock 正是小键盘数字键的开关，主机连它的
     * 锁定状态都不维护，小键盘这一路基本也就没有 —— 而模板的 `Alt Code` 输入全靠小键盘
     * （`<code>` 默认走 `Numpad*`，见 `MacroTemplateExpander.digitKeyName`），
     * 那时会静默打不出字符，所以宁可提前说一句。
     *
     * 主机一旦开过口（收到任何 LED 报告）就清掉：这条提示说的是「这台主机从不开口」，
     * 不是「灯没亮」—— 后者有自己的表达方式（空心圈，见 [KeyboardConstants.KeyboardLedsState]）。
     */
    private val _numpadWarning = MutableStateFlow(false)

    /** 见 [_numpadWarning] */
    val numpadWarning: StateFlow<Boolean> = _numpadWarning

    /**
     * 本次连接是否已经主动问过灯态（见 [sync]）。
     *
     * 主机一经开口，之后它自己每次锁定状态变化都会推过来，重复问只会白让主机那颗键闪一下，
     * 所以每次连接只做一次。由连接建立时重置（见 [onConnected]）。
     */
    private var synced = false

    /**
     * 记录主机下发的键盘灯状态。
     *
     * 主机只走 SET_REPORT 或中断通道其中一条，但哪一条因主机而异，
     * 所以 ViewModel 把两个回调都接进来。
     */
    fun onHostReport(data: ByteArray?) {
        val leds = KeyboardConstants.parseKeyboardLeds(data) ?: return
        // 主机开口了，「它不理 Num Lock」这个结论就不成立，提示该收回去
        _numpadWarning.value = false
        val current = _leds.value
        if (current.leds != leds || !current.confirmed) {
            _leds.value = KeyboardConstants.KeyboardLedsState(leds, confirmed = true)
            Log.d(
                "BluetoothHid",
                "Keyboard LEDs confirmed by host: num=${leds.numLock} caps=${leds.capsLock} scroll=${leds.scrollLock}"
            )
        }
    }

    /**
     * 本地推断：锁键按一下会翻转主机那边的锁定状态（主机随后把新的灯态推回来）。
     *
     * 主机还没确认过灯态时先翻一下，界面才不至于一直停在未确认的初值上。
     *
     * @param keyCode 报告第 2 字节的按键 Usage ID（见 `KeyboardConstants.KeyboardReport.toByteArray`）；
     *   抬起报告、修饰键报告、空报告的这个字节都是 0，会被原样返回，不影响别的按键
     */
    fun onLocalKeyPress(keyCode: Byte) {
        _leds.value = _leds.value.inferredAfterPress(keyCode)
    }

    /**
     * 新的一次连接开始。
     *
     * 必须在 ViewModel 把状态切到 [HidState.Connected] **之前**调用 —— 状态一改，
     * 等在 [sync] 里的协程就会被唤醒去读这些标志，写在后面会读到上一次连接的旧值。
     */
    fun onConnected() {
        synced = false
        // 上一次连接留下的「小键盘」结论同样作废：这一次要重新敲、重新看
        _numpadWarning.value = false
        // confirmed 也要跟着降级：它表示「**本次连接**里主机说过」，不是「曾经说过」。
        // 留着上一次链路的确认值，灯条会先摆一屏冒充真值的实心点，
        // 问灯态那一步也会误判成「主机已经回过话」
        _leds.value = _leds.value.demoted()
    }

    /** 收摊（cleanup）：主机那个灯态不再代表现状，降级成推测值留着 —— 下次连上立刻有东西可显示 */
    fun onCleanup() {
        _leds.value = _leds.value.demoted()
        synced = false
    }

    /** 用户关掉「小键盘可能不可用」的提示（见 [_numpadWarning]） */
    fun dismissNumpadWarning() {
        _numpadWarning.value = false
    }

    /**
     * 主动让主机把三盏灯的真值说出来（只由键盘页调用，见 `KeyboardModeScreen`）。
     *
     * ## 为什么要主动问
     * 主机只在两种时候下发 LED Output Report：**它自己的锁定状态变化**时，以及设备在它眼里
     * 是新的（刚配对、它走了一轮设备启动）时。普通重连它一声不吭 —— 实测日志里连接过程连
     * 一条 `GET_REPORT` / `SET_REPORT` 都没有；而 HID 里**没有让设备查询输出报告的请求**，
     * 硬件键盘也不需要它：硬件键盘每次插入都必然触发一轮设备启动，主机在那一步把指示灯状态
     * 写下去。蓝牙设备没有这个待遇，所以只能自己制造一次变化去换主机开口。
     *
     * ## 做法
     * 把 [SYNC_KEY]（Num Lock）连按两下。每按一下主机就推一帧完整的 1 字节位图，
     * 两下的净效果为零，所以推回来的最终值就是三个灯的真值。
     *
     * 选 Num Lock 而不是 Scroll Lock，是因为它**一物两用**：它的锁定状态直接决定小键盘
     * 发的是数字还是方向键，任何要收数字的系统都得维护它（兼容性接近 Caps Lock，而对输入的
     * 影响比 Caps 小）；更关键的是它顺带就是一次**小键盘探针** —— 主机连它都不理，小键盘
     * 多半也不可用，那就该在用户去模板里按 Alt Code 之前先说一声（见 [_numpadWarning]）。
     *
     * ## 两下、以及等回话
     * 两下必须都在 [NonCancellable] 里完成：中途被取消就会只按到一下，把主机那颗锁键
     * **永久留在反的状态**上。整段动作跑在 [scope] 里而不是调用方的协程里：
     * 用户在等回话的这一小段里切走键盘页，不该影响「这台主机理不理 Num Lock」这个判断。
     *
     * 只在键盘页触发，且每次连接只做一次 —— 主机一经开口，之后它每次变化都会自己推过来，
     * 重复问只是白让主机那颗键闪一下。
     */
    fun sync() {
        if (synced) return
        if (state.value !is HidState.Connected) return

        synced = true
        scope.launch {
            Log.d("BluetoothHid", "Syncing keyboard LEDs by tapping ${SYNC_KEY.displayName} twice")
            withContext(NonCancellable) {
                tapKey(SYNC_KEY)
                // 留出间隔：让两次按键在主机那边是两件独立的事，也就是两帧独立的下发
                delay(TAP_GAP_MS)
                tapKey(SYNC_KEY)
            }

            // 等主机回话：锁定状态一变，它就会推一帧整份位图回来（见 [onHostReport]）
            val answered = withTimeoutOrNull(WAIT_MS) {
                _leds.first { it.confirmed }
            } != null

            // 一个字都没回来：这台主机连 Num Lock 都不理
            if (!answered && state.value is HidState.Connected) {
                Log.w(
                    "BluetoothHid",
                    "No LED report after tapping ${SYNC_KEY.displayName}, host may not have a numpad"
                )
                _numpadWarning.value = true
            }
        }
    }

    private companion object {
        /**
         * 主动问灯态时用来「碰一下主机」的那颗锁键。
         *
         * 三个锁定键都行（主机在任何锁定状态变化时都会推整份位图），选 Num Lock 是因为它
         * **一物两用**：既换主机开口，又顺带探出这台主机认不认小键盘（见 [sync]）。
         * Caps Lock 的兼容性一样好，代价却更大 —— 同步那两下会把大写真的切来切去，输入里看得见。
         */
        val SYNC_KEY = KeyboardConstants.Keys.NUM_LOCK

        /** 两次「碰」之间的间隔：留给主机处理第一次、并按新状态推一帧回来 */
        const val TAP_GAP_MS = 150L

        /**
         * 敲完两下之后等主机把 LED 报回来；超时即认为它不理这颗键（见 [sync]）。
         *
         * 正常主机几毫秒就回，取 1 秒是为了给慢一点的实现留余地：漏报成「不支持小键盘」
         * 比晚一秒提示更糟，而真报晚了也会被 [onHostReport] 一句话收回去。
         */
        const val WAIT_MS = 1_000L
    }
}
