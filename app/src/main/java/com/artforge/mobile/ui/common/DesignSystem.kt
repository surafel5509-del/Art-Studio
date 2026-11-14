package com.artforge.mobile.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artforge.mobile.ui.theme.ForgeColors

/**
 * Shared building blocks for the ArtForge surfaces.
 *
 * Everything here is deliberately low-level and stateless so screens stay declarative:
 * cards with a real elevation/outline hierarchy, section headers with a hairline rule,
 * pill buttons with a pressed state, and keyboard/screen-reader friendly touch targets.
 */

/** A raised panel: one corner radius, one hairline outline, no drop shadow soup. */
@Composable
fun ForgeCard(
    modifier: Modifier = Modifier,
    container: Color = ForgeColors.Graphite,
    border: Color = ForgeColors.Hairline,
    corner: Int = 18,
    padding: Int = 14,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(container, shape)
            .border(BorderStroke(1.dp, border), shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(padding.dp),
        content = content,
    )
}

/**
 * Section header used across panels: an uppercase micro-label plus an optional trailing
 * action, separated from the content by a hairline rule.
 */
@Composable
fun PanelHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = ForgeColors.TextMuted,
                fontWeight = FontWeight.SemiBold,
            )
            trailing?.invoke()
        }
        Hairline()
    }
}

/** 1dp separator at the theme's hairline contrast. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = ForgeColors.Hairline) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** Micro-label used above values, e.g. "SIZE" above "24 px". */
@Composable
fun MicroLabel(text: String, modifier: Modifier = Modifier, color: Color = ForgeColors.TextMuted) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

/**
 * Primary/secondary action pill with a subtle press animation. Used for toolbars and
 * dialogs where a full Material Button would be too heavy.
 */
@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val scale by animateFloatAsState(
        targetValue = if (enabled) 1f else 1f,
        animationSpec = tween(120),
        label = "pillScale",
    )
    val interaction = remember { MutableInteractionSource() }
    val bg = when {
        !enabled -> ForgeColors.GraphiteElevated.copy(alpha = 0.5f)
        selected -> ForgeColors.Accent
        else -> ForgeColors.GraphiteRaised
    }
    val fg = when {
        !enabled -> ForgeColors.TextMuted
        selected -> Color(0xFF0B0C0F)
        else -> ForgeColors.TextSecondary
    }
    Row(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(if (enabled) Modifier.clickable(interaction, indication = null) { onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** Square icon button with a selected state, sized for a fingertip. */
@Composable
fun GestureIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Int = 40,
) {
    val bg = when {
        selected -> ForgeColors.Accent.copy(alpha = 0.16f)
        else -> Color.Transparent
    }
    val tint = when {
        !enabled -> ForgeColors.TextMuted.copy(alpha = 0.5f)
        selected -> ForgeColors.Accent
        else -> ForgeColors.TextSecondary
    }
    Box(
        modifier
            .size(size.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size((size * 0.5f).dp))
    }
}

/** Accent-tinted hero block: a soft radial wash behind headline content. */
@Composable
fun AccentWash(
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(ForgeColors.Accent, Color(0xFF9B7BFF)),
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(
                    colors.map { it.copy(alpha = 0.22f) } + ForgeColors.Graphite,
                ),
            )
            .border(BorderStroke(1.dp, colors.first().copy(alpha = 0.25f)), RoundedCornerShape(22.dp)),
    ) {
        content()
    }
}

/** Small status chip, e.g. "Auto-saved" or "3 layers". */
@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = ForgeColors.TextSecondary,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** Dot + label row used for metadata lines. */
@Composable
fun DotSeparated(parts: List<String>, modifier: Modifier = Modifier, color: Color = ForgeColors.TextMuted) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { index, part ->
            if (index > 0) {
                Text(" · ", style = MaterialTheme.typography.bodySmall, color = color.copy(alpha = 0.6f))
            }
            Text(part, style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}

/** Vertically stacked label + value, right-aligned in toolbars. */
@Composable
fun LabelValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = ForgeColors.TextPrimary,
    valueTrailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ForgeColors.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall, color = valueColor)
        valueTrailing?.invoke(this)
    }
}

@Composable
fun SpacerW(width: Int) = androidx.compose.foundation.layout.Spacer(Modifier.size(width.dp, 1.dp))

@Composable
fun SpacerH(height: Int) = androidx.compose.foundation.layout.Spacer(Modifier.size(1.dp, height.dp))

@Composable
fun SectionDivider(modifier: Modifier = Modifier) {
    Hairline(modifier, ForgeColors.Hairline)
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ForgeColors.GraphiteRaised),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = ForgeColors.TextMuted, modifier = Modifier.size(22.dp))
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = ForgeColors.TextPrimary)
        androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = ForgeColors.TextMuted,
        )
    }
}
