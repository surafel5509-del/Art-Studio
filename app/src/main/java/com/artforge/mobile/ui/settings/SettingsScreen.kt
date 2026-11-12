package com.artforge.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.artforge.mobile.data.settings.CanvasScale
import com.artforge.mobile.data.settings.GridStyle
import com.artforge.mobile.data.settings.ThemeMode
import com.artforge.mobile.ui.common.PanelHeader
import com.artforge.mobile.ui.common.ToggleRow
import com.artforge.mobile.ui.common.ValueSlider
import com.artforge.mobile.ui.theme.ForgeColors

/** Every persisted preference, grouped and editable. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().background(ForgeColors.Charcoal)) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = ForgeColors.TextPrimary) }
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = ForgeColors.TextPrimary)
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {

            SettingsSection("Interface") {
                ChipRow(
                    options = ThemeMode.entries.map { it.name },
                    selected = settings.theme.name,
                    onSelect = { name -> vm.update { it.copy(theme = ThemeMode.valueOf(name)) } },
                )
                ValueSlider("UI scale", settings.uiScale, 0.8f..1.4f, { v -> vm.update { it.copy(uiScale = v) } }, formatter = { "${(it * 100).toInt()}%" })
                ChipRow(
                    options = CanvasScale.entries.map { it.name },
                    selected = settings.canvasScale.name,
                    onSelect = { name -> vm.update { it.copy(canvasScale = CanvasScale.valueOf(name)) } },
                )
                ToggleRow("Left-handed layout", settings.useLeftHandLayout, { v -> vm.update { it.copy(useLeftHandLayout = v) } })
                ToggleRow("Haptic feedback", settings.hapticFeedback, { v -> vm.update { it.copy(hapticFeedback = v) } })
                ToggleRow("Reduce motion", settings.reduceMotion, { v -> vm.update { it.copy(reduceMotion = v) } })
            }

            SettingsSection("Stylus & input") {
                ToggleRow("Pressure sensitivity", settings.pressureEnabled, { v -> vm.update { it.copy(pressureEnabled = v) } })
                ToggleRow("Tilt support", settings.tiltEnabled, { v -> vm.update { it.copy(tiltEnabled = v) } })
                ToggleRow("Palm rejection", settings.palmRejection, { v -> vm.update { it.copy(palmRejection = v) } })
                ValueSlider(
                    "Pressure curve", settings.pressureCurve, 0.2f..3f,
                    { v -> vm.update { it.copy(pressureCurve = v) } },
                    formatter = { String.format("%.2f", it) },
                )
                ToggleRow("One-finger drawing", settings.oneFingerDraw, { v -> vm.update { it.copy(oneFingerDraw = v) } })
                ToggleRow("Two-finger rotation", settings.gestureRotate, { v -> vm.update { it.copy(gestureRotate = v) } })
            }

            SettingsSection("Brush") {
                ValueSlider("Default stabiliser", settings.strokeStabilizer, 0f..0.95f, { v -> vm.update { it.copy(strokeStabilizer = v) } })
                ToggleRow("Brush cursor", settings.brushCursorEnabled, { v -> vm.update { it.copy(brushCursorEnabled = v) } })
                ValueSlider(
                    "Undo steps", settings.maxUndoSteps.toFloat(), 20f..200f,
                    { v -> vm.update { it.copy(maxUndoSteps = v.toInt()) } },
                    formatter = { "${it.toInt()}" },
                )
            }

            SettingsSection("Canvas") {
                ChipRow(
                    options = GridStyle.entries.map { it.name },
                    selected = settings.gridStyle.name,
                    onSelect = { name -> vm.update { it.copy(gridStyle = GridStyle.valueOf(name)) } },
                )
                ToggleRow("Show guides", settings.showGuides, { v -> vm.update { it.copy(showGuides = v) } })
                ToggleRow("Pixel-perfect strokes", settings.pixelPerfect, { v -> vm.update { it.copy(pixelPerfect = v) } })
                ValueSlider(
                    "Default width", settings.defaultCanvasWidth.toFloat(), 512f..8192f,
                    { v -> vm.update { it.copy(defaultCanvasWidth = v.toInt()) } },
                    formatter = { "${it.toInt()}" },
                )
                ValueSlider(
                    "Default height", settings.defaultCanvasHeight.toFloat(), 512f..8192f,
                    { v -> vm.update { it.copy(defaultCanvasHeight = v.toInt()) } },
                    formatter = { "${it.toInt()}" },
                )
            }

            SettingsSection("Animation") {
                ValueSlider(
                    "Default FPS", settings.defaultFps.toFloat(), 6f..60f,
                    { v -> vm.update { it.copy(defaultFps = v.toInt()) } },
                    formatter = { "${it.toInt()}" },
                )
                ValueSlider(
                    "Default frames", settings.defaultFrameCount.toFloat(), 4f..240f,
                    { v -> vm.update { it.copy(defaultFrameCount = v.toInt()) } },
                    formatter = { "${it.toInt()}" },
                )
                ToggleRow("Onion skin by default", settings.onionSkinEnabled, { v -> vm.update { it.copy(onionSkinEnabled = v) } })
                ValueSlider(
                    "Onion opacity", settings.onionOpacity, 0.05f..0.9f,
                    { v -> vm.update { it.copy(onionOpacity = v) } },
                    formatter = { "${(it * 100).toInt()}%" },
                )
                ToggleRow("Loop playback", settings.loopPlayback, { v -> vm.update { it.copy(loopPlayback = v) } })
            }

            SettingsSection("Audio") {
                ValueSlider("Volume", settings.audioVolume, 0f..1f, { v -> vm.update { it.copy(audioVolume = v) } }, formatter = { "${(it * 100).toInt()}%" })
                ToggleRow("Playback sync", settings.audioSyncEnabled, { v -> vm.update { it.copy(audioSyncEnabled = v) } })
            }

            SettingsSection("Performance & storage") {
                ValueSlider(
                    "Frame cache", settings.frameCacheMb.toFloat(), 64f..768f,
                    { v -> vm.update { it.copy(frameCacheMb = v.toInt()) } },
                    formatter = { "${it.toInt()} MB" },
                )
                ValueSlider("Render quality", settings.renderQuality, 0.4f..1f, { v -> vm.update { it.copy(renderQuality = v) } }, formatter = { "${(it * 100).toInt()}%" })
                ToggleRow("Autosave", settings.autosaveEnabled, { v -> vm.update { it.copy(autosaveEnabled = v) } })
                ValueSlider(
                    "Autosave interval", settings.autosaveIntervalSeconds.toFloat(), 30f..600f,
                    { v -> vm.update { it.copy(autosaveIntervalSeconds = v.toInt()) } },
                    formatter = { "${it.toInt()} s" },
                )
                ToggleRow("Keep version history", settings.versionHistoryEnabled, { v -> vm.update { it.copy(versionHistoryEnabled = v) } })
                ToggleRow("Memory warnings", settings.memoryWarningEnabled, { v -> vm.update { it.copy(memoryWarningEnabled = v) } })
            }

            SettingsSection("Export") {
                ChipRow(
                    options = listOf("png", "jpg", "webp"),
                    selected = settings.defaultExportFormat,
                    onSelect = { v -> vm.update { it.copy(defaultExportFormat = v) } },
                )
                ValueSlider(
                    "JPEG quality", settings.exportQuality.toFloat(), 40f..100f,
                    { v -> vm.update { it.copy(exportQuality = v.toInt()) } },
                    formatter = { "${it.toInt()}" },
                )
                ToggleRow("Include background", settings.exportBackground, { v -> vm.update { it.copy(exportBackground = v) } })
            }

            SettingsSection("Accessibility") {
                ToggleRow("Large touch targets", settings.largeTouchTargets, { v -> vm.update { it.copy(largeTouchTargets = v) } })
                ToggleRow("High contrast", settings.highContrast, { v -> vm.update { it.copy(highContrast = v) } })
            }

            SettingsSection("About") {
                Text(
                    "ArtForge Mobile 1.0.0 — offline-first art & animation studio.\n" +
                        "All projects are stored on this device. No account, no network access, no telemetry.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ForgeColors.TextMuted,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        PanelHeader(title)
        Column(Modifier.fillMaxWidth().background(ForgeColors.Graphite), content = content)
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun ChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (option == selected) ForgeColors.Accent else ForgeColors.GraphiteElevated)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    option.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (option == selected) Color(0xFF0B0C0F) else ForgeColors.TextSecondary,
                )
            }
        }
    }
}
