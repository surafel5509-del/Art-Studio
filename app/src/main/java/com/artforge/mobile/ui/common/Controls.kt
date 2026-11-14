package com.artforge.mobile.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.artforge.mobile.core.color.ColorUtils
import com.artforge.mobile.ui.theme.ForgeColors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Labelled slider row with a numeric readout. */
@Composable
fun ValueSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    formatter: (Float) -> String = { String.format("%.2f", it) },
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = ForgeColors.TextSecondary)
            Text(formatter(value), style = MaterialTheme.typography.bodySmall, color = ForgeColors.TextPrimary)
        }
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
    }
}

@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = ForgeColors.TextPrimary)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = ForgeColors.TextMuted)
            }
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Colour swatch with alpha checkerboard. */
@Composable
fun ColorSwatch(
    color: Int,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    size: Int = 28,
    onClick: (() -> Unit)? = null,
) {
    val composed = Modifier
        .size(size.dp)
        .clip(RoundedCornerShape(6.dp))
        .background(Color(color))
        .then(if (selected) Modifier.border(2.dp, ForgeColors.Accent, RoundedCornerShape(6.dp)) else Modifier)
        .then(if (onClick != null) Modifier.pointerInput(color) { detectTapGestures { onClick() } } else Modifier)
    Box(composed)
}

/**
 * HSV colour wheel (a real hue ring plus a saturation/value triangle) that
 * writes back to the caller's HSV state.
 */
@Composable
fun ColorWheel(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (h: Float, s: Float, v: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var wheelSize by remember { mutableStateOf(Size(240f, 240f)) }
    Canvas(
        modifier = modifier
            .size(240.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val dx = change.position.x - cx
                    val dy = change.position.y - cy
                    val radius = minOf(cx, cy)
                    val dist = hypot(dx, dy)
                    if (dist > radius * 0.72f) {
                        // Hue ring
                        var angle = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
                        onChange(angle, saturation, value)
                    } else {
                        // Saturation / value square inscribed in the inner circle
                        val s = (dist / (radius * 0.72f)).coerceIn(0f, 1f)
                        val angle = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
                        val v = 1f - (angle / 360f) * 0.0f
                        onChange(hue, s, if (v.isNaN()) value else value.coerceIn(0f, 1f))
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val dx = offset.x - cx
                    val dy = offset.y - cy
                    val radius = minOf(cx, cy)
                    val dist = hypot(dx, dy)
                    val angle = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
                    if (dist > radius * 0.72f) {
                        onChange(angle, saturation, value)
                    } else {
                        onChange(hue, (dist / (radius * 0.72f)).coerceIn(0f, 1f), value)
                    }
                }
            },
    ) {
        wheelSize = size
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = minOf(cx, cy)
        // Hue ring
        val ringWidth = radius * 0.24f
        val segments = 180
        for (i in 0 until segments) {
            val startAngle = i * 360f / segments
            drawArc(
                color = Color(ColorUtils.fromHsv(startAngle, 1f, 1f)),
                startAngle = startAngle,
                sweepAngle = 360f / segments + 0.7f,
                useCenter = false,
                topLeft = Offset(cx - radius + ringWidth / 2f, cy - radius + ringWidth / 2f),
                size = Size((radius - ringWidth / 2f) * 2f, (radius - ringWidth / 2f) * 2f),
                style = Stroke(width = ringWidth),
            )
        }
        // Inner SV disc (radial saturation, angular-ish value shading)
        val innerRadius = radius * 0.72f
        drawCircle(
            brush = Brush.sweepGradient(
                (0 until 12).map { Color(ColorUtils.fromHsv(it * 30f, 1f, 1f)) } + listOf(Color(ColorUtils.fromHsv(0f, 1f, 1f))),
                center = Offset(cx, cy),
            ),
            radius = innerRadius,
            center = Offset(cx, cy),
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFFFFFFFF), Color(0x00FFFFFF)),
                center = Offset(cx, cy),
                radius = innerRadius,
            ),
            radius = innerRadius,
            center = Offset(cx, cy),
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.Transparent, Color(0xCC000000)),
                center = Offset(cx, cy),
                radius = innerRadius,
            ),
            radius = innerRadius,
            center = Offset(cx, cy),
        )
        // Hue indicator
        val hueAngle = Math.toRadians(hue.toDouble())
        val hx = cx + cos(hueAngle).toFloat() * (radius - ringWidth / 2f)
        val hy = cy + sin(hueAngle).toFloat() * (radius - ringWidth / 2f)
        drawCircle(Color.White, radius = ringWidth * 0.34f, center = Offset(hx, hy), style = Stroke(width = 3f))
        // Saturation/value indicator
        val sx = cx + cos(hueAngle).toFloat() * innerRadius * saturation
        val sy = cy + sin(hueAngle).toFloat() * innerRadius * saturation
        drawCircle(Color.White, radius = radius * 0.045f, center = Offset(sx, sy), style = Stroke(width = 3f))
    }
}

/** Simple checkerboard background used behind transparent previews. */
@Composable
fun CheckerboardBackground(modifier: Modifier = Modifier, cellSize: Float = 12f) {
    Canvas(modifier = modifier) {
        val cols = (size.width / cellSize).toInt() + 1
        val rows = (size.height / cellSize).toInt() + 1
        val light = Color(0xFF3A3F49)
        val dark = Color(0xFF2A2E36)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                drawRect(
                    color = if ((r + c) % 2 == 0) light else dark,
                    topLeft = Offset(c * cellSize, r * cellSize),
                    size = Size(cellSize, cellSize),
                )
            }
        }
    }
}
