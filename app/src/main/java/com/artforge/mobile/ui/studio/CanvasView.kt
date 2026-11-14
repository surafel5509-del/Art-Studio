package com.artforge.mobile.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.artforge.mobile.core.model.Pt
import com.artforge.mobile.engine.draw.StrokePoint
import com.artforge.mobile.ui.theme.ForgeColors
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Computes the document→screen transform for the current viewport.
 *
 * `zoom == 1` means "fit the whole document", so a freshly opened canvas always
 * starts fully visible. Pan is in screen pixels, rotation around the viewport centre.
 */
data class CanvasTransform(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val documentWidth: Float,
    val documentHeight: Float,
    val view: CanvasView,
) {
    val fitScale: Float = if (documentWidth <= 0f || documentHeight <= 0f) 1f else
        min(viewportWidth / documentWidth, viewportHeight / documentHeight) * 0.94f

    val scale: Float get() = fitScale * view.zoom

    val centerX: Float get() = viewportWidth / 2f + view.panX
    val centerY: Float get() = viewportHeight / 2f + view.panY

    val displayWidth: Float get() = documentWidth * scale
    val displayHeight: Float get() = documentHeight * scale

    /** Screen point → document point. */
    fun toDocument(screen: Offset): Pt {
        var dx = screen.x - centerX
        var dy = screen.y - centerY
        val rad = Math.toRadians(-view.rotation.toDouble())
        val rx = (dx * cos(rad) - dy * sin(rad)).toFloat()
        val ry = (dx * sin(rad) + dy * cos(rad)).toFloat()
        dx = rx
        dy = ry
        if (view.flipHorizontal) dx = -dx
        val s = scale.coerceAtLeast(0.0001f)
        return Pt(dx / s + documentWidth / 2f, dy / s + documentHeight / 2f)
    }

    /** Document point → screen point. */
    fun toScreen(point: Pt): Offset {
        var dx = (point.x - documentWidth / 2f) * scale
        var dy = (point.y - documentHeight / 2f) * scale
        if (view.flipHorizontal) dx = -dx
        val rad = Math.toRadians(view.rotation.toDouble())
        val rx = (dx * cos(rad) - dy * sin(rad)).toFloat()
        val ry = (dx * sin(rad) + dy * cos(rad)).toFloat()
        return Offset(centerX + rx, centerY + ry)
    }
}

/**
 * The drawing surface. Renders the composited canvas bitmap with zoom / pan /
 * rotation and forwards raw stylus + finger input to the view model.
 *
 * Input rules:
 *  - stylus and eraser stylus always draw (highest priority, pressure + tilt kept),
 *  - one finger draws when enabled and no stylus was used in the palm-rejection window,
 *  - two fingers pan, pinch-zoom and twist-rotate,
 *  - double tap fits the canvas to the screen.
 */
