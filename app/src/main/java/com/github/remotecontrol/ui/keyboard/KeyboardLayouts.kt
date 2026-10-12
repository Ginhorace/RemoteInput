package com.github.remotecontrol.ui.keyboard

import com.github.remotecontrol.util.HostKeyboardLayout
import com.github.remotecontrol.util.KeyboardConstants
import com.github.remotecontrol.util.KeyboardConstants.Keys
import com.github.remotecontrol.util.KeyboardLayoutCatalog

/**
 * 键盘分页。
 *
 * 横竖屏都是两页，都靠**点页签**切换（键盘面不做滑动翻页，理由见 `KeyboardModeScreen`）：
 * - [MAIN] 主键盘：竖屏是上下两个半键盘，横屏是整块键盘；
 * - [FUNCTION_AND_NUMPAD] 功能键区 + 小键盘区：竖屏上下排（各占半高），横屏左右排（各占半宽）。
 *
 * 枚举顺序就是分页顺序，[KeyboardPage.entries] 可以直接当页数用。
 */
enum class KeyboardPage {
    /** 主键盘：竖屏为上下两个半键盘，横屏为整块键盘 */
    MAIN,

    /** 功能键区（PrtSc/ScrLk/Pause、Ins/Home/PgUp、Del/End/PgDn、方向键）与小键盘区同一页 */
    FUNCTION_AND_NUMPAD,
}

/**
 * 各页的键盘行定义。
 *
 * ## 为什么这里没有任何修饰键
 * Ctrl / Shift / Alt / Win 已从键盘页移出，固定在 `KeyboardModifierBar`（竖屏在底部、
 * 横屏在右侧竖条）—— 键盘页会翻页，修饰键必须待在不跟着动的地方。
 * 所以本文件里不会出现 `LEFT_CTRL` / `RIGHT_SHIFT` / `LEFT_GUI` 这些常量，
 * 由 `KeyboardLayoutsTest` 的断言守住这条约束。
 *
 * ## 竖屏为什么拆成上下两半
 * 竖屏宽度只够并排放半个键盘，单键会窄到拇指点不准；改成「左半在上、右半在下」之后，
 * 每一半都能铺满屏宽，单键宽度回到可点按的水平 —— 相当于双屏手机 / 平板的分离键盘，
 * 只是排布方向从左右换成了上下。
 *
 * 键宽由 [KeyboardConstants.Key.weight] 决定（同一行内按权重分配整行宽度），
 * 单键高度由 `KeyboardCanvas` 按容器高度自适应。
 *
 * ## 行定义与宿主键盘布局的关系
 * 这里的三份清单是**US 参考矩阵上的物理位置**，与语言无关；宿主布局只决定「这个位置印什么字」，
 * 那部分在 `util/KeyboardLayoutCatalog.kt`。唯一的例外是少数布局独有的键（ISO 第 105 颗、
 * 日语的 `変換` 等），它们由 [mainRows] 插到主键盘 ENTER 左边。
 *
 * 功能键区与小键盘区在任何布局下都一样，因此仍是与布局无关的常量。
 */
object KeyboardLayouts {

    /** 竖屏左半键盘：在上，行尾是 Space（修饰键移出后这一行只剩它，于是自然铺满整行） */
    val portraitLeftHalfRows: List<List<KeyboardConstants.Key>> = listOf(
        listOf(Keys.ESC, Keys.F1, Keys.F2, Keys.F3, Keys.F4, Keys.F5, Keys.F6),
        listOf(Keys.BACKTICK, Keys.NUM_1, Keys.NUM_2, Keys.NUM_3, Keys.NUM_4, Keys.NUM_5),
        listOf(Keys.TAB, Keys.Q, Keys.W, Keys.E, Keys.R, Keys.T),
        listOf(Keys.CAPS_LOCK, Keys.A, Keys.S, Keys.D, Keys.F, Keys.G),
        listOf(Keys.Z, Keys.X, Keys.C, Keys.V, Keys.B),
        listOf(Keys.SPACE),
    )

