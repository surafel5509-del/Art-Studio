package com.artforge.mobile.ui.studio

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.artforge.mobile.ArtForgeApplication
import com.artforge.mobile.core.color.ColorUtils
import com.artforge.mobile.core.model.BlendMode
import com.artforge.mobile.core.model.BrushPreset
import com.artforge.mobile.core.model.BrushSettings
import com.artforge.mobile.core.model.FrameMeta
import com.artforge.mobile.core.model.FrameRef
import com.artforge.mobile.core.model.GradientSpec
import com.artforge.mobile.core.model.Interpolation
import com.artforge.mobile.core.model.Keyframe
import com.artforge.mobile.core.model.Layer
import com.artforge.mobile.core.model.LayerKind
import com.artforge.mobile.core.model.LayerTransform
import com.artforge.mobile.core.model.OnionSkinConfig
import com.artforge.mobile.core.model.Palette
import com.artforge.mobile.core.model.ProjectDocument
import com.artforge.mobile.core.model.ProjectKind
import com.artforge.mobile.core.model.Pt
import com.artforge.mobile.core.model.RectF2
import com.artforge.mobile.core.model.ToolId
import com.artforge.mobile.core.util.ForgeLog
import com.artforge.mobile.data.export.AnimationExportOptions
import com.artforge.mobile.data.export.ExportEngine
import com.artforge.mobile.data.export.ImageExportOptions
import com.artforge.mobile.data.settings.AppSettings
import com.artforge.mobile.engine.animation.AnimationEngine
import com.artforge.mobile.engine.animation.CharacterRig
import com.artforge.mobile.engine.animation.TimelineController
import com.artforge.mobile.engine.comic.ComicEngine
import com.artforge.mobile.engine.draw.BrushLibrary
import com.artforge.mobile.engine.draw.Compositor
import com.artforge.mobile.engine.draw.DrawingEngine
import com.artforge.mobile.engine.draw.FrameStore
import com.artforge.mobile.engine.draw.SelectionEngine
import com.artforge.mobile.engine.draw.SelectionMask
import com.artforge.mobile.engine.draw.SelectionOp
import com.artforge.mobile.engine.draw.StrokePoint
import com.artforge.mobile.engine.draw.SymmetryBuilder
import com.artforge.mobile.engine.draw.UndoCommand
import com.artforge.mobile.engine.draw.UndoManager
import com.artforge.mobile.engine.effects.EffectParams
import com.artforge.mobile.engine.effects.EffectsEngine
import com.artforge.mobile.engine.pixel.PixelEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import com.artforge.mobile.work.ExportWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager

/**
 * The studio view model. It owns the open document, the tool state, the undo
 * history, the frame store access and the animation playback — and it is the only
 * place where user gestures turn into real engine calls.
 */
