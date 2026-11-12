package com.artforge.mobile.engine.perspective

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import com.artforge.mobile.core.model.GuideLine
import com.artforge.mobile.core.model.PerspectiveGuide
import com.artforge.mobile.core.model.Pt
import com.artforge.mobile.core.model.SymmetryMode
import com.artforge.mobile.core.model.VanishingPoint
import kotlin.math.cos
import kotlin.math.sin

/**
 * Perspective grids, symmetry rulers, proportional guides and drawing rulers.
 * The engine both *draws* guides on the canvas overlay and *snaps* input points to
 * them, so the guides genuinely constrain drawing rather than being decoration.
 */
object PerspectiveEngine {

    /** Full field of view lines radiating from each vanishing point. */
    fun linesFor(points: List<VanishingPoint>, width: Float, height: Float, rays: Int = 24): List<Pair<Pt, Pt>> {
        val out = ArrayList<Pair<Pt, Pt>>()
        val diag = kotlin.math.sqrt(width * width + height * height)
        for (vp in points) {
            for (i in 0 until rays) {
                val angle = Math.PI * 2 * i / rays
                val end = Pt(vp.x + cos(angle).toFloat() * diag, vp.y + sin(angle).toFloat() * diag)
                out.add(Pt(vp.x, vp.y) to end)
            }
        }
        return out
    }

    /**
     * Horizontal floor/ceiling lines of a perspective grid, spanning [left]..[right].
     * Spacing compresses towards the horizon (square-law foreshortening), which is what
     * makes a one- or two-point floor read as receding instead of a flat ladder.
     */
    fun gridLines(
        horizonY: Float,
        floorY: Float,
        count: Int = 8,
        left: Float = 0f,
        right: Float = 0f,
    ): List<Pair<Pt, Pt>> {
        if (count <= 0) return emptyList()
        val out = ArrayList<Pair<Pt, Pt>>(count)
        for (i in 1..count) {
            val t = i.toFloat() / (count + 1)
            val y = horizonY + (floorY - horizonY) * (t * t)
            out.add(Pt(left, y) to Pt(right, y))
        }
        return out
    }

    /** Snaps a drawn point to the nearest perspective ray from [vp]. */
    fun snapToRays(point: Pt, vp: Pt, angleStepDeg: Float = 15f): Pt {
        val dx = point.x - vp.x
        val dy = point.y - vp.y
        val angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val snapped = kotlin.math.round(angle / angleStepDeg) * angleStepDeg
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        val rad = Math.toRadians(snapped.toDouble())
        return Pt(vp.x + (cos(rad) * len).toFloat(), vp.y + (sin(rad) * len).toFloat())
    }

    /** Projects a point onto the ray through [vp] closest to it (exact, not stepped). */
    fun projectOntoRay(point: Pt, vp: Pt, rayAngleRad: Float): Pt {
        val dx = point.x - vp.x
        val dy = point.y - vp.y
        val projLen = dx * cos(rayAngleRad) + dy * sin(rayAngleRad)
        return Pt(vp.x + cos(rayAngleRad) * projLen, vp.y + sin(rayAngleRad) * projLen)
    }

    /**
     * Best snapping for a free point against the full guide set: returns the point
     * snapped to the closest vanishing-point ray when inside [tolerance], else to
     * the horizon, else unchanged.
     */
    fun snap(
        point: Pt,
        guide: PerspectiveGuide,
        width: Float,
        height: Float,
        tolerance: Float = 24f,
        angleStepDeg: Float = 15f,
    ): Pt {
        if (!guide.enabled) return point
        var best = point
        var bestDist = tolerance
        // Rays leave each vanishing point at fixed angles, so a point snaps onto the
        // ray *line* it is closest to (perpendicular projection), not onto the ray
        // that already passes through it.
        val stepRad = Math.toRadians(angleStepDeg.toDouble())
        val rayCount = Math.round(Math.PI / stepRad).toInt().coerceAtLeast(1)
        for (vp in guide.points) {
            for (i in 0 until rayCount) {
                val angle = (i * stepRad).toFloat()
                val projected = projectOntoRay(point, Pt(vp.x, vp.y), angle)
                val dist = projected.distanceTo(point)
                if (dist < bestDist - 0.0001f) {
                    bestDist = dist
                    best = projected
                }
            }
        }
        // The horizon is a snap target in its own right and wins ties: "align to the
        // horizon" is the most common artistic intent when both are in range.
        val horizonDist = kotlin.math.abs(point.y - guide.horizonY)
        if (horizonDist <= tolerance && horizonDist <= bestDist) {
            bestDist = horizonDist
            best = Pt(point.x, guide.horizonY)
        }
        return best
    }

    /** Mirror a point across the symmetry axes, for the symmetry ruler preview. */
    fun symmetryPoints(point: Pt, mode: SymmetryMode, width: Float, height: Float, segments: Int = 8): List<Pt> {
        val cx = width / 2f
        val cy = height / 2f
        return when (mode) {
            SymmetryMode.None -> listOf(point)
            SymmetryMode.Horizontal -> listOf(point, Pt(2 * cx - point.x, point.y))
            SymmetryMode.Vertical -> listOf(point, Pt(point.x, 2 * cy - point.y))
            SymmetryMode.Both -> listOf(
                point,
                Pt(2 * cx - point.x, point.y),
                Pt(point.x, 2 * cy - point.y),
                Pt(2 * cx - point.x, 2 * cy - point.y),
            )
            SymmetryMode.Radial, SymmetryMode.Kaleidoscope -> {
                val out = ArrayList<Pt>()
                val step = Math.PI * 2 / segments.coerceIn(2, 64)
                for (i in 0 until segments) {
                    val a = step * i
                    val dx = point.x - cx
                    val dy = point.y - cy
                    out.add(
                        Pt(
                            cx + (dx * cos(a) - dy * sin(a)).toFloat(),
                            cy + (dx * sin(a) + dy * cos(a)).toFloat(),
                        ),
                    )
                }
                out
            }
        }
    }

