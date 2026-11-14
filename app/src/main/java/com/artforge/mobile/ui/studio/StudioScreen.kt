@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.artforge.mobile.ui.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ViewTimeline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ErrorOutline
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
import com.artforge.mobile.ui.common.GestureIconButton
import com.artforge.mobile.ui.common.Hairline
import com.artforge.mobile.ui.common.PillButton
import com.artforge.mobile.ui.common.StatusChip
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
    /** Ctrl+O: leave the studio for the project picker. */
    onOpenProjectRequest: () -> Unit = onBack,
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

    val shortcutActions = remember(vm) {
        StudioShortcutActions(
            undo = vm::undo,
            redo = vm::redo,
            save = { vm.saveProject() },
            open = onOpenProjectRequest,
            selectTool = vm::selectTool,
            previousFrame = vm::previousFrame,
            nextFrame = vm::nextFrame,
            togglePlayback = vm::togglePlayback,
            duplicateFrame = vm::duplicateFrame,
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize().studioShortcuts(shortcutActions),
    ) {
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
                        modifier = Modifier.fillMaxHeight().width(58.dp),
                    )
                } else {
                    // compact phones keep the rail as a slim vertical strip
                    ToolRail(
                        selected = tools.tool,
                        onSelect = vm::selectTool,
                        vertical = true,
                        modifier = Modifier.fillMaxHeight().width(58.dp),
                    )
                }

                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(ForgeColors.CharcoalDeep),
                ) {
                    if (state.renderError != null) {
                        RenderErrorBanner(
                            message = state.renderError,
                            onRetry = { vm.refresh() },
                        )
                    }
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
                        onLongPress = { p -> vm.setCloneSource(p) },
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

/**
 * Non-fatal render failure banner. The editor stays usable (strokes still land on the
 * layer); this only explains why the preview is missing and offers a retry.
 */
@Composable
private fun RenderErrorBanner(message: String, onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ForgeColors.Danger.copy(alpha = 0.16f))
            .border(1.dp, ForgeColors.Danger.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = ForgeColors.Danger,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Preview unavailable",
                style = MaterialTheme.typography.titleSmall,
                color = ForgeColors.TextPrimary,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = ForgeColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PillButton("Retry", onRetry)
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

    Column(
        Modifier
            .fillMaxWidth()
            .background(ForgeColors.Graphite)
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = ForgeColors.TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        titleDraft = state.document?.title ?: "Untitled"
                        renaming = true
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.document?.title ?: "Loading…",
                        style = MaterialTheme.typography.titleMedium,
                        color = ForgeColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (state.dirty) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(ForgeColors.AccentWarm),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val doc = state.document
                    Text(
                        buildString {
                            if (doc != null) {
                                append("${doc.settings.width}×${doc.settings.height}")
                                append(" · ")
                                append(doc.layers.size)
                                append(if (doc.layers.size == 1) " layer" else " layers")
                                if (doc.kind == ProjectKind.Animation) append(" · ${doc.animation.fps} fps")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = ForgeColors.TextMuted,
                        maxLines = 1,
                    )
                    if (state.saving) {
                        Spacer(Modifier.width(8.dp))
                        StatusChip("Saving…", color = ForgeColors.AccentWarm)
                    }
                }
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(ForgeColors.GraphiteElevated)
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GestureIconButton(
                    Icons.AutoMirrored.Filled.Undo,
                    "Undo",
                    onUndo,
                    enabled = state.canUndo,
                    size = 36,
                )
                GestureIconButton(
                    Icons.AutoMirrored.Filled.Redo,
                    "Redo",
                    onRedo,
                    enabled = state.canRedo,
                    size = 36,
                )
            }
            Spacer(Modifier.width(6.dp))
            GestureIconButton(Icons.Filled.Save, "Save", onSave, size = 38)
            GestureIconButton(
                Icons.Filled.IosShare,
                "Export",
                onExport,
                size = 38,
                selected = true,
            )
            Box {
                GestureIconButton(Icons.Filled.MoreVert, "More", { menuOpen = true }, size = 38)
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Export…") },
                        leadingIcon = { Icon(Icons.Filled.IosShare, null, modifier = Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; onExport() },
                    )
                    DropdownMenuItem(
                        text = { Text("Rename project") },
                        onClick = {
                            menuOpen = false
                            titleDraft = state.document?.title ?: ""
                            renaming = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (state.saving) "Saving…" else "Save now") },
                        leadingIcon = { Icon(Icons.Filled.Save, null, modifier = Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; onSave() },
                    )
                }
            }
        }
        Hairline()
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
    Column(
        modifier
            .background(ForgeColors.Graphite)
            .padding(vertical = 6.dp),
    ) {
        Column(
            Modifier
                .verticalScroll(scroll)
                .padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            var lastGroup: String? = null
            ToolCatalog.tools.forEach { spec ->
                if (lastGroup != null && spec.group != lastGroup) {
                    Box(
                        Modifier
                            .padding(vertical = 5.dp)
                            .width(20.dp)
                            .height(1.dp)
                            .background(ForgeColors.Hairline),
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
    val bg by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) ForgeColors.Accent.copy(alpha = 0.16f) else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(140),
        label = "toolBg",
    )
    Box(contentAlignment = Alignment.Center) {
        // Selection marker: a soft accent bar on the leading edge.
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .width(3.dp)
                .height(if (selected) 20.dp else 0.dp)
                .clip(RoundedCornerShape(50))
                .background(ForgeColors.Accent),
        )
        Box(
            Modifier
                .padding(vertical = 2.dp)
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                label,
                tint = if (selected) ForgeColors.Accent else ForgeColors.TextSecondary,
                modifier = Modifier.size(21.dp),
            )
        }
    }
}

/**
 * Floating context bar under the canvas: the controls a painter reaches for constantly
 * (colour, brush, layers, zoom) without leaving the artwork.
 */
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
            .height(56.dp)
            .background(ForgeColors.Graphite)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Colour stack: primary over secondary, like a real palette.
        Box(
            Modifier
                .size(38.dp)
                .clickable { onColor() },
            contentAlignment = Alignment.BottomEnd,
        ) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(tools.secondaryColor))
                    .border(1.dp, ForgeColors.GraphiteBorder, RoundedCornerShape(8.dp)),
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(tools.primaryColor))
                    .border(2.dp, ForgeColors.Graphite, RoundedCornerShape(8.dp)),
            )
        }
        Spacer(Modifier.width(4.dp))
        GestureIconButton(Icons.Filled.Brush, "Brush library", onBrush, size = 38)
        GestureIconButton(Icons.Filled.Layers, "Layers", onLayers, size = 38)
        GestureIconButton(Icons.Filled.AutoAwesome, "Effects", onEffects, size = 38)
        GestureIconButton(Icons.Filled.Tune, "Tool options", onToolOptions, size = 38)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(ForgeColors.GraphiteElevated)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GestureIconButton(Icons.Filled.Remove, "Zoom out", onZoomOut, size = 32)
            Text(
                "${(view.zoom * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = ForgeColors.TextSecondary,
                modifier = Modifier
                    .width(46.dp)
                    .clickable { onFit() },
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            GestureIconButton(Icons.Filled.Add, "Zoom in", onZoomIn, size = 32)
        }
        GestureIconButton(Icons.Filled.CenterFocusStrong, "Fit to screen", onFit, size = 38)
        GestureIconButton(Icons.Filled.RotateRight, "Rotate canvas", onRotate, size = 38)
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
    Column(Modifier.fillMaxWidth().background(ForgeColors.Graphite)) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GestureIconButton(Icons.Filled.SkipPrevious, "Previous frame", onPrev, size = 36)
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(50))
                    .background(ForgeColors.Accent)
                    .clickable { onTogglePlay() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color(0xFF0B0C0F),
                    modifier = Modifier.size(20.dp),
                )
            }
            GestureIconButton(Icons.Filled.SkipNext, "Next frame", onNext, size = 36)
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                androidx.compose.material3.Slider(
                    value = state.frame.toFloat(),
                    onValueChange = { onScrub(it.toInt()) },
                    valueRange = 0f..(frames - 1).coerceAtLeast(1).toFloat(),
                    modifier = Modifier.height(22.dp),
                )
                Text(
                    "Frame ${state.frame + 1} / $frames",
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeColors.TextMuted,
                )
            }
            PillButton("Timeline", onExpand, icon = Icons.Filled.ViewTimeline)
        }
    }
}
