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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artforge.mobile.core.model.BrushCategory
import com.artforge.mobile.core.model.BrushPreset
import com.artforge.mobile.engine.draw.BrushLibrary
import com.artforge.mobile.ui.common.PanelHeader
import com.artforge.mobile.ui.common.ValueSlider
import com.artforge.mobile.ui.theme.ForgeColors

/** Compact brush controls (size / opacity / flow / hardness / spacing / smoothing). */
@Composable
fun BrushPanel(
    tools: ToolState,
    vm: StudioViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(ForgeColors.Graphite)) {
        PanelHeader("Brush") {
            Text(
                tools.brush?.name ?: "Custom",
                style = MaterialTheme.typography.labelSmall,
                color = ForgeColors.Accent,
            )
        }
        ValueSlider("Size", tools.brushSize, 1f..400f, vm::setBrushSize, formatter = { "${it.toInt()} px" })
        ValueSlider("Opacity", tools.opacity, 0.01f..1f, vm::setOpacity, formatter = { "${(it * 100).toInt()}%" })
        ValueSlider("Flow", tools.flow, 0.01f..1f, vm::setFlow, formatter = { "${(it * 100).toInt()}%" })
        ValueSlider("Hardness", tools.hardness, 0f..1f, vm::setHardness, formatter = { "${(it * 100).toInt()}%" })
        ValueSlider("Spacing", tools.spacing, 0.01f..1f, vm::setSpacing, formatter = { "${(it * 100).toInt()}%" })
        ValueSlider("Stabiliser", tools.smoothing, 0f..0.95f, vm::setSmoothing, formatter = { "${(it * 100).toInt()}%" })
    }
}

/** Full brush browser with search, categories, favourites and preview strokes. */
@Composable
fun BrushGallerySheet(tools: ToolState, vm: StudioViewModel) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<BrushCategory?>(null) }
    val filtered = remember(query, category) {
        BrushLibrary.search(query).filter { category == null || it.category == category }
    }

    Column(Modifier.fillMaxWidth().background(ForgeColors.Graphite).padding(8.dp)) {
        Text("Brush library", style = MaterialTheme.typography.titleMedium, color = ForgeColors.TextPrimary)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search brushes") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CategoryChip("All", category == null) { category = null }
            BrushLibrary.categories().forEach { c ->
                CategoryChip(c.name, category == c) { category = c }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
            items(filtered, key = { it.id }) { preset ->
                BrushRow(preset, tools.brush?.id == preset.id) { vm.selectBrush(preset) }
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) ForgeColors.Accent else ForgeColors.GraphiteElevated)
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Color(0xFF0B0C0F) else ForgeColors.TextSecondary,
        )
    }
}

@Composable
private fun BrushRow(preset: BrushPreset, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) ForgeColors.Accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrushPreview(preset)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(preset.name, style = MaterialTheme.typography.bodyMedium, color = ForgeColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${preset.category.name} · ${preset.settings.size.toInt()}px · ${(preset.settings.opacity * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = ForgeColors.TextMuted,
            )
        }
        if (preset.favorite) {
            androidx.compose.material3.Icon(
                Icons.Filled.Star,
                "Favourite",
                tint = ForgeColors.AccentWarm,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Draws a real preview stroke for the brush using its actual parameters so the
 * swatch matches what the brush will do on canvas.
 */
@Composable
fun BrushPreview(preset: BrushPreset, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(width = 56.dp, height = 26.dp)) {
        val s = preset.settings
        val midY = size.height / 2f
        val steps = 30
        val maxR = (s.size.coerceIn(1f, 40f)) * size.height / 60f
        for (i in 0 until steps) {
            val t = i / (steps - 1f)
            val x = 4f + (size.width - 8f) * t
            val pressure = 0.35f + 0.65f * kotlin.math.sin(t * Math.PI).toFloat()
            var r = (maxR / 2f) * (1f - (1f - pressure) * s.pressureSize)
            if (r < 0.4f) r = 0.4f
            val alpha = (s.opacity * (1f - (1f - pressure) * s.pressureOpacity)).coerceIn(0.05f, 1f)
            drawCircle(
                color = Color(0xFFFFFFFF).copy(alpha = alpha),
                radius = r,
                center = androidx.compose.ui.geometry.Offset(x, midY + kotlin.math.sin(t * 6f) * 2f),
            )
        }
    }
}
