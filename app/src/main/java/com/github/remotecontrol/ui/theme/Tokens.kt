package com.github.remotecontrol.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.remotecontrol.util.HueScheme

// ══════════════════════════════════════════════════════════════════════
// 视觉权重
// ══════════════════════════════════════════════════════════════════════

/**
 * 由浅入深的浓度档位，用来表达"交互权重"：
 *
 * - [FLAT]    0%   页面底色，无内容分层
 * - [SURFACE] 第 2 档 分区容器（卡片、触摸板面、内嵌面板）
 * - [ACTIVE]  第 4 档 选中 / 按下 / 焦点
 * - [SOLID]   第 5 档 主操作按钮、强调块
 *
 * 顶栏与横屏侧栏不在这里 —— 它们是"外壳"，用 [AppTokens.appBar]（第 1 档）单独处理。
 */
enum class TintLevel { FLAT, SURFACE, ACTIVE, SOLID }

// ══════════════════════════════════════════════════════════════════════
// 语义色
// ══════════════════════════════════════════════════════════════════════

/**
 * 连接 / 错误语义色。
 *
 * 「警告 / 异常」两档**就是** [AppTokens.warn] / [AppTokens.error]（见那里的构造），
 * 不另起一套值 —— 同一个界面上出现两种「警告色」是最没有道理的一种不一致。
 */
enum class ConnStatus { OK, WARN, BAD, IDLE }

@Immutable
data class StatusColors(
    val ok: Color,
    val warn: Color,
    val bad: Color,
    val idle: Color,
) {
    operator fun get(status: ConnStatus): Color = when (status) {
        ConnStatus.OK -> ok
        ConnStatus.WARN -> warn
        ConnStatus.BAD -> bad
        ConnStatus.IDLE -> idle
    }
}

// ══════════════════════════════════════════════════════════════════════
// 核心 token
// ══════════════════════════════════════════════════════════════════════

/**
 * 全部视觉 token 的唯一来源。
 *
 * 颜色分 4 层角色：
 *
 * | 层 | 角色 | 是否随色相方案变化 |
 * |---|---|---|
 * | L0 中性层 | background / card / panel / line / on | 否 |
 * | L1 浓度阶梯 | appBar / tint / tintLine / active —— **全部从 accent 派生** | 是（自动） |
 * | L2 语义色 | warn（异色 1）/ error（异色 2）/ status | 是（**每套方案各挑一对**，见 `HueScheme.darkSemantics`） |
 * | L3 强调色 | accent / onAccent —— 「正常」的那一档 | 是 |
 *
 * 整条 L1 阶梯由 [Color.mix] 从 [accent] 算出，因此换色相方案时
 * 顶栏、卡片、描边、选中态会一起走，不会出现"某个区域忘了改"的割裂感。
 *
 * 亮暗两套的**混合比与基色都不同**（见 [darkTokens] / [lightTokens]），
 * 不是简单的明暗反转。
 */
