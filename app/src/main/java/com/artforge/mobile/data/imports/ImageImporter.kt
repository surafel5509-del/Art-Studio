package com.artforge.mobile.data.imports

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import com.artforge.mobile.core.util.ForgeLog
import com.artforge.mobile.engine.vector.VectorEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Metadata discovered for an imported file. */
data class ImportedImage(
    val bitmap: Bitmap,
    val originalWidth: Int,
    val originalHeight: Int,
    val uri: Uri,
    val mimeType: String?,
)

/**
 * Image import for PNG/JPG/JPEG/WebP/BMP/SVG.
 *
 * Raster formats use [BitmapFactory] with subsampled decoding for very large files;
 * SVG is parsed by our own path parser and rasterised at the requested size so
 * vector sources stay sharp.
 */
class ImageImporter(private val context: Context) {

    /** Decodes a picked document, honouring a maximum pixel budget. */
    suspend fun import(uri: Uri, maxDimension: Int = 8192): ImportedImage? = withContext(Dispatchers.IO) {
        try {
            val mime = context.contentResolver.getType(uri)
            if (mime?.contains("svg") == true || uri.toString().lowercase().endsWith(".svg")) {
                val svg = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (svg != null) {
                    val bmp = rasterizeSvg(svg, maxDimension, maxDimension)
                    if (bmp != null) {
                        return@withContext ImportedImage(bmp, bmp.width, bmp.height, uri, "image/svg+xml")
                    }
                }
                return@withContext null
            }

            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            val width = options.outWidth
            val height = options.outHeight
            if (width <= 0 || height <= 0) return@withContext null

            var sample = 1
            while (max(width, height) / sample > maxDimension) sample *= 2
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return@withContext null
            ImportedImage(bitmap, width, height, uri, mime)
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "import failed for $uri", t)
            null
        }
    }

    fun loadFile(file: File, maxDimension: Int = 8192): Bitmap? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        var sample = 1
        while (max(options.outWidth, options.outHeight) / sample > maxDimension) sample *= 2
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }.getOrNull()

    /** Rasterises SVG path data to a bitmap using the vector engine's parser. */
    fun rasterizeSvg(svg: String, width: Int, height: Int, color: Int = 0xFF000000.toInt()): Bitmap? {
        val paths = VectorEngine.parseSvgPaths(svg)
        if (paths.isEmpty()) return null
        val bounds = VectorEngine.svgPolylineBounds(svg)
        if (bounds.width <= 0f || bounds.height <= 0f) return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val scale = min(width / bounds.width, height / bounds.height)
        val offsetX = (width - bounds.width * scale) / 2f - bounds.left * scale
        val offsetY = (height - bounds.height * scale) / 2f - bounds.top * scale
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f / scale
            this.color = color
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        paths.forEach { points ->
            if (points.size < 2) return@forEach
            val path = android.graphics.Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
            }
            canvas.drawPath(path, paint)
        }
        return bmp
    }

    /** Fits an imported image into the canvas, scaling down only. */
    fun fitToCanvas(source: Bitmap, canvasWidth: Int, canvasHeight: Int): Pair<Bitmap, RectF> {
        val scale = min(canvasWidth.toFloat() / source.width, canvasHeight.toFloat() / source.height)
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (w == source.width && h == source.height) source else Bitmap.createScaledBitmap(source, w, h, true)
        val left = (canvasWidth - w) / 2f
        val top = (canvasHeight - h) / 2f
        return scaled to RectF(left, top, left + w, top + h)
    }

    /** Perspective / warp transform of an imported image into a quad. */
    fun warpIntoQuad(source: Bitmap, quad: FloatArray, canvasWidth: Int, canvasHeight: Int): Bitmap {
        val out = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val matrix = Matrix()
        val srcQuad = floatArrayOf(
            0f, 0f,
            source.width.toFloat(), 0f,
            source.width.toFloat(), source.height.toFloat(),
            0f, source.height.toFloat(),
        )
        matrix.setPolyToPoly(srcQuad, 0, quad, 0, 4)
        canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Simple non-linear warp used by the Warp tool (sinusoidal displacement). */
    fun warp(source: Bitmap, strength: Float, frequency: Float = 0.02f, axis: Int = 0): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = if (axis != 1) (kotlin.math.sin(y * frequency) * strength).toInt() else 0
                val dy = if (axis != 0) (kotlin.math.sin(x * frequency) * strength).toInt() else 0
                val sx = (x + dx).coerceIn(0, w - 1)
                val sy = (y + dy).coerceIn(0, h - 1)
                out[y * w + x] = pixels[sy * w + sx]
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Crop, resize, rotate and flip helpers used by the canvas management panel. */
    fun crop(source: Bitmap, rect: Rect): Bitmap = Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())

    fun resize(source: Bitmap, width: Int, height: Int, filter: Boolean = true): Bitmap =
        Bitmap.createScaledBitmap(source, max(1, width), max(1, height), filter)

    fun rotate(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    fun flip(source: Bitmap, horizontal: Boolean): Bitmap {
        val matrix = Matrix().apply { if (horizontal) postScale(-1f, 1f) else postScale(1f, -1f) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    fun flatten(source: Bitmap, background: Int): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(background)
        canvas.drawBitmap(source, 0f, 0f, null)
        return out
    }

    companion object {
        private const val TAG = "ImageImporter"
        val SUPPORTED = listOf("png", "jpg", "jpeg", "webp", "bmp", "svg")

        fun mimeFor(extension: String): String = when (extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }
    }
}
