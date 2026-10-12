package com.github.remotecontrol.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * 这一层是"风格收敛"的杠杆点：
 * 把「圆角 + 1dp 描边 + 0 阴影 + 浓度档位」四件事一次性封装。
 * 之前散在 5 处的卡片写法（surfaceVariant / surface+8dp 阴影 / 默认 Card /
 * clip(16.dp) / 无 shape）全部由 ElevatedCard 接管，各屏只传 TintLevel。
 */

/**
 * 统一的区块卡片。
 *
 * @param level 浓度档位。默认 [TintLevel.SURFACE]（第 2 档）——浅色相底 + 1dp 描边。
 * @param onClick 非空则整卡可点击
 */
@Composable
fun ElevatedCard(
    modifier: Modifier = Modifier,
    level: TintLevel = TintLevel.SURFACE,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dimens = LocalAppDimens.current
    val style = tintStyle(level)
    val shape = remember(dimens.cardCorner) { RoundedCornerShape(dimens.cardCorner) }
    val padding = contentPadding ?: PaddingValues(dimens.cardPadding)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = style.container,
        border = BorderStroke(dimens.strokeWidth, style.border),
        // 浓度阶梯负责分层，不用阴影：B 风格全程 elevation = 0
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(padding), content = content)
    }
}

/**
 * 区块标题：3dp 竖色条（accent）+ 可选图标 + 标题 + 可选右侧胶囊标签。
 *
 * 竖色条是"这个区块属于同一套视觉语言"的锚点 —— 因为整屏只有一个色相，
 * 它不再承担"区分功能区"的职责，转而承担"统一节奏"的职责。
 */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    tag: String? = null,
    icon: ImageVector? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val tokens = LocalAppTokens.current
    val dimens = LocalAppDimens.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = dimens.sectionGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(remember { RoundedCornerShape(2.dp) })
                .background(tokens.accent)
        )
        Spacer(Modifier.width(7.dp))
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tokens.accent,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (tag != null || trailing != null) {
            Spacer(Modifier.weight(1f))
        }
        if (tag != null) {
            TagPill(tag)
        }
        if (trailing != null) {
            trailing()
        }
    }
}

/** 区块标签胶囊：第 2 档底 + 第 3 档描边 + accent 文字 */
@Composable
fun TagPill(text: String, modifier: Modifier = Modifier) {
    val tokens = LocalAppTokens.current
    val shape = remember { RoundedCornerShape(4.dp) }

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = tokens.accent,
        modifier = modifier
            .clip(shape)
            .background(tokens.tint)
            .border(1.dp, tokens.tintLine, shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 主操作按钮：第 5 档满浓度填充，用所在色相的强调色 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tokens = LocalAppTokens.current
    val dimens = LocalAppDimens.current

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(dimens.buttonHeight),
        shape = remember(dimens.innerCorner) { RoundedCornerShape(dimens.innerCorner) },
        colors = ButtonDefaults.buttonColors(
            containerColor = tokens.accent,
            contentColor = tokens.onAccent,
            disabledContainerColor = tokens.panel,
            disabledContentColor = tokens.onVar,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        // 按钮文案强制单行：宽度由文本自然撑开，不受语言影响的固定宽度会把它挤成两三行。
        // 调用方若要控制尺寸，请用 widthIn(min = ...) 而不是 width(...)（见扫描页的扫描按钮）。
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
    }
}
/**
 * 次操作按钮：第 2 档底 + 第 3 档描边 + 强调色文字。
 *
 * @param maxLines 文案最多几行。默认单行 —— 宽度由文本自然撑开时不会被压成两三行；
 *   等宽排在窄格里（模板编辑器的四个动作按钮）才需要放宽到两行
 * @param height 指定高度；为 null 时用 [AppDimens.buttonHeight]。两行文案要传更大的值
 * @param contentPadding 内容内边距。窄格（`weight(1f)` 分到六七十 dp）里要收窄，
 *   否则水平留白会把文字挤没
 * @param selected 选中态：描边换成强调色。用于「点开后会在下方展开内容」这类开关按钮
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxLines: Int = 1,
    height: Dp? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    selected: Boolean = false,
) {
    val tokens = LocalAppTokens.current
    val dimens = LocalAppDimens.current

    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height ?: dimens.buttonHeight),
        shape = remember(dimens.innerCorner) { RoundedCornerShape(dimens.innerCorner) },
        border = BorderStroke(
            dimens.strokeWidth,
            if (selected) tokens.accent else tokens.tintLine,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = tokens.tint,
            contentColor = tokens.accent,
        ),
        contentPadding = contentPadding,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = maxLines,
            textAlign = TextAlign.Center,
        )
    }
}

/** 连接状态圆点 + 文案 */
@Composable
fun StatusDot(
    status: ConnStatus,
    label: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val tokens = LocalAppTokens.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(tokens.status[status])
        )
        Spacer(Modifier.width(5.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = textColor)
    }
}

/** 键位/面板的表面：第 2 档底 + 第 3 档描边，可选按下态 */
@Composable
fun PanelSurface(
    modifier: Modifier = Modifier,
    pressed: Boolean = false,
    corner: androidx.compose.ui.unit.Dp = LocalAppDimens.current.innerCorner,
    content: @Composable () -> Unit = {},
) {
    val tokens = LocalAppTokens.current
    val level = if (pressed) TintLevel.ACTIVE else TintLevel.SURFACE
    val style = tintStyle(level)
    val shape = remember(corner) { RoundedCornerShape(corner) }

    Box(
        modifier = modifier
            .clip(shape)
            .background(style.container)
            .border(1.dp, style.border, shape),
        contentAlignment = Alignment.Center,
    ) { content() }
}