@Immutable
data class AppTokens(
    /** L0 页面底色 */
    val background: Color,
    /** L1 第 1 档：顶栏 / 横屏侧栏（"外壳"层） */
    val appBar: Color,
    /** L0 中性卡片底 */
    val card: Color,
    /** L0 中性面板底（键位、内嵌槽）。深色下比 card 亮，浅色下比 card 暗 */
    val panel: Color,
    /** L0 一般描边 */
    val line: Color,
    /** L0 强描边 */
    val lineStrong: Color,
    /** L0 主文字 */
    val on: Color,
    /** L0 次文字 */
    val onVar: Color,
    /** L1 第 2 档：分区容器底 */
    val tint: Color,
    /** L1 第 3 档：分区描边 */
    val tintLine: Color,
    /** L1 第 4 档：选中 / 按下 */
    val active: Color,
    /** L1 第 4 档描边（取满浓度 accent） */
    val activeLine: Color,
    /** L3 第 5 档：按钮 / 强调。**整屏的「正常」色就是它** */
    val accent: Color,
    /** accent 上的文字色。深色下是近黑，浅色下是纯白 */
    val onAccent: Color,
    /**
     * L2 **异色 1：警告**。
     *
     * 「异色」= 落在 [accent] 那个色系**之外**：整屏只有一族色相时，语义只能靠色相之外
     * 的东西表达，而「跟随 accent 的警告色」等于没有警告色 —— 原先 M3 的 `tertiary`
     * 被接成 [accent]，模板诊断的警告文案就和正常文案同色了。
     *
     * 具体取哪个色**每套色相方案各一对**（[HueScheme.darkSemantics] /
     * [HueScheme.lightSemantics]），挑法只有一条 —— **取 accent 的对面那一侧**
     * （蓝配橙、青绿配品红、金配紫、红粉配青）：不是为了「暖」，是为了「相冲」。
     * 深浅两套模式各有一个值，与 `ok` 同一套待遇。
     */
    val warn: Color,
    /** warn 上的文字色（与 [onAccent] 同一套处理：深色压近黑、浅色用纯白） */
    val onWarn: Color,
    /**
     * warn 的**低浓度底**（M3 的 `tertiaryContainer` 取它）：铺在卡片上做「这一块和旁边不一样」
     * 的轻标记（设置页自定义字符集行铺的就是它）。
     *
     * 关键在于**混在谁上面算**：它按浓度阶梯第 2 档（卡片底 [tint]）混，因为那才是它实际落上去
     * 的那一层。早期版本按中性的 [card] 混，铺到卡片上只剩 1.15:1，等于没铺 —— 颜色挑得再对，
     * 底混错了层级也看不出来。
     */
    val warnContainer: Color,
    /**
     * L2 **异色 2：错误**（偏红一侧）。
     *
     * 与 [warn] 之间靠色相 + 明度双重拉开（警告偏橙且更亮、错误更红更深），
     * 连同 [accent] 三者两两可分 —— 这是每套方案挑那对色时的硬约束，见
     * [HueScheme.darkSemantics] 里 ROSE 那条注释（它的 accent 是红粉，最容易被错误色追上）。
     */
    val error: Color,
    /** error 上的文字色 */
    val onError: Color,
    /** error 的低浓度底，口径与那条警告同见 [warnContainer] */
    val errorContainer: Color,
    /** L2 语义色 */
    val status: StatusColors,
)

val LocalAppTokens = staticCompositionLocalOf { darkTokens(HueScheme.AZURE) }

// ══════════════════════════════════════════════════════════════════════
// 尺寸 · 形状 · 排版
// ══════════════════════════════════════════════════════════════════════

/**
 * 尺寸 token：各屏不再写 `.dp` 字面量。
 * 换风格时只改这里，界面代码不用动。
 */
@Immutable
data class AppDimens(
    val screenPadding: Dp = 12.dp,
    val sectionGap: Dp = 10.dp,
    val cardPadding: Dp = 12.dp,
    /** 卡片 / 容器圆角 */
    val cardCorner: Dp = 8.dp,
    /** 内嵌元素（按钮、输入框、面板）圆角 */
    val innerCorner: Dp = 6.dp,
    /** 键位圆角（KeyboardCanvas 自绘用） */
    val keyCorner: Dp = 6.dp,
    /** 键盘按容器高度自适应反推单键高度时的下限 */
    val keyMinHeight: Dp = 26.dp,
    /** 键盘按容器高度自适应反推单键高度时的上限（行数少的页面靠它避免键位被拉得过大） */
    val keyMaxHeight: Dp = 44.dp,
    /** 键盘行与行之间的间距 */
    val keyRowSpacing: Dp = 4.dp,
    /** 键盘同一行内的键间距 */
    val keySpacing: Dp = 4.dp,
    /** 键盘画布四周的内边距 */
    val keyContentPadding: Dp = 8.dp,
    /** 固定修饰键区：竖屏底部条的高度 */
    val modifierBarHeight: Dp = 52.dp,
    /** 固定修饰键区：横屏右侧竖条的宽度 */
    val modifierBarWidth: Dp = 56.dp,
    /** 固定修饰键区四周内边距（条高去掉两倍 padding 正好是一枚键位的高度） */
    val modifierBarPadding: Dp = 6.dp,
    /** 修饰键锁定指示条宽度（高度取 [strokeWidth]） */
    val modifierLockIndicatorSize: Dp = 12.dp,
    /**
     * 键盘分页指示器里每个标签的高度。
     *
     * 取与键位高度上限（[keyMaxHeight]）一致的值：滑动翻页取消后，翻页只剩点页签这一条路，
     * 标签做大到和键位同高才方便按。
     */
    val keyboardPageIndicatorHeight: Dp = keyMaxHeight,
    /** 键盘灯条高度（夹在分页标签与键盘之间的那一条）。灯条上三个灯位是可按的按钮，按到 40dp 才好点 */
    val keyboardLedBarHeight: Dp = 40.dp,
    /** 键盘指示灯直径。参照笔记本右上角的指示灯：小到不抢注意力，又要能一眼看出亮没亮 */
    val keyboardLedDotSize: Dp = 8.dp,
    val buttonHeight: Dp = 40.dp,
    val iconButtonSize: Dp = 34.dp,
    val strokeWidth: Dp = 1.dp,
    val cardElevation: Dp = 0.dp,
)