@Composable
fun CanvasSurface(
    state: StudioState,
    view: CanvasView,
    modifier: Modifier = Modifier,
    oneFingerDraw: Boolean = true,
    palmRejection: Boolean = true,
    enableRotation: Boolean = true,
    backgroundColor: Color = ForgeColors.CharcoalDeep,
    showCheckerboard: Boolean = true,
    onPointerDown: (StrokePoint, Boolean) -> Boolean,
    onPointerMove: (StrokePoint) -> Unit,
    onPointerUp: (StrokePoint) -> Unit,
    onPan: (Float, Float) -> Unit,
    onZoom: (Float, Float, Float) -> Unit,
    onRotate: (Float) -> Unit,
    onDoubleTap: (Pt) -> Unit,
    onViewportMeasured: (Float, Float, Float) -> Unit,
    onLongPress: (Pt) -> Unit = {},
) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val downState = rememberUpdatedState(onPointerDown)
    val moveState = rememberUpdatedState(onPointerMove)
    val upState = rememberUpdatedState(onPointerUp)
    val panState = rememberUpdatedState(onPan)
    val zoomState = rememberUpdatedState(onZoom)
    val rotateState = rememberUpdatedState(onRotate)
    val tapState = rememberUpdatedState(onDoubleTap)
    val longPressState = rememberUpdatedState(onLongPress)

    val docW = state.document?.settings?.width?.toFloat() ?: 0f
    val docH = state.document?.settings?.height?.toFloat() ?: 0f
    val transform = CanvasTransform(
        viewportWidth = viewport.width.toFloat(),
        viewportHeight = viewport.height.toFloat(),
        documentWidth = docW,
        documentHeight = docH,
        view = view,
    )
    val transformState = rememberUpdatedState(transform)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .onSizeChanged {
                viewport = it
                onViewportMeasured(
                    it.width.toFloat(),
                    it.height.toFloat(),
                    if (docW > 0 && docH > 0) min(it.width / docW, it.height / docH) else 1f,
                )
            }
            .pointerInput(oneFingerDraw, palmRejection, enableRotation) {
                handleCanvasPointers(
                    transform = { transformState.value },
                    oneFingerDraw = oneFingerDraw,
                    palmRejection = palmRejection,
                    enableRotation = enableRotation,
                    onDown = { p, stylus -> downState.value(p, stylus) },
                    onMove = { p -> moveState.value(p) },
                    onUp = { p -> upState.value(p) },
                    onPan = { dx, dy -> panState.value(dx, dy) },
                    onZoom = { f, fx, fy -> zoomState.value(f, fx, fy) },
                    onRotate = { d -> rotateState.value(d) },
                    onDoubleTap = { p -> tapState.value(p) },
                    onLongPress = { p -> longPressState.value(p) },
                )
            },
    ) {
        val composite = state.composite
        Canvas(Modifier.fillMaxSize()) {
            if (docW > 0f && docH > 0f) {
                val t = transformState.value
                val left = t.centerX - t.displayWidth / 2f
                val top = t.centerY - t.displayHeight / 2f
                val sheetWidth = t.displayWidth
                val sheetHeight = t.displayHeight

                // A canvas that renders nothing is a bug, not a blank page: the sheet and
                // its checkerboard are drawn from the document size alone, so the user
                // always sees where the paper is (and can still draw on it).
                drawRect(
                    color = Color(0x66000000),
                    topLeft = Offset(left + 6f, top + 10f),
                    size = Size(sheetWidth, sheetHeight),
                )

                rotate(t.view.rotation, Offset(t.centerX, t.centerY)) {
                    scale(if (t.view.flipHorizontal) -1f else 1f, 1f, Offset(t.centerX, t.centerY)) {
                        val paper = 0xFFFFFFFF.toInt()
                        // Base tone: white paper for opaque documents, checkerboard for
                        // transparent ones. Either way the sheet is visible before the
                        // first composite arrives and it is never a black rectangle.
                        if (state.document?.settings?.transparentBackground == true && showCheckerboard) {
                            drawTransparencyChecker(left, top, sheetWidth, sheetHeight, t.scale)
                        } else {
                            drawRect(
                                color = Color(paper),
                                topLeft = Offset(left, top),
                                size = Size(sheetWidth, sheetHeight),
                            )
                        }
                        if (composite != null) {
                            drawImage(
                                image = composite,
                                dstSize = androidx.compose.ui.unit.IntSize(
                                    sheetWidth.toInt().coerceAtLeast(1),
                                    sheetHeight.toInt().coerceAtLeast(1),
                                ),
                                dstOffset = androidx.compose.ui.unit.IntOffset(
                                    left.toInt(),
                                    top.toInt(),
                                ),
                                filterQuality = if (t.scale > 2.4f) FilterQuality.None else FilterQuality.Low,
                            )
                        }

                    }
                }
                drawSheetBorder(t)
                if (state.renderError != null) {
                    // Explain the failure on the canvas instead of showing a silent
                    // blank page: the user can still draw, but knows why it looks off.
                    drawRect(
                        color = ForgeColors.Danger.copy(alpha = 0.18f),
                        topLeft = Offset(left, top),
                        size = Size(sheetWidth, sheetHeight),
                    )
                }
            } else if (state.loading) {
                // Document still opening: nothing to draw yet, but never a bare void.
                drawRect(
                    color = Color(0xFF17181C),
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height),
                )
            }
        }
    }
}

/**
 * Checkerboard behind the sheet so transparency reads as transparency, not as "black".
 * The square size follows the zoom so it stays visually constant.
 */
