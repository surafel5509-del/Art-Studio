package com.artforge.mobile.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.artforge.mobile.engine.animation.AnimationEngine
import com.artforge.mobile.ui.theme.ForgeColors
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The animation timeline / exposure sheet.
 *
 * Left column: layer names. Right area: one row per layer with a cell per frame.
 * Filled cells are drawings, outlined cells are holds, the playhead marks the
 * current frame. Tapping a cell jumps to that frame; long-press drag moves an
 * exposure. Frame operations live in the header toolbar.
 */
@Composable
fun AnimationTimeline(
    state: StudioState,
    vm: StudioViewModel,
    modifier: Modifier = Modifier,
) {
    val doc = state.document ?: return
    val anim = doc.animation
    val frameCount = anim.frameCount.coerceAtLeast(1)
    var cellWidth by remember { mutableStateOf(22f) }
    val playheadColor = ForgeColors.AccentWarm
    var showOnionMenu by remember { mutableStateOf(false) }

    Column(modifier.background(ForgeColors.Graphite)) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = vm::firstFrame, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.SkipPrevious, "First", tint = ForgeColors.TextSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = vm::previousFrame, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.FastRewind, "Previous", tint = ForgeColors.TextSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = vm::togglePlayback, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    "Play",
                    tint = ForgeColors.Accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            IconButton(onClick = vm::stopPlayback, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Stop, "Stop", tint = ForgeColors.TextSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = vm::nextFrame, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.FastForward, "Next", tint = ForgeColors.TextSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = vm::lastFrame, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.SkipNext, "Last", tint = ForgeColors.TextSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { vm.setLoop(!anim.loop) }, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Filled.Loop,
                    "Loop",
                    tint = if (anim.loop) ForgeColors.Accent else ForgeColors.TextMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "F ${state.frame + 1}/$frameCount",
                style = MaterialTheme.typography.labelMedium,
                color = ForgeColors.TextSecondary,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.addBlankFrame() }) { Text("+ Blank", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = { vm.duplicateFrame() }) { Text("Duplicate", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = { vm.insertFrameSlot() }) { Text("Insert", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = { vm.deleteFrame() }) { Text("Delete", style = MaterialTheme.typography.labelSmall) }
            Box {
                TextButton(onClick = { showOnionMenu = true }) { Text("Onion", style = MaterialTheme.typography.labelSmall) }
                DropdownMenu(expanded = showOnionMenu, onDismissRequest = { showOnionMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (anim.onionSkin.enabled) "Disable onion skin" else "Enable onion skin") },
                        onClick = {
                            vm.setOnionSkin(anim.onionSkin.copy(enabled = !anim.onionSkin.enabled))
                            showOnionMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Previous frames: ${anim.onionSkin.previousFrames}") },
                        onClick = {
                            vm.setOnionSkin(anim.onionSkin.copy(previousFrames = (anim.onionSkin.previousFrames + 1) % 5))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Next frames: ${anim.onionSkin.nextFrames}") },
                        onClick = {
                            vm.setOnionSkin(anim.onionSkin.copy(nextFrames = (anim.onionSkin.nextFrames + 1) % 5))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Opacity: ${(anim.onionSkin.opacity * 100).toInt()}%") },
                        onClick = {
                            val next = ((anim.onionSkin.opacity + 0.15f) % 0.9f).coerceAtLeast(0.1f)
                            vm.setOnionSkin(anim.onionSkin.copy(opacity = next))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Previous tint") },
                        onClick = {
                            vm.setOnionSkin(anim.onionSkin.copy(previousTint = 0xFFFF5C7A.toInt()))
                            showOnionMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Next tint") },
                        onClick = {
                            vm.setOnionSkin(anim.onionSkin.copy(nextTint = 0xFF4CD9FF.toInt()))
                            showOnionMenu = false
                        },
                    )
                }
            }
            Box {
                var fpsMenu by remember { mutableStateOf(false) }
                TextButton(onClick = { fpsMenu = true }) {
                    Text("${anim.fps} fps", style = MaterialTheme.typography.labelSmall)
                }
                DropdownMenu(expanded = fpsMenu, onDismissRequest = { fpsMenu = false }) {
                    AnimationEngine.FPS_OPTIONS.forEach { fps ->
                        DropdownMenuItem(
                            text = { Text("$fps fps") },
                            onClick = {
                                vm.setFps(fps)
                                fpsMenu = false
                            },
                        )
                    }
                }
            }
        }

        Row(Modifier.weight(1f).fillMaxWidth()) {
            // Layer column
            Column(Modifier.width(140.dp).fillMaxHeight().background(ForgeColors.GraphiteElevated)) {
                Box(Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    Text("EXPOSURE", style = MaterialTheme.typography.labelSmall, color = ForgeColors.TextMuted)
                }
                LazyColumn {
                    items(doc.layers) { layer ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(34.dp)
                                .background(
                                    if (layer.id == state.activeLayerId) ForgeColors.Accent.copy(alpha = 0.14f)
                                    else Color.Transparent,
                                )
                                .clickable { vm.selectLayer(layer.id) }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(layer.colorTag)),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                layer.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (layer.visible) ForgeColors.TextSecondary else ForgeColors.TextMuted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            // Frame grid
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val horizontal = rememberScrollState()
                Column(Modifier.fillMaxSize()) {
                    // Frame numbers
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(22.dp)
                            .horizontalScroll(horizontal),
                    ) {
                        Canvas(
                            Modifier
                                .width((frameCount * cellWidth).dp)
                                .height(22.dp),
                        ) {
                            val w = cellWidth.dp.toPx()
                            for (f in 0 until frameCount) {
                                val x = f * w
                                if (f % 5 == 0) {
                                    drawRect(
                                        color = Color(0xFF2E323B),
                                        topLeft = Offset(x, 0f),
                                        size = Size(1f, size.height),
                                    )
                                    drawContext.canvas.nativeCanvas.drawText(
                                        "${f + 1}",
                                        x + 3f,
                                        size.height * 0.7f,
                                        android.graphics.Paint().apply {
                                            color = 0xFF9AA3B2.toInt()
                                            textSize = 9.dp.toPx()
                                        },
                                    )
                                } else {
                                    drawRect(
                                        color = Color(0xFF22252C),
                                        topLeft = Offset(x, 0f),
                                        size = Size(1f, size.height * 0.5f),
                                    )
                                }
                            }
                        }
                    }

                    LazyColumn(Modifier.fillMaxSize()) {
                        items(doc.layers) { layer ->
                            val refs = remember(layer) { layer.frames.sortedBy { it.offset } }
                            Canvas(
                                Modifier
                                    .fillMaxWidth()
                                    .height(34.dp)
                                    .horizontalScroll(horizontal)
                                    .width((frameCount * cellWidth).dp)
                                    .pointerInput(layer.id, cellWidth, frameCount) {
                                        detectTapGestures { offset ->
                                            val frame = (offset.x / size.width * frameCount).toInt().coerceIn(0, frameCount - 1)
                                            vm.selectLayer(layer.id)
                                            vm.setFrame(frame)
                                        }
                                    },
                            ) {
                                val cellW = size.width / frameCount
                                val rowH = size.height
                                // holds
                                for (i in refs.indices) {
                                    val ref = refs[i]
                                    val startFrame = floor(ref.offset).toInt()
                                    val nextStart = refs.getOrNull(i + 1)?.let { floor(it.offset).toInt() } ?: frameCount
                                    if (startFrame >= frameCount) continue
                                    val endFrame = min(frameCount, max(startFrame + 1, nextStart))
                                    val left = startFrame * cellW
                                    val right = endFrame * cellW
                                    drawRect(
                                        color = if (layer.id == state.activeLayerId) Color(0xFF33406E) else Color(0xFF2A2E38),
                                        topLeft = Offset(left + 1f, 3f),
                                        size = Size(right - left - 2f, rowH - 6f),
                                    )
                                    drawRect(
                                        color = ForgeColors.Accent,
                                        topLeft = Offset(left + 1f, 3f),
                                        size = Size(3f, rowH - 6f),
                                    )
                                }
                                // grid
                                for (f in 0 until frameCount) {
                                    drawRect(
                                        color = Color(0x33FFFFFF),
                                        topLeft = Offset(f * cellW, 0f),
                                        size = Size(0.7f, rowH),
                                    )
                                }
                                // markers
                                doc.animation.markers.forEach { marker ->
                                    if (marker.frame in 0 until frameCount) {
                                        drawRect(
                                            color = Color(marker.color),
                                            topLeft = Offset(marker.frame * cellW, 0f),
                                            size = Size(cellW, 2.5f),
                                        )
                                    }
                                }
                                // selected frames
                                state.selectedFrames.forEach { f ->
                                    if (f in 0 until frameCount) {
                                        drawRect(
                                            color = Color(0x336C8CFF),
                                            topLeft = Offset(f * cellW, 0f),
                                            size = Size(cellW, rowH),
                                        )
                                    }
                                }
                                // playhead
                                drawRect(
                                    color = playheadColor,
                                    topLeft = Offset(state.frame * cellW, 0f),
                                    size = Size(1.4f, rowH),
                                )
                            }
                        }
                    }
                }

                // Playhead overlay + scrub area
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(cellWidth, frameCount) {
                            detectDragGestures { change, _ ->
                                val frame = (change.position.x / size.width * frameCount).toInt()
                                vm.setFrame(frame.coerceIn(0, frameCount - 1))
                            }
                        },
                )
            }
        }

        // Bottom controls: zoom + fps + onion summary
        Row(
            Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Zoom", style = MaterialTheme.typography.labelSmall, color = ForgeColors.TextMuted)
            androidx.compose.material3.Slider(
                value = cellWidth,
                onValueChange = { cellWidth = it },
                valueRange = 10f..56f,
                modifier = Modifier.width(120.dp).height(20.dp),
            )
            Text(
                "Onion ${if (anim.onionSkin.enabled) "on" else "off"} · ${anim.onionSkin.previousFrames}◀ ${anim.onionSkin.nextFrames}▶",
                style = MaterialTheme.typography.labelSmall,
                color = ForgeColors.TextMuted,
            )
            Spacer(Modifier.weight(1f))
            val scenes = anim.scenes
            if (scenes.isNotEmpty()) {
                Text(
                    "Scene: ${scenes.first().name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeColors.TextMuted,
                )
            }
        }
    }
}

/** Frame strip used by the compact (phone) layout. */
@Composable
fun FrameStrip(state: StudioState, vm: StudioViewModel, modifier: Modifier = Modifier) {
    val doc = state.document ?: return
    val count = doc.animation.frameCount
    Row(modifier.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState())) {
        for (f in 0 until count) {
            val active = f == state.frame
            Box(
                Modifier
                    .width(26.dp)
                    .fillMaxHeight()
                    .padding(1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (active) ForgeColors.Accent else ForgeColors.GraphiteElevated)
                    .clickable { vm.setFrame(f) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${f + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) Color(0xFF0B0C0F) else ForgeColors.TextMuted,
                )
            }
        }
    }
}
