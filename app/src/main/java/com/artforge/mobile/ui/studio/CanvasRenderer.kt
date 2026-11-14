package com.artforge.mobile.ui.studio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import com.artforge.mobile.core.model.ProjectDocument
import com.artforge.mobile.core.model.Pt
import com.artforge.mobile.engine.comic.ComicEngine
import com.artforge.mobile.engine.draw.Compositor
import com.artforge.mobile.engine.draw.FrameStore
import com.artforge.mobile.engine.perspective.PerspectiveEngine
import com.artforge.mobile.engine.pixel.PixelEngine
import kotlin.math.max

/**
 * Renders the document into a display bitmap: the composited layers, onion skin,
 * guides, selection marching ants, comic page furniture and the live tool preview.
 *
 * This is the "canvas" the Compose layer shows — a real bitmap produced by the real
 * compositor, not a mock. It is rebuilt on demand and downsampled when the document
 * exceeds the display budget so large canvases stay interactive.
 */
class CanvasRenderer(
    private val frameStore: FrameStore,
    private val compositor: Compositor,
) {

    private var compositeBuffer: Bitmap? = null
    private var displayBuffer: Bitmap? = null

    /** Maximum pixels rendered per frame; larger documents are downsampled. */
    /**
     * Upper bound on the pixels actually pushed to the screen. 4.2 MP is more than any
     * phone needs for a zoom-to-fit canvas and keeps peak memory low: a 2000x3000
     * document used to allocate 24 MB for the composite, 24 MB for the display buffer
     * and another 24 MB for the copy handed to Compose, which is enough to throw
     * OutOfMemoryError on a mid-range device and leave the canvas black.
     */
    var maxDisplayPixels: Long = 4_200_000L

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val noFilterPaint = Paint().apply { isFilterBitmap = false }

    data class RenderRequest(
        val document: ProjectDocument,
        val frame: Int,
        val onionGhosts: List<Compositor.GhostFrame>,
        val selection: com.artforge.mobile.engine.draw.SelectionMask?,
        val showSelection: Boolean,
        val showGuides: Boolean,
        val showGrid: Boolean,
        val gridSpacing: Float,
        val symmetrySegments: Int,
        val preview: DrawPreview?,
        val brushCursor: Pair<Float, Float>?,
        val brushCursorRadius: Float,
        val showComicFurniture: Boolean,
        val pixelMode: Boolean,
        val previewQuality: Float,
        val ghostShapes: List<com.artforge.mobile.core.model.VectorShape> = emptyList(),
        val activeStrokeBitmap: Bitmap? = null,
        /** Crosshair position of the clone-stamp source, if the tool has one. */
        val cloneSource: Pair<Float, Float>? = null,
    )

    fun render(request: RenderRequest): Bitmap {
        val doc = request.document
        val docW = doc.settings.width
        val docH = doc.settings.height
        val composite = obtainComposite(docW, docH)
        compositor.composite(
            doc,
            composite,
            request.frame,
            onionGhosts = request.onionGhosts,
            includeBackground = true,
        )

        // Downscale for display when the document is larger than the render budget.
        val pixels = docW.toLong() * docH
        val displayW: Int
        val displayH: Int
        val source: Bitmap
        if (pixels > maxDisplayPixels) {
            val scale = kotlin.math.sqrt(maxDisplayPixels.toDouble() / pixels).toFloat()
            displayW = max(1, (docW * scale).toInt())
            displayH = max(1, (docH * scale).toInt())
            source = obtainDisplay(displayW, displayH)
            val canvas = Canvas(source)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(composite, null, Rect(0, 0, displayW, displayH), paint)
        } else {
            displayW = docW
            displayH = docH
            source = obtainDisplay(displayW, displayH)
            val canvas = Canvas(source)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(composite, 0f, 0f, null)
        }

        val canvas = Canvas(source)
        val scaleX = displayW.toFloat() / docW
        val scaleY = displayH.toFloat() / docH

        if (request.pixelMode && request.showGrid) {
            PixelEngine.drawGrid(canvas, displayW.toFloat(), displayH.toFloat(), request.gridSpacing * scaleX, 0x33888888)
        } else if (request.showGrid && request.gridSpacing > 0f) {
            drawGrid(canvas, displayW.toFloat(), displayH.toFloat(), request.gridSpacing * scaleX)
        }

        if (request.showComicFurniture) {
            val page = doc.pages.firstOrNull()
            if (page != null) {
                val save = canvas.save()
                canvas.scale(scaleX, scaleY)
                page.panels.forEach { ComicEngine.drawPanel(canvas, it) }
                page.bubbles.forEach { ComicEngine.drawBubble(canvas, it) }
                canvas.restoreToCount(save)
            }
        }

        if (request.showGuides) {
            val save = canvas.save()
            canvas.scale(scaleX, scaleY)
            PerspectiveEngine.drawGuides(
                canvas,
                doc.perspective,
                doc.symmetry,
                request.symmetrySegments,
                doc.guides,
                docW.toFloat(),
                docH.toFloat(),
            )
            canvas.restoreToCount(save)
        }

        if (request.showSelection && request.selection != null) {
            drawSelection(canvas, request.selection, scaleX, scaleY)
        }

        request.preview?.let { drawPreview(canvas, it, scaleX, scaleY, request) }

        request.cloneSource?.let { (sx, sy) ->
            val cx = sx * scaleX
            val cy = sy * scaleY
            val srcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.6f
                color = 0xFF4FD1E8.toInt()
            }
            canvas.drawCircle(cx, cy, 10f, srcPaint)
            canvas.drawLine(cx - 14f, cy, cx + 14f, cy, srcPaint)
            canvas.drawLine(cx, cy - 14f, cx, cy + 14f, srcPaint)
        }

        request.brushCursor?.let { (x, y) ->
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.4f
                color = 0xCCFFFFFF.toInt()
                pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f)
            }
            canvas.drawCircle(x, y, request.brushCursorRadius.coerceAtLeast(2f), ringPaint)
            val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f
                color = 0x66000000
            }
            canvas.drawCircle(x, y, request.brushCursorRadius + 1f, inner)
        }

        return source
    }

    /** Composites the current stroke's offscreen buffer for live feedback. */
    fun overlayStroke(target: Bitmap, strokeBitmap: Bitmap, scaleX: Float, scaleY: Float) {
        val canvas = Canvas(target)
        val m = Matrix().apply { setScale(scaleX, scaleY) }
        canvas.drawBitmap(strokeBitmap, m, paint)
    }

    private fun drawGrid(canvas: Canvas, width: Float, height: Float, step: Float) {
        if (step < 4f) return
        val gridPaint = Paint().apply {
            color = 0x22FFFFFF
            strokeWidth = 1f
            isAntiAlias = false
        }
        var x = 0f
        while (x <= width) {
            canvas.drawLine(x, 0f, x, height, gridPaint)
            x += step
        }
        var y = 0f
        while (y <= height) {
            canvas.drawLine(0f, y, width, y, gridPaint)
            y += step
        }
    }

    private fun drawSelection(canvas: Canvas, mask: com.artforge.mobile.engine.draw.SelectionMask, scaleX: Float, scaleY: Float) {
        val bounds = mask.bounds()
        if (bounds.isEmpty) return
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = 0xFFFFFFFF.toInt()
            pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        }
        canvas.drawRect(
            RectF(bounds.left * scaleX, bounds.top * scaleY, bounds.right * scaleX, bounds.bottom * scaleY),
            outline,
        )
        val dark = Paint(outline).apply { color = 0x88000000.toInt(); pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 8f) }
        canvas.drawRect(
            RectF(bounds.left * scaleX, bounds.top * scaleY, bounds.right * scaleX, bounds.bottom * scaleY),
            dark,
        )
    }

    private fun drawPreview(canvas: Canvas, preview: DrawPreview, scaleX: Float, scaleY: Float, request: RenderRequest) {
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.5f, 2f)
            color = 0xFF6C8CFF.toInt()
        }
        when (preview.kind) {
            PreviewKind.Shape -> {
                val from = preview.from ?: return
                val to = preview.to ?: return
                val engine = com.artforge.mobile.engine.draw.DrawingEngine(frameStore)
                val path = engine.buildShapePath(preview.tool, from, to, constrain = false)
                val m = Matrix().apply { setScale(scaleX, scaleY) }
                path.transform(m)
                canvas.drawPath(path, strokePaint)
            }
            PreviewKind.Polyline, PreviewKind.Lasso -> {
                if (preview.points.size < 2) return
                val path = Path()
                path.moveTo(preview.points[0].x * scaleX, preview.points[0].y * scaleY)
                for (i in 1 until preview.points.size) {
                    path.lineTo(preview.points[i].x * scaleX, preview.points[i].y * scaleY)
                }
                canvas.drawPath(path, strokePaint)
            }
            PreviewKind.Gradient -> {
                val from = preview.from ?: return
                val to = preview.to ?: return
                val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                    color = 0xAA6C8CFF.toInt()
                    pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
                }
                canvas.drawLine(from.x * scaleX, from.y * scaleY, to.x * scaleX, to.y * scaleY, gp)
            }
            PreviewKind.Marque -> {
                val from = preview.from ?: return
                val to = preview.to ?: return
                canvas.drawRect(
                    RectF(
                        minOf(from.x, to.x) * scaleX, minOf(from.y, to.y) * scaleY,
                        maxOf(from.x, to.x) * scaleX, maxOf(from.y, to.y) * scaleY,
                    ),
                    strokePaint,
                )
            }
            else -> Unit
        }
    }

    private var previousDisplay: Bitmap? = null

    private fun obtainComposite(width: Int, height: Int): Bitmap {
        val current = compositeBuffer
        if (current == null || current.width != width || current.height != height || current.isRecycled) {
            current?.recycle()
            compositeBuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        return compositeBuffer!!
    }

    private fun obtainDisplay(width: Int, height: Int): Bitmap {
        // Two buffers alternate. Compose may still be drawing the bitmap published by the
        // previous render when the next one starts, so it must never be recycled while it
        // is on screen — only after it has rotated out.
        val current = displayBuffer
        // The spare becomes the drawing target; whatever was on screen becomes the spare.
        // Nothing that Compose might still be painting is recycled in this frame.
        previousDisplay?.let { if (!it.isRecycled) it.recycle() }
        previousDisplay = current
        displayBuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return displayBuffer!!
    }

    fun release() {
        compositeBuffer?.recycle()
        displayBuffer?.recycle()
        previousDisplay?.let { if (!it.isRecycled) it.recycle() }
        previousDisplay = null
        compositeBuffer = null
        displayBuffer = null
        compositor.release()
    }

    fun currentDisplayBitmap(): Bitmap? = displayBuffer

    companion object {
        /** Maps a screen point onto document coordinates for a given view transform. */
        fun screenToDocument(
            offsetX: Float,
            offsetY: Float,
            viewWidth: Float,
            viewHeight: Float,
            zoom: Float,
            panX: Float,
            panY: Float,
            rotation: Float,
            flipHorizontal: Boolean,
        ): Pt {
            var x = offsetX - viewWidth / 2f - panX
            var y = offsetY - viewHeight / 2f - panY
            val rad = Math.toRadians(-rotation.toDouble())
            val rx = (x * kotlin.math.cos(rad) - y * kotlin.math.sin(rad)).toFloat()
            val ry = (x * kotlin.math.sin(rad) + y * kotlin.math.cos(rad)).toFloat()
            x = rx
            y = ry
            if (flipHorizontal) x = -x
            return Pt(x / zoom, y / zoom)
        }
    }
}