    /**
     * 竖屏右半键盘：在下，行尾是 Enter。
     *
     * Enter 从主行（HJKL 那一行）移到了第 6 行：修饰键移出后右半第 6 行本来会空着，
     * 与其留一条空行，不如把它交给 Enter，两半也就都是 6 行、行高对齐。
     */
    val portraitRightHalfRows: List<List<KeyboardConstants.Key>> = listOf(
        listOf(Keys.F7, Keys.F8, Keys.F9, Keys.F10, Keys.F11, Keys.F12),
        listOf(
            Keys.NUM_6, Keys.NUM_7, Keys.NUM_8, Keys.NUM_9, Keys.NUM_0,
            Keys.MINUS, Keys.EQUAL, Keys.BACKSPACE,
        ),
        listOf(
            Keys.Y, Keys.U, Keys.I, Keys.O, Keys.P,
            Keys.LEFT_BRACKET, Keys.RIGHT_BRACKET, Keys.BACKSLASH,
        ),
        listOf(Keys.H, Keys.J, Keys.K, Keys.L, Keys.SEMICOLON, Keys.QUOTE),
        listOf(Keys.N, Keys.M, Keys.COMMA, Keys.PERIOD, Keys.SLASH),
        listOf(Keys.ENTER),
    )

    /**
     * 布局占位键：只按权重占掉一行里的一段宽度，不绘制也不可点。
     *
     * 方向键的反 T 形排布靠它把 ↑ 摆在正中间，与上面几行的三列对齐。
     * 刻意不放进 [Keys]：那里会被 `charToKeyMap` 的反射遍历，也会成为模板里可写的键名。
     */
    private val spacer = KeyboardConstants.Key(displayName = "", value = 0, isSpacer = true)

    /** 功能键区（与「小键盘区」同在第二页；竖屏在页面上半，横屏在页面左半） */
    val functionRows: List<List<KeyboardConstants.Key>> = listOf(
        listOf(Keys.PRINT_SCREEN, Keys.SCROLL_LOCK, Keys.PAUSE),
        listOf(Keys.INSERT, Keys.HOME, Keys.PAGE_UP),
        listOf(Keys.DELETE, Keys.END, Keys.PAGE_DOWN),
        // 方向键按实体键盘的反 T 形摆放：↑ 单独一行、居中（两侧是占位），下一行 ← ↓ →
        listOf(spacer, Keys.ARROW_UP, spacer),
        listOf(Keys.ARROW_LEFT, Keys.ARROW_DOWN, Keys.ARROW_RIGHT),
    )

    /**
     * 小键盘区：按实体键盘的数字区摆放。
     *
     * ```
     *  NumLk  KP/    KP*    KP-
     *  KP7    KP8    KP9    KP+
     *  KP4    KP5    KP6    └─ KP+ 的下半
     *  KP1    KP2    KP3    KP Enter
     *  KP0（两格宽）  KP.    └─ Enter 的下半
     * ```
     *
     * - 首行左侧是 Num Lock，与实体数字区的排布一致；
     * - [KeyboardConstants.Key.rowSpan] = 2 的 KP+ / KP Enter 各自跨两行，下一行同列用占位键让位；
     * - [KeyboardConstants.Key.weight] = 2 的 KP0 占两格宽。
     */
    val numpadRows: List<List<KeyboardConstants.Key>> = listOf(
        listOf(Keys.NUM_LOCK, Keys.KP_DIVIDE, Keys.KP_MULTIPLY, Keys.KP_MINUS),
        listOf(Keys.KP_NUM_7, Keys.KP_NUM_8, Keys.KP_NUM_9, Keys.KP_PLUS.copy(rowSpan = 2)),
        listOf(Keys.KP_NUM_4, Keys.KP_NUM_5, Keys.KP_NUM_6, spacer),
        listOf(Keys.KP_NUM_1, Keys.KP_NUM_2, Keys.KP_NUM_3, Keys.KP_ENTER.copy(rowSpan = 2)),
        listOf(Keys.KP_NUM_0.copy(weight = 2f), Keys.KP_DOT, spacer),
    )

