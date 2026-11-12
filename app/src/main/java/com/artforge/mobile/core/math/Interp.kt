package com.artforge.mobile.core.math

import com.artforge.mobile.core.model.Pt
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Smoothing / interpolation helpers shared by the drawing engine and the
 * animation keyframe evaluator. Everything here is pure Kotlin so it can be
 * unit tested without an Android device.
 */
object Interp {

    /** Catmull-Rom interpolation between p1 and p2, with p0/p3 as neighbours. */
    fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * (
            (2f * p1) +
                (-p0 + p2) * t +
                (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
                (-p0 + 3f * p1 - 3f * p2 + p3) * t3
            )
    }

    fun catmullRomPoint(p0: Pt, p1: Pt, p2: Pt, p3: Pt, t: Float): Pt = Pt(
        catmullRom(p0.x, p1.x, p2.x, p3.x, t),
        catmullRom(p0.y, p1.y, p2.y, p3.y, t),
    )

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    fun smoothStep(t: Float): Float {
        val c = clamp01(t)
        return c * c * (3f - 2f * c)
    }

    fun easeIn(t: Float): Float = clamp01(t) * clamp01(t)
    fun easeOut(t: Float): Float = 1f - (1f - clamp01(t)) * (1f - clamp01(t))
    fun easeInOut(t: Float): Float {
        val c = clamp01(t)
        return if (c < 0.5f) 2f * c * c else 1f - 2f * (1f - c) * (1f - c)
    }

    /** Cubic Bézier on a single axis with the usual two control handles. */
    fun cubicBezier(t: Float, c1: Float, c2: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * c1 + 3f * u * t * t * c2 + t * t * t
    }
}

/** Uniform Catmull-Rom resampling used for stroke smoothing / stabilisation. */
object StrokeSmoother {

    /**
     * Exponential moving average on the incoming points with [strength] in 0..1.
     * A strength of 0 returns the input unchanged.
     */
    fun stabilize(points: List<Pt>, strength: Float): List<Pt> {
        if (strength <= 0f || points.size < 2) return points
        val alpha = (1f - strength.coerceIn(0f, 0.98f))
        val out = ArrayList<Pt>(points.size)
        var sx = points[0].x
        var sy = points[0].y
        out.add(points[0])
        for (i in 1 until points.size) {
            sx += (points[i].x - sx) * alpha
            sy += (points[i].y - sy) * alpha
            out.add(Pt(sx, sy))
        }
        // Always honour the final raw point so the stroke ends where the pen lifted.
        out[out.size - 1] = points.last()
        return out
    }

    /**
     * Re-samples a polyline so that consecutive points are [spacing] apart,
     * interpolating with Catmull-Rom for organic smoothness. Used by the brush
     * engine to place dabs at a controlled density.
     */
    fun resample(points: List<Pt>, spacing: Float): List<Pt> {
        if (points.size < 2) return points
        val step = max(spacing, 0.5f)
        val out = ArrayList<Pt>()
        out.add(points[0])
        var carry = 0f
        for (i in 0 until points.size - 1) {
            val p0 = points[max(0, i - 1)]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points[min(points.size - 1, i + 2)]
            // Approximate arc length of the segment with a few subdivisions.
            val segLen = max(p1.distanceTo(p2), 0.01f)
            val subdiv = max(2, ceil(segLen / step).toInt() * 3)
            var prev = p1
            for (s in 1..subdiv) {
                val t = s.toFloat() / subdiv
                val cur = Interp.catmullRomPoint(p0, p1, p2, p3, t)
                var d = prev.distanceTo(cur)
                while (carry + d >= step) {
                    val need = step - carry
                    val frac = if (d <= 0.0001f) 1f else need / d
                    val point = Pt(prev.x + (cur.x - prev.x) * frac, prev.y + (cur.y - prev.y) * frac)
                    out.add(point)
                    prev = point
                    d = prev.distanceTo(cur)
                    carry = 0f
                }
                carry += d
                prev = cur
            }
        }
        if (out.last().distanceTo(points.last()) > 0.01f) out.add(points.last())
        return out
    }

