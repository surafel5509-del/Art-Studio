@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.artforge.mobile.ui.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.artforge.mobile.core.model.ProjectKind
import com.artforge.mobile.core.model.ToolId
import com.artforge.mobile.ui.common.ColorPickerDialog
import com.artforge.mobile.ui.common.ColorSwatch
import com.artforge.mobile.ui.theme.ForgeColors

/**
 * The studio workspace.
 *
 * Layout adapts by width:
 *  - compact (phones, portrait): rail + canvas, with slide-up sheets for layers,
 *    colour and tool options,
 *  - expanded (tablets, foldables, landscape): permanent left tool rail, right
 *    inspector column (layers / colour / brush), bottom timeline.
 */
@Composable
fun StudioScreen(
    projectId: String,
    onBack: () -> Unit,
    vm: StudioViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tools by vm.tools.collectAsStateWithLifecycle()
    val view by vm.view.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val playback by vm.playbackState.collectAsStateWithLifecycle()

    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp
    val isTablet = screenWidth >= 840
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

    var showLayersSheet by remember { mutableStateOf(false) }
    var showColorSheet by remember { mutableStateOf(false) }
    var showBrushSheet by remember { mutableStateOf(false) }
    var showTimelineSheet by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showEffectsSheet by remember { mutableStateOf(false) }
    var showToolOptions by remember { mutableStateOf(false) }
    var colorPickerTarget by remember { mutableStateOf<ColorTarget?>(null) }
    var pendingBack by remember { mutableStateOf(false) }
    var viewportWidth by remember { mutableStateOf(1080f) }
    var viewportHeight by remember { mutableStateOf(1920f) }
    var fitScale by remember { mutableStateOf(1f) }

    LaunchedEffect(projectId) { vm.openProject(projectId) }

    LaunchedEffect(state.isPlaying) {
        // Keep the timeline in sync with the playback controller.
    }

    BackHandler {
        if (pendingBack) {
            onBack()
        } else {
            pendingBack = true
            vm.saveProject(userInitiated = false)
        }
    }

    LaunchedEffect(pendingBack) {
        if (pendingBack) {
            kotlinx.coroutines.delay(180)
            pendingBack = false
        }
    }

    if (colorPickerTarget != null) {
        ColorPickerDialog(
            initialColor = when (colorPickerTarget) {
                ColorTarget.Primary -> tools.primaryColor
                ColorTarget.Secondary -> tools.secondaryColor
                else -> tools.primaryColor
            },
            recentColors = tools.recentColors,
            onDismiss = { colorPickerTarget = null },
            onConfirm = { color ->
                when (colorPickerTarget) {
                    ColorTarget.Secondary -> vm.setSecondaryColor(color)
                    else -> vm.setPrimaryColor(color)
                }
                colorPickerTarget = null
            },
        )
    }

    if (showExportDialog) {
        ExportDialog(
            document = state.document,
            defaultFps = state.document?.animation?.fps ?: 24,
            isAnimation = state.document?.kind == ProjectKind.Animation,
            onDismiss = { showExportDialog = false },
            onExportImage = { options ->
                showExportDialog = false
                vm.exportImage(options) { uri ->
                    uri?.let { com.artforge.mobile.ui.common.ShareHelper.share(it, "image/*", "Export") }
                }
            },
            onExportAnimation = { options ->
                showExportDialog = false
                vm.exportAnimation(options) { uri ->
                    uri?.let { com.artforge.mobile.ui.common.ShareHelper.share(it, "video/*", "Animation") }
                }
            },
        )
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            StudioTopBar(
                state = state,
                onBack = { if (state.dirty) vm.saveProject(); onBack() },
                onUndo = vm::undo,
                onRedo = vm::redo,
                onSave = { vm.saveProject() },
                onExport = { showExportDialog = true },
                onRename = vm::renameProject,
            )

            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (isTablet || isLandscape) {
                    ToolRail(
                        selected = tools.tool,
                        onSelect = vm::selectTool,
                        vertical = true,
                        modifier = Modifier.fillMaxHeight().width(64.dp),
                    )
                } else {
                    // compact phones keep the rail as a slim vertical strip
                    ToolRail(
                        selected = tools.tool,
                        onSelect = vm::selectTool,
                        vertical = true,
                        modifier = Modifier.fillMaxHeight().width(56.dp),
                    )
                }

                Column(Modifier.weight(1f).fillMaxHeight()) {
                    CanvasSurface(
                        state = state,
                        view = view,
                        modifier = Modifier.weight(1f),
                        oneFingerDraw = settings.oneFingerDraw,
                        palmRejection = settings.palmRejection,
                        enableRotation = settings.gestureRotate,
                        showCheckerboard = true,
                        onPointerDown = { p, stylus -> vm.onPointerDown(p, settings.pressureEnabled && stylus) },
                        onPointerMove = { p -> vm.onPointerMove(p, settings.pressureEnabled) },
                        onPointerUp = { p -> vm.onPointerUp(p) },
                        onPan = { dx, dy -> vm.panBy(dx, dy) },
                        onZoom = { f, fx, fy -> vm.zoomBy(f, fx, fy) },
                        onRotate = { d -> vm.rotateBy(d) },
                        onDoubleTap = { vm.resetView() },
                        onViewportMeasured = { w, h, scale ->
                            viewportWidth = w
                            viewportHeight = h
                            fitScale = scale
                        },
                    )

                    QuickActionBar(
                        tools = tools,
                        view = view,
                        isAnimation = state.document?.kind == ProjectKind.Animation,
                        onBrushSize = vm::setBrushSize,
                        onZoomIn = { vm.zoomBy(1.25f) },
                        onZoomOut = { vm.zoomBy(0.8f) },
                        onFit = { vm.fitToScreen(viewportWidth, viewportHeight) },
                        onRotate = { vm.rotateBy(15f) },
                        onFlip = vm::toggleFlipCanvas,
                        onLayers = { showLayersSheet = true },
                        onColor = { showColorSheet = true },
                        onBrush = { showBrushSheet = true },
                        onEffects = { showEffectsSheet = true },
                        onToolOptions = { showToolOptions = true },
                    )

                    if (isTablet && isLandscape) {
                        AnimationTimeline(
                            state = state,
                            vm = vm,
                            modifier = Modifier.fillMaxWidth().height(196.dp),
                        )
                    } else if (state.document?.kind == ProjectKind.Animation) {
                        CompactTimeline(
                            state = state,
                            isPlaying = playback.isPlaying,
                            onTogglePlay = vm::togglePlayback,
                            onPrev = vm::previousFrame,
                            onNext = vm::nextFrame,
                            onExpand = { showTimelineSheet = true },
                            onScrub = vm::setFrame,
                        )
                    }
                }

                if (isTablet && isLandscape) {
                    Column(
                        Modifier
                            .fillMaxHeight()
                            .width(288.dp)
                            .background(ForgeColors.Graphite)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ColorPanel(tools, vm, onPickColor = { colorPickerTarget = it })
                        LayerPanel(state, vm, modifier = Modifier.fillMaxWidth())
                        BrushPanel(tools, vm, modifier = Modifier.fillMaxWidth())
                        ToolOptionsPanel(tools, vm)
                    }
                }
            }
        }
    }

    if (showLayersSheet) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showLayersSheet = false }) {
            Box(Modifier.fillMaxHeight(0.75f)) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LayerPanel(state, vm, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (showColorSheet) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showColorSheet = false }) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ColorPanel(tools, vm, onPickColor = { showColorSheet = false; colorPickerTarget = it })
            }
        }
    }
    if (showBrushSheet) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showBrushSheet = false }) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BrushPanel(tools, vm, modifier = Modifier.fillMaxWidth())
                BrushGallerySheet(tools, vm)
            }
        }
    }
    if (showToolOptions) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showToolOptions = false }) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ToolOptionsPanel(tools, vm)
            }
        }
    }
    if (showEffectsSheet) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showEffectsSheet = false }) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                EffectsPanel(vm)
            }
        }
    }
    if (showTimelineSheet) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showTimelineSheet = false }) {
            Box(Modifier.fillMaxHeight(0.7f)) {
                AnimationTimeline(state, vm, Modifier.fillMaxSize())
            }
        }
    }
}