    /**
     * 横屏主键盘（第 1 页）。
     *
     * 键集与竖屏两个半键盘拼起来完全一致，差异只有 ENTER 的位置：横屏不分左右半，
     * ENTER 留在主行行尾 —— 与重写前的横屏观感保持一致（用户明确表示横屏主键盘「挺满意」）。
     * 第 6 行只剩 Space，因此自然铺满整行。
     */
    val landscapeMainRows: List<List<KeyboardConstants.Key>> = listOf(
        listOf(
            Keys.ESC, Keys.F1, Keys.F2, Keys.F3, Keys.F4, Keys.F5, Keys.F6,
            Keys.F7, Keys.F8, Keys.F9, Keys.F10, Keys.F11, Keys.F12,
        ),
        listOf(
            Keys.BACKTICK, Keys.NUM_1, Keys.NUM_2, Keys.NUM_3, Keys.NUM_4, Keys.NUM_5,
            Keys.NUM_6, Keys.NUM_7, Keys.NUM_8, Keys.NUM_9, Keys.NUM_0,
            Keys.MINUS, Keys.EQUAL, Keys.BACKSPACE,
        ),
        listOf(
            Keys.TAB, Keys.Q, Keys.W, Keys.E, Keys.R, Keys.T, Keys.Y, Keys.U, Keys.I, Keys.O,
            Keys.P, Keys.LEFT_BRACKET, Keys.RIGHT_BRACKET, Keys.BACKSLASH,
        ),
        listOf(
            Keys.CAPS_LOCK, Keys.A, Keys.S, Keys.D, Keys.F, Keys.G, Keys.H, Keys.J, Keys.K,
            Keys.L, Keys.SEMICOLON, Keys.QUOTE, Keys.ENTER,
        ),
        listOf(
            Keys.Z, Keys.X, Keys.C, Keys.V, Keys.B, Keys.N, Keys.M,
            Keys.COMMA, Keys.PERIOD, Keys.SLASH,
        ),
        listOf(Keys.SPACE),
    )

    /** 该布局独有的键插在 [KeyboardConstants.Key] 左侧；[extraKeys] 为空时原样返回（US 零差异） */
    private fun List<List<KeyboardConstants.Key>>.withExtraBefore(
        anchor: KeyboardConstants.Key,
        extraKeys: List<KeyboardConstants.Key>,
    ): List<List<KeyboardConstants.Key>> = if (extraKeys.isEmpty()) {
        this
    } else {
        map { row ->
            val index = row.indexOf(anchor)
            if (index < 0) row else row.take(index) + extraKeys + row.drop(index)
        }
    }

    private fun buildMainRows(layout: HostKeyboardLayout): KeyboardMainRows = KeyboardMainRows(
        portraitLeftHalf = portraitLeftHalfRows,
        portraitRightHalf = portraitRightHalfRows.withExtraBefore(Keys.ENTER, layout.extraKeys),
        landscape = landscapeMainRows.withExtraBefore(Keys.ENTER, layout.extraKeys),
    )

    /**
     * 每个布局一份行定义，**类加载时一次算好**。
     *
     * 必须缓存、不能每次组合现算：`KeyboardCanvas` 拿 `rows` 当几何与文本缓存的 key，
     * 引用一变就会重算整张键盘的几何与逐键文本测量 —— 那正是「键盘界面重写」花力气
     * 消掉的每帧开销。US 走 [withExtraBefore] 的空分支，返回的就是上面那三个单例引用。
     */
    private val MAIN_ROWS_BY_LAYOUT: Map<String, KeyboardMainRows> =
        KeyboardLayoutCatalog.ALL.associate { it.id to buildMainRows(it) }

    /** 该布局下的主键盘行定义；未知 id 回落到 US（`ALL` 里一定有 US，取不到才会走到） */
    fun mainRows(layout: HostKeyboardLayout): KeyboardMainRows =
        MAIN_ROWS_BY_LAYOUT[layout.id] ?: MAIN_ROWS_BY_LAYOUT.getValue(KeyboardLayoutCatalog.US.id)
}

/**
 * 一份主键盘行定义（竖屏两半 + 横屏整块）。
 *
 * 三份都随宿主布局变化：布局有额外键时它们被插到 ENTER 左边，行数不变，
 * 因此「竖屏两半各 6 行、行高对齐」与「两半键集 == 横屏键集」两条不变量继续成立。
 */
data class KeyboardMainRows(
    val portraitLeftHalf: List<List<KeyboardConstants.Key>>,
    val portraitRightHalf: List<List<KeyboardConstants.Key>>,
    val landscape: List<List<KeyboardConstants.Key>>,
)