    /** Draws all active guides (perspective, symmetry, custom ruler lines). */
    fun drawGuides(
        canvas: Canvas,
        guide: PerspectiveGuide,
        symmetry: SymmetryMode,
        symmetrySegments: Int,
        guides: List<GuideLine>,
        width: Float,
        height: Float,
        showGuides: Boolean = true,
        /** Must match the step used by [snap], so drawn rays are the ones you snap to. */
        angleStepDeg: Float = 15f,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
            color = 0x446C8CFF
        }
        if (showGuides && guide.enabled) {
            val diag = kotlin.math.sqrt(width * width + height * height)
            for (vp in guide.points) {
                paint.shader = null
                paint.color = vp.color and 0x00FFFFFF or 0x40000000
                val rayCount = Math.max(4, Math.round(360f / angleStepDeg))
                for (i in 0 until rayCount) {
                    val a = Math.PI * 2 * i / rayCount
                    canvas.drawLine(
                        vp.x, vp.y,
                        vp.x + (cos(a) * diag).toFloat(),
                        vp.y + (sin(a) * diag).toFloat(),
                        paint,
                    )
                }
                canvas.drawCircle(vp.x, vp.y, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = vp.color
                })
            }
            // Horizon
            val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                color = 0x66FF8A4C
            }
            canvas.drawLine(0f, guide.horizonY, width, guide.horizonY, horizonPaint)
            // Foreshortened floor/ceiling lines below the horizon complete the grid.
            val roomLines = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f
                color = 0x2A6C8CFF
            }
            gridLines(guide.horizonY, height, count = 8, left = 0f, right = width)
                .forEach { (a, b) -> canvas.drawLine(a.x, a.y, b.x, b.y, roomLines) }
        }

        if (symmetry != SymmetryMode.None) {
            val symPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.2f
                color = 0x55FF8A4C
                pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
            }
            val cx = width / 2f
            val cy = height / 2f
            when (symmetry) {
                SymmetryMode.Horizontal, SymmetryMode.Both -> canvas.drawLine(cx, 0f, cx, height, symPaint)
                else -> Unit
            }
            when (symmetry) {
                SymmetryMode.Vertical, SymmetryMode.Both -> canvas.drawLine(0f, cy, width, cy, symPaint)
                else -> Unit
            }
            when (symmetry) {
                SymmetryMode.Radial, SymmetryMode.Kaleidoscope -> {
                    val segments = symmetrySegments.coerceIn(2, 64)
                    for (i in 0 until segments) {
                        val a = Math.PI * 2 * i / segments
                        canvas.drawLine(
                            cx, cy,
                            cx + (cos(a) * width).toFloat() * 1.5f,
                            cy + (sin(a) * height).toFloat() * 1.5f,
                            symPaint,
                        )
                    }
                }
                else -> Unit
            }
        }

        guides.forEach { g ->
            val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.4f
                color = g.color
            }
            canvas.drawLine(g.x1, g.y1, g.x2, g.y2, gp)
        }
    }

    /** Golden-ratio / thirds grid overlay. */
    fun drawProportionGuides(canvas: Canvas, width: Float, height: Float, thirds: Boolean = true, golden: Boolean = false) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = 0x33FFFFFF
            pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
        }
        if (thirds) {
            canvas.drawLine(width / 3f, 0f, width / 3f, height, paint)
            canvas.drawLine(width * 2f / 3f, 0f, width * 2f / 3f, height, paint)
            canvas.drawLine(0f, height / 3f, width, height / 3f, paint)
            canvas.drawLine(0f, height * 2f / 3f, width, height * 2f / 3f, paint)
        }
        if (golden) {
            val phi = 0.618034f
            canvas.drawLine(width * phi, 0f, width * phi, height, paint)
            canvas.drawLine(0f, height * phi, width, height * phi, paint)
        }
    }

    /** Ellipse perspective guide for drawing wheels in three dimensions. */
    fun ellipseGuide(cx: Float, cy: Float, rx: Float, ry: Float): List<Pt> = (0 until 64).map {
        val a = Math.PI * 2 * it / 64
        Pt(cx + (rx * cos(a)).toFloat(), cy + (ry * sin(a)).toFloat())
    }

    fun defaultGuide(width: Float, height: Float, mode: Int = 2): PerspectiveGuide {
        val horizon = height * 0.45f
        val points = when (mode) {
            1 -> listOf(VanishingPoint("vp1", width / 2f, horizon))
            2 -> listOf(
                VanishingPoint("vp1", width * 0.06f, horizon),
                VanishingPoint("vp2", width * 0.94f, horizon),
            )
            else -> listOf(
                VanishingPoint("vp1", width * 0.06f, horizon),
                VanishingPoint("vp2", width * 0.94f, horizon),
                VanishingPoint("vp3", width / 2f, height * 0.05f),
            )
        }
        return PerspectiveGuide(mode = mode, points = points, horizonY = horizon, enabled = true)
    }
}