    /** Builds a tapered polygon ribbon between two consecutive dab centres. */
    fun ribbonQuad(cx: Float, cy: Float, radius: Float, halfLen: Float, angleRad: Float): FloatArray {
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)
        fun p(along: Float, across: Float) = floatArrayOf(
            cx + cosA * along - sinA * across,
            cy + sinA * along + cosA * across,
        )
        val a = p(-halfLen, -radius)
        val b = p(halfLen, -radius)
        val c = p(halfLen, radius)
        val d = p(-halfLen, radius)
        return floatArrayOf(
            a[0], a[1], b[0], b[1], c[0], c[1], d[0], d[1],
        )
    }
}

/** Simple 3x3 affine transform stored row-major. */
data class Mat3(
    val m00: Float = 1f, val m01: Float = 0f, val m02: Float = 0f,
    val m10: Float = 0f, val m11: Float = 1f, val m12: Float = 0f,
    val m20: Float = 0f, val m21: Float = 0f, val m22: Float = 1f,
) {
    fun map(x: Float, y: Float): Pt = Pt(
        m00 * x + m01 * y + m02,
        m10 * x + m11 * y + m12,
    )

    fun times(o: Mat3): Mat3 = Mat3(
        m00 * o.m00 + m01 * o.m10 + m02 * o.m20,
        m00 * o.m01 + m01 * o.m11 + m02 * o.m21,
        m00 * o.m02 + m01 * o.m12 + m02 * o.m22,
        m10 * o.m00 + m11 * o.m10 + m12 * o.m20,
        m10 * o.m01 + m11 * o.m11 + m12 * o.m21,
        m10 * o.m02 + m11 * o.m12 + m12 * o.m22,
        m20 * o.m00 + m21 * o.m10 + m22 * o.m20,
        m20 * o.m01 + m21 * o.m11 + m22 * o.m21,
        m20 * o.m02 + m21 * o.m12 + m22 * o.m22,
    )

    fun invert(): Mat3 {
        val det = m00 * (m11 * m22 - m12 * m21) - m01 * (m10 * m22 - m12 * m20) + m02 * (m10 * m21 - m11 * m20)
        if (abs(det) < 1e-9f) return Mat3()
        val inv = 1f / det
        return Mat3(
            (m11 * m22 - m12 * m21) * inv,
            (m02 * m21 - m01 * m22) * inv,
            (m01 * m12 - m02 * m11) * inv,
            (m12 * m20 - m10 * m22) * inv,
            (m00 * m22 - m02 * m20) * inv,
            (m02 * m10 - m00 * m12) * inv,
            (m10 * m21 - m11 * m20) * inv,
            (m01 * m20 - m00 * m21) * inv,
            (m00 * m11 - m01 * m10) * inv,
        )
    }

    companion object {
        fun translate(tx: Float, ty: Float) = Mat3(1f, 0f, tx, 0f, 1f, ty)
        fun scale(sx: Float, sy: Float) = Mat3(sx, 0f, 0f, 0f, sy, 0f)
        fun rotate(deg: Float): Mat3 {
            val r = Math.toRadians(deg.toDouble())
            val c = cos(r).toFloat()
            val s = sin(r).toFloat()
            return Mat3(c, -s, 0f, s, c, 0f)
        }
        fun rotateAround(deg: Float, px: Float, py: Float): Mat3 =
            translate(px, py).times(rotate(deg)).times(translate(-px, -py))
    }
}

/**
 * Spatial hash used by the fill tool and magic wand flood fill to test whether a
 * pixel has already been visited without allocating an IntArray of canvas size.
 */
class BitGrid(val width: Int, val height: Int) {
    private val bits = LongArray(((width.toLong() * height + 63) / 64).toInt())

    fun get(x: Int, y: Int): Boolean {
        if (x < 0 || y < 0 || x >= width || y >= height) return true
        val i = y.toLong() * width + x
        val word = (i ushr 6).toInt()
        val bit = (i and 63L).toInt()
        return (bits[word] shr bit) and 1L == 1L
    }

