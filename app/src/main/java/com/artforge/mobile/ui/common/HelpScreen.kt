package com.artforge.mobile.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artforge.mobile.ui.theme.ForgeColors

/** Offline help: gestures, shortcuts and an honest feature status list. */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(ForgeColors.Charcoal)) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = ForgeColors.TextPrimary) }
            Text("Help & shortcuts", style = MaterialTheme.typography.titleLarge, color = ForgeColors.TextPrimary)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            HelpSection("Gestures", listOf(
                "One finger — draw (when enabled in settings)",
                "Two fingers — pan, pinch-zoom, twist-rotate",
                "Double tap — fit canvas to screen",
                "Stylus pressure / tilt / rotation — variable stroke",
                "Palm rejection — touches after a stylus lift are ignored for 800 ms",
            ))
            HelpSection("Keyboard shortcuts", listOf(
                "Ctrl+Z / Ctrl+Shift+Z — undo / redo",
                "Ctrl+S — save, Ctrl+O — open",
                "B brush · P pencil · E eraser · I eyedropper · G fill",
                "L line · R rectangle · O ellipse · T text · M rect select",
                "V move · F transform · C crop · W magic wand · Space hand",
                "Left / Right — previous / next frame",
                "F5 — play/pause, F6 — duplicate frame",
            ))
            HelpSection("Animation workflow", listOf(
                "Pick the animation layer, then draw on each frame in the timeline.",
                "Frames that hold a drawing are shown outlined; drawing on a hold creates a new drawing.",
                "Onion skin shows previous (red) and next (blue) drawings.",
                "Export GIF/MP4/WebM/PNG sequence or a sprite sheet from the export sheet.",
                "Audio tracks can be imported, trimmed and synced to playback.",
            ))
            HelpSection("Feature status (honest notes)", listOf(
                "Implemented: painting engine, brushes, layers, blend modes, masks, clipping,",
                "  selections, fills, gradients, shapes, text, vector data, effects, comic panels,",
                "  bubbles, pixel tools, sprite sheets, frame-by-frame animation, onion skin,",
                "  keyframe channels, rigs + IK, audio import/waveform/trim, GIF/PNG/MP4/WebM export.",
                "Limitations: MP4/WebM depend on the device encoder; SVG import supports path data",
                "  (not embedded raster/CSS); 3D brushes and cloud sync are intentionally absent.",
            ))
        }
    }
}

@Composable
private fun HelpSection(title: String, lines: List<String>) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = ForgeColors.TextPrimary)
    Spacer(Modifier.height(4.dp))
    lines.forEach { line ->
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = ForgeColors.TextSecondary,
            modifier = Modifier.padding(vertical = 1.dp),
        )
    }
    Spacer(Modifier.height(14.dp))
}
