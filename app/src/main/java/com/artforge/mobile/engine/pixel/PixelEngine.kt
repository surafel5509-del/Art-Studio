package com.artforge.mobile.engine.pixel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.artforge.mobile.core.color.ColorUtils
import kotlin.math.max
import kotlin.math.min

/**
 * Pixel-art specific operations: crisp 1px plotting with a Bresenham line, palette
 * enforcement, nearest-neighbour scaling, grid overlay and sprite-sheet packing.
 *
 * The pixel canvas is a real bitmap at 1:1 document resolution; the UI scales it
 * with [BitmapShader]/`FILTER_BITMAP_FLAG = false` so pixels stay sharp.
 */
object PixelEngine {

    /** Plots a single pixel (no anti-aliasing, no blending unless alpha < 255). */
    fun plot(bitmap: Bitmap, x: Int, y: Int, color: Int) {
        if (x < 0 || y < 0 || x >= bitmap.width || y >= bitmap.height) return
        if (ColorUtils.alpha(color) >= 255) {
            bitmap.setPixel(x, y, color)
        } else {
            bitmap.setPixel(x, y, ColorUtils.lerp(bitmap.getPixel(x, y), color, ColorUtils.alpha(color) / 255f))
        }
    }

    /** Bresenham line — guarantees connected, pixel-perfect strokes. */
    fun line(bitmap: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, color: Int, thickness: Int = 1) {
        var x = x0
        var y = y0
        val dx = kotlin.math.abs(x1 - x0)
        val dy = -kotlin.math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            brush(bitmap, x, y, color, thickness)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) {
                err += dy
                x += sx
            }
            if (e2 <= dx) {
                err += dx
                y += sy
            }
        }
    }

    fun brush(bitmap: Bitmap, cx: Int, cy: Int, color: Int, size: Int) {
        if (size <= 1) {
            plot(bitmap, cx, cy, color)
            return
        }
        val half = size / 2
        for (dy in -half until size - half) {
            for (dx in -half until size - half) plot(bitmap, cx + dx, cy + dy, color)
        }
    }

    fun rect(bitmap: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, color: Int, fill: Boolean = false, thickness: Int = 1) {
        val l = min(x0, x1)
        val t = min(y0, y1)
        val r = max(x0, x1)
        val b = max(y0, y1)
        if (fill) {
            for (y in t..b) for (x in l..r) plot(bitmap, x, y, color)
        } else {
            for (k in 0 until thickness) {
                line(bitmap, l + k, t + k, r - k, t + k, color)
                line(bitmap, l + k, b - k, r - k, b - k, color)
                line(bitmap, l + k, t + k, l + k, b - k, color)
                line(bitmap, r - k, t + k, r - k, b - k, color)
            }
        }
    }

    fun ellipse(bitmap: Bitmap, cx: Int, cy: Int, rx: Int, ry: Int, color: Int, fill: Boolean = false) {
        if (rx <= 0 || ry <= 0) return
        if (fill) {
            for (y in -ry..ry) {
                val span = (rx * kotlin.math.sqrt(max(0.0, 1.0 - (y.toDouble() * y) / (ry.toDouble() * ry)))).toInt()
                for (x in -span..span) plot(bitmap, cx + x, cy + y, color)
            }
        } else {
            var x = 0
            var y = ry
            var d1 = (ry * ry) - (rx * rx * ry) + (0.25f * rx * rx)
            var dx = 2 * ry * ry * x
            var dy = 2 * rx * rx * y
            while (dx < dy) {
                plot(bitmap, cx + x, cy + y, color); plot(bitmap, cx - x, cy + y, color)
                plot(bitmap, cx + x, cy - y, color); plot(bitmap, cx - x, cy - y, color)
                x++
                dx += 2 * ry * ry
                if (d1 < 0) d1 += dx + ry * ry else {
                    y--
                    dy -= 2 * rx * rx
                    d1 += dx - dy + ry * ry
                }
            }
            var d2 = (ry * ry * (x + 0.5f) * (x + 0.5f)) + (rx * rx * (y - 1f) * (y - 1f)) - (rx * rx * ry * ry).toFloat()
            while (y >= 0) {
                plot(bitmap, cx + x, cy + y, color); plot(bitmap, cx - x, cy + y, color)
                plot(bitmap, cx + x, cy - y, color); plot(bitmap, cx - x, cy - y, color)
                y--
                dy -= 2 * rx * rx
                if (d2 > 0) d2 += rx * rx - dy else {
                    x++
                    dx += 2 * ry * ry
                    d2 += dx - dy + rx * rx
                }
            }
        }
    }

    /** Removes every pixel whose colour is not in [palette] (nearest match). */
    fun enforcePalette(bitmap: Bitmap, palette: List<Int>) {
        if (palette.isEmpty()) return
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            if (ColorUtils.alpha(c) == 0) continue
            var best = palette[0]
            var bestDist = Int.MAX_VALUE
            for (p in palette) {
                val dr = ColorUtils.red(c) - ColorUtils.red(p)
                val dg = ColorUtils.green(c) - ColorUtils.green(p)
                val db = ColorUtils.blue(c) - ColorUtils.blue(p)
                val d = dr * dr + dg * dg + db * db
                if (d < bestDist) {
                    bestDist = d
                    best = p
                }
            }
            pixels[i] = ColorUtils.withAlpha(best, ColorUtils.alpha(c))
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    /**
     * Nearest-neighbour upscale — the only correct scaling for pixel art.
     *
     * Implemented by pixel replication rather than `Canvas.drawBitmap` with the
     * filter disabled: Skia's non-filtered draw is not guaranteed to preserve exact
     * colours and alpha on every API level, and pixel art must not shift.
     */
    fun scaleNearest(source: Bitmap, factor: Int): Bitmap {
        val f = factor.coerceAtLeast(1)
        return resizeNearest(source, source.width * f, source.height * f)
    }

    fun resizeNearest(source: Bitmap, width: Int, height: Int): Bitmap {
        val outW = width.coerceAtLeast(1)
        val outH = height.coerceAtLeast(1)
        val srcW = source.width.coerceAtLeast(1)
        val srcH = source.height.coerceAtLeast(1)
        val src = IntArray(srcW * srcH)
        source.getPixels(src, 0, srcW, 0, 0, srcW, srcH)
        val dst = IntArray(outW * outH)
        for (y in 0 until outH) {
            val sy = (y.toLong() * srcH / outH).toInt().coerceIn(0, srcH - 1)
            val rowStart = sy * srcW
            val dstRow = y * outW
            for (x in 0 until outW) {
                val sx = (x.toLong() * srcW / outW).toInt().coerceIn(0, srcW - 1)
                dst[dstRow + x] = src[rowStart + sx]
            }
        }
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        out.setPixels(dst, 0, outW, 0, 0, outW, outH)
        return out
    }

    /** Packs animation frames into a sprite sheet with configurable columns. */
    fun buildSpriteSheet(
        frames: List<Bitmap>,
        columns: Int = 4,
        padding: Int = 0,
        background: Int = Color.TRANSPARENT,
    ): Bitmap {
        if (frames.isEmpty()) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val fw = frames.maxOf { it.width }
        val fh = frames.maxOf { it.height }
        val cols = columns.coerceIn(1, frames.size)
        val rows = (frames.size + cols - 1) / cols
        val sheetW = cols * fw + (cols + 1) * padding
        val sheetH = rows * fh + (rows + 1) * padding
        val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        if (background != Color.TRANSPARENT) canvas.drawColor(background)
        frames.forEachIndexed { i, frame ->
            val col = i % cols
            val row = i / cols
            val x = padding + col * (fw + padding)
            val y = padding + row * (fh + padding)
            canvas.drawBitmap(frame, x.toFloat(), y.toFloat(), Paint().apply { isFilterBitmap = false })
        }
        return sheet
    }

    /** Splits a sprite sheet back into frames. */
    fun splitSpriteSheet(sheet: Bitmap, frameWidth: Int, frameHeight: Int, count: Int): List<Bitmap> {
        val out = ArrayList<Bitmap>(count)
        var i = 0
        var y = 0
        while (y + frameHeight <= sheet.height && i < count) {
            var x = 0
            while (x + frameWidth <= sheet.width && i < count) {
                out.add(Bitmap.createBitmap(sheet, x, y, frameWidth, frameHeight))
                x += frameWidth
                i++
            }
            y += frameHeight
        }
        return out
    }

    /** Draws the pixel grid overlay (only when zoomed far enough to be useful). */
    fun drawGrid(canvas: Canvas, viewWidth: Float, viewHeight: Float, zoomScale: Float, color: Int = 0x33888888) {
        if (zoomScale < 6f) return
        val paint = Paint().apply {
            this.color = color
            strokeWidth = 1f
            isAntiAlias = false
        }
        var x = 0f
        while (x < viewWidth) {
            canvas.drawLine(x, 0f, x, viewHeight, paint)
            x += zoomScale
        }
        var y = 0f
        while (y < viewHeight) {
            canvas.drawLine(0f, y, viewWidth, y, paint)
            y += zoomScale
        }
    }

    /** Perceptual distance used by the palette optimiser. */
    private fun distanceSq(a: Int, b: Int): Int {
        val dr = ColorUtils.red(a) - ColorUtils.red(b)
        val dg = ColorUtils.green(a) - ColorUtils.green(b)
        val db = ColorUtils.blue(a) - ColorUtils.blue(b)
        return dr * dr + dg * dg + db * db
    }

    /** Quantises an image to at most [maxColors] using a median-cut style histogram. */
    fun quantize(source: Bitmap, maxColors: Int): Pair<Bitmap, List<Int>> {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val histogram = HashMap<Int, Int>()
        for (p in pixels) {
            if (ColorUtils.alpha(p) < 8) continue
            histogram[p] = (histogram[p] ?: 0) + 1
        }
        if (histogram.size <= maxColors) {
            return source.copy(Bitmap.Config.ARGB_8888, true) to histogram.keys.toList()
        }
        val palette = ArrayList<Int>()
        val entries = histogram.entries.sortedByDescending { it.value }.map { it.key }
        val bucketSize = (entries.size + maxColors - 1) / maxColors
        var i = 0
        while (i < entries.size) {
            val end = min(entries.size, i + bucketSize)
            var a = 0L
            var r = 0L
            var g = 0L
            var b = 0L
            var count = 0
            for (j in i until end) {
                val c = entries[j]
                a += ColorUtils.alpha(c); r += ColorUtils.red(c); g += ColorUtils.green(c); b += ColorUtils.blue(c)
                count++
            }
            if (count > 0) {
                palette.add(ColorUtils.argb((a / count).toInt(), (r / count).toInt(), (g / count).toInt(), (b / count).toInt()))
            }
            i = end
        }
        val out = IntArray(w * h)
        for (idx in pixels.indices) {
            val c = pixels[idx]
            if (ColorUtils.alpha(c) < 8) {
                out[idx] = Color.TRANSPARENT
                continue
            }
            var best = palette.first()
            var bestD = Int.MAX_VALUE
            for (p in palette) {
                val d = distanceSq(c, p)
                if (d < bestD) {
                    bestD = d
                    best = p
                }
            }
            out[idx] = ColorUtils.withAlpha(best, ColorUtils.alpha(c))
        }
        val quantized = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        quantized.setPixels(out, 0, w, 0, 0, w, h)
        return quantized to palette
    }

    /** Tile editor helper: paints a tile into a repeating pattern bitmap. */
    fun tilePreview(tile: Bitmap, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint().apply { isFilterBitmap = false }
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                canvas.drawBitmap(tile, x.toFloat(), y.toFloat(), paint)
                x += tile.width
            }
            y += tile.height
        }
        return out
    }
}
