package com.artforge.mobile.engine.draw

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.SweepGradient
import com.artforge.mobile.core.color.ColorUtils
import com.artforge.mobile.core.model.Argb
import com.artforge.mobile.core.model.BrushBuildUp
import com.artforge.mobile.core.model.BrushSettings
import com.artforge.mobile.core.model.DabShape
import com.artforge.mobile.core.model.GradientSpec
import com.artforge.mobile.core.model.GradientType
import com.artforge.mobile.core.model.Pt
import com.artforge.mobile.core.model.RectF2
import com.artforge.mobile.core.model.ToolId
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/** One sampled stylus event with all the hardware data we can get from MotionEvent. */
data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    val tilt: Float = 0f,
    val orientation: Float = 0f,
    val timeMs: Long = 0L,
)

/**
 * Painting surface for a single layer frame.
 *
 * The engine paints with [android.graphics.Bitmap] + [Canvas] using hardware-accelerated
 * Android graphics; dab stamping is done into an offscreen bitmap that is only
 * composited to the layer when the stroke ends. That keeps a long stroke at a
 * constant cost per segment (no full-layer readback per dab) while remaining
 * pixel-accurate and fully anti-aliased.
 */
class DrawingEngine(
    private val frameStore: FrameStore,
) {

    /** Callback invoked with the dirty rectangle after each committed operation. */
    var onInvalidate: ((RectF2) -> Unit)? = null

    private val dabPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    // --- active stroke state -------------------------------------------------
    private var activeStrokeBmp: Bitmap? = null
    private var activeStrokeCanvas: Canvas? = null
    private var activeTarget: Bitmap? = null
    private var activeSettings: BrushSettings? = null
    private var activeColor: Argb = Color.BLACK
    var activeTool: ToolId = ToolId.Brush
        private set

    /**
     * True while the engine only accumulates dabs in the offscreen buffer and the
     * caller decides when to commit them via [commitToTarget]. The view model needs
     * this so dodge/burn can post-process the committed pixels exactly once.
     */
    private var previewOnly: Boolean = false
    private val liveMergePaint = Paint().apply { isAntiAlias = false }

    private var lastDab: Pt? = null
    private var strokeLeftovers = 0f
    private var strokeBounds: RectF2? = null
    private val rng = Random(System.nanoTime())
    private var smudgeSampler: Bitmap? = null
    private var accumulatedAlpha = 0f

    val isStrokeActive: Boolean get() = activeStrokeBmp != null

    /**
     * Starts a stroke on [target] (a mutable bitmap owned by the frame store).
     * [seed] makes dab jitter deterministic, which matters for undo replay and tests.
     */
    fun beginStroke(
        target: Bitmap,
        tool: ToolId,
        color: Argb,
        settings: BrushSettings,
        first: StrokePoint,
        seed: Long = System.nanoTime(),
        previewOnly: Boolean = false,
    ) {
        this.previewOnly = previewOnly
        if (activeStrokeBmp != null) cancelStroke()
        activeTarget = target
        activeTool = tool
        activeColor = color
        activeSettings = settings
        strokeLeftovers = 0f
        accumulatedAlpha = 0f
        lastDab = null
        strokeBounds = null
        lazyRng = Random(seed)

        val buffer = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        activeStrokeBmp = buffer
        activeStrokeCanvas = Canvas(buffer)

        if (tool == ToolId.Smudge || tool == ToolId.Blur || tool == ToolId.Sharpen) {
            smudgeSampler = target.copy(Bitmap.Config.ARGB_8888, false)
        }
        moveTo(first)
    }

    private var lazyRng: Random = Random(0)

    fun moveTo(point: StrokePoint) {
        val target = activeTarget ?: return
        val settings = activeSettings ?: return
        val color = activeColor
        val tool = activeTool

        if (tool == ToolId.Smudge || tool == ToolId.Blur || tool == ToolId.Sharpen) {
            paintEffectDab(point, settings)
            return
        }

        val pts = ArrayList<Pt>(4)
        val last = lastDab
        if (last == null) {
            pts.add(Pt(point.x, point.y))
        } else {
            // Interpolate from the previous dab to the current input point so fast
            // strokes stay continuous instead of showing gaps.
            val current = Pt(point.x, point.y)
            val dist = last.distanceTo(current)
            val step = (settings.size * settings.spacing).coerceAtLeast(0.6f)
            var travelled = strokeLeftovers
            var prev: Pt = last
            var remaining = dist
            while (travelled + remaining >= step) {
                // Distance from the previous dab, measured along the segment.
                val fromPrev = prev.distanceTo(current)
                val t = if (fromPrev <= 0.0001f) 1f else ((step - travelled) / fromPrev).coerceIn(0f, 1f)
                val nx = prev.x + (current.x - prev.x) * t
                val ny = prev.y + (current.y - prev.y) * t
                val placed = Pt(nx, ny)
                pts.add(placed)
                prev = placed
                remaining = placed.distanceTo(current)
                travelled = 0f
            }
            strokeLeftovers = travelled + remaining
            if (pts.isEmpty()) {
                // Not enough travel for a dab yet; the next move will catch up.
                return
            }
        }
        for (p in pts) {
            stampDab(p.x, p.y, point, settings, color)
            lastDab = p
        }
        flushStrokeToTarget()
    }

    /** Ends the stroke, compositing the offscreen buffer into the layer. */
    fun endStroke(): RectF2? {
        val bounds = strokeBounds
        val target = activeTarget
        val buffer = activeStrokeBmp
        if (target != null && buffer != null) {
            when (activeTool) {
                // Erasers clear coverage where the stroke buffer has pigment.
                ToolId.Eraser, ToolId.PixelEraser -> {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                    }
                    Canvas(target).drawBitmap(buffer, 0f, 0f, paint)
                }
                // The effect tools (smudge/blur/sharpen/dodge/burn/clone) already wrote
                // their result straight into the layer, so the buffer must not be merged.
                ToolId.Smudge, ToolId.Blur, ToolId.Sharpen, ToolId.Dodge, ToolId.Burn,
                ToolId.CloneStamp -> Unit
                else -> commitToTarget()
            }
            target.setHasAlpha(true)
        }
        buffer?.recycle()
        smudgeSampler?.recycle()
        smudgeSampler = null
        activeStrokeBmp = null
        activeStrokeCanvas = null
        activeTarget = null
        lastDab = null
        strokeLeftovers = 0f
        previewOnly = false
        return bounds
    }

    fun cancelStroke() {
        activeStrokeBmp?.recycle()
        smudgeSampler?.recycle()
        activeStrokeBmp = null
        activeStrokeCanvas = null
        smudgeSampler = null
        activeTarget = null
        lastDab = null
        strokeBounds = null
        previewOnly = false
    }

    /**
     * Draws the in-progress stroke buffer on top of the layer being displayed so
     * the canvas compositor can show live strokes without touching the layer.
     */
    fun drawActiveStroke(canvas: Canvas, matrix: Matrix) {
        val buffer = activeStrokeBmp ?: return
        canvas.drawBitmap(buffer, matrix, null)
    }

    // -----------------------------------------------------------------------
    // Dab stamping
    // -----------------------------------------------------------------------

    private fun stampDab(x: Float, y: Float, point: StrokePoint, s: BrushSettings, color: Argb) {
        val canvas = activeStrokeCanvas ?: return
        val r = rng
        val pressure = point.pressure.coerceIn(0f, 1f)

        val sizeMul = 1f + (pressure - 0.5f) * 2f * s.pressureSize + point.tilt * s.tiltSize
        var size = (s.size * sizeMul.coerceAtLeast(0.02f))
        if (s.sizeJitter > 0f) size *= (1f - r.nextFloat() * s.sizeJitter * 0.6f)
        size = size.coerceAtLeast(0.4f)

        val radius = size / 2f
        var alpha = s.opacity * (1f - (1f - pressure) * (1f - s.pressureOpacity))
        alpha = (alpha * s.flow).coerceIn(0f, 1f)
        if (s.colorJitter > 0f) alpha *= (1f - r.nextFloat() * s.colorJitter * 0.35f)
        if (alpha <= 0.002f) return

        var dx = x
        var dy = y
        if (s.scatter > 0f) {
            dx += (r.nextFloat() - 0.5f) * s.scatter * size
            dy += (r.nextFloat() - 0.5f) * s.scatter * size
        }

        val angleDeg = if (s.rotationFollowsStylus > 0f) {
            s.angle + point.orientation * 180f / Math.PI.toFloat() * s.rotationFollowsStylus
        } else {
            s.angle
        } + (if (s.angleJitter > 0f) (r.nextFloat() - 0.5f) * s.angleJitter * 180f else 0f)

        val ratio = s.roundness.coerceIn(0.05f, 1f)
        val hard = s.hardness.coerceIn(0f, 1f)

        // Save/restore the clip so soft brushes can extend beyond the strict bounds.
        dabPaint.color = ColorUtils.withAlphaF(color, alpha)
        dabPaint.maskFilter = null
        when (s.buildUp) {
            BrushBuildUp.Normal -> dabPaint.xfermode = null
            BrushBuildUp.Multiply -> dabPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            BrushBuildUp.Add -> dabPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }

        when (s.shape) {
            DabShape.Round, DabShape.Grain -> {
                if (hard >= 0.999f) {
                    canvas.drawCircle(dx, dy, radius, dabPaint)
                } else {
                    val blurRadius = (radius * (1f - hard) * 1.6f).coerceIn(0.4f, 400f)
                    dabPaint.maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
                    canvas.drawCircle(dx, dy, radius, dabPaint)
                }
            }
            DabShape.Flat, DabShape.Chisel, DabShape.Square -> {
                canvas.save()
                canvas.rotate(angleDeg, dx, dy)
                if (s.shape == DabShape.Square || s.shape == DabShape.Chisel) {
                    val hw = radius
                    val hh = radius * ratio
                    dabPaint.maskFilter = if (hard < 0.999f) {
                        BlurMaskFilter((radius * (1f - hard) * 1.2f).coerceIn(0.4f, 300f), BlurMaskFilter.Blur.NORMAL)
                    } else null
                    canvas.drawRect(dx - (if (s.shape == DabShape.Chisel) hw * 0.4f else hw), dy - hh, dx + hw, dy + hh, dabPaint)
                } else {
                    val matrix = Matrix()
                    matrix.setScale(1f, ratio, dx, dy)
                    canvas.save()
                    canvas.concat(matrix)
                    if (hard >= 0.999f) {
                        canvas.drawCircle(dx, dy, radius, dabPaint)
                    } else {
                        dabPaint.maskFilter =
                            BlurMaskFilter((radius * (1f - hard) * 1.6f).coerceIn(0.4f, 400f), BlurMaskFilter.Blur.NORMAL)
                        canvas.drawCircle(dx, dy, radius, dabPaint)
                    }
                    canvas.restore()
                }
                canvas.restore()
            }
        }
        dabPaint.maskFilter = null
        dabPaint.xfermode = null

        val b = RectF2(dx - radius, dy - radius, dx + radius, dy + radius)
        strokeBounds = strokeBounds?.union(b) ?: b

        if (s.wetness > 0f) applyWetBleed(dx, dy, radius, s.wetness, color)
    }

    /**
     * Cheap "wet edge" approximation for watercolour / gouache brushes: nudges the
     * alpha of the rim pixels of the just painted dab so pigment pools at the edge.
     */
    private fun applyWetBleed(x: Float, y: Float, radius: Float, wetness: Float, color: Argb) {
        val canvas = activeStrokeCanvas ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (radius * 0.35f).coerceAtLeast(1f)
        paint.color = ColorUtils.withAlphaF(color, wetness * 0.22f)
        canvas.drawCircle(x, y, radius * 0.85f, paint)
    }

    /** Smudge / blur / sharpen dabs sample the layer and write back into it. */
    private fun paintEffectDab(point: StrokePoint, s: BrushSettings) {
        val target = activeTarget ?: return
        val sampler = smudgeSampler ?: return
        val radius = (s.size / 2f).coerceAtLeast(1f)
        val cx = point.x.toInt()
        val cy = point.y.toInt()
        val r = radius.toInt().coerceAtLeast(1)
        val left = (cx - r).coerceIn(0, target.width - 1)
        val top = (cy - r).coerceIn(0, target.height - 1)
        val right = (cx + r).coerceIn(0, target.width)
        val bottom = (cy + r).coerceIn(0, target.height)
        val w = right - left
        val h = bottom - top
        if (w <= 1 || h <= 1) return

        val canvas = Canvas(target)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (activeTool) {
            ToolId.Smudge -> {
                val src = Bitmap.createBitmap(sampler, left, top, w, h)
                val drag = s.smudgeStrength.coerceIn(0.02f, 1f)
                paint.alpha = (drag * 255f).toInt().coerceIn(1, 255)
                canvas.drawBitmap(src, left.toFloat(), top.toFloat(), paint)
                src.recycle()
            }
            ToolId.Blur -> {
                val src = Bitmap.createBitmap(sampler, left, top, w, h)
                paint.alpha = (s.smudgeStrength.coerceIn(0.05f, 1f) * 180f).toInt()
                paint.maskFilter = BlurMaskFilter(
                    (radius * s.smudgeStrength.coerceIn(0.1f, 1f) * 0.8f).coerceAtLeast(1f),
                    BlurMaskFilter.Blur.NORMAL,
                )
                canvas.drawBitmap(src, left.toFloat(), top.toFloat(), paint)
                src.recycle()
            }
            ToolId.Sharpen -> {
                val src = Bitmap.createBitmap(sampler, left, top, w, h)
                val sharpened = sharpenBitmap(src, s.smudgeStrength.coerceIn(0.05f, 1f))
                src.recycle()
                val mask = Paint(Paint.ANTI_ALIAS_FLAG)
                canvas.saveLayer(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), mask)
                canvas.drawBitmap(sharpened, left.toFloat(), top.toFloat(), null)
                // Restore the untouched pixels outside the brush circle.
                val cut = Paint(Paint.ANTI_ALIAS_FLAG)
                cut.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                cut.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
                val path = Path().apply { addRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), Path.Direction.CW) }
                path.addCircle(cx.toFloat(), cy.toFloat(), radius, Path.Direction.CCW)
                canvas.drawPath(path, cut)
                val restore = Paint()
                canvas.drawBitmap(src, left.toFloat(), top.toFloat(), restore)
                canvas.restore()
                sharpened.recycle()
            }
            else -> Unit
        }
        strokeBounds = (strokeBounds ?: RectF2(
            left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(),
        )).union(RectF2(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()))
    }

    private fun sharpenBitmap(src: Bitmap, amount: Float): Bitmap {
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val result = IntArray(w * h)
        val k = amount.coerceIn(0f, 2f)
        val center = 1f + 4f * k
        val side = -k
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
                    result[y * w + x] = pixels[y * w + x]
                    continue
                }
                val c = pixels[y * w + x]
                val sumR = ColorUtils.red(c) * center +
                    ColorUtils.red(pixels[y * w + x - 1]) * side +
                    ColorUtils.red(pixels[y * w + x + 1]) * side +
                    ColorUtils.red(pixels[(y - 1) * w + x]) * side +
                    ColorUtils.red(pixels[(y + 1) * w + x]) * side
                val sumG = ColorUtils.green(c) * center +
                    ColorUtils.green(pixels[y * w + x - 1]) * side +
                    ColorUtils.green(pixels[y * w + x + 1]) * side +
                    ColorUtils.green(pixels[(y - 1) * w + x]) * side +
                    ColorUtils.green(pixels[(y + 1) * w + x]) * side
                val sumB = ColorUtils.blue(c) * center +
                    ColorUtils.blue(pixels[y * w + x - 1]) * side +
                    ColorUtils.blue(pixels[y * w + x + 1]) * side +
                    ColorUtils.blue(pixels[(y - 1) * w + x]) * side +
                    ColorUtils.blue(pixels[(y + 1) * w + x]) * side
                val a = ColorUtils.alpha(c)
                result[y * w + x] = ColorUtils.argb(
                    a,
                    sumR.toInt().coerceIn(0, 255),
                    sumG.toInt().coerceIn(0, 255),
                    sumB.toInt().coerceIn(0, 255),
                )
            }
        }
        out.setPixels(result, 0, w, 0, 0, w, h)
        return out
    }

    /** Copies the offscreen stroke buffer into the layer bitmap. */
    /**
     * Copies the offscreen stroke buffer into the layer straight away.
     *
     * The buffer exists so the compositor can show a live preview and so undo can
     * capture the exact dirty rect; merging per dab keeps the layer content and the
     * preview in sync even if the stroke is interrupted by a re-render.
     */
    private fun flushStrokeToTarget() {
        if (previewOnly) return
        commitToTarget()
    }

    /**
     * Merges the accumulated stroke buffer into the layer as it currently stands.
     * Called by preview-mode callers right before the stroke ends so the pixels land
     * in exactly one place, and by [endStroke] otherwise.
     */
    fun commitToTarget() {
        val target = activeTarget ?: return
        val buffer = activeStrokeBmp ?: return
        if (activeTool == ToolId.Smudge || activeTool == ToolId.Blur || activeTool == ToolId.Sharpen) return
        Canvas(target).drawBitmap(buffer, 0f, 0f, liveMergePaint)
    }

    // -----------------------------------------------------------------------
    // Shape rasterisation (line/rect/ellipse/polygon/star/curve)
    // -----------------------------------------------------------------------

    /**
     * Renders a shape from [from] to [to] onto [target]. Shapes are previewed live
     * by the canvas via [buildShapePath] and committed here on release.
     */
    fun renderShape(
        target: Bitmap,
        tool: ToolId,
        from: Pt,
        to: Pt,
        color: Argb,
        strokeWidth: Float,
        fill: Boolean,
        cornerRadius: Float = 0f,
        sides: Int = 6,
        starPoints: Int = 5,
        starInner: Float = 0.45f,
        constrain: Boolean = false,
        symmetry: SymmetryBuilder? = null,
    ) {
        val canvas = Canvas(target)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }
        val path = buildShapePath(tool, from, to, cornerRadius, sides, starPoints, starInner, constrain)
        withSymmetry(canvas, symmetry, target.width, target.height) {
            canvas.drawPath(path, paint)
            if (fill) {
                val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    this.strokeWidth = strokeWidth
                    this.color = color
                }
                canvas.drawPath(path, outline)
            }
        }
        onInvalidate?.invoke(
            RectF2(
                min(from.x, to.x) - strokeWidth * 2, min(from.y, to.y) - strokeWidth * 2,
                kotlin.math.max(from.x, to.x) + strokeWidth * 2, kotlin.math.max(from.y, to.y) + strokeWidth * 2,
            ),
        )
    }

    fun buildShapePath(
        tool: ToolId,
        from: Pt,
        to: Pt,
        cornerRadius: Float = 0f,
        sides: Int = 6,
        starPoints: Int = 5,
        starInner: Float = 0.45f,
        constrain: Boolean = false,
    ): Path {
        val path = Path()
        var tx = to.x
        var ty = to.y
        if (constrain) {
            val dx = to.x - from.x
            val dy = to.y - from.y
            val size = maxOf(abs(dx), abs(dy))
            tx = from.x + if (dx < 0) -size else size
            ty = from.y + if (dy < 0) -size else size
        }
        val left = min(from.x, tx)
        val top = min(from.y, ty)
        val right = maxOf(from.x, tx)
        val bottom = maxOf(from.y, ty)
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val rx = (right - left) / 2f
        val ry = (bottom - top) / 2f

        when (tool) {
            ToolId.Line, ToolId.Curve -> {
                path.moveTo(from.x, from.y)
                if (tool == ToolId.Curve) {
                    path.quadTo(from.x, (from.y + ty) / 2f, tx, ty)
                } else {
                    path.lineTo(tx, ty)
                }
            }
            ToolId.Rectangle -> path.addRect(left, top, right, bottom, Path.Direction.CW)
            ToolId.RoundedRectangle -> path.addRoundRect(left, top, right, bottom, cornerRadius, cornerRadius, Path.Direction.CW)
            ToolId.Ellipse -> path.addOval(left, top, right, bottom, Path.Direction.CW)
            ToolId.Polygon -> {
                val pts = com.artforge.mobile.core.math.Geometry.regularPolygon(cx, cy, rx, ry, sides)
                path.moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                path.close()
            }
            ToolId.Star -> {
                val pts = com.artforge.mobile.core.math.Geometry.star(cx, cy, rx, ry, starPoints, starInner)
                path.moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                path.close()
            }
            else -> {
                path.moveTo(from.x, from.y)
                path.lineTo(tx, ty)
            }
        }
        return path
    }

    /** Renders a freehand polyline (used by lasso/path/pen preview commits). */
    fun renderPolyline(
        target: Bitmap,
        points: List<Pt>,
        color: Argb,
        strokeWidth: Float,
        closed: Boolean = false,
        fill: Boolean = false,
    ) {
        if (points.size < 2) return
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
            if (closed) close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }
        Canvas(target).drawPath(path, paint)
        onInvalidate?.invoke(pointsBounds(points, strokeWidth))
    }

    /** Bézier path from explicit anchor + control points. */
    fun renderBezier(
        target: Bitmap,
        anchors: List<Pt>,
        controlsIn: List<Pt>,
        controlsOut: List<Pt>,
        color: Argb,
        strokeWidth: Float,
        closed: Boolean = false,
        fill: Argb? = null,
    ) {
        val path = buildBezierPath(anchors, controlsIn, controlsOut, closed)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }
        val canvas = Canvas(target)
        if (fill != null && anchors.size > 2) {
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                this.color = fill
            })
        }
        canvas.drawPath(path, stroke)
        onInvalidate?.invoke(pointsBounds(anchors, strokeWidth * 2))
    }

    fun buildBezierPath(anchors: List<Pt>, controlsIn: List<Pt>, controlsOut: List<Pt>, closed: Boolean): Path {
        val path = Path()
        if (anchors.isEmpty()) return path
        path.moveTo(anchors[0].x, anchors[0].y)
        for (i in 0 until anchors.size - 1) {
            val c1 = controlsOut.getOrNull(i) ?: anchors[i]
            val c2 = controlsIn.getOrNull(i + 1) ?: anchors[i + 1]
            path.cubicTo(c1.x, c1.y, c2.x, c2.y, anchors[i + 1].x, anchors[i + 1].y)
        }
        if (closed && anchors.size > 2) {
            val c1 = controlsOut.getOrNull(anchors.size - 1) ?: anchors.last()
            val c2 = controlsIn.getOrNull(0) ?: anchors.first()
            path.cubicTo(c1.x, c1.y, c2.x, c2.y, anchors[0].x, anchors[0].y)
            path.close()
        }
        return path
    }

    // -----------------------------------------------------------------------
    // Fill / gradient
    // -----------------------------------------------------------------------

    /** Scanline flood fill with tolerance, anti-alias aware edge blending. */
    fun floodFill(
        target: Bitmap,
        x: Int,
        y: Int,
        color: Argb,
        tolerance: Int = 16,
        contiguous: Boolean = true,
        symmetry: SymmetryBuilder? = null,
    ): RectF2? {
        if (x < 0 || y < 0 || x >= target.width || y >= target.height) return null
        val w = target.width
        val h = target.height
        val pixels = IntArray(w * h)
        target.getPixels(pixels, 0, w, 0, 0, w, h)
        val seed = pixels[y * w + x]
        if (seed == color && tolerance == 0) return null

        val visited = com.artforge.mobile.core.math.BitGrid(w, h)
        var minX = x
        var maxX = x
        var minY = y
        var maxY = y
        val fillA = ColorUtils.alpha(color)
        val seedAlpha = ColorUtils.alpha(seed)
        val seedR = ColorUtils.red(seed)
        val seedG = ColorUtils.green(seed)
        val seedB = ColorUtils.blue(seed)
        val tol = tolerance * tolerance * 3

        fun matches(c: Int): Boolean {
            if (c == seed) return true
            // Fully transparent pixels have no meaningful RGB, so compare coverage
            // only: this is what lets a fill spread across an empty canvas.
            if (seedAlpha == 0 || ColorUtils.alpha(c) == 0) {
                return kotlin.math.abs(ColorUtils.alpha(c) - seedAlpha) <= tolerance
            }
            val dr = ColorUtils.red(c) - seedR
            val dg = ColorUtils.green(c) - seedG
            val db = ColorUtils.blue(c) - seedB
            return dr * dr + dg * dg + db * db <= tol
        }

        fun apply(idx: Int) {
            if (fillA >= 255) {
                pixels[idx] = color
            } else {
                pixels[idx] = ColorUtils.lerp(pixels[idx], color, fillA / 255f)
            }
        }

        if (!contiguous) {
            for (i in pixels.indices) if (matches(pixels[i]) && !visited.get(i % w, i / w)) {
                apply(i)
                visited.set(i % w, i / w)
            }
        } else {
            val stack = ArrayDeque<Int>()
            stack.addLast(y * w + x)
            while (stack.isNotEmpty()) {
                val idx = stack.removeLast()
                val px = idx % w
                val py = idx / w
                if (visited.get(px, py)) continue
                if (!matches(pixels[idx])) continue
                visited.set(px, py)
                apply(idx)
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
                if (px > 0) stack.addLast(idx - 1)
                if (px < w - 1) stack.addLast(idx + 1)
                if (py > 0) stack.addLast(idx - w)
                if (py < h - 1) stack.addLast(idx + w)
            }
        }
        target.setPixels(pixels, 0, w, 0, 0, w, h)

        if (symmetry != null) {
            symmetry.mirrors(x, y, w, h).forEach { (mx, my) ->
                if (mx != x || my != y) floodFill(target, mx, my, color, tolerance, contiguous, null)
            }
        }
        val rect = RectF2(minX.toFloat(), minY.toFloat(), (maxX + 1).toFloat(), (maxY + 1).toFloat())
        onInvalidate?.invoke(rect)
        return rect
    }

    /** Paints a gradient over the selection rect (or whole canvas when null). */
    fun drawGradient(
        target: Bitmap,
        spec: GradientSpec,
        from: Pt,
        to: Pt,
        clip: RectF2? = null,
    ) {
        if (spec.stops.isEmpty()) return
        val colors = spec.stops.sortedBy { it.position }.map { it.color }.toIntArray()
        val positions = spec.stops.sortedBy { it.position }.map { it.position.coerceIn(0f, 1f) }.toFloatArray()
        val shader: Shader = when (spec.type) {
            GradientType.Linear -> LinearGradient(from.x, from.y, to.x, to.y, colors, positions, Shader.TileMode.CLAMP)
            GradientType.Radial -> RadialGradient(
                from.x, from.y,
                from.distanceTo(to).coerceAtLeast(1f),
                colors, positions, Shader.TileMode.CLAMP,
            )
            GradientType.Sweep -> SweepGradient(from.x, from.y, colors, positions)
            GradientType.AngleReflected -> SweepGradient(from.x, from.y, colors + colors.reversedArray(), null)
        }
        shaderPaint.shader = shader
        val canvas = Canvas(target)
        val rect = clip ?: RectF2.ofSize(target.width, target.height)
        canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, shaderPaint)
        shaderPaint.shader = null
        onInvalidate?.invoke(rect)
    }

    // -----------------------------------------------------------------------
    // Selection helpers
    // -----------------------------------------------------------------------

    /** Applies a selection mask to a frame: pixels outside [mask] are cleared. */
    fun applySelectionMask(target: Bitmap, mask: Bitmap?) {
        if (mask == null) return
        val canvas = Canvas(target)
        val paint = Paint()
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawBitmap(mask, 0f, 0f, paint)
        paint.xfermode = null
    }

    /** Erases a rect / masks a rect depending on [erase]. */
    fun modifyRegion(target: Bitmap, rect: Rect, erase: Boolean, fillColor: Argb? = null) {
        val canvas = Canvas(target)
        if (erase) {
            val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            canvas.drawRect(rect, paint)
        } else if (fillColor != null) {
            canvas.drawRect(rect, Paint().apply { color = fillColor })
        }
        onInvalidate?.invoke(RectF2(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat()))
    }

    fun copyRegionToNewBitmap(source: Bitmap, rect: Rect): Bitmap = FrameStore.copyRegion(source, rect.left, rect.top, rect.width(), rect.height())

    fun pasteBitmap(target: Bitmap, source: Bitmap, x: Float, y: Float) {
        Canvas(target).drawBitmap(source, x, y, Paint(Paint.FILTER_BITMAP_FLAG))
        onInvalidate?.invoke(RectF2(x, y, x + source.width, y + source.height))
    }

    /** Applies a hue/saturation/brightness shift to a whole frame (dodge/burn style tools). */
    fun transformPixels(target: Bitmap, block: (Int) -> Int) {
        val w = target.width
        val h = target.height
        val pixels = IntArray(w * h)
        target.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) pixels[i] = block(pixels[i])
        target.setPixels(pixels, 0, w, 0, 0, w, h)
        onInvalidate?.invoke(RectF2.ofSize(w, h))
    }

    // -----------------------------------------------------------------------
    // Symmetry support
    // -----------------------------------------------------------------------

    fun withSymmetry(canvas: Canvas, symmetry: SymmetryBuilder?, width: Int, height: Int, block: () -> Unit) {
        block()
        if (symmetry == null) return
        val transforms = symmetry.mirrorTransforms(width.toFloat(), height.toFloat())
        for (m in transforms) {
            canvas.save()
            canvas.concat(m)
            block()
            canvas.restore()
        }
    }

    private fun pointsBounds(points: List<Pt>, pad: Float): RectF2 {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        return RectF2(minX - pad, minY - pad, maxX + pad, maxY + pad)
    }

    /** Rotation of a stroke segment in radians, used for flat/chisel brushes. */
    private fun segmentAngle(a: Pt, b: Pt): Float = kotlin.math.atan2(b.y - a.y, b.x - a.x)

    companion object {
        /** Combines the stylus tilt + orientation into a single pressure-like scalar for tests. */
        fun effectivePressure(pressure: Float, tilt: Float): Float =
            (pressure * (1f - 0.4f * tilt.coerceIn(0f, 1f))).coerceIn(0f, 1f)
    }
}