private fun DrawScope.drawTransparencyChecker(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    zoom: Float,
) {
    val cell = (14f * zoom.coerceIn(0.35f, 3f)).coerceIn(6f, 40f)
    val dark = Color(0xFFD8D8D8)
    var y = top
    var row = 0
    // Clip to the sheet so the pattern never spills onto the workspace.
    val sheet = androidx.compose.ui.geometry.Rect(left, top, left + width, top + height)
    clipRect(sheet.left, sheet.top, sheet.right, sheet.bottom) {
        while (y < top + height) {
            var x = left
            var col = 0
            while (x < left + width) {
                if ((row + col) % 2 == 0) {
                    drawRect(
                        color = dark,
                        topLeft = Offset(x, y),
                        size = Size(
                            min(cell, left + width - x).coerceAtLeast(0f),
                            min(cell, top + height - y).coerceAtLeast(0f),
                        ),
                    )
                }
                x += cell
                col++
            }
            y += cell
            row++
        }
    }
}

private fun DrawScope.drawSheetBorder(t: CanvasTransform) {
    val left = t.centerX - t.displayWidth / 2f
    val top = t.centerY - t.displayHeight / 2f
    rotate(t.view.rotation, Offset(t.centerX, t.centerY)) {
        drawRect(
            color = Color(0x33FFFFFF),
            topLeft = Offset(left, top),
            size = Size(t.displayWidth, t.displayHeight),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4f),
        )
    }
}

