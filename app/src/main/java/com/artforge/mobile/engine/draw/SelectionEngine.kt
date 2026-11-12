package com.artforge.mobile.engine.draw

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.artforge.mobile.core.color.ColorUtils
import com.artforge.mobile.core.math.BitGrid
import com.artforge.mobile.core.model.Pt
import com.artforge.mobile.core.model.RectF2
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A selection is stored as an 8-bit alpha mask. The same mask feeds:
 *  - clipping of drawing operations,
 *  - transform of the selected pixels,
 *  - save/load selection as a channel,
 *  - magic wand and colour-range results.
 */
data class SelectionMask(
    val width: Int,
    val height: Int,
    /** 0..255 coverage per pixel, row-major. */
    val alpha: ByteArray,
) {
    fun isEmpty(): Boolean {
        for (b in alpha) if (b.toInt() and 0xFF > 0) return false
        return true
    }

    fun bounds(): Rect {
        var l = width
        var t = height
        var r = -1
        var b = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (alpha[row + x].toInt() != 0) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > b) b = y
                }
            }
        }
        if (r < l || b < t) return Rect(0, 0, 0, 0)
        return Rect(l, t, r + 1, b + 1)
    }

    fun coverageAt(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0
        return alpha[y * width + x].toInt() and 0xFF
    }

    fun countSelected(): Int {
        var n = 0
        for (b in alpha) if (b.toInt() != 0) n++
        return n
    }

    fun toBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(alpha))
        return bmp
    }

    /** Marching-ants outline used by the canvas overlay. */
    fun outlinePath(): Path {
        val path = Path()
        val edge = BitGrid(width, height)
        fun inside(x: Int, y: Int) = coverageAt(x, y) > 8
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!inside(x, y)) continue
                if (!inside(x - 1, y)) path.addRect(x.toFloat(), y.toFloat(), x + 0.02f, y + 1f, Path.Direction.CW)
                if (!inside(x + 1, y)) path.addRect(x + 1f - 0.02f, y.toFloat(), x + 1f, y + 1f, Path.Direction.CW)
                if (!inside(x, y - 1)) path.addRect(x.toFloat(), y.toFloat(), x + 1f, y + 0.02f, Path.Direction.CW)
                if (!inside(x, y + 1)) path.addRect(x.toFloat(), y + 1f - 0.02f, x + 1f, y + 1f, Path.Direction.CW)
            }
        }
        edge.clear()
        return path
    }

    fun invert(): SelectionMask {
        val out = ByteArray(alpha.size)
        for (i in alpha.indices) out[i] = (255 - (alpha[i].toInt() and 0xFF)).toByte()
        return SelectionMask(width, height, out)
    }

    fun feather(radius: Int): SelectionMask = SelectionEngine.feather(this, radius)

    fun expand(pixels: Int): SelectionMask = SelectionEngine.morph(this, pixels, dilate = true)

    fun contract(pixels: Int): SelectionMask = SelectionEngine.morph(this, pixels, dilate = false)

    fun intersect(other: SelectionMask): SelectionMask {
        val out = ByteArray(alpha.size)
        for (i in alpha.indices) out[i] = min(alpha[i].toInt() and 0xFF, other.alpha[i].toInt() and 0xFF).toByte()
        return SelectionMask(width, height, out)
    }

    fun union(other: SelectionMask): SelectionMask {
        val out = ByteArray(alpha.size)
        for (i in alpha.indices) out[i] = max(alpha[i].toInt() and 0xFF, other.alpha[i].toInt() and 0xFF).toByte()
        return SelectionMask(width, height, out)
    }

    fun subtract(other: SelectionMask): SelectionMask {
        val out = ByteArray(alpha.size)
        for (i in alpha.indices) {
            val a = alpha[i].toInt() and 0xFF
            val b = other.alpha[i].toInt() and 0xFF
            out[i] = max(0, a - b).toByte()
        }
        return SelectionMask(width, height, out)
    }

    fun translate(dx: Int, dy: Int): SelectionMask {
        val out = ByteArray(alpha.size)
        for (y in 0 until height) {
            val sy = y - dy
            if (sy < 0 || sy >= height) continue
            for (x in 0 until width) {
                val sx = x - dx
                if (sx < 0 || sx >= width) continue
                out[y * width + x] = alpha[sy * width + sx]
            }
        }
        return SelectionMask(width, height, out)
    }
}

