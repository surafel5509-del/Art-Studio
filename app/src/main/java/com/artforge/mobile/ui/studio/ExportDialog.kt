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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.artforge.mobile.core.model.ProjectDocument
import com.artforge.mobile.data.export.AnimationExportOptions
import com.artforge.mobile.data.export.AnimationFormat
import com.artforge.mobile.data.export.ExportEngine
import com.artforge.mobile.data.export.ImageExportOptions
import com.artforge.mobile.data.export.ImageFormat
import com.artforge.mobile.ui.common.ToggleRow
import com.artforge.mobile.ui.common.ValueSlider
import com.artforge.mobile.ui.theme.ForgeColors

/**
 * Export sheet. Options map 1:1 to the real export engine, so every control here
 * changes the produced file (resolution, quality, range, loop, transparency).
 */
@Composable
fun ExportDialog(
    document: ProjectDocument?,
    defaultFps: Int,
    isAnimation: Boolean,
    onDismiss: () -> Unit,
    onExportImage: (ImageExportOptions) -> Unit,
    onExportAnimation: (AnimationExportOptions) -> Unit,
) {
    if (document == null) return
    var tab by remember { mutableStateOf(if (isAnimation) 1 else 0) }
    var imageFormat by remember { mutableStateOf(ImageFormat.Png) }
    var animationFormat by remember { mutableStateOf(AnimationFormat.Gif) }
    var quality by remember { mutableStateOf(92f) }
    var scale by remember { mutableStateOf(1f) }
    var includeBackground by remember { mutableStateOf(true) }
    var startFrame by remember { mutableStateOf(0f) }
    var endFrame by remember { mutableStateOf((document.animation.frameCount - 1).toFloat()) }
    var spriteColumns by remember { mutableStateOf(6f) }
    var loop by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TabChip("Image", tab == 0) { tab = 0 }
                    if (isAnimation) TabChip("Animation", tab == 1) { tab = 1 }
                    TabChip("Sprite sheet", tab == 2) { tab = 2 }
                }
                Spacer(Modifier.height(10.dp))

                when (tab) {
                    0 -> {
                        Text("Format", style = MaterialTheme.typography.labelMedium, color = ForgeColors.TextMuted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(ImageFormat.Png, ImageFormat.Jpeg, ImageFormat.WebP, ImageFormat.Svg).forEach { fmt ->
                                TabChip(fmt.name, imageFormat == fmt) { imageFormat = fmt }
                            }
                        }
                        ValueSlider("Scale", scale, 0.25f..2f, { scale = it }, formatter = { "${(it * 100).toInt()}%" })
                        ValueSlider("Quality", quality, 40f..100f, { quality = it }, formatter = { "${it.toInt()}" })
                        ToggleRow("Include background", includeBackground, { includeBackground = it })
                        ValueSlider(
                            "Frame",
                            startFrame,
                            0f..(document.animation.frameCount - 1).coerceAtLeast(1).toFloat(),
                            { startFrame = it },
                            formatter = { "${it.toInt() + 1}" },
                        )
                    }

                    1 -> {
                        Text("Format", style = MaterialTheme.typography.labelMedium, color = ForgeColors.TextMuted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(AnimationFormat.Gif, AnimationFormat.Mp4, AnimationFormat.WebM, AnimationFormat.PngSequence)
                                .forEach { fmt -> TabChip(fmt.name, animationFormat == fmt) { animationFormat = fmt } }
                        }
                        ValueSlider("Scale", scale, 0.25f..2f, { scale = it }, formatter = { "${(it * 100).toInt()}%" })
                        ValueSlider("Quality", quality, 40f..100f, { quality = it }, formatter = { "${it.toInt()}" })
                        ValueSlider(
                            "Start frame",
                            startFrame,
                            0f..(document.animation.frameCount - 1).coerceAtLeast(1).toFloat(),
                            { startFrame = it },
                            formatter = { "${it.toInt() + 1}" },
                        )
                        ValueSlider(
                            "End frame",
                            endFrame,
                            startFrame..(document.animation.frameCount - 1).coerceAtLeast(1).toFloat(),
                            { endFrame = it },
                            formatter = { "${it.toInt() + 1}" },
                        )
                        ToggleRow("Loop", loop, { loop = it })
                        ToggleRow("Include background", includeBackground, { includeBackground = it })
                        Text(
                            "GIF and PNG sequences are generated fully offline. MP4/WebM use the device " +
                                "hardware encoder and may be unavailable on rare devices.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ForgeColors.TextMuted,
                        )
                    }

                    else -> {
                        ValueSlider("Columns", spriteColumns, 1f..12f, { spriteColumns = it }, formatter = { "${it.toInt()}" })
                        ValueSlider("Scale", scale, 0.25f..2f, { scale = it }, formatter = { "${(it * 100).toInt()}%" })
                        ToggleRow("Include background", includeBackground, { includeBackground = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (tab) {
                    0 -> onExportImage(
                        ImageExportOptions(
                            format = imageFormat,
                            quality = quality.toInt(),
                            scale = ExportEngine.clampScale(scale, document),
                            includeBackground = includeBackground,
                            frameIndex = startFrame.toInt(),
                            transparent = !includeBackground,
                        ),
                    )
                    1 -> onExportAnimation(
                        AnimationExportOptions(
                            format = animationFormat,
                            fps = defaultFps,
                            startFrame = startFrame.toInt(),
                            endFrame = endFrame.toInt(),
                            scale = ExportEngine.clampScale(scale, document),
                            quality = quality.toInt(),
                            includeBackground = includeBackground,
                            loop = loop,
                        ),
                    )
                    else -> onExportAnimation(
                        AnimationExportOptions(
                            format = AnimationFormat.SpriteSheet,
                            fps = defaultFps,
                            startFrame = 0,
                            endFrame = (document.animation.frameCount - 1).coerceAtLeast(0),
                            scale = ExportEngine.clampScale(scale, document),
                            includeBackground = includeBackground,
                            spriteColumns = spriteColumns.toInt(),
                        ),
                    )
                }
            }) { Text("Export") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) ForgeColors.Accent else ForgeColors.GraphiteElevated)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color(0xFF0B0C0F) else ForgeColors.TextSecondary,
        )
    }
}
