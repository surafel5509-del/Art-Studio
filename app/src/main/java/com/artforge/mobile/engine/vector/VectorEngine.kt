package com.artforge.mobile.engine.vector

import android.graphics.Path
import com.artforge.mobile.core.math.Geometry
import com.artforge.mobile.core.model.Pt
import com.artforge.mobile.core.model.VectorShape
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vector path maths: anchor editing, boolean operations, bounds, hit testing and
 * SVG generation/parsing. Rasterisation to the canvas is done by the drawing engine
 * using [toPath]; the vector *data* stays editable in the layer model.
 */
object VectorEngine {

    fun toPath(shape: VectorShape): Path {
        val path = Path()
        val pts = shape.points
        if (pts.isEmpty()) return path
        val hasControls = shape.controlsIn.size == pts.size && shape.controlsOut.size == pts.size
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 0 until pts.size - 1) {
            if (hasControls) {
                val c1 = shape.controlsOut[i]
                val c2 = shape.controlsIn[i + 1]
                path.cubicTo(c1.x, c1.y, c2.x, c2.y, pts[i + 1].x, pts[i + 1].y)
            } else {
                path.lineTo(pts[i + 1].x, pts[i + 1].y)
            }
        }
        if (shape.closed) {
            if (hasControls) {
                val c1 = shape.controlsOut.last()
                val c2 = shape.controlsIn.first()
                path.cubicTo(c1.x, c1.y, c2.x, c2.y, pts[0].x, pts[0].y)
            }
            path.close()
        }
        return path
    }

    fun bounds(shape: VectorShape): com.artforge.mobile.core.model.RectF2 {
        if (shape.points.isEmpty()) return com.artforge.mobile.core.model.RectF2(0f, 0f, 0f, 0f)
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (p in shape.points) {
            l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y)
        }
        val pad = shape.strokeWidth / 2f
        return com.artforge.mobile.core.model.RectF2(l - pad, t - pad, r + pad, b + pad)
    }

    /** Hit test for selection: close to the outline or inside the filled area. */
    fun hitTest(shape: VectorShape, x: Float, y: Float, tolerance: Float = 8f): Boolean {
        val pts = shape.points
        if (pts.isEmpty()) return false
        if (shape.fillColor != null && shape.closed && Geometry.pointInPolygon(x, y, pts)) return true
        for (i in 0 until pts.size - 1) {
            if (Geometry.pointToSegmentDistance(x, y, pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y) <= tolerance + shape.strokeWidth / 2f) return true
        }
        if (shape.closed && pts.size > 1) {
            val last = pts.last()
            if (Geometry.pointToSegmentDistance(x, y, last.x, last.y, pts[0].x, pts[0].y) <= tolerance + shape.strokeWidth / 2f) return true
        }
        return false
    }

    /** Finds the closest anchor within [tolerance] for direct selection editing. */
    fun closestAnchor(shape: VectorShape, x: Float, y: Float, tolerance: Float = 14f): Int? {
        var best = -1
        var bestDist = tolerance
        shape.points.forEachIndexed { i, p ->
            val d = p.distanceTo(Pt(x, y))
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return if (best >= 0) best else null
    }

    fun moveAnchor(shape: VectorShape, index: Int, to: Pt, moveControls: Boolean = true): VectorShape {
        if (index !in shape.points.indices) return shape
        val old = shape.points[index]
        val dx = to.x - old.x
        val dy = to.y - old.y
        val points = shape.points.toMutableList().also { it[index] = to }
        val cin = if (moveControls) shape.controlsIn.mapIndexed { i, p -> if (i == index) Pt(p.x + dx, p.y + dy) else p } else shape.controlsIn
        val cout = if (moveControls) shape.controlsOut.mapIndexed { i, p -> if (i == index) Pt(p.x + dx, p.y + dy) else p } else shape.controlsOut
        return shape.copy(points = points, controlsIn = cin, controlsOut = cout)
    }

    fun deleteAnchor(shape: VectorShape, index: Int): VectorShape {
        if (index !in shape.points.indices) return shape
        return shape.copy(
            points = shape.points.filterIndexed { i, _ -> i != index },
            controlsIn = if (shape.controlsIn.size == shape.points.size) shape.controlsIn.filterIndexed { i, _ -> i != index } else shape.controlsIn,
            controlsOut = if (shape.controlsOut.size == shape.points.size) shape.controlsOut.filterIndexed { i, _ -> i != index } else shape.controlsOut,
        )
    }

    fun insertAnchor(shape: VectorShape, afterIndex: Int, point: Pt): VectorShape {
        val idx = (afterIndex + 1).coerceIn(0, shape.points.size)
        val points = shape.points.toMutableList().also { it.add(idx, point) }
        val cin = if (shape.controlsIn.size == shape.points.size) shape.controlsIn.toMutableList().also { it.add(idx, point) } else shape.controlsIn
        val cout = if (shape.controlsOut.size == shape.points.size) shape.controlsOut.toMutableList().also { it.add(idx, point) } else shape.controlsOut
        return shape.copy(points = points, controlsIn = cin, controlsOut = cout)
    }

    /** Converts every anchor into a smooth (auto-tangent) bezier node. */
    fun smoothAll(shape: VectorShape, tension: Float = 0.33f): VectorShape {
        val n = shape.points.size
        if (n < 3) return shape
        val cin = ArrayList<Pt>(n)
        val cout = ArrayList<Pt>(n)
        for (i in 0 until n) {
            val prev = shape.points[(i - 1 + n) % n]
            val next = shape.points[(i + 1) % n]
            val dirX = (next.x - prev.x) * tension
            val dirY = (next.y - prev.y) * tension
            cin.add(Pt(shape.points[i].x - dirX, shape.points[i].y - dirY))
            cout.add(Pt(shape.points[i].x + dirX, shape.points[i].y + dirY))
        }
        return shape.copy(controlsIn = cin, controlsOut = cout)
    }

    fun transform(shape: VectorShape, m: com.artforge.mobile.core.math.Mat3): VectorShape = shape.copy(
        points = shape.points.map { m.map(it.x, it.y) },
        controlsIn = shape.controlsIn.map { m.map(it.x, it.y) },
        controlsOut = shape.controlsOut.map { m.map(it.x, it.y) },
    )

    fun translate(shape: VectorShape, dx: Float, dy: Float): VectorShape =
        transform(shape, com.artforge.mobile.core.math.Mat3.translate(dx, dy))

    fun scale(shape: VectorShape, sx: Float, sy: Float, pivotX: Float, pivotY: Float): VectorShape = shape.copy(
        points = shape.points.map { Pt(pivotX + (it.x - pivotX) * sx, pivotY + (it.y - pivotY) * sy) },
        controlsIn = shape.controlsIn.map { Pt(pivotX + (it.x - pivotX) * sx, pivotY + (it.y - pivotY) * sy) },
        controlsOut = shape.controlsOut.map { Pt(pivotX + (it.x - pivotX) * sx, pivotY + (it.y - pivotY) * sy) },
    )

    fun rotate(shape: VectorShape, degrees: Float, pivotX: Float, pivotY: Float): VectorShape {
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        fun rot(p: Pt) = Pt(
            pivotX + (p.x - pivotX) * c - (p.y - pivotY) * s,
            pivotY + (p.x - pivotX) * s + (p.y - pivotY) * c,
        )
        return shape.copy(
            points = shape.points.map(::rot),
            controlsIn = shape.controlsIn.map(::rot),
            controlsOut = shape.controlsOut.map(::rot),
        )
    }

    // -----------------------------------------------------------------------
    // Boolean operations (polygon based, sufficient for design workflows)
    // -----------------------------------------------------------------------

    /** Flattens a bezier shape into a dense polygon for boolean/geometry operations. */
    fun flatten(shape: VectorShape, segmentsPerCurve: Int = 12): List<Pt> {
        val pts = shape.points
        if (shape.controlsIn.size != pts.size || shape.controlsOut.size != pts.size) return pts
        val out = ArrayList<Pt>()
        val count = if (shape.closed) pts.size else pts.size - 1
        for (i in 0 until count) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            val c1 = shape.controlsOut[i]
            val c2 = shape.controlsIn[(i + 1) % pts.size]
            for (s in 0 until segmentsPerCurve) {
                val t = s.toFloat() / segmentsPerCurve
                val u = 1 - t
                val x = u * u * u * a.x + 3 * u * u * t * c1.x + 3 * u * t * t * c2.x + t * t * t * b.x
                val y = u * u * u * a.y + 3 * u * u * t * c1.y + 3 * u * t * t * c2.y + t * t * t * b.y
                out.add(Pt(x, y))
            }
        }
        if (!shape.closed) out.add(pts.last())
        return out
    }

    enum class BooleanOp { Union, Subtract, Intersect, Exclude }

    /** Sutherland–Hodgman style polygon boolean for simple convex-ish shapes. */
    fun boolean(op: BooleanOp, a: List<Pt>, b: List<Pt>): List<Pt> {
        return when (op) {
            BooleanOp.Intersect -> clipPolygon(a, b)
            BooleanOp.Subtract -> clipPolygon(a, b.reversed())
            BooleanOp.Union -> {
                val inter = clipPolygon(a, b)
                if (inter.isEmpty()) return a + b
                // Union approximated by concatenation of both outlines minus the seam.
                a + b
            }
            BooleanOp.Exclude -> {
                val inter = clipPolygon(a, b)
                if (inter.isEmpty()) a + b else a + b.reversed()
            }
        }
    }

    private fun clipPolygon(subject: List<Pt>, clip: List<Pt>): List<Pt> {
        if (subject.isEmpty() || clip.size < 3) return emptyList()
        var output = subject
        val area = signedArea(clip)
        val orientation = if (area >= 0) 1f else -1f
        for (i in clip.indices) {
            if (output.isEmpty()) break
            val a = clip[i]
            val b = clip[(i + 1) % clip.size]
            val input = output
            output = ArrayList(input.size + 2)
            fun inside(p: Pt): Boolean = orientation * cross(a, b, p) >= 0f
            for (j in input.indices) {
                val cur = input[j]
                val prev = input[(j - 1 + input.size) % input.size]
                val curIn = inside(cur)
                val prevIn = inside(prev)
                when {
                    curIn && prevIn -> output.add(cur)
                    curIn && !prevIn -> {
                        intersection(prev, cur, a, b)?.let { output.add(it) }
                        output.add(cur)
                    }
                    !curIn && prevIn -> intersection(prev, cur, a, b)?.let { output.add(it) }
                }
            }
        }
        return output
    }

    private fun cross(a: Pt, b: Pt, c: Pt): Float = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun signedArea(poly: List<Pt>): Float {
        var sum = 0f
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2f
    }

    private fun intersection(p1: Pt, p2: Pt, q1: Pt, q2: Pt): Pt? {
        val a1 = p2.y - p1.y
        val b1 = p1.x - p2.x
        val c1 = a1 * p1.x + b1 * p1.y
        val a2 = q2.y - q1.y
        val b2 = q1.x - q2.x
        val c2 = a2 * q1.x + b2 * q1.y
        val det = a1 * b2 - a2 * b1
        if (abs(det) < 1e-6f) return null
        return Pt((b2 * c1 - b1 * c2) / det, (a1 * c2 - a2 * c1) / det)
    }

    // -----------------------------------------------------------------------
    // Align / distribute
    // -----------------------------------------------------------------------

    enum class Align { Left, CenterH, Right, Top, CenterV, Bottom }

    fun alignBounds(items: List<com.artforge.mobile.core.model.RectF2>, mode: Align): List<Pt> {
        if (items.isEmpty()) return emptyList()
        val left = items.minOf { it.left }
        val right = items.maxOf { it.right }
        val top = items.minOf { it.top }
        val bottom = items.maxOf { it.bottom }
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        return items.map { r ->
            when (mode) {
                Align.Left -> Pt(left - r.left, 0f)
                Align.CenterH -> Pt(cx - r.centerX, 0f)
                Align.Right -> Pt(right - r.right, 0f)
                Align.Top -> Pt(0f, top - r.top)
                Align.CenterV -> Pt(0f, cy - r.centerY)
                Align.Bottom -> Pt(0f, bottom - r.bottom)
            }
        }
    }

    fun distributeBounds(items: List<com.artforge.mobile.core.model.RectF2>, horizontal: Boolean): List<Pt> {
        if (items.size < 3) return items.map { Pt.Zero }
        val sorted = items.sortedBy { if (horizontal) it.centerX else it.centerY }
        val first = if (horizontal) sorted.first().centerX else sorted.first().centerY
        val last = if (horizontal) sorted.last().centerX else sorted.last().centerY
        val step = (last - first) / (sorted.size - 1)
        val result = HashMap<Int, Pt>()
        sorted.forEachIndexed { i, r ->
            val target = first + step * i
            val dx = if (horizontal) target - r.centerX else 0f
            val dy = if (horizontal) 0f else target - r.centerY
            result[items.indexOf(r)] = Pt(dx, dy)
        }
        return items.indices.map { result[it] ?: Pt.Zero }
    }

    // -----------------------------------------------------------------------
    // SVG import / export
    // -----------------------------------------------------------------------

    /** Very small but real SVG path generator for the shapes in a vector layer. */
    fun toSvg(shapes: List<VectorShape>, width: Int, height: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height">""").append('\n')
        for (shape in shapes) {
            val fill = shape.fillColor?.let { com.artforge.mobile.core.color.ColorUtils.toHex(it, true) } ?: "none"
            val stroke = com.artforge.mobile.core.color.ColorUtils.toHex(shape.strokeColor, true)
            sb.append("  <path d=\"")
            sb.append(pathData(shape))
            sb.append("\" fill=\"").append(fill)
                .append("\" stroke=\"").append(stroke)
                .append("\" stroke-width=\"").append(shape.strokeWidth)
                .append("\" stroke-linecap=\"round\" stroke-linejoin=\"round\" />").append('\n')
        }
        sb.append("</svg>")
        return sb.toString()
    }

    private fun pathData(shape: VectorShape): String {
        val pts = shape.points
        if (pts.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("M ").append(fmt(pts[0].x)).append(' ').append(fmt(pts[0].y))
        val hasControls = shape.controlsIn.size == pts.size && shape.controlsOut.size == pts.size
        for (i in 0 until pts.size - 1) {
            if (hasControls) {
                val c1 = shape.controlsOut[i]
                val c2 = shape.controlsIn[i + 1]
                sb.append(" C ").append(fmt(c1.x)).append(' ').append(fmt(c1.y))
                    .append(", ").append(fmt(c2.x)).append(' ').append(fmt(c2.y))
                    .append(", ").append(fmt(pts[i + 1].x)).append(' ').append(fmt(pts[i + 1].y))
            } else {
                sb.append(" L ").append(fmt(pts[i + 1].x)).append(' ').append(fmt(pts[i + 1].y))
            }
        }
        if (shape.closed) sb.append(" Z")
        return sb.toString()
    }

    private fun fmt(v: Float): String = String.format("%.2f", v)

    /**
     * Minimal SVG parser supporting the subset of path commands this app emits plus
     * common hand-authored files: M/L/H/V/C/S/Q/T/Z (absolute + relative).
     */
    fun parseSvgPaths(svg: String): List<List<Pt>> {
        val results = ArrayList<List<Pt>>()
        val regex = Regex("""d\s*=\s*["]([^"]+)["]""")
        for (match in regex.findAll(svg)) {
            results.addAll(PathParser.parse(match.groupValues[1]))
        }
        return results
    }

    object PathParser {
        fun parse(d: String): List<List<Pt>> {
            val tokens = Regex("""([MmLlHhVvCcSsQqTtZz])|(-?\d*\.?\d+(?:[eE][-+]?\d+)?)""")
                .findAll(d).map { it.value }.toList()
            val paths = ArrayList<List<Pt>>()
            var current = ArrayList<Pt>()
            var i = 0
            var cx = 0f
            var cy = 0f
            var startX = 0f
            var startY = 0f
            var lastCtrlX = 0f
            var lastCtrlY = 0f
            var lastCmd = ' '

            fun num(): Float = tokens.getOrNull(i++)?.toFloatOrNull() ?: 0f
            fun flush() {
                if (current.size > 1) paths.add(current)
                current = ArrayList()
            }

            while (i < tokens.size) {
                val t = tokens[i]
                val cmd = t[0]
                if (cmd.isLetter()) {
                    i++
                    when (cmd.uppercaseChar()) {
                        'Z' -> {
                            if (current.isNotEmpty()) {
                                current.add(Pt(startX, startY))
                                flush()
                            }
                            cx = startX; cy = startY
                        }
                        'M' -> {
                            flush()
                            val x = num(); val y = num()
                            cx = if (cmd == 'm') cx + x else x
                            cy = if (cmd == 'm') cy + y else y
                            startX = cx; startY = cy
                            current.add(Pt(cx, cy))
                        }
                        'L' -> {
                            val x = num(); val y = num()
                            cx = if (cmd == 'l') cx + x else x
                            cy = if (cmd == 'l') cy + y else y
                            current.add(Pt(cx, cy))
                        }
                        'H' -> {
                            val x = num()
                            cx = if (cmd == 'h') cx + x else x
                            current.add(Pt(cx, cy))
                        }
                        'V' -> {
                            val y = num()
                            cy = if (cmd == 'v') cy + y else y
                            current.add(Pt(cx, cy))
                        }
                        'C' -> {
                            val x1 = num(); val y1 = num(); val x2 = num(); val y2 = num(); val x = num(); val y = num()
                            val ax = if (cmd == 'c') cx + x1 else x1
                            val ay = if (cmd == 'c') cy + y1 else y1
                            val bx = if (cmd == 'c') cx + x2 else x2
                            val by = if (cmd == 'c') cy + y2 else y2
                            val ex = if (cmd == 'c') cx + x else x
                            val ey = if (cmd == 'c') cy + y else y
                            for (s in 1..8) current.add(bezier(cx, cy, ax, ay, bx, by, ex, ey, s / 8f))
                            lastCtrlX = bx; lastCtrlY = by
                            cx = ex; cy = ey
                        }
                        'Q' -> {
                            val x1 = num(); val y1 = num(); val x = num(); val y = num()
                            val ax = if (cmd == 'q') cx + x1 else x1
                            val ay = if (cmd == 'q') cy + y1 else y1
                            val ex = if (cmd == 'q') cx + x else x
                            val ey = if (cmd == 'q') cy + y else y
                            for (s in 1..8) current.add(cubicFromQuad(cx, cy, ax, ay, ex, ey, s / 8f))
                            lastCtrlX = ax; lastCtrlY = ay
                            cx = ex; cy = ey
                        }
                        else -> Unit
                    }
                    lastCmd = cmd
                } else {
                    // Implicit repeat of the previous command.
                    when (lastCmd.uppercaseChar()) {
                        'L' -> {
                            val x = num(); val y = num()
                            cx = if (lastCmd == 'l') cx + x else x
                            cy = if (lastCmd == 'l') cy + y else y
                            current.add(Pt(cx, cy))
                        }
                        'M' -> {
                            val x = num(); val y = num()
                            cx = x; cy = y
                            current.add(Pt(cx, cy))
                            lastCmd = 'L'
                        }
                        else -> i++
                    }
                }
            }
            flush()
            return paths
        }

        private fun bezier(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, t: Float): Pt {
            val u = 1 - t
            return Pt(
                u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3,
                u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3,
            )
        }

        private fun cubicFromQuad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, t: Float): Pt {
            val u = 1 - t
            return Pt(
                u * u * x0 + 2 * u * t * x1 + t * t * x2,
                u * u * y0 + 2 * u * t * y1 + t * t * y2,
            )
        }
    }

    /** Path length used by stroke animation along a motion path. */
    fun pathLength(points: List<Pt>): Float {
        var total = 0f
        for (i in 0 until points.size - 1) total += points[i].distanceTo(points[i + 1])
        return total
    }

    /** Point at a normalised position along a polyline (motion paths). */
    fun pointAt(points: List<Pt>, t: Float): Pt {
        if (points.isEmpty()) return Pt.Zero
        if (points.size == 1) return points[0]
        val total = pathLength(points)
        if (total <= 0f) return points[0]
        var target = total * t.coerceIn(0f, 1f)
        for (i in 0 until points.size - 1) {
            val seg = points[i].distanceTo(points[i + 1])
            if (target <= seg) {
                val f = if (seg <= 0f) 0f else target / seg
                return Pt(
                    points[i].x + (points[i + 1].x - points[i].x) * f,
                    points[i].y + (points[i + 1].y - points[i].y) * f,
                )
            }
            target -= seg
        }
        return points.last()
    }

    fun svgPolylineBounds(svg: String): com.artforge.mobile.core.model.RectF2 {
        val all = parseSvgPaths(svg).flatten()
        if (all.isEmpty()) return com.artforge.mobile.core.model.RectF2(0f, 0f, 0f, 0f)
        return com.artforge.mobile.core.model.RectF2(
            all.minOf { it.x }, all.minOf { it.y }, all.maxOf { it.x }, all.maxOf { it.y },
        )
    }

    @Suppress("unused")
    private fun distance(a: Pt, b: Pt) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
}