enum class SelectionOp { Replace, Add, Subtract, Intersect }

enum class SelectMode { Rectangle, Ellipse, Lasso, Polygon, MagicWand, ColorRange }

/** Builds selection masks from user gestures and image content. */
object SelectionEngine {

    fun empty(width: Int, height: Int) = SelectionMask(width, height, ByteArray(width * height))

    fun all(width: Int, height: Int) = SelectionMask(width, height, ByteArray(width * height) { 0xFF.toByte() })

    fun fromRect(width: Int, height: Int, rect: RectF2, feather: Int = 0): SelectionMask {
        val mask = ByteArray(width * height)
        val l = floor(rect.left).toInt().coerceIn(0, width)
        val t = floor(rect.top).toInt().coerceIn(0, height)
        val r = ceil(rect.right).toInt().coerceIn(0, width)
        val b = ceil(rect.bottom).toInt().coerceIn(0, height)
        for (y in t until b) {
            val row = y * width
            for (x in l until r) mask[row + x] = 0xFF.toByte()
        }
        val result = SelectionMask(width, height, mask)
        return if (feather > 0) feather(result, feather) else result
    }

    fun fromEllipse(width: Int, height: Int, rect: RectF2, feather: Int = 0, antialias: Boolean = true): SelectionMask {
        val mask = ByteArray(width * height)
        val cx = rect.centerX
        val cy = rect.centerY
        val rx = max(rect.width / 2f, 0.001f)
        val ry = max(rect.height / 2f, 0.001f)
        val l = floor(rect.left).toInt().coerceIn(0, width)
        val t = floor(rect.top).toInt().coerceIn(0, height)
        val r = ceil(rect.right).toInt().coerceIn(0, width)
        val b = ceil(rect.bottom).toInt().coerceIn(0, height)
        for (y in t until b) {
            val row = y * width
            for (x in l until r) {
                val dx = (x + 0.5f - cx) / rx
                val dy = (y + 0.5f - cy) / ry
                val d = sqrt(dx * dx + dy * dy)
                val cov = when {
                    d <= 0.98f -> 1f
                    d >= 1.02f -> 0f
                    antialias -> (1f - (d - 0.98f) / 0.04f).coerceIn(0f, 1f)
                    else -> 0f
                }
                mask[row + x] = (cov * 255f).toInt().toByte()
            }
        }
        val result = SelectionMask(width, height, mask)
        return if (feather > 0) feather(result, feather) else result
    }