val LocalAppDimens = staticCompositionLocalOf { AppDimens() }

/** 形状：4 徽标 / 6 按钮内嵌 / 8 卡片 / 12 大容器 / 16 对话框 */
val UtilityShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/**
 * 排版：把原先散落在各屏的 18 / 16 / 15 / 14 / 13 / 12 / 11 / 10.sp 收编成一套。
 * 各屏请用 `MaterialTheme.typography.xxx`，不要再写 `fontSize = 13.sp`。
 */
val UtilityTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp),
)

/**
 * 等宽「值文本」样式：键名、码点、展开报告这类靠字符对齐才看得清的文本。
 *
 * 不放进 [UtilityTypography]，是因为 M3 的 [Typography] 是**固定角色集**，加不了自定义档。
 * 各屏请用这两个常量，不要再写 `TextStyle(fontFamily = FontFamily.Monospace, fontSize = …)`。
 */
val MonoSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp)
val MonoMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

// ══════════════════════════════════════════════════════════════════════
// 浓度阶梯
// ══════════════════════════════════════════════════════════════════════

/** 把本颜色按 [ratio] 混到 [base] 上 —— 整条浓度阶梯的唯一派生手段 */
internal fun Color.mix(base: Color, ratio: Float): Color =
    copy(alpha = ratio).compositeOver(base)

/** 一个浓度档位展开出的四个颜色，覆盖组件的全部视觉需求 */
@Immutable
data class TintStyle(
    val container: Color,
    val border: Color,
    val content: Color,
    val onContainer: Color,
)