enum class ColorTarget { Primary, Secondary, Palette }

@Composable
private fun StudioTopBar(
    state: StudioState,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onExport: () -> Unit,
    onRename: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var titleDraft by remember { mutableStateOf("") }

    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(ForgeColors.Charcoal)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ForgeColors.TextPrimary)
        }
        Column(Modifier.weight(1f).clickable {
            titleDraft = state.document?.title ?: "Untitled"
            renaming = true
        }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.document?.title ?: "Loading…",
                    style = MaterialTheme.typography.titleMedium,
                    color = ForgeColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.dirty) {
                    Text(" •", color = ForgeColors.AccentWarm, style = MaterialTheme.typography.titleMedium)
                }
            }
            Text(
                buildString {
                    val doc = state.document
                    if (doc != null) {
                        append("${doc.settings.width}×${doc.settings.height}")
                        append(" · ")
                        append(doc.layers.size)
                        append(" layers")
                        if (doc.kind == ProjectKind.Animation) {
                            append(" · ${doc.animation.fps}fps")
                        }
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = ForgeColors.TextMuted,
            )
        }
        IconButton(onClick = onUndo, enabled = state.canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = if (state.canUndo) ForgeColors.TextPrimary else ForgeColors.TextMuted)
        }
        IconButton(onClick = onRedo, enabled = state.canRedo) {
            Icon(Icons.AutoMirrored.Filled.Redo, "Redo", tint = if (state.canRedo) ForgeColors.TextPrimary else ForgeColors.TextMuted)
        }
        IconButton(onClick = onSave) {
            Icon(Icons.Filled.Save, "Save", tint = ForgeColors.TextPrimary)
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, "More", tint = ForgeColors.TextPrimary)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Export") }, onClick = { menuOpen = false; onExport() })
                DropdownMenuItem(
                    text = { Text("Rename project") },
                    onClick = { menuOpen = false; titleDraft = state.document?.title ?: ""; renaming = true },
                )
                DropdownMenuItem(
                    text = { Text(if (state.saving) "Saving…" else "Save now") },
                    onClick = { menuOpen = false; onSave() },
                )
            }
        }
    }

    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename project") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = titleDraft,
                    onValueChange = { titleDraft = it },
                    singleLine = true,
                    label = { Text("Title") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(titleDraft)
                    renaming = false
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

/** Vertical tool rail grouped by tool family. */
@Composable
fun ToolRail(
    selected: ToolId,
    onSelect: (ToolId) -> Unit,
    vertical: Boolean,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Box(modifier.background(ForgeColors.Graphite)) {
        Column(
            Modifier
                .verticalScroll(scroll)
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            var lastGroup: String? = null
            ToolCatalog.tools.forEach { spec ->
                if (lastGroup != null && spec.group != lastGroup) {
                    Box(
                        Modifier
                            .padding(vertical = 3.dp)
                            .width(28.dp)
                            .height(1.dp)
                            .background(ForgeColors.GraphiteBorder),
                    )
                }
                lastGroup = spec.group
                ToolButton(spec.icon, spec.label, selected == spec.id) { onSelect(spec.id) }
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) ForgeColors.Accent.copy(alpha = 0.2f) else Color.Transparent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            label,
            tint = if (selected) ForgeColors.Accent else ForgeColors.TextSecondary,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun QuickActionBar(
    tools: ToolState,
    view: CanvasView,
    isAnimation: Boolean,
    onBrushSize: (Float) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    onRotate: () -> Unit,
    onFlip: () -> Unit,
    onLayers: () -> Unit,
    onColor: () -> Unit,
    onBrush: () -> Unit,
    onEffects: () -> Unit,
    onToolOptions: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(ForgeColors.Graphite)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ColorSwatch(tools.primaryColor, size = 30, selected = true, onClick = onColor)
        ColorSwatch(tools.secondaryColor, size = 22, onClick = onColor)
        Spacer(Modifier.width(6.dp))
        IconButton(onClick = onBrush, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Brush, "Brush library", tint = ForgeColors.TextSecondary, modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onLayers, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Layers, "Layers", tint = ForgeColors.TextSecondary, modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onEffects, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.AutoAwesome, "Effects", tint = ForgeColors.TextSecondary, modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onToolOptions, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Tune, "Tool options", tint = ForgeColors.TextSecondary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(
            "${(view.zoom * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = ForgeColors.TextMuted,
        )
        IconButton(onClick = onZoomOut, modifier = Modifier.size(34.dp)) {
            Text("−", color = ForgeColors.TextSecondary, style = MaterialTheme.typography.titleLarge)
        }
        IconButton(onClick = onZoomIn, modifier = Modifier.size(34.dp)) {
            Text("+", color = ForgeColors.TextSecondary, style = MaterialTheme.typography.titleLarge)
        }
        IconButton(onClick = onFit, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Filled.Palette, "Fit", tint = ForgeColors.TextSecondary, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onRotate, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Filled.Close, "Rotate", tint = ForgeColors.TextSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

/** Bottom scrub bar shown on phones for animation projects. */
@Composable
private fun CompactTimeline(
    state: StudioState,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    onScrub: (Int) -> Unit,
) {
    val frames = state.document?.animation?.frameCount ?: 1
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(ForgeColors.Graphite)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) { Text("◀", color = ForgeColors.TextSecondary) }
        IconButton(onClick = onTogglePlay) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                "Play",
                tint = ForgeColors.Accent,
            )
        }
        IconButton(onClick = onNext) { Text("▶", color = ForgeColors.TextSecondary) }
        Column(Modifier.weight(1f)) {
            androidx.compose.material3.Slider(
                value = state.frame.toFloat(),
                onValueChange = { onScrub(it.toInt()) },
                valueRange = 0f..(frames - 1).coerceAtLeast(1).toFloat(),
                modifier = Modifier.height(24.dp),
            )
            Text(
                "Frame ${state.frame + 1} / $frames",
                style = MaterialTheme.typography.labelSmall,
                color = ForgeColors.TextMuted,
            )
        }
        TextButton(onClick = onExpand) {
            Text("Timeline", style = MaterialTheme.typography.labelMedium)
        }
    }
}