    fun fromPolygon(width: Int, height: Int, points: List<Pt>, feather: Int = 0, antialias: Boolean = true): SelectionMask {
        if (points.size < 3) return empty(width, height)
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
            close()
        }
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        if (!antialias) paint.isAntiAlias = false
        canvas.drawPath(path, paint)
        val bytes = ByteArray(width * height)
        bmp.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(bytes))
        bmp.recycle()
        val result = SelectionMask(width, height, bytes)
        return if (feather > 0) feather(result, feather) else result
    }

    /**
     * Magic wand: contiguous flood over pixels within [tolerance] of the clicked
     * colour. [sampleMerged] uses the visible composite instead of a single layer.
     */
    fun magicWand(
        width: Int,
        height: Int,
        source: Bitmap,
        x: Int,
        y: Int,
        tolerance: Int,
        contiguous: Boolean,
        feather: Int = 0,
        antialiasEdges: Boolean = true,
    ): SelectionMask {
        val mask = ByteArray(width * height)
        if (x < 0 || y < 0 || x >= width || y >= height) return SelectionMask(width, height, mask)
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val seed = pixels[y * width + x]
        val tol = tolerance.toDouble() * tolerance * 3

        fun matches(c: Int): Boolean {
            if (c == seed) return true
            val dr = ColorUtils.red(c) - ColorUtils.red(seed)
            val dg = ColorUtils.green(c) - ColorUtils.green(seed)
            val db = ColorUtils.blue(c) - ColorUtils.blue(seed)
            val da = ColorUtils.alpha(c) - ColorUtils.alpha(seed)
            return (dr * dr + dg * dg + db * db).toDouble() <= tol && abs(da) <= tolerance
        }

        if (!contiguous) {
            for (i in pixels.indices) if (matches(pixels[i])) mask[i] = 0xFF.toByte()
        } else {
            val visited = BitGrid(width, height)
            val stack = ArrayDeque<Int>()
            stack.addLast(y * width + x)
            while (stack.isNotEmpty()) {
                val idx = stack.removeLast()
                val px = idx % width
                val py = idx / width
                if (visited.get(px, py)) continue
                if (!matches(pixels[idx])) {
                    visited.set(px, py)
                    continue
                }
                visited.set(px, py)
                mask[idx] = 0xFF.toByte()
                if (px > 0) stack.addLast(idx - 1)
                if (px < width - 1) stack.addLast(idx + 1)
                if (py > 0) stack.addLast(idx - width)
                if (py < height - 1) stack.addLast(idx + width)
            }
        }
        val result = SelectionMask(width, height, mask)
        return if (feather > 0) feather(result, feather) else result
    }

    /** Select all pixels whose colour lies within the tolerance of [color]. */
    fun colorRange(width: Int, height: Int, source: Bitmap, color: Int, tolerance: Int, feather: Int = 0): SelectionMask {
        val mask = ByteArray(width * height)
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val tol = tolerance.toDouble() * tolerance * 3
        for (i in pixels.indices) {
            val dr = ColorUtils.red(pixels[i]) - ColorUtils.red(color)
            val dg = ColorUtils.green(pixels[i]) - ColorUtils.green(color)
            val db = ColorUtils.blue(pixels[i]) - ColorUtils.blue(color)
            if ((dr * dr + dg * dg + db * db).toDouble() <= tol) mask[i] = 0xFF.toByte()
        }
        val result = SelectionMask(width, height, mask)
        return if (feather > 0) feather(result, feather) else result
    }

    /** Box blur on the selection alpha — the cheap, predictable feathered edge. */
    fun feather(mask: SelectionMask, radius: Int): SelectionMask {
        if (radius <= 0) return mask
        val w = mask.width
        val h = mask.height
        val src = mask.alpha
        val tmp = ByteArray(src.size)
        val out = ByteArray(src.size)
        val r = radius.coerceAtMost(64)
        for (y in 0 until h) {
            var sum = 0
            for (i in -r..r) sum += src[y * w + i.coerceIn(0, w - 1)].toInt() and 0xFF
            for (x in 0 until w) {
                tmp[y * w + x] = (sum / (2 * r + 1)).toByte()
                val addIdx = (x + r + 1).coerceAtMost(w - 1)
                val subIdx = (x - r).coerceAtLeast(0)
                sum += (src[y * w + addIdx].toInt() and 0xFF) - (src[y * w + subIdx].toInt() and 0xFF)
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (i in -r..r) sum += tmp[i.coerceIn(0, h - 1) * w + x].toInt() and 0xFF
            for (y in 0 until h) {
                out[y * w + x] = (sum / (2 * r + 1)).toByte()
                val addIdx = (y + r + 1).coerceAtMost(h - 1)
                val subIdx = (y - r).coerceAtLeast(0)
                sum += (tmp[addIdx * w + x].toInt() and 0xFF) - (tmp[subIdx * w + x].toInt() and 0xFF)
            }
        }
        return SelectionMask(mask.width, mask.height, out)
    }

    /** Dilate (expand) or erode (contract) using a separable max/min filter. */
    fun morph(mask: SelectionMask, pixels: Int, dilate: Boolean): SelectionMask {
        if (pixels <= 0) return mask
        val w = mask.width
        val h = mask.height
        var current = mask.alpha.copyOf()
        repeat(pixels.coerceAtMost(64)) {
            val step = ByteArray(current.size)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var v = current[y * w + x].toInt() and 0xFF
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nx = (x + dx).coerceIn(0, w - 1)
                            val ny = (y + dy).coerceIn(0, h - 1)
                            val n = current[ny * w + nx].toInt() and 0xFF
                            v = if (dilate) max(v, n) else min(v, n)
                        }
                    }
                    step[y * w + x] = v.toByte()
                }
            }
            current = step
        }
        return SelectionMask(w, h, current)
    }

    fun combine(base: SelectionMask?, incoming: SelectionMask, op: SelectionOp): SelectionMask? {
        if (base == null || op == SelectionOp.Replace) return if (op == SelectionOp.Replace) incoming else base ?: incoming
        return when (op) {
            SelectionOp.Add -> base.union(incoming)
            SelectionOp.Subtract -> base.subtract(incoming)
            SelectionOp.Intersect -> base.intersect(incoming)
            SelectionOp.Replace -> incoming
        }
    }

    /** Clips drawing to the mask by clearing everything outside it. */
    fun clipBitmapToSelection(bitmap: Bitmap, mask: SelectionMask) {
        val maskBmp = mask.toBitmap()
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawBitmap(maskBmp, 0f, 0f, paint)
        paint.xfermode = null
        maskBmp.recycle()
    }

    /** Applies an affine offset to selected pixels, leaving the rest untouched. */
    fun moveSelectedPixels(bitmap: Bitmap, mask: SelectionMask, dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        val w = bitmap.width
        val h = bitmap.height
        val src = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val srcPixels = IntArray(w * h)
        src.getPixels(srcPixels, 0, w, 0, 0, w, h)

        val dstPixels = IntArray(w * h)
        bitmap.getPixels(dstPixels, 0, w, 0, 0, w, h)

        // Clear the original selection area, then paint the moved copy.
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (mask.coverageAt(x, y) > 0) dstPixels[y * w + x] = 0
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val cov = mask.coverageAt(x, y)
                if (cov == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val c = srcPixels[y * w + x]
                if (cov == 255) dstPixels[ny * w + nx] = c
                else {
                    val a = (ColorUtils.alpha(c) * cov / 255f).toInt()
                    dstPixels[ny * w + nx] = ColorUtils.withAlpha(c, a)
                }
            }
        }
        bitmap.setPixels(dstPixels, 0, w, 0, 0, w, h)
        src.recycle()
    }

    /** Extracts the selected pixels into a new bitmap (for copy/paste and export). */
    fun extractSelection(bitmap: Bitmap, mask: SelectionMask): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bitmap, 0f, 0f, null)
        val maskBmp = mask.toBitmap()
        Canvas(out).drawBitmap(maskBmp, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        maskBmp.recycle()
        return out
    }

    /** Bounding-box hit test used to draw handles for selection transforms. */
    fun hitTestHandle(bounds: Rect, x: Float, y: Float, handleSize: Float): Int {
        val corners = listOf(
            bounds.left to bounds.top,
            bounds.right to bounds.top,
            bounds.right to bounds.bottom,
            bounds.left to bounds.bottom,
        )
        corners.forEachIndexed { idx, (cx, cy) ->
            if (abs(x - cx) <= handleSize && abs(y - cy) <= handleSize) return idx
        }
        return -1
    }

    fun polygonFromPoints(points: List<Pt>) = points
}