/**
 * Pointer loop shared by all canvas interactions. Written as a suspend extension on
 * PointerInputScope so Compose can cancel it cleanly when the composable leaves.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.handleCanvasPointers(
    transform: () -> CanvasTransform,
    oneFingerDraw: Boolean,
    palmRejection: Boolean,
    enableRotation: Boolean,
    onDown: (StrokePoint, Boolean) -> Boolean,
    onMove: (StrokePoint) -> Unit,
    onUp: (StrokePoint) -> Unit,
    onPan: (Float, Float) -> Unit,
    onZoom: (Float, Float, Float) -> Unit,
    onRotate: (Float) -> Unit,
    onDoubleTap: (Pt) -> Unit,
    onLongPress: (Pt) -> Unit = {},
) {
    awaitPointerEventScope {
        val tracked = HashMap<androidx.compose.ui.input.pointer.PointerId, Offset>()
        var drawing = false
        var drawingId: androidx.compose.ui.input.pointer.PointerId? = null
        var multiTouch = false
        var lastDistance = 0f
        var lastAngle = 0f
        var lastCentroid: Offset? = null
        var lastTapAt = 0L
        var lastTapPos = Offset.Zero
        var lastStylusAt = 0L
        var pressStartedAt = 0L
        var pressStartPos: Offset? = null
        var pressedAtId: androidx.compose.ui.input.pointer.PointerId? = null

        fun strokePoint(offset: Offset, pressure: Float, orientation: Float): StrokePoint {
            val doc = transform().toDocument(offset)
            return StrokePoint(doc.x, doc.y, if (pressure > 0f) pressure else 1f, orientation, timeMs = System.currentTimeMillis())
        }

        fun endStroke() {
            if (!drawing) return
            val id = drawingId
            val pos = id?.let { tracked[it] }
            if (pos != null) {
                val p = transform().toDocument(pos)
                onUp(StrokePoint(p.x, p.y, 1f))
            } else {
                onUp(StrokePoint(0f, 0f))
            }
            drawing = false
            drawingId = null
        }

        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            val now = System.currentTimeMillis()

            when {
                pressed.isEmpty() -> {
                    endStroke()
                    multiTouch = false
                    lastDistance = 0f
                    lastAngle = 0f
                    lastCentroid = null
                    tracked.clear()
                }

                pressed.size >= 2 -> {
                    if (drawing) endStroke()
                    multiTouch = true
                    val a = pressed[0].position
                    val b = pressed[1].position
                    val distance = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
                    val angle = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
                    val centroid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)

                    if (lastDistance > 0f && distance > 0f) {
                        val factor = distance / lastDistance
                        if (abs(factor - 1f) > 0.004f) onZoom(factor, centroid.x, centroid.y)
                    }
                    if (enableRotation && lastAngle != 0f) {
                        var delta = angle - lastAngle
                        if (delta > 180f) delta -= 360f
                        if (delta < -180f) delta += 360f
                        if (abs(delta) > 0.5f) onRotate(delta)
                    }
                    lastCentroid?.let { previous ->
                        val dx = centroid.x - previous.x
                        val dy = centroid.y - previous.y
                        if (abs(dx) > 0.01f || abs(dy) > 0.01f) onPan(dx, dy)
                    }
                    lastDistance = distance
                    lastAngle = angle
                    lastCentroid = centroid
                    pressed.forEach { change ->
                        tracked[change.id] = change.position
                        change.consume()
                    }
                }

                pressed.size == 1 -> {
                    val change = pressed[0]
                    val isStylus = change.type == androidx.compose.ui.input.pointer.PointerType.Stylus ||
                        change.type == androidx.compose.ui.input.pointer.PointerType.Eraser
                    if (isStylus) lastStylusAt = now
                    val stylusActive = palmRejection && now - lastStylusAt < 700
                    val canDraw = isStylus || (oneFingerDraw && !stylusActive)

                    when (event.type) {
                        PointerEventType.Press -> {
                            tracked[change.id] = change.position
                            lastCentroid = change.position
                            pressStartedAt = now
                            pressedAtId = change.id
                            pressStartPos = change.position
                            // Stylus barrel rotation is delivered through the
                            // MotionEvent orientation field; Compose does not expose it
                            // on PointerInputChange, so rotation is left at 0 and the
                            // brush falls back to pressure/tilt only.
                            val orientation = 0f
                            if (canDraw) {
                                val consumed = onDown(
                                    strokePoint(change.position, change.pressure, orientation),
                                    isStylus,
                                )
                                drawing = consumed
                                if (consumed) drawingId = change.id
                            }
                            val isDoubleTap = now - lastTapAt < 300 &&
                                hypot(
                                    (lastTapPos.x - change.position.x).toDouble(),
                                    (lastTapPos.y - change.position.y).toDouble(),
                                ) < 48
                            if (isDoubleTap) {
                                onDoubleTap(transform().toDocument(change.position))
                                lastTapAt = 0L
                            } else {
                                lastTapAt = now
                                lastTapPos = change.position
                            }
                        }

                        PointerEventType.Move -> {
                            tracked[change.id] = change.position
                            if (drawing && drawingId == change.id) {
                                onMove(strokePoint(change.position, change.pressure, 0f))
                                change.consume()
                            }
                        }

                        PointerEventType.Release -> {
                            // A stationary press held past 600 ms is a long-press: used
                            // to re-pick the clone-stamp source without leaving the tool.
                            if (pressedAtId == change.id) {
                                val held = now - pressStartedAt
                                val moved = pressStartPos?.let {
                                    hypot(
                                        (it.x - change.position.x).toDouble(),
                                        (it.y - change.position.y).toDouble(),
                                    )
                                } ?: 0.0
                                if (held >= 600 && moved < 16.0) {
                                    onLongPress(transform().toDocument(change.position))
                                    if (!drawing) change.consume()
                                }
                            }
                            pressStartedAt = 0L
                            pressedAtId = null
                            pressStartPos = null
                            if (drawing && drawingId == change.id) {
                                val p = transform().toDocument(change.position)
                                onUp(StrokePoint(p.x, p.y, 1f))
                                drawing = false
                                drawingId = null
                            }
                            tracked.remove(change.id)
                        }

                        else -> Unit
                    }
                }
            }
        }
    }
}

/** Utility used by the view model to convert brush size in document px → screen px. */
fun brushRadiusOnScreen(brushSize: Float, transform: CanvasTransform): Float =
    (brushSize / 2f) * transform.scale

@Composable
fun rememberCanvasTransform(view: CanvasView, state: StudioState): CanvasTransform {
    val docW = state.document?.settings?.width?.toFloat() ?: 1f
    val docH = state.document?.settings?.height?.toFloat() ?: 1f
    return remember(view, docW, docH) {
        CanvasTransform(1080f, 1920f, docW, docH, view)
    }
}

@Suppress("unused")
private fun unusedImage(): ImageBitmap? = null