class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ArtForgeApplication
    private val repo = app.projectRepository
    private val settingsRepo = app.settingsRepository
    private val frameStore = app.frameStore
    private val compositor = Compositor(frameStore)
    private val drawingEngine = DrawingEngine(frameStore)
    private val undo = UndoManager()
    private val renderer = CanvasRenderer(frameStore, compositor)
    private val exportEngine = ExportEngine(app, frameStore, compositor)
    private val timeline = TimelineController(viewModelScope) { frame -> onPlaybackFrame(frame) }

    private val _state = MutableStateFlow(StudioState())
    val state: StateFlow<StudioState> = _state.asStateFlow()

    private val _tools = MutableStateFlow(ToolState())
    val tools: StateFlow<ToolState> = _tools.asStateFlow()

    private val _view = MutableStateFlow(CanvasView())
    val view: StateFlow<CanvasView> = _view.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val playbackState = timeline.state

    private var autosaveJob: Job? = null
    private var activeStrokeLayer: Layer? = null
    private var activeStrokeFrameId: String? = null
    private var pendingUndo: UndoCommand.PixelPatch? = null
    private var selection: SelectionMask? = null
    private var strokePoints: ArrayList<StrokePoint> = ArrayList()
    private var renderJob: Job? = null
    private var polygonPoints: ArrayList<Pt> = ArrayList()
    private var bezierAnchors: ArrayList<Pt> = ArrayList()
    private var cloneOffset: Pt? = null
    private var displayBitmap: Bitmap? = null

    init {
        viewModelScope.launch {
            _settings.value = settingsRepo.settings.first()
            val s = _settings.value
            _tools.value = _tools.value.copy(
                brush = BrushLibrary.get(s.defaultBrushId) ?: BrushLibrary.presets.first(),
                eraser = BrushLibrary.get(s.defaultEraserId) ?: BrushLibrary.get("eraser_soft"),
                smoothing = s.strokeStabilizer,
            )
            val brush = _tools.value.brush
            if (brush != null) {
                _tools.value = _tools.value.copy(
                    brushSize = brush.settings.size,
                    opacity = brush.settings.opacity,
                    flow = brush.settings.flow,
                    hardness = brush.settings.hardness,
                    spacing = brush.settings.spacing,
                )
            }
        }
        drawingEngine.onInvalidate = { rect -> scheduleRender() }
    }

    // -----------------------------------------------------------------------
    // Document lifecycle
    // -----------------------------------------------------------------------

    fun openProject(projectId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val doc = repo.loadProject(projectId)
            if (doc == null) {
                _state.value = _state.value.copy(loading = false, statusMessage = "Could not open project")
                return@launch
            }
            repo.ensureFrames(doc)
            undo.clear()
            selection = null
            _state.value = StudioState(
                loading = false,
                document = doc,
                activeLayerId = doc.activeLayerId ?: doc.layers.firstOrNull()?.id,
                frame = 0,
                canvasWidth = doc.settings.width,
                canvasHeight = doc.settings.height,
                frameRange = 0..max(0, doc.animation.frameCount - 1),
                dirty = false,
            )
            _view.value = CanvasView()
            settingsRepo.update { it.copy(lastProjectId = doc.id) }
            startAutosave()
            render()
        }
    }

    fun createProject(
        kind: ProjectKind,
        title: String,
        width: Int,
        height: Int,
        fps: Int,
        frameCount: Int,
        transparent: Boolean,
        backgroundColor: Int,
        onCreated: (String) -> Unit,
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val doc = repo.createDocument(kind, title, width, height, fps, frameCount, transparent, backgroundColor)
            val prepared = if (kind == ProjectKind.Animation) {
                // Give the first animation frame a real blank drawing.
                val frameId = doc.animation.frames.first().id
                repo.registerProject(doc)
                frameStore.createBlankFrame(doc.id, frameId, width, height)
                doc
            } else {
                val frameId = UUID.randomUUID().toString()
                val layer = doc.layers.first().let { l ->
                    if (l.activeFrameId == null) l.copy(activeFrameId = frameId, frames = listOf(FrameRef(frameId, 0.0))) else l
                }
                val updated = doc.copy(layers = listOf(layer) + doc.layers.drop(1), activeLayerId = layer.id)
                repo.registerProject(updated)
                val finalFrame = layer.activeFrameId ?: frameId
                frameStore.createBlankFrame(updated.id, finalFrame, width, height)
                updated
            }
            repo.ensureFrames(prepared)
            repo.saveProject(prepared, renderThumbnail(prepared))
            onCreated(prepared.id)
            openProject(prepared.id)
        }
    }

    private fun renderThumbnail(doc: ProjectDocument): Bitmap? = try {
        val full = Bitmap.createBitmap(doc.settings.width, doc.settings.height, Bitmap.Config.ARGB_8888)
        compositor.composite(doc, full, 0, includeBackground = true)
        val thumb = com.artforge.mobile.engine.draw.FrameStore.fitInto(full, 512)
        val result = Bitmap.createBitmap(thumb.width, thumb.height, Bitmap.Config.ARGB_8888).also {
            android.graphics.Canvas(it).drawBitmap(thumb, 0f, 0f, null)
        }
        full.recycle()
        if (thumb !== full) thumb.recycle()
        result
    } catch (t: Throwable) {
        ForgeLog.e(TAG, "thumbnail render failed", t)
        null
    }

    fun closeProject() {
        val doc = _state.value.document ?: return
        viewModelScope.launch {
            if (_state.value.dirty) {
                frameStore.flushAll(doc.id)
                repo.saveProject(doc, renderThumbnail(doc), versioned = _settings.value.versionHistoryEnabled)
            }
            autosaveJob?.cancel()
            timeline.stop()
            renderer.release()
            undo.clear()
            _state.value = StudioState(loading = false)
        }
    }

    fun saveProject(userInitiated: Boolean = true) {
        val doc = _state.value.document ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true)
            try {
                frameStore.flushAll(doc.id)
                withContext(Dispatchers.IO) {
                    repo.saveProject(doc, renderThumbnail(doc), versioned = _settings.value.versionHistoryEnabled)
                }
                _state.value = _state.value.copy(
                    saving = false,
                    dirty = false,
                    lastSavedAt = System.currentTimeMillis(),
                    statusMessage = if (userInitiated) "Saved" else null,
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(saving = false, statusMessage = "Save failed: ${t.message}")
            }
        }
    }

    fun renameProject(title: String) {
        val doc = _state.value.document ?: return
        val updated = doc.copy(title = title.ifBlank { "Untitled" })
        _state.value = _state.value.copy(document = updated, dirty = true)
        scheduleRender()
    }

    fun restoreRecovery() {
        val doc = _state.value.document ?: return
        viewModelScope.launch {
            val info = repo.recoveryFor(doc.id)
            if (info != null) {
                _state.value = _state.value.copy(
                    document = info.document,
                    pendingRecovery = false,
                    statusMessage = "Recovered autosave",
                )
                render()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Undo / redo
    // -----------------------------------------------------------------------

    private fun pushUndoForActiveFrame(label: String) {
        val doc = _state.value.document ?: return
        val layer = activeLayer() ?: return
        val frameId = currentFrameId() ?: return
        val bitmap = frameStore.load(doc.id, frameId, doc.settings.width, doc.settings.height)
        val patch = undo.captureRegion(
            label,
            layer.id,
            frameId,
            bitmap,
            Rect(0, 0, doc.settings.width, doc.settings.height),
        )
        undo.push(patch)
        _state.value = _state.value.copy(canUndo = undo.canUndo, canRedo = undo.canRedo, dirty = true)
    }

    fun undo() {
        val doc = _state.value.document ?: return
        val cmd = undo.popUndo() ?: return
        applyUndo(cmd, redo = false)
        _state.value = _state.value.copy(
            canUndo = undo.canUndo, canRedo = undo.canRedo,
            statusMessage = "Undo ${cmd.label}",
        )
        scheduleRender()
    }

    fun redo() {
        val doc = _state.value.document ?: return
        val cmd = undo.popRedo() ?: return
        applyUndo(cmd, redo = true)
        _state.value = _state.value.copy(canUndo = undo.canUndo, canRedo = undo.canRedo, statusMessage = "Redo ${cmd.label}")
        scheduleRender()
    }

    private fun applyUndo(cmd: UndoCommand, redo: Boolean) {
        val doc = _state.value.document ?: return
        when (cmd) {
            is UndoCommand.PixelPatch -> {
                val bitmap = frameStore.load(doc.id, cmd.frameId, doc.settings.width, doc.settings.height)
                cmd.applyTo(bitmap)
                frameStore.markDirty(cmd.frameId)
            }
            is UndoCommand.Structural -> {
                // Structural commands re-apply the recorded document JSON.
                val json = if (redo) cmd.after else cmd.before
                runCatching {
                    val manifest = com.artforge.mobile.data.project.ProjectSerializer.decodeManifest(json)
                    _state.value = _state.value.copy(document = com.artforge.mobile.data.project.ProjectSerializer.toDocument(manifest))
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Tool selection
    // -----------------------------------------------------------------------

    fun selectTool(tool: ToolId) {
        _tools.value = _tools.value.copy(
            previousTool = _tools.value.tool,
            tool = tool,
            isEraserActive = tool == ToolId.Eraser || tool == ToolId.PixelEraser,
        )
        if (tool != ToolId.BucketFill) Unit
        scheduleRender()
    }

    fun selectBrush(preset: BrushPreset) {
        val isEraser = preset.category == com.artforge.mobile.core.model.BrushCategory.Eraser
        _tools.value = _tools.value.copy(
            brush = if (isEraser) _tools.value.brush else preset,
            eraser = if (isEraser) preset else _tools.value.eraser,
            tool = when {
                isEraser && preset.id == "eraser_pixel" -> ToolId.PixelEraser
                isEraser -> ToolId.Eraser
                preset.category == com.artforge.mobile.core.model.BrushCategory.Blend -> ToolId.Smudge
                else -> ToolId.Brush
            },
            brushSize = preset.settings.size,
            opacity = preset.settings.opacity,
            flow = preset.settings.flow,
            hardness = preset.settings.hardness,
            spacing = preset.settings.spacing,
            smoothing = preset.settings.smoothing,
        )
        scheduleRender()
    }

    fun setBrushSize(size: Float) {
        _tools.value = _tools.value.copy(brushSize = size.coerceIn(1f, 600f))
        scheduleRender()
    }

    fun setOpacity(opacity: Float) {
        _tools.value = _tools.value.copy(opacity = opacity.coerceIn(0.01f, 1f))
    }

    fun setFlow(flow: Float) {
        _tools.value = _tools.value.copy(flow = flow.coerceIn(0.01f, 1f))
    }

    fun setHardness(value: Float) {
        _tools.value = _tools.value.copy(hardness = value.coerceIn(0f, 1f))
    }

    fun setSpacing(value: Float) {
        _tools.value = _tools.value.copy(spacing = value.coerceIn(0.01f, 2f))
    }

    fun setSmoothing(value: Float) {
        _tools.value = _tools.value.copy(smoothing = value.coerceIn(0f, 0.95f))
    }

    fun setTolerance(value: Int) {
        _tools.value = _tools.value.copy(tolerance = value.coerceIn(0, 120))
    }

    fun setPrimaryColor(color: Int) {
        _tools.value = _tools.value.copy(
            primaryColor = color,
            recentColors = (listOf(color) + _tools.value.recentColors).distinct().take(24),
        )
        scheduleRender()
    }

    fun setSecondaryColor(color: Int) {
        _tools.value = _tools.value.copy(secondaryColor = color)
    }

    fun swapColors() {
        val t = _tools.value
        _tools.value = t.copy(primaryColor = t.secondaryColor, secondaryColor = t.primaryColor)
    }

    fun setShapeFill(fill: Boolean) {
        _tools.value = _tools.value.copy(shapeFill = fill)
    }

    fun setShapeSides(sides: Int) {
        _tools.value = _tools.value.copy(shapeSides = sides.coerceIn(3, 24))
    }

    fun setStarPoints(points: Int) {
        _tools.value = _tools.value.copy(starPoints = points.coerceIn(3, 24))
    }

    fun setCornerRadius(radius: Float) {
        _tools.value = _tools.value.copy(cornerRadius = radius.coerceIn(0f, 400f))
    }

    fun setSelectionOp(op: SelectionOp) {
        _tools.value = _tools.value.copy(selectionOp = op)
    }

    fun setSelectionFeather(feather: Int) {
        _tools.value = _tools.value.copy(selectionFeather = feather.coerceIn(0, 48))
    }

    fun setGradientType(type: com.artforge.mobile.core.model.GradientType) {
        _tools.value = _tools.value.copy(gradientType = type)
    }

    fun setTextSize(size: Float) {
        _tools.value = _tools.value.copy(textSize = size.coerceIn(8f, 600f))
    }

    fun setPalette(palette: Palette?) {
        _tools.value = _tools.value.copy(palette = palette)
    }

    // -----------------------------------------------------------------------
    // View transform
    // -----------------------------------------------------------------------

    fun zoomBy(factor: Float, focusX: Float = 0f, focusY: Float = 0f) {
        val v = _view.value
        val newZoom = (v.zoom * factor).coerceIn(0.05f, 32f)
        val scale = newZoom / v.zoom
        _view.value = v.copy(
            zoom = newZoom,
            panX = focusX + (v.panX - focusX) * scale,
            panY = focusY + (v.panY - focusY) * scale,
        )
        scheduleRender()
    }

    fun setZoom(zoom: Float) {
        _view.value = _view.value.copy(zoom = zoom.coerceIn(0.05f, 32f))
        scheduleRender()
    }

    fun panBy(dx: Float, dy: Float) {
        _view.value = _view.value.copy(panX = _view.value.panX + dx, panY = _view.value.panY + dy)
    }

    fun rotateBy(degrees: Float) {
        _view.value = _view.value.copy(rotation = (_view.value.rotation + degrees) % 360f)
        scheduleRender()
    }

    fun setRotation(degrees: Float) {
        _view.value = _view.value.copy(rotation = degrees % 360f)
    }

    fun resetView() {
        _view.value = CanvasView()
        scheduleRender()
    }

    fun fitToScreen(viewWidth: Float, viewHeight: Float) {
        val doc = _state.value.document ?: return
        val scale = min(viewWidth / doc.settings.width, viewHeight / doc.settings.height) * 0.92f
        _view.value = CanvasView(zoom = scale)
        scheduleRender()
    }

    fun toggleFlipCanvas() {
        _view.value = _view.value.copy(flipHorizontal = !_view.value.flipHorizontal)
        scheduleRender()
    }

    // -----------------------------------------------------------------------
    // Drawing input
    // -----------------------------------------------------------------------

    private fun activeLayer(): Layer? = _state.value.activeLayer
    private fun currentFrameId(): String? {
        val doc = _state.value.document ?: return null
        val layer = activeLayer() ?: return null
        return compositor.resolveFrame(layer, _state.value.frame)
    }

    /**
     * Called on pointer-down. Returns true when the engine consumed the event
     * (i.e. it started a stroke) so the gesture layer knows not to pan.
     */
    fun onPointerDown(point: StrokePoint, pressureEnabled: Boolean): Boolean {
        val doc = _state.value.document ?: return false
        val layer = activeLayer() ?: return false
        if (layer.locked) {
            _state.value = _state.value.copy(statusMessage = "Layer is locked")
            return false
        }
        val tool = _tools.value.tool

        return when (tool) {
            ToolId.Brush, ToolId.Pencil, ToolIdEraser, ToolId.PixelEraser, ToolId.Smudge,
            ToolId.Blur, ToolId.Sharpen, ToolId.Dodge, ToolId.Burn, ToolId.CloneStamp,
            -> startFreehandStroke(point, pressureEnabled)

            ToolId.Line, ToolId.Rectangle, ToolId.RoundedRectangle, ToolId.Ellipse,
            ToolId.Polygon, ToolId.Star, ToolId.Curve,
            -> {
                _state.value = _state.value.copy(
                    drawPreview = DrawPreview(
                        kind = PreviewKind.Shape,
                        from = Pt(point.x, point.y),
                        to = Pt(point.x, point.y),
                        tool = tool,
                    ),
                )
                true
            }

            ToolId.Gradient -> {
                _state.value = _state.value.copy(
                    drawPreview = DrawPreview(kind = PreviewKind.Gradient, from = Pt(point.x, point.y), to = Pt(point.x, point.y)),
                )
                true
            }

            ToolId.BezierPen, ToolId.Path -> {
                val snapped = snapPoint(Pt(point.x, point.y))
                bezierAnchors.add(snapped)
                _state.value = _state.value.copy(
                    drawPreview = DrawPreview(kind = PreviewKind.Polyline, points = bezierAnchors.toList()),
                )
                true
            }

            ToolId.RectSelect, ToolId.EllipseSelect -> {
                _state.value = _state.value.copy(
                    drawPreview = DrawPreview(
                        kind = PreviewKind.Marque,
                        from = Pt(point.x, point.y),
                        to = Pt(point.x, point.y),
                        tool = tool,
                    ),
                )
                true
            }

            ToolId.LassoSelect, ToolId.PolygonSelect -> {
                strokePoints = arrayListOf(point)
                _state.value = _state.value.copy(
                    drawPreview = DrawPreview(kind = PreviewKind.Lasso, points = listOf(Pt(point.x, point.y))),
                )
                true
            }

            ToolId.BucketFill -> {
                val frameId = currentFrameId() ?: return false
                val bitmap = frameStore.load(doc.id, frameId, doc.settings.width, doc.settings.height)
                pushUndoForActiveFrame("Fill")
                val symmetry = symmetryBuilder()
                drawingEngine.floodFill(
                    bitmap,
                    point.x.toInt(),
                    point.y.toInt(),
                    _tools.value.primaryColor,
                    _tools.value.tolerance,
                    contiguous = true,
                    symmetry = symmetry,
                )
                frameStore.markDirty(frameId)
                _state.value = _state.value.copy(dirty = true)
                scheduleRender()
                true
            }

            ToolId.Eyedropper -> {
                pickColorAt(point.x, point.y)
                true
            }

            ToolId.Perspective -> {
                _state.value = _state.value.copy(
                    drawPreview = DrawPreview(kind = PreviewKind.Shape, from = Pt(point.x, point.y), to = Pt(point.x, point.y), tool = ToolId.Line),
                )
                true
            }

            ToolId.Text -> {
                addTextLayerAt(Pt(point.x, point.y))
                false
            }

            ToolId.Transform, ToolId.Move, ToolId.Crop -> {
                polygonPoints.clear()
                polygonPoints.add(Pt(point.x, point.y))
                true
            }

            ToolId.CloneStamp -> {
                if (cloneOffset == null) {
                    cloneOffset = Pt(point.x, point.y)
                    _state.value = _state.value.copy(statusMessage = "Clone source set — now paint")
                }
                startFreehandStroke(point, pressureEnabled)
            }

            ToolId.Hand, ToolId.Zoom, ToolId.RotateCanvas -> false
        }
    }

    fun onPointerMove(point: StrokePoint, pressureEnabled: Boolean) {
        val tool = _tools.value.tool
        when (tool) {
            ToolId.Brush, ToolId.Pencil, ToolId.Eraser, ToolId.PixelEraser, ToolId.Smudge,
            ToolId.Blur, ToolId.Sharpen, ToolId.Dodge, ToolId.Burn, ToolId.CloneStamp,
            -> {
                if (drawingEngine.isStrokeActive) {
                    val smoothed = applyPressureCurve(point)
                    strokePoints.add(smoothed)
                    drawingEngine.moveTo(smoothed)
                }
            }
            ToolId.Line, ToolId.Rectangle, ToolId.RoundedRectangle, ToolId.Ellipse,
            ToolId.Polygon, ToolId.Star, ToolId.Curve, ToolId.Gradient,
            -> {
                val current = _state.value.drawPreview ?: return
                val snapped = snapPoint(Pt(point.x, point.y))
                _state.value = _state.value.copy(
                    drawPreview = current.copy(to = snapped),
                )
            }
            ToolId.RectSelect, ToolId.EllipseSelect -> {
                val current = _state.value.drawPreview ?: return
                _state.value = _state.value.copy(drawPreview = current.copy(to = Pt(point.x, point.y)))
            }
            ToolId.LassoSelect, ToolId.PolygonSelect -> {
                strokePoints.add(point)
                if (strokePoints.size % 2 == 0) {
                    _state.value = _state.value.copy(
                        drawPreview = DrawPreview(
                            kind = PreviewKind.Lasso,
                            points = strokePoints.map { Pt(it.x, it.y) },
                        ),
                    )
                }
            }
            ToolId.Transform, ToolId.Move -> {
                if (polygonPoints.isNotEmpty()) {
                    val start = polygonPoints[0]
                    val dx = point.x - start.x
                    val dy = point.y - start.y
                    polygonPoints[0] = Pt(point.x, point.y)
                    transformActiveLayer(dx, dy)
                }
            }
            else -> Unit
        }
    }

    fun onPointerUp(point: StrokePoint) {
        val doc = _state.value.document ?: return
        val tool = _tools.value.tool
        when (tool) {
            ToolId.Brush, ToolId.Pencil, ToolId.Eraser, ToolId.PixelEraser, ToolId.Smudge,
            ToolId.Blur, ToolId.Sharpen, ToolId.Dodge, ToolId.Burn, ToolId.CloneStamp,
            -> finishFreehandStroke()

            ToolId.Line, ToolId.Rectangle, ToolId.RoundedRectangle, ToolId.Ellipse,
            ToolId.Polygon, ToolId.Star, ToolId.Curve,
            -> commitShape()

            ToolId.Gradient -> commitGradient()

            ToolId.RectSelect, ToolId.EllipseSelect, ToolId.LassoSelect, ToolId.PolygonSelect,
            -> commitSelection(tool)

            ToolId.Transform, ToolId.Move -> {
                polygonPoints.clear()
                _state.value = _state.value.copy(dirty = true)
                saveProject(userInitiated = false)
            }

            else -> Unit
        }
        if (tool == ToolId.Perspective) commitShape()
    }

    private fun startFreehandStroke(point: StrokePoint, pressureEnabled: Boolean): Boolean {
        val doc = _state.value.document ?: return false
        val layer = activeLayer() ?: return false
        val frameId = ensureDrawingForStroke(layer) ?: return false
        val bitmap = frameStore.load(doc.id, frameId, doc.settings.width, doc.settings.height)
        val settings = effectiveBrushSettings()
        val tool = _tools.value.tool

        val effectiveTool = when (tool) {
            ToolId.Dodge -> ToolId.Brush
            ToolId.Burn -> ToolId.Brush
            ToolId.CloneStamp -> ToolId.Brush
            else -> tool
        }
        val color = when (tool) {
            ToolId.Eraser, ToolId.PixelEraser -> ColorUtils.withAlpha(_tools.value.primaryColor, 255)
            ToolId.Dodge -> ColorUtils.lerp(0x00000000, 0xFFFFFFFF.toInt(), 0.25f)
            ToolId.Burn -> 0x40000000
            ToolId.Smudge, ToolId.Blur, ToolId.Sharpen -> _tools.value.primaryColor
            else -> _tools.value.primaryColor
        }

        pendingUndo = undo.captureRegion(
            strokeLabel(tool),
            layer.id,
            frameId,
            bitmap,
            Rect(0, 0, doc.settings.width, doc.settings.height),
        )
        activeStrokeLayer = layer
        activeStrokeFrameId = frameId
        strokePoints = arrayListOf(point)
        drawingEngine.beginStroke(bitmap, effectiveTool, color, settings, applyPressureCurve(point))
        return true
    }

    private fun finishFreehandStroke() {
        val doc = _state.value.document ?: return
        val frameId = activeStrokeFrameId
        if (drawingEngine.isStrokeActive) {
            val bounds = drawingEngine.endStroke()
            if (frameId != null) {
                frameStore.markDirty(frameId)
                pendingUndo?.let { undo.push(it) }
                pendingUndo = null
                _state.value = _state.value.copy(
                    dirty = true,
                    canUndo = undo.canUndo,
                    canRedo = undo.canRedo,
                )
            }
            applyDodgeBurnIfNeeded(bounds)
        }
        activeStrokeLayer = null
        activeStrokeFrameId = null
        scheduleRender()
    }

    private fun applyDodgeBurnIfNeeded(bounds: RectF2?) {
        // Dodge/burn are implemented as real pixel maths inside the stroke bounds.
        val doc = _state.value.document ?: return
        val frameId = activeStrokeFrameId ?: return
        val tool = _tools.value.tool
        if (tool != ToolId.Dodge && tool != ToolId.Burn) return
        if (bounds == null || bounds.isEmpty()) return
        val bitmap = frameStore.load(doc.id, frameId, doc.settings.width, doc.settings.height)
        val rect = UndoManager.toRect(bounds, bitmap.width, bitmap.height)
        val amount = if (tool == ToolId.Dodge) 1.08f else 0.92f
        val w = rect.width()
        val h = rect.height()
        if (w <= 0 || h <= 0) return
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, rect.left, rect.top, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            if (ColorUtils.alpha(c) == 0) continue
            pixels[i] = ColorUtils.argb(
                ColorUtils.alpha(c),
                (ColorUtils.red(c) * amount).toInt().coerceIn(0, 255),
                (ColorUtils.green(c) * amount).toInt().coerceIn(0, 255),
                (ColorUtils.blue(c) * amount).toInt().coerceIn(0, 255),
            )
        }
        bitmap.setPixels(pixels, 0, w, rect.left, rect.top, w, h)
        frameStore.markDirty(frameId)
    }

    private fun ensureDrawingForStroke(layer: Layer): String? {
        val doc = _state.value.document ?: return null
        if (doc.kind != ProjectKind.Animation) {
            // Static layer: use (and create if needed) the layer's single drawing.
            val existing = layer.activeFrameId ?: layer.frames.firstOrNull()?.frameId
            if (existing != null) return existing
            val newId = UUID.randomUUID().toString()
            val updated = layer.copy(activeFrameId = newId, frames = listOf(FrameRef(newId, 0.0)))
            _state.value = _state.value.copy(
                document = DocumentMutator.replaceLayer(doc, updated),
                activeLayerId = updated.id,
            )
            repo.registerProject(doc)
            frameStore.createBlankFrame(doc.id, newId, doc.settings.width, doc.settings.height)
            return newId
        }

        // Animation: ensure a drawing exists at the current frame. When the frame is
        // a hold, duplicate the held drawing so only this exposure is edited.
        val frame = _state.value.frame
        val exact = layer.frames.firstOrNull { kotlin.math.abs(it.offset - frame) < 0.001 }
        if (exact != null) return exact.frameId

        val newId = UUID.randomUUID().toString()
        repo.registerProject(doc)
        val held = compositor.resolveFrame(layer, frame)
        if (held != null) {
            frameStore.duplicateFrame(doc.id, held, newId, doc.settings.width, doc.settings.height)
        } else {
            frameStore.createBlankFrame(doc.id, newId, doc.settings.width, doc.settings.height)
        }
        val updated = layer.copy(
            frames = (layer.frames + FrameRef(newId, frame.toDouble())).sortedBy { it.offset },
            activeFrameId = newId,
            kind = if (layer.kind == LayerKind.Raster) LayerKind.Animation else layer.kind,
        )
        val withMeta = _state.value.document?.animation?.let { anim ->
            if (anim.frames.any { it.id == newId }) anim else anim.copy(frames = anim.frames + FrameMeta(newId))
        }
        _state.value = _state.value.copy(
            document = DocumentMutator.replaceLayer(doc, updated).copy(animation = withMeta ?: doc.animation),
            dirty = true,
        )
        return newId
    }

    private fun effectiveBrushSettings(): BrushSettings {
        val t = _tools.value
        val base = when (t.tool) {
            ToolId.Eraser, ToolId.PixelEraser -> t.eraser?.settings ?: BrushSettings()
            ToolId.Smudge -> BrushSettings(size = t.brushSize, opacity = t.opacity, smudgeStrength = 0.6f, spacing = 0.08f)
            ToolId.Blur -> BrushSettings(size = t.brushSize, opacity = t.opacity, smudgeStrength = 0.5f, spacing = 0.1f)
            ToolId.Sharpen -> BrushSettings(size = t.brushSize, opacity = t.opacity, smudgeStrength = 0.35f, spacing = 0.1f)
            ToolId.Pencil -> BrushLibrary.get("pencil_hb")?.settings ?: BrushSettings()
            else -> t.brush?.settings ?: BrushSettings()
        }
        val s = _settings.value
        return base.copy(
            size = t.brushSize,
            opacity = if (t.brush != null && t.brush.settings.size == t.brushSize) min(t.opacity, base.opacity + 0.2f) else t.opacity,
            flow = t.flow,
            hardness = t.hardness,
            spacing = t.spacing,
            smoothing = t.smoothing,
            tiltSize = if (s.tiltEnabled) base.tiltSize else 0f,
            pressureSize = if (s.pressureEnabled) base.pressureSize else 0f,
            pressureOpacity = if (s.pressureEnabled) base.pressureOpacity else 0f,
        )
    }

    private fun applyPressureCurve(point: StrokePoint): StrokePoint {
        val s = _settings.value
        if (!s.pressureEnabled) return point.copy(pressure = 1f)
        val curve = s.pressureCurve.coerceIn(0.2f, 3f)
        val p = Math.pow(point.pressure.coerceIn(0.01f, 1f).toDouble(), curve.toDouble()).toFloat()
        return point.copy(pressure = p)
    }

    private fun strokeLabel(tool: ToolId): String = when (tool) {
        ToolId.Brush -> "Brush stroke"
        ToolId.Pencil -> "Pencil stroke"
        ToolId.Eraser, ToolId.PixelEraser -> "Eraser"
        ToolId.Smudge -> "Smudge"
        ToolId.Blur -> "Blur"
        ToolId.Sharpen -> "Sharpen"
        ToolId.Dodge -> "Dodge"
        ToolId.Burn -> "Burn"
        ToolId.CloneStamp -> "Clone stamp"
        else -> "Stroke"
    }

    // -----------------------------------------------------------------------
    // Shapes, gradients, text, selections
    // -----------------------------------------------------------------------

    private fun commitShape() {
        val preview = _state.value.drawPreview ?: return
        val doc = _state.value.document ?: return
        val layer = activeLayer() ?: return
        val frameId = ensureDrawingForStroke(layer) ?: return
        val from = preview.from ?: return
        val to = preview.to ?: return
        if (from.distanceTo(to) < 1f) {
            _state.value = _state.value.copy(drawPreview = null)
            return
        }
        val bitmap = frameStore.load(doc.id, frameId, doc.settings.width, doc.settings.height)
        pushUndoForActiveFrame("Shape")
        val t = _tools.value
        val strokeWidth = if (t.brushSize < 1f) 2f else max(1f, t.brushSize * 0.35f)
        drawingEngine.renderShape(
            bitmap,
            preview.tool,
            from,
            to,
            t.primaryColor,
            strokeWidth,
            t.shapeFill,
            t.cornerRadius,
            t.shapeSides,
            t.starPoints,
            0.45f,
            t.constrain,
            symmetryBuilder(),
        )
        frameStore.markDirty(frameId)

        // Vector layers keep editable geometry in addition to the rasterised pixels.
        val updatedDoc = if (layer.kind == LayerKind.Vector) {
            val path = drawingEngine.buildShapePath(
                preview.tool, from, to, t.cornerRadius, t.shapeSides, t.starPoints, 0.45f, t.constrain,
            )
            val points = samplePath(path)
            val shape = VectorShape(
                id = UUID.randomUUID().toString(),
                kind = preview.tool.name,
                points = points,
                closed = t.shapeFill || preview.tool != ToolId.Line,
                strokeColor = t.primaryColor,
                strokeWidth = strokeWidth,
                fillColor = if (t.shapeFill) t.primaryColor else null,
            )
            val withShape = DocumentMutator.updateLayer(doc, layer.id) { it.copy(shapes = it.shapes + shape) }
            _state.value = _state.value.copy(document = withShape, drawPreview = null, dirty = true)
            scheduleRender()
            return
        }

        _state.value = _state.value.copy(drawPreview = null, dirty = true)
        scheduleRender()
    }

    /** Flattens an Android Path into a polyline for the vector layer model. */
    private fun samplePath(path: android.graphics.Path, steps: Int = 240): List<Pt> {
        val measure = android.graphics.PathMeasure(path, false)
        val length = measure.length
        if (length <= 0f) return emptyList()
        val out = ArrayList<Pt>(steps)
        val pos = FloatArray(2)
        for (i in 0..steps) {
            measure.getPosTan(length * i / steps, pos, null)
            out.add(Pt(pos[0], pos[1]))
        }
        return out
    }

    private fun commitGradient() {
        val preview = _state.value.drawPreview ?: return
        val doc = _state.value.document ?: return
        val layer = activeLayer() ?: return
        val frameId = ensureDrawingForStroke(layer) ?: return
        val from = preview.from ?: return
        val to = preview.to ?: return
        if (from.distanceTo(to) < 4f) {
            _state.value = _state.value.copy(drawPreview = null)
            return
        }
        val bitmap = frameStore.load(doc.id, frameId, doc.settings.width, doc.settings.height)
        pushUndoForActiveFrame("Gradient")
        val t = _tools.value
        drawingEngine.drawGradient(
            bitmap,
            GradientSpec(
                type = t.gradientType,
                stops = listOf(
                    com.artforge.mobile.core.model.GradientStop(0f, t.primaryColor),
                    com.artforge.mobile.core.model.GradientStop(1f, t.secondaryColor),
                ),
            ),
            from,
            to,
        )
        frameStore.markDirty(frameId)
        _state.value = _state.value.copy(drawPreview = null, dirty = true)
        scheduleRender()
    }

    private fun commitSelection(tool: ToolId) {
        val doc = _state.value.document ?: return
        val preview = _state.value.drawPreview
        val t = _tools.value
        val mask: SelectionMask? = when (tool) {
            ToolId.RectSelect -> {
                val from = preview?.from
                val to = preview?.to
                if (from == null || to == null) null else SelectionEngine.fromRect(
                    doc.settings.width, doc.settings.height,
                    RectF2(min(from.x, to.x), min(from.y, to.y), max(from.x, to.x), max(from.y, to.y)),
                    t.selectionFeather,
                )
            }
            ToolId.EllipseSelect -> {
                val from = preview?.from
                val to = preview?.to
                if (from == null || to == null) null else SelectionEngine.fromEllipse(
                    doc.settings.width, doc.settings.height,
                    RectF2(min(from.x, to.x), min(from.y, to.y), max(from.x, to.x), max(from.y, to.y)),
                    t.selectionFeather,
                )
            }
            ToolId.LassoSelect, ToolId.PolygonSelect -> {
                val points = strokePoints.map { Pt(it.x, it.y) }
                if (points.size < 3) null else SelectionEngine.fromPolygon(
                    doc.settings.width, doc.settings.height, points, t.selectionFeather,
                )
            }
            else -> null
        }
        strokePoints = ArrayList()
        _state.value = _state.value.copy(drawPreview = null)
        applySelection(mask, t.selectionOp)
    }

    private fun applySelection(mask: SelectionMask?, op: SelectionOp) {
        if (mask == null) {
            selection = null
            _state.value = _state.value.copy(selection = null, selectionBounds = null)
            scheduleRender()
            return
        }
        selection = SelectionEngine.combine(selection, mask, op)
        // An empty selection that started as a Replace means "deselect".
        if (selection?.isEmpty() == true && op == SelectionOp.Replace) selection = null
        val bounds = selection?.bounds()?.let { RectF2(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
        _state.value = _state.value.copy(selection = selection, selectionBounds = bounds)
        scheduleRender()
    }

    fun selectAll() {
        val doc = _state.value.document ?: return
        applySelection(SelectionEngine.all(doc.settings.width, doc.settings.height), SelectionOp.Replace)
    }

    fun deselect() {
        selection = null
        _state.value = _state.value.copy(selection = null, selectionBounds = null)
        scheduleRender()
    }

    fun invertSelection() {
        selection = selection?.invert() ?: return
        val bounds = selection?.bounds()?.let { RectF2(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
        _state.value = _state.value.copy(selection = selection, selectionBounds = bounds)
        scheduleRender()
    }

    fun expandSelection(pixels: Int) {
        selection = selection?.expand(pixels) ?: return
        scheduleRender()
    }

    fun contractSelection(pixels: Int) {
        selection = selection?.contract(pixels) ?: return
        scheduleRender()
    }

    fun featherSelection(pixels: Int) {
        selection = selection?.feather(pixels) ?: return
        scheduleRender()
    }

    fun magicWand(x: Float, y: Float) {
        val doc = _state.value.document ?: return
        val layer = activeLayer() ?: return
        val frameId = currentFrameId() ?: return
        val bitmap = frameStore.load(doc.id, frameId, doc.settings.width, doc.settings.height)
        val t = _tools.value
        val mask = SelectionEngine.magicWand(
            doc.settings.width, doc.settings.height, bitmap,
            x.toInt(), y.toInt(), t.tolerance, contiguous = true, feather = t.selectionFeather,
        )
        applySelection(mask, t.selectionOp)
    }

    fun clearSelectionContents() {
        val doc = _state.value.document ?: return
        val layer = activeLayer() ?: return
        val frameId = currentFrameId() ?: return
        val mask = selection ?: return
        val bitmap = frameStore.load(doc.id, frameId, doc.settings.width, doc.settings.height)
        pushUndoForActiveFrame("Clear selection")
        val clipped = SelectionEngine.extractSelection(bitmap, mask)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawBitmap(clipped, 0f, 0f, android.graphics.Paint().apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
        })
        clipped.recycle()
        frameStore.markDirty(frameId)
        _state.value = _state.value.copy(dirty = true)
        scheduleRender()
    }

    fun fillSelection() {
        val doc = _state.value.document ?: return
        val layer = activeLayer() ?: return
        val frameId = currentFrameId() ?: return
        val mask = selection ?: return
        val bitmap = frameStore.load(doc.id, frameId, doc.settings.width, doc.settings.height)
        pushUndoForActiveFrame("Fill selection")
        val maskBmp = mask.toBitmap()
        val layerBmp = Bitmap.createBitmap(doc.settings.width, doc.settings.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(layerBmp).drawColor(_tools.value.primaryColor)
        android.graphics.Canvas(layerBmp).drawBitmap(maskBmp, 0f, 0f, android.graphics.Paint().apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        })
        android.graphics.Canvas(bitmap).drawBitmap(layerBmp, 0f, 0f, null)
        maskBmp.recycle()
        layerBmp.recycle()
        frameStore.markDirty(frameId)
        _state.value = _state.value.copy(dirty = true)
        scheduleRender()
    }

    fun copySelection() {
        val doc = _state.value.document ?: return
        val frameId = currentFrameId() ?: return
        val mask = selection ?: SelectionEngine.all(doc.settings.width, doc.settings.height)
        val bitmap = frameStore.load(doc.id, frameId, doc.settings.width, doc.settings.height)
        app.clipboard = SelectionEngine.extractSelection(bitmap, mask)
        app.clipboardMask = mask
        _state.value = _state.value.copy(statusMessage = "Copied selection")
    }

    fun cutSelection() {
        copySelection()
        clearSelectionContents()
    }

    fun pasteClipboard() {
        val doc = _state.value.document ?: return
        val layer = activeLayer() ?: return
        val clip = app.clipboard ?: return
        val frameId = ensureDrawingForStroke(layer) ?: return
        val bitmap = frameStore.load(doc.id, frameId, doc.settings.width, doc.settings.height)
        pushUndoForActiveFrame("Paste")
        drawingEngine.pasteBitmap(bitmap, clip, 0f, 0f)
        frameStore.markDirty(frameId)
        _state.value = _state.value.copy(dirty = true, statusMessage = "Pasted")
        scheduleRender()
    }

    fun hasClipboard(): Boolean = app.clipboard != null

    fun pickColorAt(x: Float, y: Float) {
        val doc = _state.value.document ?: return
        val frameId = currentFrameId() ?: return
        val bitmap = frameStore.load(doc.id, frameId, doc.settings.width, doc.settings.height)
        val px = x.toInt().coerceIn(0, bitmap.width - 1)
        val py = y.toInt().coerceIn(0, bitmap.height - 1)
        val color = bitmap.getPixel(px, py)
        if (ColorUtils.alpha(color) > 0) setPrimaryColor(color)
        _state.value = _state.value.copy(statusMessage = "Picked ${ColorUtils.toHex(color)}")
    }

    private fun addTextLayerAt(point: Pt) {
        val doc = _state.value.document ?: return
        val frameId = UUID.randomUUID().toString()
        repo.registerProject(doc)
        frameStore.createBlankFrame(doc.id, frameId, doc.settings.width, doc.settings.height)
        // Render the text into the frame so it is a real raster drawing.
        val bitmap = frameStore.load(doc.id, frameId, doc.settings.width, doc.settings.height)
        val layerId = UUID.randomUUID().toString()
        val text = com.artforge.mobile.core.model.TextLayerData(
            text = "Text",
            fontSize = _tools.value.textSize,
            color = _tools.value.primaryColor,
        )
        val layer = Layer(
            id = layerId,
            name = "Text",
            kind = LayerKind.Text,
            activeFrameId = frameId,
            frames = listOf(FrameRef(frameId, 0.0)),
            text = text,
        )
        TextRenderer.render(bitmap, text, point.x, point.y, doc.settings.width, doc.settings.height)
        frameStore.markDirty(frameId)
        _state.value = _state.value.copy(
            document = DocumentMutator.addLayer(doc, layer),
            activeLayerId = layer.id,
            dirty = true,
            statusMessage = "Text layer added",
        )
        scheduleRender()
    }

    fun updateTextLayer(layerId: String, text: com.artforge.mobile.core.model.TextLayerData) {
        val doc = _state.value.document ?: return
        val layer = doc.layers.firstOrNull { it.id == layerId } ?: return
        val frameId = layer.activeFrameId ?: return
        repo.registerProject(doc)
        val bitmap = frameStore.load(doc.id, frameId, doc.settings.width, doc.settings.height)
        bitmap.eraseColor(0)
        TextRenderer.render(bitmap, text, 0f, 0f, doc.settings.width, doc.settings.height)
        frameStore.markDirty(frameId)
        val updated = DocumentMutator.updateLayer(doc, layerId) { it.copy(text = text) }
        _state.value = _state.value.copy(document = updated, dirty = true)
        scheduleRender()
    }

    // -----------------------------------------------------------------------
    // Symmetry / perspective
    // -----------------------------------------------------------------------

    private fun symmetryBuilder(): SymmetryBuilder? {
        val doc = _state.value.document ?: return null
        if (doc.symmetry == com.artforge.mobile.core.model.SymmetryMode.None) return null
        return SymmetryBuilder(
            doc.symmetry,
            _tools.value.symmetrySegments,
            doc.settings.width / 2f,
            doc.settings.height / 2f,
        )
    }

    fun setSymmetry(mode: com.artforge.mobile.core.model.SymmetryMode) {
        val doc = _state.value.document ?: return
        _state.value = _state.value.copy(document = doc.copy(symmetry = mode), dirty = true)
        scheduleRender()
    }

    fun setPerspective(mode: Int, enabled: Boolean) {
        val doc = _state.value.document ?: return
        val guide = com.artforge.mobile.engine.perspective.PerspectiveEngine.defaultGuide(
            doc.settings.width.toFloat(), doc.settings.height.toFloat(), mode,
        ).copy(enabled = enabled)
        _state.value = _state.value.copy(document = doc.copy(perspective = guide), dirty = true)
        scheduleRender()
    }

    fun toggleGuides(show: Boolean) {
        _state.value = _state.value.copy(document = _state.value.document?.copy(), dirty = _state.value.dirty)
    }

    private fun snapPoint(point: Pt): Pt {
        val doc = _state.value.document ?: return point
        return com.artforge.mobile.engine.perspective.PerspectiveEngine.snap(
            point, doc.perspective, doc.settings.width.toFloat(), doc.settings.height.toFloat(),
            tolerance = 24f / _view.value.zoom.coerceAtLeast(0.1f),
        )
    }

    // -----------------------------------------------------------------------
    // Layers
    // -----------------------------------------------------------------------

    fun addLayer(kind: LayerKind = LayerKind.Raster) {
        val doc = _state.value.document ?: return
        val frameId = UUID.randomUUID().toString()
        repo.registerProject(doc)
        frameStore.createBlankFrame(doc.id, frameId, doc.settings.width, doc.settings.height)
        val layer = Layer(
            id = UUID.randomUUID().toString(),
            name = when (kind) {
                LayerKind.Group -> "Group"
                LayerKind.Text -> "Text"
                LayerKind.Vector -> "Vector"
                LayerKind.Reference -> "Reference"
                LayerKind.Adjustment -> "Adjustment"
                LayerKind.Animation -> "Anim Layer"
                else -> "Layer ${doc.layers.size + 1}"
            },
            kind = kind,
            activeFrameId = frameId,
            frames = listOf(FrameRef(frameId, 0.0)),
        )
        val updated = DocumentMutator.addLayer(doc, layer)
        _state.value = _state.value.copy(document = updated, activeLayerId = layer.id, dirty = true)
        scheduleRender()
    }

    fun duplicateLayer(layerId: String) {
        val doc = _state.value.document ?: return
        val source = doc.layers.firstOrNull { it.id == layerId } ?: return
        val frameId = UUID.randomUUID().toString()
        repo.registerProject(doc)
        val sourceFrame = source.activeFrameId ?: source.frames.firstOrNull()?.frameId
        if (sourceFrame != null) {
            frameStore.duplicateFrame(doc.id, sourceFrame, frameId, doc.settings.width, doc.settings.height)
        } else {
            frameStore.createBlankFrame(doc.id, frameId, doc.settings.width, doc.settings.height)
        }
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = "${source.name} copy",
            frames = listOf(FrameRef(frameId, 0.0)),
            activeFrameId = frameId,
            maskFrameId = null,
        )
        val updated = DocumentMutator.addLayer(doc, copy, layerId)
        _state.value = _state.value.copy(document = updated, activeLayerId = copy.id, dirty = true)
        scheduleRender()
    }

    fun deleteLayer(layerId: String) {
        val doc = _state.value.document ?: return
        val updated = DocumentMutator.deleteLayer(doc, layerId)
        _state.value = _state.value.copy(document = updated, activeLayerId = updated.activeLayerId, dirty = true)
        scheduleRender()
    }

    fun selectLayer(layerId: String) {
        _state.value = _state.value.copy(activeLayerId = layerId)
        scheduleRender()
    }

    fun setLayerVisible(layerId: String, visible: Boolean) {
        val doc = _state.value.document ?: return
        _state.value = _state.value.copy(document = DocumentMutator.setVisible(doc, layerId, visible), dirty = true)
        scheduleRender()
    }

    fun setLayerLocked(layerId: String, locked: Boolean) {
        val doc = _state.value.document ?: return
        _state.value = _state.value.copy(document = DocumentMutator.setLocked(doc, layerId, locked), dirty = true)
        scheduleRender()
    }

    fun setLayerOpacity(layerId: String, opacity: Float) {
        val doc = _state.value.document ?: return
        _state.value = _state.value.copy(document = DocumentMutator.setOpacity(doc, layerId, opacity), dirty = true)
        scheduleRender()
    }

    fun setLayerBlendMode(layerId: String, mode: BlendMode) {
        val doc = _state.value.document ?: return
        _state.value = _state.value.copy(document = DocumentMutator.setBlendMode(doc, layerId, mode), dirty = true)
        scheduleRender()
    }

    fun renameLayer(layerId: String, name: String) {
        val doc = _state.value.document ?: return
        _state.value = _state.value.copy(document = DocumentMutator.rename(doc, layerId, name), dirty = true)
    }

    fun setAlphaLock(layerId: String, locked: Boolean) {
        val doc = _state.value.document ?: return
        _state.value = _state.value.copy(document = DocumentMutator.setAlphaLock(doc, layerId, locked), dirty = true)
    }

    fun setLayerExpanded(layerId: String, expanded: Boolean) {
        val doc = _state.value.document ?: return
        _state.value = _state.value.copy(document = DocumentMutator.setExpanded(doc, layerId, expanded))
    }

    fun setClipping(layerId: String, clipped: Boolean) {
        val doc = _state.value.document ?: return
        _state.value = _state.value.copy(document = DocumentMutator.setClipping(doc, layerId, clipped), dirty = true)
        scheduleRender()
    }

    fun moveLayer(fromId: String, toIndex: Int) {
        val doc = _state.value.document ?: return
        _state.value = _state.value.copy(document = DocumentMutator.moveLayer(doc, fromId, toIndex), dirty = true)
        scheduleRender()
    }

    fun groupSelected() {
        val doc = _state.value.document ?: return
        val ids = _state.value.selectedFrames // reused set field for multi-select layer ids
        val activeId = _state.value.activeLayerId
        val targets = if (ids.isEmpty() && activeId != null) listOf(activeId) else ids.toList()
        val updated = DocumentMutator.groupLayers(doc, targets)
        _state.value = _state.value.copy(document = updated, dirty = true)
        scheduleRender()
    }

    fun ungroup(layerId: String) {
        val doc = _state.value.document ?: return
        _state.value = _state.value.copy(document = DocumentMutator.ungroup(doc, layerId), dirty = true)
        scheduleRender()
    }

    fun addLayerMask(layerId: String) {
        val doc = _state.value.document ?: return
        val layer = doc.layers.firstOrNull { it.id == layerId } ?: return
        val maskId = UUID.randomUUID().toString()
        repo.registerProject(doc)
        val maskBitmap = frameStore.createBlankFrame(doc.id, maskId, doc.settings.width, doc.settings.height, 0xFFFFFFFF.toInt())
        // When a selection exists, reveal only the selection: mask is the selection.
        val mask = selection
        if (mask != null) {
            maskBitmap.eraseColor(0)
            val maskCanvas = android.graphics.Canvas(maskBitmap)
            maskCanvas.drawColor(0xFFFFFFFF.toInt())
            maskCanvas.drawBitmap(mask.toBitmap(), 0f, 0f, null)
        }
        frameStore.markDirty(maskId)
        val updated = DocumentMutator.addMask(doc, layerId, maskId)
        _state.value = _state.value.copy(document = updated, dirty = true, statusMessage = "Layer mask added")
        scheduleRender()
    }

    fun removeLayerMask(layerId: String) {
        val doc = _state.value.document ?: return
        _state.value = _state.value.copy(document = DocumentMutator.removeMask(doc, layerId), dirty = true)
        scheduleRender()
    }

    fun mergeDown(layerId: String) {
        val doc = _state.value.document ?: return
        val index = doc.layers.indexOfFirst { it.id == layerId }
        if (index < 0 || index >= doc.layers.size - 1) return
        val upper = doc.layers[index]
        val lower = doc.layers[index + 1]
        val upperFrame = compositor.resolveFrame(upper, _state.value.frame)
        val lowerFrame = compositor.resolveFrame(lower, _state.value.frame)
        if (lowerFrame == null && upperFrame == null) return
        repo.registerProject(doc)
        val target = frameStore.load(doc.id, lowerFrame ?: UUID.randomUUID().toString(), doc.settings.width, doc.settings.height)
        if (upperFrame != null) {
            val upperBmp = frameStore.load(doc.id, upperFrame, doc.settings.width, doc.settings.height)
            val paint = android.graphics.Paint().apply {
                alpha = (upper.opacity * 255).toInt()
                blendMode = compositor.mapBlend(upper.blendMode)
            }
            android.graphics.Canvas(target).drawBitmap(upperBmp, 0f, 0f, paint)
        }
        if (lowerFrame != null) frameStore.markDirty(lowerFrame)
        val merged = DocumentMutator.mergeDown(doc, layerId) ?: return
        _state.value = _state.value.copy(document = merged, activeLayerId = lower.id, dirty = true)
        scheduleRender()
    }

    fun flattenLayers() {
        val doc = _state.value.document ?: return
        val newFrame = UUID.randomUUID().toString()
        repo.registerProject(doc)
        val flat = frameStore.createBlankFrame(doc.id, newFrame, doc.settings.width, doc.settings.height)
        compositor.composite(doc, flat, _state.value.frame, includeBackground = false)
        frameStore.markDirty(newFrame)
        val updated = DocumentMutator.flatten(doc, newFrame)
        _state.value = _state.value.copy(document = updated, activeLayerId = updated.layers.first().id, dirty = true)
        scheduleRender()
    }

    private fun transformActiveLayer(dx: Float, dy: Float) {
        val doc = _state.value.document ?: return
        val layer = activeLayer() ?: return
        val t = layer.transform
        val updated = layer.copy(transform = t.copy(translateX = t.translateX + dx, translateY = t.translateY + dy))
        _state.value = _state.value.copy(document = DocumentMutator.replaceLayer(doc, updated), dirty = true)
        scheduleRender()
    }

    fun setLayerTransform(layerId: String, transform: LayerTransform) {
        val doc = _state.value.document ?: return
        _state.value = _state.value.copy(document = DocumentMutator.setTransform(doc, layerId, transform), dirty = true)
        scheduleRender()
    }

    // -----------------------------------------------------------------------
    // Frames / animation
    // -----------------------------------------------------------------------

    fun setFrame(frame: Int) {
        val doc = _state.value.document ?: return
        val clamped = frame.coerceIn(0, max(0, doc.animation.frameCount - 1))
        if (clamped == _state.value.frame) return
        _state.value = _state.value.copy(frame = clamped)
        scheduleRender()
    }

    fun nextFrame() = setFrame(_state.value.frame + 1)
    fun previousFrame() = setFrame(_state.value.frame - 1)
    fun firstFrame() = setFrame(0)
    fun lastFrame() = setFrame((_state.value.document?.animation?.frameCount ?: 1) - 1)

    fun addBlankFrame() {
        val doc = _state.value.document ?: return
        val layer = activeLayer() ?: return
        val frame = _state.value.frame
        val newId = UUID.randomUUID().toString()
        repo.registerProject(doc)
        frameStore.createBlankFrame(doc.id, newId, doc.settings.width, doc.settings.height)
        val updated = layer.copy(
            frames = (layer.frames.filterNot { kotlin.math.abs(it.offset - frame) < 0.001 } + FrameRef(newId, frame.toDouble()))
                .sortedBy { it.offset },
            activeFrameId = newId,
            kind = LayerKind.Animation,
        )
        val anim = doc.animation.copy(
            frames = doc.animation.frames.filterNot { it.id == newId } + FrameMeta(newId),
            frameCount = max(doc.animation.frameCount, frame + 1),
        )
        _state.value = _state.value.copy(
            document = DocumentMutator.replaceLayer(doc, updated).copy(animation = anim),
            frameRange = 0..max(0, anim.frameCount - 1),
            dirty = true,
            statusMessage = "Blank frame added",
        )
        scheduleRender()
    }

    fun duplicateFrame() {
        val doc = _state.value.document ?: return
        val layer = activeLayer() ?: return
        val frame = _state.value.frame
        val sourceFrame = compositor.resolveFrame(layer, frame) ?: return
        val newId = UUID.randomUUID().toString()
        repo.registerProject(doc)
        frameStore.duplicateFrame(doc.id, sourceFrame, newId, doc.settings.width, doc.settings.height)
        val updated = layer.copy(
            frames = (layer.frames.filterNot { kotlin.math.abs(it.offset - frame) < 0.001 } + FrameRef(newId, frame.toDouble()))
                .sortedBy { it.offset },
            activeFrameId = newId,
            kind = LayerKind.Animation,
        )
        val anim = doc.animation.copy(frames = doc.animation.frames + FrameMeta(newId))
        _state.value = _state.value.copy(
            document = DocumentMutator.replaceLayer(doc, updated).copy(animation = anim),
            dirty = true,
            statusMessage = "Frame duplicated",
        )
        scheduleRender()
    }

    fun deleteFrame() {
        val doc = _state.value.document ?: return
        val layer = activeLayer() ?: return
        val frame = _state.value.frame
        val ref = layer.frames.firstOrNull { kotlin.math.abs(it.offset - frame) < 0.001 } ?: run {
            _state.value = _state.value.copy(statusMessage = "This is a hold frame — no drawing to delete")
            return
        }
        val updated = layer.copy(
            frames = layer.frames.filterNot { it.frameId == ref.frameId },
            activeFrameId = layer.frames.firstOrNull { it.frameId != ref.frameId }?.frameId,
        )
        frameStore.deleteFrame(doc.id, ref.frameId)
        val anim = doc.animation.copy(frames = doc.animation.frames.filterNot { it.id == ref.frameId })
        _state.value = _state.value.copy(
            document = DocumentMutator.replaceLayer(doc, updated).copy(animation = anim),
            dirty = true,
            statusMessage = "Frame deleted",
        )
        scheduleRender()
    }

    fun insertFrameSlot() {
        val doc = _state.value.document ?: return
        val anim = doc.animation
        val layers = doc.layers.map { AnimationEngine.insertFrames(it, _state.value.frame, 1) }
        val updated = doc.copy(layers = layers, animation = anim.copy(frameCount = anim.frameCount + 1))
        _state.value = _state.value.copy(
            document = updated,
            frameRange = 0..max(0, updated.animation.frameCount - 1),
            dirty = true,
            statusMessage = "Frame slot inserted",
        )
        scheduleRender()
    }

    fun extendExposure(frames: Int) {
        val doc = _state.value.document ?: return
        val layer = activeLayer() ?: return
        val updated = AnimationEngine.extendExposure(layer, _state.value.frame, frames)
        _state.value = _state.value.copy(document = DocumentMutator.replaceLayer(doc, updated), dirty = true)
        scheduleRender()
    }

    fun setFps(fps: Int) {
        val doc = _state.value.document ?: return
        val clamped = AnimationEngine.FPS_OPTIONS.minByOrNull { kotlin.math.abs(it - fps) } ?: fps
        _state.value = _state.value.copy(document = doc.copy(animation = doc.animation.copy(fps = clamped)), dirty = true)
    }

    fun setFrameCount(count: Int) {
        val doc = _state.value.document ?: return
        val clamped = count.coerceIn(1, 2000)
        _state.value = _state.value.copy(
            document = doc.copy(animation = doc.animation.copy(frameCount = clamped)),
            frameRange = 0..(clamped - 1),
            dirty = true,
        )
    }

    fun setLoop(loop: Boolean) {
        val doc = _state.value.document ?: return
        timeline.setLoop(loop)
        _state.value = _state.value.copy(document = doc.copy(animation = doc.animation.copy(loop = loop)), dirty = true)
    }

    fun setOnionSkin(config: OnionSkinConfig) {
        val doc = _state.value.document ?: return
        _state.value = _state.value.copy(document = doc.copy(animation = doc.animation.copy(onionSkin = config)), dirty = true)
        scheduleRender()
    }

    fun togglePlayback() {
        val doc = _state.value.document ?: return
        if (playbackState.value.isPlaying) {
            timeline.pause()
            _state.value = _state.value.copy(isPlaying = false)
        } else {
            timeline.setLoop(doc.animation.loop)
            timeline.play(_state.value.frame, doc.animation.fps, doc.animation, selectedRange())
            _state.value = _state.value.copy(isPlaying = true)
        }
    }

    fun stopPlayback() {
        timeline.stop()
        _state.value = _state.value.copy(isPlaying = false)
    }

    private fun onPlaybackFrame(frame: Int) {
        _state.value = _state.value.copy(frame = frame)
        scheduleRender()
    }

    private fun selectedRange(): IntRange? {
        val selected = _state.value.selectedFrames
        if (selected.isEmpty()) return null
        return selected.min()..selected.max()
    }

    fun toggleFrameSelection(frame: Int) {
        val current = _state.value.selectedFrames
        _state.value = _state.value.copy(
            selectedFrames = if (frame in current) current - frame else current + frame,
        )
    }

    fun clearFrameSelection() {
        _state.value = _state.value.copy(selectedFrames = emptySet())
    }

    fun addMarker(label: String, color: Int) {
        val doc = _state.value.document ?: return
        val marker = com.artforge.mobile.core.model.Marker(_state.value.frame, label, color)
        val anim = doc.animation.copy(markers = doc.animation.markers + marker)
        _state.value = _state.value.copy(document = doc.copy(animation = anim), dirty = true)
    }

    fun removeMarkerAt(frame: Int) {
        val doc = _state.value.document ?: return
        val anim = doc.animation.copy(markers = doc.animation.markers.filterNot { it.frame == frame })
        _state.value = _state.value.copy(document = doc.copy(animation = anim), dirty = true)
    }

    fun addScene(name: String) {
        val doc = _state.value.document ?: return
        val anim = doc.animation
        val scene = com.artforge.mobile.core.model.Scene(
            id = UUID.randomUUID().toString(),
            name = name,
            startFrame = _state.value.frame,
            endFrame = max(_state.value.frame, anim.frameCount - 1),
        )
        _state.value = _state.value.copy(
            document = doc.copy(animation = anim.copy(scenes = anim.scenes + scene)),
            dirty = true,
        )
    }

    /** Applies a keyframe on the active layer for a transform channel. */
    fun addKeyframe(channel: String, value: Float) {
        val doc = _state.value.document ?: return
        val layer = activeLayer() ?: return
        val frame = _state.value.frame
        val trackId = "track_${layer.id}"
        val existingTrack = doc.animation.tracks.firstOrNull { it.id == trackId }
        val channelValue = existingTrack?.channels?.firstOrNull { it.channel == channel }
        val updatedChannel = AnimationEngine.addKeyframe(
            channelValue?.keyframes ?: emptyList(),
            Keyframe(frame, value, Interpolation.EaseInOut),
        )
        val channelNew = com.artforge.mobile.core.model.AnimatableValue(channel, updatedChannel)
        val track = (existingTrack ?: com.artforge.mobile.core.model.AnimTrack(trackId, layer.name, layer.id))
            .let { t ->
                t.copy(channels = t.channels.filterNot { it.channel == channel } + channelNew)
            }
        val anim = doc.animation.copy(
            tracks = doc.animation.tracks.filterNot { it.id == trackId } + track,
        )
        _state.value = _state.value.copy(document = doc.copy(animation = anim), dirty = true, statusMessage = "Keyframe set")
    }

    fun applyAnimTracksAtFrame() {
        val doc = _state.value.document ?: return
        val frame = _state.value.frame
        val layers = doc.layers.map { layer ->
            val track = doc.animation.tracks.firstOrNull { it.layerId == layer.id } ?: return@map layer
            val opacity = AnimationEngine.evaluate(track, "opacity", frame, layer.opacity)
            val rot = AnimationEngine.evaluate(track, "rotation", frame, layer.transform.rotationDeg)
            val scale = AnimationEngine.evaluate(track, "scale", frame, layer.transform.scaleX)
            val tx = AnimationEngine.evaluate(track, "x", frame, layer.transform.translateX)
            val ty = AnimationEngine.evaluate(track, "y", frame, layer.transform.translateY)
            layer.copy(
                opacity = opacity.coerceIn(0f, 1f),
                transform = layer.transform.copy(
                    rotationDeg = rot,
                    scaleX = scale,
                    scaleY = scale,
                    translateX = tx,
                    translateY = ty,
                ),
            )
        }
        _state.value = _state.value.copy(document = doc.copy(layers = layers))
    }

    // -----------------------------------------------------------------------
    // Effects
    // -----------------------------------------------------------------------

    fun applyEffect(params: EffectParams) {
        val doc = _state.value.document ?: return
        val layer = activeLayer() ?: return
        val frameId = currentFrameId() ?: return
        viewModelScope.launch {
            pushUndoForActiveFrame(params.type.name)
            val frame = frameStore.load(doc.id, frameId, doc.settings.width, doc.settings.height)
            val result = withContext(Dispatchers.Default) { EffectsEngine.apply(frame, params) }
            android.graphics.Canvas(frame).drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            val src = if (result.width != frame.width || result.height != frame.height) {
                Bitmap.createScaledBitmap(result, frame.width, frame.height, true)
            } else result
            android.graphics.Canvas(frame).drawBitmap(src, 0f, 0f, null)
            if (src !== result) src.recycle()
            result.recycle()
            frameStore.markDirty(frameId)
            _state.value = _state.value.copy(dirty = true, statusMessage = "${params.type.name} applied")
            scheduleRender()
        }
    }

    fun adjustBrightnessContrast(brightness: Float, contrast: Float) {
        applyEffect(EffectParams(com.artforge.mobile.engine.effects.EffectType.BrightnessContrast, brightness = brightness, contrast = contrast))
    }

    // -----------------------------------------------------------------------
    // Pixel art
    // -----------------------------------------------------------------------

    fun togglePixelGrid(show: Boolean) {
        val doc = _state.value.document ?: return
        _state.value = _state.value.copy(document = doc.copy(pixel = doc.pixel.copy(showGrid = show)), dirty = true)
        scheduleRender()
    }

    fun enforcePixelPalette() {
        val doc = _state.value.document ?: return
        val frameId = currentFrameId() ?: return
        val palette = doc.pixel.restrictedPalette
        if (palette.isEmpty()) {
            _state.value = _state.value.copy(statusMessage = "No restricted palette set")
            return
        }
        val bitmap = frameStore.load(doc.id, frameId, doc.settings.width, doc.settings.height)
        pushUndoForActiveFrame("Palette lock")
        PixelEngine.enforcePalette(bitmap, palette)
        frameStore.markDirty(frameId)
        _state.value = _state.value.copy(dirty = true, statusMessage = "Palette enforced")
        scheduleRender()
    }

    fun setPixelPalette(colors: List<Int>) {
        val doc = _state.value.document ?: return
        _state.value = _state.value.copy(document = doc.copy(pixel = doc.pixel.copy(restrictedPalette = colors)), dirty = true)
    }

    fun exportSpriteSheet(columns: Int, onDone: (String) -> Unit) {
        val doc = _state.value.document ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true)
            val options = AnimationExportOptions(
                format = com.artforge.mobile.data.export.AnimationFormat.SpriteSheet,
                fps = doc.animation.fps,
                startFrame = 0,
                endFrame = doc.animation.frameCount - 1,
                spriteColumns = columns,
                includeBackground = false,
                scale = 1f,
            )
            val result = exportEngine.exportAnimation(doc, options, nameHint = ExportEngine.sanitizeFileName(doc.title) + "_sheet")
            _state.value = _state.value.copy(saving = false, statusMessage = result.message)
            if (result.success && result.file != null) onDone(result.file.absolutePath)
        }
    }

    // -----------------------------------------------------------------------
    // Comic
    // -----------------------------------------------------------------------

    fun generateComicLayout(preset: ComicEngine.LayoutPreset) {
        val doc = _state.value.document ?: return
        val panels = ComicEngine.generateLayout(
            preset,
            doc.settings.width.toFloat(),
            doc.settings.height.toFloat(),
        )
        val page = doc.pages.firstOrNull() ?: com.artforge.mobile.core.model.ComicPage(0)
        val updatedPages = doc.pages.toMutableList()
        if (updatedPages.isEmpty()) updatedPages.add(page)
        updatedPages[0] = page.copy(panels = panels)
        _state.value = _state.value.copy(document = doc.copy(pages = updatedPages), dirty = true, statusMessage = "Layout applied")
        scheduleRender()
    }

    fun addBubble(kind: String, x: Float, y: Float, text: String) {
        val doc = _state.value.document ?: return
        val page = doc.pages.firstOrNull() ?: com.artforge.mobile.core.model.ComicPage(0)
        val (w, h) = ComicEngine.autoBubbleSize(text, 32f, doc.settings.width * 0.5f)
        val bubble = com.artforge.mobile.core.model.ComicBubble(
            id = UUID.randomUUID().toString(),
            kind = kind,
            x = x, y = y, width = w, height = h,
            text = text,
            tailTargetX = x + 20f,
            tailTargetY = y + h + 60f,
        )
        val pages = if (doc.pages.isEmpty()) listOf(page.copy(bubbles = listOf(bubble)))
        else doc.pages.mapIndexed { i, p -> if (i == 0) p.copy(bubbles = p.bubbles + bubble) else p }
        _state.value = _state.value.copy(document = doc.copy(pages = pages), dirty = true)
        scheduleRender()
    }

    fun updateBubble(bubble: com.artforge.mobile.core.model.ComicBubble) {
        val doc = _state.value.document ?: return
        val pages = doc.pages.mapIndexed { i, p ->
            if (i == 0) p.copy(bubbles = p.bubbles.map { if (it.id == bubble.id) bubble else it }) else p
        }
        _state.value = _state.value.copy(document = doc.copy(pages = pages), dirty = true)
        scheduleRender()
    }

    fun deleteBubble(bubbleId: String) {
        val doc = _state.value.document ?: return
        val pages = doc.pages.mapIndexed { i, p -> if (i == 0) p.copy(bubbles = p.bubbles.filterNot { it.id == bubbleId }) else p }
        _state.value = _state.value.copy(document = doc.copy(pages = pages), dirty = true)
        scheduleRender()
    }

    // -----------------------------------------------------------------------
    // Character rig
    // -----------------------------------------------------------------------

    fun addBone(parentId: String?, x: Float, y: Float) {
        val doc = _state.value.document ?: return
        val bone = com.artforge.mobile.core.model.Bone(
            id = UUID.randomUUID().toString(),
            name = "Bone ${doc.animation.bones.size + 1}",
            parentId = parentId,
            restX = x,
            restY = y,
            layerId = _state.value.activeLayerId,
        )
        val anim = doc.animation.copy(bones = doc.animation.bones + bone)
        _state.value = _state.value.copy(document = doc.copy(animation = anim), dirty = true)
    }

    fun updateBone(bone: com.artforge.mobile.core.model.Bone) {
        val doc = _state.value.document ?: return
        val anim = doc.animation.copy(bones = doc.animation.bones.map { if (it.id == bone.id) bone else it })
        _state.value = _state.value.copy(document = doc.copy(animation = anim), dirty = true)
    }

    fun deleteBone(boneId: String) {
        val doc = _state.value.document ?: return
        val anim = doc.animation.copy(
            bones = doc.animation.bones.filterNot { it.id == boneId || it.parentId == boneId },
        )
        _state.value = _state.value.copy(document = doc.copy(animation = anim), dirty = true)
    }

    fun capturePose(name: String) {
        val doc = _state.value.document ?: return
        val pose = CharacterRig.capturePose(name, doc.animation.bones)
        val anim = doc.animation.copy(poses = doc.animation.poses + pose)
        _state.value = _state.value.copy(document = doc.copy(animation = anim), dirty = true, statusMessage = "Pose saved")
    }

    fun applyPose(poseId: String) {
        val doc = _state.value.document ?: return
        val pose = doc.animation.poses.firstOrNull { it.id == poseId } ?: return
        val bones = CharacterRig.applyPose(doc.animation.bones, pose)
        val anim = doc.animation.copy(bones = bones)
        _state.value = _state.value.copy(document = doc.copy(animation = anim), dirty = true)
    }

    // -----------------------------------------------------------------------
    // Palettes
    // -----------------------------------------------------------------------

    fun addPalette(palette: Palette) {
        val doc = _state.value.document ?: return
        _state.value = _state.value.copy(
            document = doc.copy(
                palettes = doc.palettes.filterNot { it.id == palette.id } + palette,
                activePaletteId = palette.id,
            ),
            dirty = true,
        )
        _tools.value = _tools.value.copy(palette = palette)
    }

    fun removePalette(paletteId: String) {
        val doc = _state.value.document ?: return
        _state.value = _state.value.copy(
            document = doc.copy(palettes = doc.palettes.filterNot { it.id == paletteId }),
            dirty = true,
        )
    }

    // -----------------------------------------------------------------------
    // Export
    // -----------------------------------------------------------------------

    /**
     * True when the current project is big enough that encoding belongs in a
     * background worker instead of the view-model scope.
     */
    private fun shouldExportInBackground(doc: ProjectDocument, frames: Int): Boolean =
        doc.settings.width.toLong() * doc.settings.height > 4_000_000L || frames > 48

    /**
     * Image export. Small documents encode inline with progress; large ones are
     * handed to [ExportWorker] so the work survives rotation and screen-off.
     */
    fun exportImage(options: ImageExportOptions, onDone: (android.net.Uri?) -> Unit) {
        val doc = _state.value.document ?: return
        saveProject(userInitiated = false)
        if (shouldExportInBackground(doc, 1)) {
            ExportWorker.enqueueImage(
                app, doc.id, options.format, options.scale, options.quality,
                options.transparent, options.frameIndex,
            )
            observeBackgroundExport(onDone)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, statusMessage = "Encoding image…")
            val result = exportEngine.exportImage(
                doc, options,
                nameHint = ExportEngine.sanitizeFileName(doc.title),
                onProgress = { p -> _state.value = _state.value.copy(statusMessage = "Encoding ${(p * 100).toInt()}%") },
            )
            _state.value = _state.value.copy(saving = false, statusMessage = result.message)
            onDone(result.file?.let { runCatching { ExportEngine.shareUri(app, it) }.getOrNull() })
        }
    }

    /** Mirrors [ExportWorker] progress into the toolbar status and finishes the callback. */
    private fun observeBackgroundExport(onDone: (android.net.Uri?) -> Unit) {
        _state.value = _state.value.copy(saving = true, statusMessage = "Exporting in background…")
        viewModelScope.launch {
            val manager = WorkManager.getInstance(app)
            var finished: WorkInfo? = null
            var attempts = 0
            while (finished == null && attempts < 900) {
                attempts++
                val info = manager.getWorkInfosForUniqueWorkFlow(ExportWorker.UNIQUE_NAME).first().firstOrNull()
                if (info == null) break
                if (info.state.isFinished) {
                    finished = info
                } else {
                    val progress = info.progress.getFloat(ExportWorker.KEY_PROGRESS, -1f)
                    if (progress >= 0f) {
                        _state.value = _state.value.copy(
                            statusMessage = "Exporting ${(progress * 100).toInt()}%",
                        )
                    }
                    delay(400)
                }
            }
            val info = finished
            if (info == null) {
                _state.value = _state.value.copy(saving = false, statusMessage = "Export could not start")
                onDone(null)
                return@launch
            }
            val path = info.outputData.getString(ExportWorker.KEY_OUTPUT_PATH)
            val error = info.outputData.getString(ExportWorker.KEY_ERROR)
            _state.value = _state.value.copy(
                saving = false,
                statusMessage = when {
                    path != null -> "Exported ${java.io.File(path).name}"
                    error != null -> error
                    else -> "Export finished"
                },
            )
            onDone(path?.let { file -> runCatching { ExportEngine.shareUri(app, java.io.File(file)) }.getOrNull() })
        }
    }

    fun exportAnimation(options: AnimationExportOptions, onDone: (android.net.Uri?) -> Unit) {
        val doc = _state.value.document ?: return
        saveProject(userInitiated = false)
        val frameSpan = (options.endFrame - options.startFrame + 1).coerceAtLeast(1)
        if (shouldExportInBackground(doc, frameSpan)) {
            ExportWorker.enqueueAnimation(
                app, doc.id, options.format, options.fps, options.startFrame, options.endFrame,
                options.scale, options.quality, options.loop, options.spriteColumns,
                options.background, options.includeBackground,
            )
            observeBackgroundExport(onDone)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, statusMessage = "Rendering frames…")
            val result = exportEngine.exportAnimation(
                doc, options,
                nameHint = ExportEngine.sanitizeFileName(doc.title),
                onProgress = { p ->
                    _state.value = _state.value.copy(statusMessage = "Rendering ${(p * 100).toInt()}%")
                },
            )
            _state.value = _state.value.copy(saving = false, statusMessage = result.message)
            onDone(result.file?.let { runCatching { ExportEngine.shareUri(app, it) }.getOrNull() })
        }
    }

    fun exportProjectPackage(onDone: (android.net.Uri?) -> Unit) {
        val doc = _state.value.document ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true)
            saveProject(userInitiated = false)
            delay(120)
            val file = repo.projectFile(doc.id)
            val uri = runCatching { ExportEngine.shareUri(app, file) }.getOrNull()
            _state.value = _state.value.copy(saving = false, statusMessage = "Project package ready")
            onDone(uri)
        }
    }

    // -----------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------

    private fun scheduleRender() {
        if (renderJob?.isActive == true) return
        renderJob = viewModelScope.launch {
            delay(16)
            render()
        }
    }

    fun render() {
        val doc = _state.value.document ?: return
        val layer = activeLayer()
        val frame = _state.value.frame
        val frameId = layer?.let { compositor.resolveFrame(it, frame) }

        // Live stroke buffer: composite the in-progress stroke above the frame.
        val strokeBitmap = if (drawingEngine.isStrokeActive && frameId != null) {
            val target = frameStore.load(doc.id, frameId, doc.settings.width, doc.settings.height)
            val buffer = Bitmap.createBitmap(doc.settings.width, doc.settings.height, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(buffer).drawBitmap(target, 0f, 0f, null)
            buffer
        } else null

        val ghosts = if (doc.kind == ProjectKind.Animation && layer != null && doc.animation.onionSkin.enabled) {
            AnimationEngine.onionGhosts(layer, frame, doc.animation.onionSkin)
        } else emptyList()

        val request = CanvasRenderer.RenderRequest(
            document = doc,
            frame = frame,
            onionGhosts = ghosts,
            selection = selection,
            showSelection = selection != null,
            showGuides = _settings.value.showGuides,
            showGrid = _settings.value.gridStyle != com.artforge.mobile.data.settings.GridStyle.None,
            gridSpacing = when (_settings.value.gridStyle) {
                com.artforge.mobile.data.settings.GridStyle.Fine -> 32f
                com.artforge.mobile.data.settings.GridStyle.Coarse -> 128f
                com.artforge.mobile.data.settings.GridStyle.Isometric -> 64f
                else -> 0f
            },
            symmetrySegments = _tools.value.symmetrySegments,
            preview = _state.value.drawPreview,
            brushCursor = null,
            brushCursorRadius = _tools.value.brushSize / 2f,
            showComicFurniture = doc.kind == ProjectKind.Comic,
            pixelMode = doc.kind == ProjectKind.PixelArt,
            previewQuality = _settings.value.renderQuality,
            activeStrokeBitmap = strokeBitmap,
        )
        val result = renderer.render(request)
        displayBitmap = result
        val image = result.copy(Bitmap.Config.ARGB_8888, true)
        _state.value = _state.value.copy(
            composite = image.asImageBitmap(),
            canvasWidth = doc.settings.width,
            canvasHeight = doc.settings.height,
            canUndo = undo.canUndo,
            canRedo = undo.canRedo,
        )
    }

    fun startAutosave() {
        autosaveJob?.cancel()
        val settings = _settings.value
        if (!settings.autosaveEnabled) return
        autosaveJob = viewModelScope.launch {
            while (true) {
                delay(settings.autosaveIntervalSeconds.coerceAtLeast(30) * 1000L)
                val doc = _state.value.document ?: continue
                if (_state.value.dirty) {
                    frameStore.flushAll(doc.id)
                    repo.autosave(doc)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        autosaveJob?.cancel()
        timeline.release()
        renderer.release()
        drawingEngine.cancelStroke()
    }

    companion object {
        private const val TAG = "StudioViewModel"
    }
}