@Composable
fun tintStyle(level: TintLevel): TintStyle {
    val t = LocalAppTokens.current
    return remember(level, t) {
        when (level) {
            TintLevel.FLAT -> TintStyle(t.background, t.line, t.onVar, t.on)
            TintLevel.SURFACE -> TintStyle(t.tint, t.tintLine, t.accent, t.on)
            TintLevel.ACTIVE -> TintStyle(t.active, t.activeLine, t.on, t.on)
            TintLevel.SOLID -> TintStyle(t.accent, t.accent, t.onAccent, t.onAccent)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// 亮 / 暗两套构建器
// ══════════════════════════════════════════════════════════════════════

// ── 深色：暗底 + 亮色相，浓度越高越亮 ──
private val Ink = Color(0xFF0D1117)
private val InkCard = Color(0xFF161B22)

// ── 浅色：亮底 + 暗色相，靠描边分层 ──
private val Paper = Color(0xFFF5F7FA)
private val PaperCard = Color(0xFFFFFFFF)

// ── 异色：**每套色相方案各一对**，深浅两套模式各一个值 ──
//
// 挑法只有一条：**要跟 accent 相冲**。所以警告色取的是 accent 的对面那一侧（蓝底配暖橙、
// 青绿底配品红、金底配紫、红粉底配青），而不是「一律暖色」—— 一律暖色等于放过了一半方案，
// 那些方案里正常和警告本来就已经在同一个色系里。
//
// 错误色仍留在红 / 品红那一侧（比警告更紧张的一档），但不与 accent 同族；绿、青留给
// `ok` 与连接状态，不拿来当错误色，否则「出错」会被读成「正常」。

/** 一套色相方案在某个模式下的两个异色 */
internal data class SemanticPair(val warn: Color, val error: Color)

// AZURE：蓝的对面是橙红，标准冷暖对撞
private val AzurePairDark = SemanticPair(warn = Color(0xFFF0883E), error = Color(0xFFFF5252))
private val AzurePairLight = SemanticPair(warn = Color(0xFFC2410C), error = Color(0xFFD32F2F))

// TEAL：青绿的对面是品红，警告取品红、错误再压深一档
private val TealPairDark = SemanticPair(warn = Color(0xFFF04E98), error = Color(0xFFE5484D))
private val TealPairLight = SemanticPair(warn = Color(0xFFA21CAF), error = Color(0xFFBE123C))

// VIOLET：紫的对面是黄，警告取明黄；错误取绯红（与紫、与黄都隔得开）
private val VioletPairDark = SemanticPair(warn = Color(0xFFE9C63C), error = Color(0xFFFF4D6B))
private val VioletPairLight = SemanticPair(warn = Color(0xFFA16207), error = Color(0xFFC2185B))

// GRAPHITE：中性灰没有「对面」，异色不必最艳，琥珀 + 砖红、整体收敛一点
private val GraphitePairDark = SemanticPair(warn = Color(0xFFEFA22B), error = Color(0xFFF2545B))
private val GraphitePairLight = SemanticPair(warn = Color(0xFF8A4B08), error = Color(0xFFB42318))

// AMBER：金底几乎把暖色区占满，警告只能反过来取**紫**（对面那一段），错误保持正红
private val AmberPairDark = SemanticPair(warn = Color(0xFFA855F7), error = Color(0xFFFF3B30))
private val AmberPairLight = SemanticPair(warn = Color(0xFF7E22CE), error = Color(0xFFC0271C))

// ROSE：红粉底与红最近，错误往橙里挪；警告干脆取**青**（与粉正对面，也不与绿撞）
private val RosePairDark = SemanticPair(warn = Color(0xFF22D3EE), error = Color(0xFFFF6B4A))
private val RosePairLight = SemanticPair(warn = Color(0xFF0E7490), error = Color(0xFFC2410C))

/**
 * 深色模式下的异色对。`when` 里**没有 else 分支**：新增色相方案时编译器会逼着这里表态。
 */
internal val HueScheme.darkSemantics: SemanticPair
    get() = when (this) {
        HueScheme.AZURE -> AzurePairDark
        HueScheme.TEAL -> TealPairDark
        HueScheme.VIOLET -> VioletPairDark
        HueScheme.GRAPHITE -> GraphitePairDark
        HueScheme.AMBER -> AmberPairDark
        HueScheme.ROSE -> RosePairDark
    }

/**
 * 浅色模式下的异色对。
 *
 * 与深色**各配一套**（不是简单压暗）：浅底上能用的暖色区间窄得多，饱和度与深度都得另定，
 * 否则会糊成一片深棕。
 */
internal val HueScheme.lightSemantics: SemanticPair
    get() = when (this) {
        HueScheme.AZURE -> AzurePairLight
        HueScheme.TEAL -> TealPairLight
        HueScheme.VIOLET -> VioletPairLight
        HueScheme.GRAPHITE -> GraphitePairLight
        HueScheme.AMBER -> AmberPairLight
        HueScheme.ROSE -> RosePairLight
    }

/** 亮色块上的文字色：深色模式压近黑、浅色模式用纯白（[AppTokens.onAccent] 等三处共用） */
private val OnSolidDark = Ink
private val OnSolidLight = Color(0xFFFFFFFF)

/**
 * 深色模式：顶栏 20，第 2~4 档混合比 16 / 30 / 46
 *
 * `onAccent` 取近黑 —— 亮色强调块上放深色文字，填充态与文字态双向都够对比度。
 *
 * 两个异色取自 [HueScheme.darkSemantics]（每套方案各一对），「底」再由它按同一套混合比派生。
 */
internal fun darkTokens(hue: HueScheme): AppTokens {
    val accent = hue.darkAccent
    val semantics = hue.darkSemantics
    // 浓度阶梯第 2 档 = 卡片底。异色的「底」**压在它上面**算：这一层才是边上真实衬着的那层
    val surface = accent.mix(InkCard, 0.16f)

    return AppTokens(
        background = Ink,
        appBar = accent.mix(Ink, 0.20f),
        card = InkCard,
        panel = Color(0xFF21262D),          // 比 card 亮
        line = Color(0xFF30363D),
        lineStrong = Color(0xFF4A5460),
        on = Color(0xFFE6EDF3),
        onVar = Color(0xFF8B949E),
        tint = surface,
        tintLine = accent.mix(InkCard, 0.30f),
        active = accent.mix(InkCard, 0.46f),
        activeLine = accent,
        accent = accent,
        onAccent = OnSolidDark,
        warn = semantics.warn,
        onWarn = OnSolidDark,
        warnContainer = semantics.warn.mix(surface, 0.20f),
        error = semantics.error,
        onError = OnSolidDark,
        errorContainer = semantics.error.mix(surface, 0.20f),
        // 连接状态里的「警告 / 异常」直接用异色那两档：同一个界面上不该有两种警告色
        status = StatusColors(
            ok = Color(0xFF3FB950),
            warn = semantics.warn,
            bad = semantics.error,
            idle = Color(0xFF6E7681),
        ),
    )
}

/**
 * 浅色模式：**独立配比**，不是把深色反过来。
 *
 * 与深色的四处关键差异：
 * 1. 混合比整体降一档：顶栏 22，第 2~4 档 8 / 22 / 32（浅底上用深色那套浓度会发灰发脏）
 * 2. [panel] 比 [card] **暗**（深色模式是比 card 亮）—— 最容易写反的一处
 * 3. 层次主要靠 1dp 描边，而不是底色差
 * 4. `onAccent` 是纯白（强调色本身已被压暗）
 *
 * 两个异色取自 [HueScheme.lightSemantics]。
 */
internal fun lightTokens(hue: HueScheme): AppTokens {
    val accent = hue.lightAccent
    val semantics = hue.lightSemantics
    val surface = accent.mix(PaperCard, 0.08f)

    return AppTokens(
        background = Paper,
        appBar = accent.mix(Paper, 0.22f),
        card = PaperCard,
        panel = Color(0xFFEDF1F5),          // 比 card 暗
        line = Color(0xFFD5DCE4),
        lineStrong = Color(0xFFB4BEC9),
        on = Color(0xFF14181D),
        onVar = Color(0xFF5C6672),
        tint = surface,
        tintLine = accent.mix(PaperCard, 0.22f),
        active = accent.mix(PaperCard, 0.32f),
        activeLine = accent,
        accent = accent,
        onAccent = OnSolidLight,
        warn = semantics.warn,
        onWarn = OnSolidLight,
        // 浅色下的比例更低一档：白底上的色相铺底稍浓一点就会发脏（与浓度阶梯同一口径）
        warnContainer = semantics.warn.mix(surface, 0.16f),
        error = semantics.error,
        onError = OnSolidLight,
        errorContainer = semantics.error.mix(surface, 0.16f),
        status = StatusColors(
            ok = Color(0xFF1A7F37),
            warn = semantics.warn,
            bad = semantics.error,
            idle = Color(0xFF6E7681),
        ),
    )
}

// ══════════════════════════════════════════════════════════════════════
// 色相方案 → 色值
// ══════════════════════════════════════════════════════════════════════

/**
 * 色相方案的色值映射。
 *
 * 枚举本身放在 [com.github.remotecontrol.util.HueScheme]（它是 SharedPreferences 里存的值），
 * 色值放在这里，避免 util 层依赖 Compose 图形 API。
 *
 * 浅色下 accent 必须单独压暗：直接复用深色的亮色相会让白字按钮对比度只剩 2:1。
 */
internal val HueScheme.darkAccent: Color
    get() = when (this) {
        HueScheme.AZURE -> Color(0xFF58A6FF)
        HueScheme.TEAL -> Color(0xFF2DD4BF)
        HueScheme.VIOLET -> Color(0xFFA371F7)
        HueScheme.AMBER -> Color(0xFFE3B341)
        HueScheme.ROSE -> Color(0xFFF0619B)
        HueScheme.GRAPHITE -> Color(0xFF9CA3AF)
    }

internal val HueScheme.lightAccent: Color
    get() = when (this) {
        HueScheme.AZURE -> Color(0xFF0969DA)
        HueScheme.TEAL -> Color(0xFF0F766E)
        HueScheme.VIOLET -> Color(0xFF7C3AED)
        HueScheme.AMBER -> Color(0xFF9A6700)
        HueScheme.ROSE -> Color(0xFFBE185D)
        HueScheme.GRAPHITE -> Color(0xFF4B5563)
    }

/** 供设置页的色相选择器取"当前模式下这个方案长什么样"的圆点颜色 */
@Composable
fun hueDotColor(hue: HueScheme, darkTheme: Boolean): Color = remember(hue, darkTheme) {
    if (darkTheme) hue.darkAccent else hue.lightAccent
}

/**
 * 把 token 展开成 Material 3 的 [ColorScheme]。
 *
 * 必须填满所有 surface 角色 —— 只设 primary 的话，其余角色会落回 M3 出厂默认的
 * 紫调基线，这正是旧版"5 套主题色只有按钮变了色"的根因。
 */
internal fun buildColorScheme(t: AppTokens, darkTheme: Boolean): ColorScheme {
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = t.accent,
        onPrimary = t.onAccent,
        primaryContainer = t.tint,
        onPrimaryContainer = t.on,
        secondary = t.lineStrong,
        onSecondary = t.on,
        secondaryContainer = t.tint,
        onSecondaryContainer = t.on,
        // M3 的 tertiary 在本项目里**就是「警告」角色**：模板诊断的警告文案取它
        // （见 TemplateUiCommon）。它此前被接成 accent，于是「警告」与「正常」同色 ——
        // 这行是整屏唯一一个「异色」入口，别再改回 accent。
        tertiary = t.warn,
        onTertiary = t.onWarn,
        tertiaryContainer = t.warnContainer,
        onTertiaryContainer = t.on,
        background = t.background,
        onBackground = t.on,
        // surface 保持与 background 一致，卡片走 surfaceContainerLow
        surface = t.background,
        onSurface = t.on,
        surfaceVariant = t.panel,
        onSurfaceVariant = t.onVar,
        surfaceContainerLowest = t.background,
        surfaceContainerLow = t.card,
        surfaceContainer = t.card,
        surfaceContainerHigh = t.panel,
        surfaceContainerHighest = t.panel,
        surfaceTint = t.accent,
        outline = t.lineStrong,
        outlineVariant = t.line,
        error = t.error,
        onError = t.onError,
        errorContainer = t.errorContainer,
        onErrorContainer = t.on,
        scrim = Color(0xCC000000),
    )
}

// ── 以下两个是给单元测试用的不变量校验（见 test/.../TokensTest.kt）──

/**
 * panel 必须和 card 拉出方向正确的一档：
 * 深色模式 panel 比 card **亮**，浅色模式 panel 比 card **暗**。
 * 这是同步两套模式时最容易写反的一处 —— 反了键位就会糊在卡片里。
 */
internal fun AppTokens.isPanelDirectionCorrect(darkTheme: Boolean): Boolean =
    if (darkTheme) panel.luminance() > card.luminance() else panel.luminance() < card.luminance()

/**
 * 浓度阶梯必须单调：card → tint(第 2 档) → active(第 4 档) → accent(第 5 档)
 * 依次递进，中间不能有来回。
 */
internal fun AppTokens.isRampMonotonic(darkTheme: Boolean): Boolean {
    val a = card.luminance()
    val b = tint.luminance()
    val c = active.luminance()
    val d = accent.luminance()
    return if (darkTheme) a < b && b < c && c < d else a > b && b > c && c > d
}

/**
 * 语义色的「底」必须落在卡片与它自己的前景色之间：
 * 太浅就认不出铺了底，太深会盖住压在上面的前景文字。
 * 深浅两套的方向相反（深色模式下越亮越强），与浓度阶梯同一个口径。
 */
internal fun AppTokens.isSemanticWashOrdered(darkTheme: Boolean): Boolean {
    fun between(cardSide: Color, container: Color, foreground: Color): Boolean = if (darkTheme) {
        cardSide.luminance() < container.luminance() && container.luminance() < foreground.luminance()
    } else {
        cardSide.luminance() > container.luminance() && container.luminance() > foreground.luminance()
    }

    return between(card, warnContainer, warn) && between(card, errorContainer, error)
}

internal fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