    fun set(x: Int, y: Int) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        val i = y.toLong() * width + x
        val word = (i ushr 6).toInt()
        val bit = (i and 63L).toInt()
        bits[word] = bits[word] or (1L shl bit)
    }

    fun clear() = bits.fill(0L)
}

object Geometry {
    fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()

    fun pointToSegmentDistance(
        px: Float, py: Float,
        ax: Float, ay: Float,
        bx: Float, by: Float,
    ): Float {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-6f) return distance(px, py, ax, ay)
        var t = ((px - ax) * dx + (py - ay) * dy) / lenSq
        t = t.coerceIn(0f, 1f)
        return distance(px, py, ax + t * dx, ay + t * dy)
    }

    fun pointInPolygon(px: Float, py: Float, poly: List<Pt>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val xi = poly[i].x
            val yi = poly[i].y
            val xj = poly[j].x
            val yj = poly[j].y
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    fun polygonArea(poly: List<Pt>): Float {
        if (poly.size < 3) return 0f
        var sum = 0f
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2f
    }

    /** Points of a regular polygon inscribed in the given bounds. */
    fun regularPolygon(cx: Float, cy: Float, rx: Float, ry: Float, sides: Int, rotationDeg: Float = -90f): List<Pt> {
        val n = sides.coerceAtLeast(3)
        val start = Math.toRadians(rotationDeg.toDouble())
        return (0 until n).map { i ->
            val a = start + 2.0 * Math.PI * i / n
            Pt(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat())
        }
    }

    fun star(cx: Float, cy: Float, rx: Float, ry: Float, points: Int, innerRatio: Float): List<Pt> {
        val n = points.coerceAtLeast(3)
        val total = n * 2
        val ratio = innerRatio.coerceIn(0.05f, 0.95f)
        return (0 until total).map { i ->
            val a = -Math.PI / 2 + Math.PI * i / n
            val f = if (i % 2 == 0) 1f else ratio
            Pt(cx + rx * f * cos(a).toFloat(), cy + ry * f * sin(a).toFloat())
        }
    }

    /** Rounds a polygon using quadratic corner cutting — used by the rounded rectangle tool. */
    fun roundPolygon(corners: List<Pt>, radius: Float, segments: Int = 6): List<Pt> {
        if (corners.size < 3 || radius <= 0f) return corners
        val out = ArrayList<Pt>(corners.size * segments)
        for (i in corners.indices) {
            val prev = corners[(i - 1 + corners.size) % corners.size]
            val cur = corners[i]
            val next = corners[(i + 1) % corners.size]
            val r1 = min(radius, cur.distanceTo(prev) / 2f)
            val r2 = min(radius, cur.distanceTo(next) / 2f)
            val p1 = Pt(
                cur.x + (prev.x - cur.x) / max(cur.distanceTo(prev), 0.001f) * r1,
                cur.y + (prev.y - cur.y) / max(cur.distanceTo(prev), 0.001f) * r1,
            )
            val p2 = Pt(
                cur.x + (next.x - cur.x) / max(cur.distanceTo(next), 0.001f) * r2,
                cur.y + (next.y - cur.y) / max(cur.distanceTo(next), 0.001f) * r2,
            )
            for (s in 0..segments) {
                val t = s.toFloat() / segments
                val x = (1 - t) * (1 - t) * p1.x + 2 * (1 - t) * t * cur.x + t * t * p2.x
                val y = (1 - t) * (1 - t) * p1.y + 2 * (1 - t) * t * cur.y + t * t * p2.y
                out.add(Pt(x, y))
            }
        }
        return out
    }

    fun ellipsePoints(cx: Float, cy: Float, rx: Float, ry: Float, segments: Int = 96): List<Pt> =
        (0 until segments).map {
            val a = 2 * Math.PI * it / segments
            Pt(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat())
        }

    /** Snaps a value to the nearest multiple of [step]; used for grids/guides. */
    fun snap(value: Float, step: Float): Float = if (step <= 0f) value else floor(value / step + 0.5f) * step
}
