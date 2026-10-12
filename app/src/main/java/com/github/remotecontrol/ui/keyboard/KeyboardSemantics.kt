package com.github.remotecontrol.ui.keyboard

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import com.github.remotecontrol.util.KeyLegend
import com.github.remotecontrol.util.KeyboardConstants
import kotlin.math.roundToInt

/**
 * 键盘画布的无障碍语义层。
 *
 * ## 为什么需要单独一层
 * 键位是自绘的（`drawBehind` 一次画完整块键盘），语义树上整块画布只有**一个**节点，
 * 读屏既探索不到单个键、也没法逐项导航 —— TalkBack 靠每个节点的屏幕矩形判断手指落在
 * 哪个元素上，「逐键可读」就必须有逐键的真实语义节点。Android 侧本可以用
 * `AccessibilityNodeProvider`（`ExploreByTouchHelper`）在**一个 View 里**暴露虚拟节点，
 * 但 Compose 没有暴露这条 API，`Modifier.semantics` 只能挂在真实节点上；
 * 因此这里在自绘层之上叠一层**只有布局与语义、不画任何东西**的透明节点。
 *
 * ## 代价控制
 * - 位置直接复用 [KeyboardHitArea]（即 [buildKeyboardGeometry] 的结果：行序、跨行键高、
 *   占位键剔除都已算好），不引入第二套布局逻辑，语义与视觉不可能走叉；
 * - 节点只在**触摸探索**开启时才创建（见 [rememberTouchExplorationEnabled]）：常规操作下
 *   这一层完全不存在，「不为每个键建 composable」的性能前提不受影响；
 * - 播报的是**键帽图例**（[KeyLegend.label]）而不是 HID Usage ID —— `0x04` 这种数字没人
 *   记得住；且非 US 宿主布局下「印的字」本来就和「发出的位置」不是一回事（见
 *   `HostKeyboardLayout`），播报图例恰好是「所见即所得」。
 */

/**
 * 一个键位的无障碍语义。
 *
 * @param value 该键的 HID Usage ID，读屏激活时原样交给按键回调
 * @param description 播报给读屏的文字（键帽图例）
 * @param rect 在画布坐标系里的位置（px），与绘制层用的是同一份矩形
 */
internal data class KeyboardSemanticItem(
    val value: Byte,
    val description: String,
    val rect: Rect,
)

/**
 * 由命中区域与图例算出语义项。纯函数，可直接单测（见 KeyboardSemanticsTest）。
 *
 * 顺序即 [hitAreas] 的顺序（按行、行内按权重），与视觉上的键盘排布一致，
 * 读屏的遍历顺序因此天然正确。
 */
internal fun buildKeyboardSemantics(
    hitAreas: List<KeyboardHitArea>,
    legends: Map<KeyboardConstants.Key, KeyLegend>,
): List<KeyboardSemanticItem> = hitAreas.map { area ->
    KeyboardSemanticItem(
        value = area.key.value,
        // 图例缺失时回落到键位自身的 displayName（正常不会发生，图例表覆盖所有位置）
        description = legends[area.key]?.label ?: area.key.displayName,
        rect = area.rect,
    )
}

/**
 * 触摸探索（TalkBack / Switch Access 等读屏的触摸模式）当前是否开启。
 *
 * 用 `isTouchExplorationEnabled` 而不是 `isEnabled`：后者只要挂了任何无障碍服务就为 true，
 * 而会把触摸事件全部拦走的只有触摸探索这一类。
 *
 * 注：注册监听走单参重载 —— 当前 compileSdk 上它已被标记弃用（新形态要求额外的
 * Handler），但新形态在低版本上不存在，所以这里 @Suppress("DEPRECATION") 压掉警告。
 */
@Composable
internal fun rememberTouchExplorationEnabled(): Boolean {
    val view = LocalView.current
    var enabled by remember(view) { mutableStateOf(false) }

    DisposableEffect(view) {
        val manager = view.context.getSystemService(AccessibilityManager::class.java)
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        enabled = manager?.isTouchExplorationEnabled == true
        if (manager != null) {
            @Suppress("DEPRECATION")
            manager.addTouchExplorationStateChangeListener(listener)
        }
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }

    return enabled
}

/**
 * 透明语义层：把每个键位摆成一个只有语义的节点，叠在自绘键盘之上。
 *
 * 容器铺满画布是为了让所有子节点都落在容器内（子节点用 offset 相对容器左上角定位，
 * 与绘制几何共用同一个坐标系）。
 *
 * @param items 由 [buildKeyboardSemantics] 算出
 * @param onActivate 读屏激活某键时回调（参数为 HID Usage ID）
 */
@Composable
internal fun KeyboardSemanticsLayer(
    items: List<KeyboardSemanticItem>,
    onActivate: (Byte) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val currentOnActivate by rememberUpdatedState(onActivate)

    Box(
        modifier = modifier
            .fillMaxSize()
            // 遍历分组：读屏按子节点顺序（行序）走，不会漏进页签 / 修饰键条之间
            .semantics { isTraversalGroup = true },
    ) {
        items.forEach { item ->
            Box(
                modifier = Modifier
                    .offset { IntOffset(item.rect.left.roundToInt(), item.rect.top.roundToInt()) }
                    .size(
                        width = with(density) { item.rect.width.toDp() },
                        height = with(density) { item.rect.height.toDp() },
                    )
                    .semantics {
                        contentDescription = item.description
                        role = Role.Button
                        // 动作名不写死："双击以激活" 由系统按界面语言播报，
                        // 写死中文会在其他语言下露出
                        onClick {
                            currentOnActivate(item.value)
                            true
                        }
                    },
            )
        }
    }
}
