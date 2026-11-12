@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.artforge.mobile.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.artforge.mobile.core.model.GradientType
import com.artforge.mobile.core.model.SymmetryMode
import com.artforge.mobile.core.model.ToolId
import com.artforge.mobile.engine.draw.SelectionOp
import com.artforge.mobile.ui.common.PanelHeader
import com.artforge.mobile.ui.common.ToggleRow
import com.artforge.mobile.ui.common.ValueSlider
import com.artforge.mobile.ui.theme.ForgeColors

/**
 * Contextual settings for the active tool. Each branch only shows controls that the
 * engine actually reads, so nothing here is decorative.
 */
@Composable
fun ToolOptionsPanel(tools: ToolState, vm: StudioViewModel) {
    Column(Modifier.fillMaxWidth().background(ForgeColors.Graphite)) {
        PanelHeader("${ToolCatalog.spec(tools.tool).label} options")

        when (tools.tool) {
            ToolId.Brush, ToolId.Pencil, ToolId.Eraser, ToolId.PixelEraser, ToolId.Smudge,
            ToolId.Blur, ToolId.Sharpen, ToolId.Dodge, ToolId.Burn, ToolId.CloneStamp,
            -> {
                ValueSlider("Size", tools.brushSize, 1f..400f, vm::setBrushSize, formatter = { "${it.toInt()} px" })
                ValueSlider("Opacity", tools.opacity, 0.01f..1f, vm::setOpacity, formatter = { "${(it * 100).toInt()}%" })
                ValueSlider("Hardness", tools.hardness, 0f..1f, vm::setHardness, formatter = { "${(it * 100).toInt()}%" })
            }

            ToolId.Line, ToolId.Rectangle, ToolId.RoundedRectangle, ToolId.Ellipse,
            ToolId.Polygon, ToolId.Star, ToolId.Curve,
            -> {
                ValueSlider("Stroke width", tools.brushSize, 1f..120f, vm::setBrushSize, formatter = { "${it.toInt()} px" })
                ToggleRow("Fill shape", tools.shapeFill, vm::setShapeFill)
                ToggleRow("Constrain proportions", tools.constrain, { vm.selectTool(tools.tool) })
                if (tools.tool == ToolId.Polygon) {
                    ValueSlider("Sides", tools.shapeSides.toFloat(), 3f..24f, { vm.setShapeSides(it.toInt()) }, formatter = { "${it.toInt()}" })
                }
                if (tools.tool == ToolId.Star) {
                    ValueSlider("Points", tools.starPoints.toFloat(), 3f..24f, { vm.setStarPoints(it.toInt()) }, formatter = { "${it.toInt()}" })
                }
                if (tools.tool == ToolId.RoundedRectangle) {
                    ValueSlider("Corner radius", tools.cornerRadius, 0f..200f, vm::setCornerRadius, formatter = { "${it.toInt()} px" })
                }
            }

            ToolId.BucketFill -> {
                ValueSlider("Tolerance", tools.tolerance.toFloat(), 0f..120f, { vm.setTolerance(it.toInt()) }, formatter = { "${it.toInt()}" })
                Text(
                    "Tap the canvas to fill the enclosed region on the active layer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ForgeColors.TextMuted,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            ToolId.Gradient -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    GradientType.entries.forEach { type ->
                        OptionChip(type.name, tools.gradientType == type) { vm.setGradientType(type) }
                    }
                }
                Text(
                    "Drag across the canvas to place the gradient between the primary and secondary colours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ForgeColors.TextMuted,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            ToolId.RectSelect, ToolId.EllipseSelect, ToolId.LassoSelect, ToolId.PolygonSelect, ToolId.MagicWand -> {
                ValueSlider(
                    "Tolerance",
                    tools.tolerance.toFloat(),
                    0f..120f,
                    { vm.setTolerance(it.toInt()) },
                    formatter = { "${it.toInt()}" },
                )
                ValueSlider(
                    "Feather",
                    tools.selectionFeather.toFloat(),
                    0f..48f,
                    { vm.setSelectionFeather(it.toInt()) },
                    formatter = { "${it.toInt()} px" },
                )
                FlowRow(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SelectionOp.entries.forEach { op ->
                        OptionChip(op.name, tools.selectionOp == op) { vm.setSelectionOp(op) }
                    }
                }
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OptionChip("Select all") { vm.selectAll() }
                    OptionChip("Deselect") { vm.deselect() }
                    OptionChip("Invert") { vm.invertSelection() }
                    OptionChip("Expand") { vm.expandSelection(2) }
                    OptionChip("Contract") { vm.contractSelection(2) }
                    OptionChip("Fill") { vm.fillSelection() }
                    OptionChip("Clear") { vm.clearSelectionContents() }
                    OptionChip("Copy") { vm.copySelection() }
                    OptionChip("Cut") { vm.cutSelection() }
                    OptionChip("Paste") { vm.pasteClipboard() }
                }
            }

            ToolId.Text -> {
                ValueSlider("Font size", tools.textSize, 8f..400f, vm::setTextSize, formatter = { "${it.toInt()} px" })
                Text(
                    "Tap the canvas to place a text layer, then edit its content from the layer menu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ForgeColors.TextMuted,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            ToolId.Perspective -> {
                Text(
                    "Perspective guides",
                    style = MaterialTheme.typography.labelMedium,
                    color = ForgeColors.TextMuted,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                FlowRow(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OptionChip("Off") { vm.setPerspective(0, false) }
                    OptionChip("1 point") { vm.setPerspective(1, true) }
                    OptionChip("2 point") { vm.setPerspective(2, true) }
                    OptionChip("3 point") { vm.setPerspective(3, true) }
                }
                Text(
                    "Symmetry",
                    style = MaterialTheme.typography.labelMedium,
                    color = ForgeColors.TextMuted,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                FlowRow(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SymmetryMode.entries.forEach { mode ->
                        OptionChip(mode.name) { vm.setSymmetry(mode) }
                    }
                }
            }

            else -> {
                Text(
                    "No options for this tool.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ForgeColors.TextMuted,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun OptionChip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) ForgeColors.Accent else ForgeColors.GraphiteElevated)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Color(0xFF0B0C0F) else ForgeColors.TextSecondary,
        )
    }
}

/** Effects and filters panel with live parameter sliders. */
@Composable
fun EffectsPanel(vm: StudioViewModel) {
    var selected by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(com.artforge.mobile.engine.effects.EffectType.BrightnessContrast)
    }
    var amount by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0.3f) }
    var radius by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(12f) }
    var angle by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(45f) }
    var size by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(8f) }

    Column(Modifier.fillMaxWidth().background(ForgeColors.Graphite).padding(8.dp)) {
        Text("Filters & effects", style = MaterialTheme.typography.titleMedium, color = ForgeColors.TextPrimary)
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            com.artforge.mobile.engine.effects.EffectType.entries.forEach { effect ->
                OptionChip(effect.name, selected == effect) { selected = effect }
            }
        }
        ValueSlider("Amount", amount, 0f..2f, { amount = it })
        ValueSlider("Radius", radius, 0f..80f, { radius = it }, formatter = { "${it.toInt()} px" })
        ValueSlider("Angle", angle, 0f..360f, { angle = it }, formatter = { "${it.toInt()}°" })
        ValueSlider("Size", size, 1f..100f, { size = it }, formatter = { "${it.toInt()} px" })
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionChip("Apply to layer") {
                vm.applyEffect(
                    com.artforge.mobile.engine.effects.EffectParams(
                        type = selected,
                        amount = amount,
                        radius = radius,
                        angle = angle,
                        size = size,
                    ),
                )
            }
        }
        Text(
            "Effects are applied to the active layer's current drawing. Undo restores the previous pixels.",
            style = MaterialTheme.typography.bodySmall,
            color = ForgeColors.TextMuted,
        )
    }
}
