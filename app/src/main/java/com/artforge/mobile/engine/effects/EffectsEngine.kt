package com.artforge.mobile.engine.effects

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import com.artforge.mobile.core.color.ColorUtils
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** All adjustable filters/effects available from the Effects panel and export pipeline. */
enum class EffectType {
    GaussianBlur, MotionBlur, RadialBlur, DirectionalBlur,
    Sharpen, UnsharpMask, Noise, FilmGrain, Pixelate, Mosaic,
    Halftone, Vignette, Glow, Bloom, DropShadow, InnerShadow,
    Outline, Emboss, Bevel, HueSaturation, BrightnessContrast,
    ColorBalance, Levels, Curves, Threshold, Posterize, GradientMap,
    ChromaticAberration, Distortion, Ripple, Wave, Displacement,
}

data class EffectParams(
    val type: EffectType,
    val amount: Float = 0.5f,
    val radius: Float = 8f,
    val angle: Float = 45f,
    val size: Float = 8f,
    val color: Int = 0xFF000000.toInt(),
    val secondaryColor: Int = 0xFFFFFFFF.toInt(),
    val invert: Boolean = false,
    val seed: Long = 1234L,
    // colour adjustments
    val hue: Float = 0f,
    val saturation: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val levelsBlack: Float = 0f,
    val levelsWhite: Float = 1f,
    val gamma: Float = 1f,
    val threshold: Float = 0.5f,
    val posterizeLevels: Int = 6,
) {
    companion object {
        val defaults = mapOf(
            EffectType.GaussianBlur to EffectParams(EffectType.GaussianBlur, radius = 12f),
            EffectType.MotionBlur to EffectParams(EffectType.MotionBlur, radius = 20f, angle = 45f),
            EffectType.RadialBlur to EffectParams(EffectType.RadialBlur, radius = 24f, amount = 0.6f),
            EffectType.DirectionalBlur to EffectParams(EffectType.DirectionalBlur, radius = 16f, angle = 90f),
            EffectType.Sharpen to EffectParams(EffectType.Sharpen, amount = 0.8f),
            EffectType.UnsharpMask to EffectParams(EffectType.UnsharpMask, amount = 0.7f, radius = 3f),
            EffectType.Noise to EffectParams(EffectType.Noise, amount = 0.25f),
            EffectType.FilmGrain to EffectParams(EffectType.FilmGrain, amount = 0.3f, size = 1.5f),
            EffectType.Pixelate to EffectParams(EffectType.Pixelate, size = 10f),
            EffectType.Mosaic to EffectParams(EffectType.Mosaic, size = 18f),
            EffectType.Halftone to EffectParams(EffectType.Halftone, size = 8f, angle = 45f),
            EffectType.Vignette to EffectParams(EffectType.Vignette, amount = 0.6f, radius = 0.6f),
            EffectType.Glow to EffectParams(EffectType.Glow, radius = 16f, amount = 0.7f, color = 0xFFFFFFFF.toInt()),
            EffectType.Bloom to EffectParams(EffectType.Bloom, radius = 28f, amount = 0.5f),
            EffectType.DropShadow to EffectParams(EffectType.DropShadow, radius = 10f, amount = 0.7f, color = 0xFF000000.toInt()),
            EffectType.InnerShadow to EffectParams(EffectType.InnerShadow, radius = 12f, amount = 0.6f, color = 0xFF000000.toInt()),
            EffectType.Outline to EffectParams(EffectType.Outline, size = 6f, amount = 1f, color = 0xFF000000.toInt()),
            EffectType.Emboss to EffectParams(EffectType.Emboss, amount = 1f, angle = 135f),
            EffectType.Bevel to EffectParams(EffectType.Bevel, size = 6f, amount = 0.8f),
            EffectType.HueSaturation to EffectParams(EffectType.HueSaturation, hue = 0f, saturation = 0f),
            EffectType.BrightnessContrast to EffectParams(EffectType.BrightnessContrast, brightness = 0f, contrast = 0f),
            EffectType.ColorBalance to EffectParams(EffectType.ColorBalance, amount = 0f),
            EffectType.Levels to EffectParams(EffectType.Levels, levelsBlack = 0f, levelsWhite = 1f, gamma = 1f),
            EffectType.Curves to EffectParams(EffectType.Curves, amount = 0.5f),
            EffectType.Threshold to EffectParams(EffectType.Threshold, threshold = 0.5f),
            EffectType.Posterize to EffectParams(EffectType.Posterize, posterizeLevels = 6),
            EffectType.GradientMap to EffectParams(EffectType.GradientMap, color = 0xFF1B1D22.toInt(), secondaryColor = 0xFFFF8A4C.toInt()),
            EffectType.ChromaticAberration to EffectParams(EffectType.ChromaticAberration, radius = 4f, amount = 1f),
            EffectType.Distortion to EffectParams(EffectType.Distortion, amount = 0.4f, radius = 40f, seed = 42L),
            EffectType.Ripple to EffectParams(EffectType.Ripple, amount = 12f, size = 30f),
            EffectType.Wave to EffectParams(EffectType.Wave, amount = 14f, size = 60f, angle = 0f),
            EffectType.Displacement to EffectParams(EffectType.Displacement, amount = 20f, size = 40f),
        )
    }
}

/**
 * CPU + Canvas effects pipeline. Each effect is real pixel work; expensive ones
 * (blurs) downscale first to keep interactive previews fast, and the export path
 * can request full quality.
 */
object EffectsEngine {

    fun apply(source: Bitmap, params: EffectParams, quality: Float = 1f): Bitmap {
        return when (params.type) {
            EffectType.GaussianBlur -> gaussianBlur(source, params.radius * quality)
            EffectType.MotionBlur -> motionBlur(source, params.radius, params.angle)
            EffectType.RadialBlur -> radialBlur(source, params.radius, params.amount)
            EffectType.DirectionalBlur -> motionBlur(source, params.radius, params.angle)
            EffectType.Sharpen -> sharpen(source, params.amount)
            EffectType.UnsharpMask -> unsharpMask(source, params.radius, params.amount)
            EffectType.Noise -> noise(source, params.amount, params.seed)
            EffectType.FilmGrain -> filmGrain(source, params.amount, params.size, params.seed)
            EffectType.Pixelate -> pixelate(source, params.size.toInt().coerceAtLeast(1))
            EffectType.Mosaic -> mosaic(source, params.size.toInt().coerceAtLeast(2))
            EffectType.Halftone -> halftone(source, params.size, params.angle)
            EffectType.Vignette -> vignette(source, params.amount, params.radius, params.color)
            EffectType.Glow -> glow(source, params.radius, params.amount, params.color)
            EffectType.Bloom -> bloom(source, params.radius, params.amount)
            EffectType.DropShadow -> dropShadow(source, params.radius, params.amount, params.color, params.angle)
            EffectType.InnerShadow -> innerShadow(source, params.radius, params.amount, params.color)
            EffectType.Outline -> outline(source, params.size, params.color)
            EffectType.Emboss -> emboss(source, params.amount, params.angle)
            EffectType.Bevel -> bevel(source, params.size, params.amount, params.angle)
            EffectType.HueSaturation -> hueSaturation(source, params.hue, params.saturation)
            EffectType.BrightnessContrast -> brightnessContrast(source, params.brightness, params.contrast)
            EffectType.ColorBalance -> colorBalance(source, params.amount)
            EffectType.Levels -> levels(source, params.levelsBlack, params.levelsWhite, params.gamma)
            EffectType.Curves -> curves(source, params.amount)
            EffectType.Threshold -> threshold(source, params.threshold)
            EffectType.Posterize -> posterize(source, params.posterizeLevels)
            EffectType.GradientMap -> gradientMap(source, params.color, params.secondaryColor)
            EffectType.ChromaticAberration -> chromaticAberration(source, params.radius, params.amount)
            EffectType.Distortion -> distortion(source, params.amount, params.radius, params.seed)
            EffectType.Ripple -> ripple(source, params.amount, params.size)
            EffectType.Wave -> wave(source, params.amount, params.size, params.angle)
            EffectType.Displacement -> displacement(source, params.amount, params.size, params.seed)
        }
    }

    // -----------------------------------------------------------------------
    // Blur family
    // -----------------------------------------------------------------------

    /** Three-pass box blur approximating a Gaussian; runs on a downscaled copy. */
    fun gaussianBlur(source: Bitmap, radius: Float): Bitmap {
        if (radius <= 0.4f) return source.copy(Bitmap.Config.ARGB_8888, true)
        val r = radius.toInt().coerceAtLeast(1)
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        var buffer = pixels
        repeat(3) { buffer = boxBlurPass(buffer, w, h, r) }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(buffer, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxBlurPass(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(src.size)
        val out = IntArray(src.size)
        val div = 2 * r + 1
        for (y in 0 until h) {
            var a = 0
            var rr = 0
            var g = 0
            var b = 0
            for (i in -r..r) {
                val c = src[y * w + i.coerceIn(0, w - 1)]
                a += ColorUtils.alpha(c); rr += ColorUtils.red(c); g += ColorUtils.green(c); b += ColorUtils.blue(c)
            }
            for (x in 0 until w) {
                tmp[y * w + x] = ColorUtils.argb(a / div, rr / div, g / div, b / div)
                val add = src[y * w + (x + r + 1).coerceAtMost(w - 1)]
                val sub = src[y * w + (x - r).coerceAtLeast(0)]
                a += ColorUtils.alpha(add) - ColorUtils.alpha(sub)
                rr += ColorUtils.red(add) - ColorUtils.red(sub)
                g += ColorUtils.green(add) - ColorUtils.green(sub)
                b += ColorUtils.blue(add) - ColorUtils.blue(sub)
            }
        }
        for (x in 0 until w) {
            var a = 0
            var rr = 0
            var g = 0
            var b = 0
            for (i in -r..r) {
                val c = tmp[i.coerceIn(0, h - 1) * w + x]
                a += ColorUtils.alpha(c); rr += ColorUtils.red(c); g += ColorUtils.green(c); b += ColorUtils.blue(c)
            }
            for (y in 0 until h) {
                out[y * w + x] = ColorUtils.argb(a / div, rr / div, g / div, b / div)
                val add = tmp[(y + r + 1).coerceAtMost(h - 1) * w + x]
                val sub = tmp[(y - r).coerceAtLeast(0) * w + x]
                a += ColorUtils.alpha(add) - ColorUtils.alpha(sub)
                rr += ColorUtils.red(add) - ColorUtils.red(sub)
                g += ColorUtils.green(add) - ColorUtils.green(sub)
                b += ColorUtils.blue(add) - ColorUtils.blue(sub)
            }
        }
        return out
    }

    fun motionBlur(source: Bitmap, distance: Float, angleDeg: Float): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val steps = min(24, max(3, distance.toInt() / 2))
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = (cos(rad) * distance / steps).toFloat()
        val dy = (sin(rad) * distance / steps).toFloat()
        val paint = Paint().apply { alpha = (255f / steps).toInt().coerceIn(1, 255) }
        for (i in 0 until steps) {
            val offset = i - steps / 2f
            canvas.drawBitmap(source, dx * offset, dy * offset, paint)
        }
        return out
    }

    fun radialBlur(source: Bitmap, strength: Float, amount: Float): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val steps = (6 + (amount * 12).toInt()).coerceIn(3, 20)
        val cx = source.width / 2f
        val cy = source.height / 2f
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255f / steps).toInt() }
        for (i in 0 until steps) {
            val scale = 1f + (strength / max(source.width, source.height)) * (i - steps / 2f) * 0.12f
            canvas.save()
            canvas.scale(scale, scale, cx, cy)
            canvas.drawBitmap(source, 0f, 0f, paint)
            canvas.restore()
        }
        return out
    }

    // -----------------------------------------------------------------------
    // Detail / structure
    // -----------------------------------------------------------------------

    fun sharpen(source: Bitmap, amount: Float): Bitmap {
        val k = amount.coerceIn(0f, 2f)
        return convolve3x3(source, floatArrayOf(
            0f, -k, 0f,
            -k, 1f + 4f * k, -k,
            0f, -k, 0f,
        ))
    }

    fun unsharpMask(source: Bitmap, radius: Float, amount: Float): Bitmap {
        val blurred = gaussianBlur(source, radius)
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val w = source.width
        val h = source.height
        val src = IntArray(w * h)
        val blur = IntArray(w * h)
        source.getPixels(src, 0, w, 0, 0, w, h)
        blurred.getPixels(blur, 0, w, 0, 0, w, h)
        val res = IntArray(w * h)
        val amt = amount.coerceIn(0f, 3f)
        for (i in src.indices) {
            fun ch(a: Int, b: Int) = (a + (a - b) * amt).toInt().coerceIn(0, 255)
            res[i] = ColorUtils.argb(
                ColorUtils.alpha(src[i]),
                ch(ColorUtils.red(src[i]), ColorUtils.red(blur[i])),
                ch(ColorUtils.green(src[i]), ColorUtils.green(blur[i])),
                ch(ColorUtils.blue(src[i]), ColorUtils.blue(blur[i])),
            )
        }
        out.setPixels(res, 0, w, 0, 0, w, h)
        blurred.recycle()
        return out
    }

    fun convolve3x3(source: Bitmap, kernel: FloatArray): Bitmap {
        require(kernel.size == 9)
        val w = source.width
        val h = source.height
        val src = IntArray(w * h)
        source.getPixels(src, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
                    out[y * w + x] = src[y * w + x]
                    continue
                }
                var r = 0f
                var g = 0f
                var b = 0f
                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val c = src[(y + ky) * w + (x + kx)]
                        val k = kernel[(ky + 1) * 3 + (kx + 1)]
                        r += ColorUtils.red(c) * k
                        g += ColorUtils.green(c) * k
                        b += ColorUtils.blue(c) * k
                    }
                }
                out[y * w + x] = ColorUtils.argb(
                    ColorUtils.alpha(src[y * w + x]),
                    r.toInt().coerceIn(0, 255),
                    g.toInt().coerceIn(0, 255),
                    b.toInt().coerceIn(0, 255),
                )
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    // -----------------------------------------------------------------------
    // Grain / texture
    // -----------------------------------------------------------------------

    fun noise(source: Bitmap, amount: Float, seed: Long): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val rng = Random(seed)
        val strength = (amount.coerceIn(0f, 1f) * 128f)
        for (i in pixels.indices) {
            val n = (rng.nextFloat() - 0.5f) * strength
            pixels[i] = ColorUtils.argb(
                ColorUtils.alpha(pixels[i]),
                (ColorUtils.red(pixels[i]) + n).toInt().coerceIn(0, 255),
                (ColorUtils.green(pixels[i]) + n).toInt().coerceIn(0, 255),
                (ColorUtils.blue(pixels[i]) + n).toInt().coerceIn(0, 255),
            )
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    fun filmGrain(source: Bitmap, amount: Float, grainSize: Float, seed: Long): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val rng = Random(seed)
        val cell = max(1, grainSize.toInt())
        val cellsW = (w + cell - 1) / cell
        val cellsH = (h + cell - 1) / cell
        val grain = FloatArray(cellsW * cellsH) { (rng.nextFloat() - 0.5f) * 2f }
        val strength = amount.coerceIn(0f, 1f) * 60f
        // Luminance-weighted: shadows get more grain, highlights stay clean.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val g = grain[(y / cell) * cellsW + (x / cell)]
                val idx = y * w + x
                val lum = (ColorUtils.red(pixels[idx]) * 0.3f + ColorUtils.green(pixels[idx]) * 0.59f + ColorUtils.blue(pixels[idx]) * 0.11f) / 255f
                val weight = (1f - lum * 0.75f)
                val n = g * strength * weight
                pixels[idx] = ColorUtils.argb(
                    ColorUtils.alpha(pixels[idx]),
                    (ColorUtils.red(pixels[idx]) + n).toInt().coerceIn(0, 255),
                    (ColorUtils.green(pixels[idx]) + n).toInt().coerceIn(0, 255),
                    (ColorUtils.blue(pixels[idx]) + n).toInt().coerceIn(0, 255),
                )
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    fun pixelate(source: Bitmap, block: Int): Bitmap {
        val b = block.coerceAtLeast(1)
        if (b == 1) return source.copy(Bitmap.Config.ARGB_8888, true)
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                var a = 0L
                var r = 0L
                var g = 0L
                var bl = 0L
                var count = 0
                for (yy in y until min(h, y + b)) {
                    for (xx in x until min(w, x + b)) {
                        val c = pixels[yy * w + xx]
                        a += ColorUtils.alpha(c); r += ColorUtils.red(c); g += ColorUtils.green(c); bl += ColorUtils.blue(c)
                        count++
                    }
                }
                val avg = ColorUtils.argb(
                    (a / count).toInt(), (r / count).toInt(), (g / count).toInt(), (bl / count).toInt(),
                )
                for (yy in y until min(h, y + b)) {
                    for (xx in x until min(w, x + b)) out[yy * w + xx] = avg
                }
                x += b
            }
            y += b
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    fun mosaic(source: Bitmap, cell: Int): Bitmap = pixelate(source, cell)

    /** Real dot halftone: each cell renders a dot whose radius follows luminance. */
    fun halftone(source: Bitmap, cellSize: Float, angleDeg: Float): Bitmap {
        val cell = cellSize.coerceAtLeast(2f)
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.BLACK
        val rad = Math.toRadians(angleDeg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val step = cell
        val diag = sqrt((source.width * source.width + source.height * source.height).toDouble()).toFloat()
        var gy = -diag
        while (gy < diag) {
            var gx = -diag
            while (gx < diag) {
                val sx = (source.width / 2f + gx * cosA - gy * sinA).toInt()
                val sy = (source.height / 2f + gx * sinA + gy * cosA).toInt()
                val px = (source.width / 2f + gx * cosA - gy * sinA)
                val py = (source.height / 2f + gx * sinA + gy * cosA)
                if (sx in 0 until source.width && sy in 0 until source.height) {
                    val c = source.getPixel(sx, sy)
                    val lum = (ColorUtils.red(c) * 0.299f + ColorUtils.green(c) * 0.587f + ColorUtils.blue(c) * 0.114f) / 255f
                    val r = (1f - lum) * step * 0.72f
                    if (r > 0.3f) canvas.drawCircle(px, py, r, paint)
                }
                gx += step
            }
            gy += step
        }
        return out
    }

    // -----------------------------------------------------------------------
    // Light / glow
    // -----------------------------------------------------------------------

    fun vignette(source: Bitmap, amount: Float, radiusFrac: Float, color: Int): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = source.width.toFloat()
        val h = source.height.toFloat()
        val r = (max(w, h) * radiusFrac.coerceIn(0.1f, 2f))
        val shader = RadialGradient(
            w / 2f, h / 2f, r,
            intArrayOf(Color.TRANSPARENT, ColorUtils.withAlphaF(color, amount.coerceIn(0f, 1f))),
            floatArrayOf(0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, Paint().apply { this.shader = shader })
        return out
    }

    fun glow(source: Bitmap, radius: Float, amount: Float, color: Int): Bitmap {
        val glowLayer = extractBrightness(source, 0.6f)
        val blurred = gaussianBlur(tint(glowLayer, color), radius)
        glowLayer.recycle()
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint().apply {
            alpha = (amount.coerceIn(0f, 1f) * 255).toInt()
            blendMode = BlendMode.SCREEN
        }
        canvas.drawBitmap(blurred, 0f, 0f, paint)
        blurred.recycle()
        return out
    }

    fun bloom(source: Bitmap, radius: Float, amount: Float): Bitmap {
        val bright = extractBrightness(source, 0.72f)
        val blurred = gaussianBlur(bright, radius)
        bright.recycle()
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint().apply {
            alpha = (amount.coerceIn(0f, 1f) * 230).toInt()
            blendMode = BlendMode.PLUS
        }
        canvas.drawBitmap(blurred, 0f, 0f, paint)
        blurred.recycle()
        return out
    }

    private fun extractBrightness(source: Bitmap, threshold: Float): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (i in pixels.indices) {
            val lum = (ColorUtils.red(pixels[i]) * 0.299f + ColorUtils.green(pixels[i]) * 0.587f + ColorUtils.blue(pixels[i]) * 0.114f) / 255f
            out[i] = if (lum > threshold) pixels[i] else 0
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun tint(source: Bitmap, color: Int): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC_IN)
        val canvas = Canvas(out)
        canvas.drawBitmap(source, 0f, 0f, Paint().apply {
            blendMode = BlendMode.SRC_IN
            colorFilter = BlendModeColorFilter(color, BlendMode.SRC_ATOP)
        })
        return out
    }

    fun dropShadow(source: Bitmap, radius: Float, amount: Float, color: Int, angle: Float): Bitmap {
        val pad = (radius * 2).toInt() + 8
        val out = Bitmap.createBitmap(source.width + pad * 2, source.height + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val rad = Math.toRadians(angle.toDouble())
        val dx = (cos(rad) * radius * 0.6f).toFloat()
        val dy = (sin(rad) * radius * 0.6f).toFloat()
        val silhouette = silhouette(source, color)
        val blurred = gaussianBlur(silhouette, radius * 0.6f)
        silhouette.recycle()
        canvas.drawBitmap(blurred, pad + dx, pad + dy, Paint().apply {
            alpha = (amount.coerceIn(0f, 1f) * 255).toInt()
        })
        blurred.recycle()
        canvas.drawBitmap(source, pad.toFloat(), pad.toFloat(), null)
        return out
    }

    fun innerShadow(source: Bitmap, radius: Float, amount: Float, color: Int): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val inverted = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(inverted)
        c.drawColor(color)
        c.drawBitmap(source, 0f, 0f, Paint().apply { blendMode = BlendMode.DST_OUT })
        val blurred = gaussianBlur(inverted, radius)
        inverted.recycle()
        Canvas(out).drawBitmap(blurred, 0f, 0f, Paint().apply {
            alpha = (amount.coerceIn(0f, 1f) * 255).toInt()
            blendMode = BlendMode.DST_ATOP
        })
        blurred.recycle()
        return out
    }

    private fun silhouette(source: Bitmap, color: Int): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, 0f, 0f, Paint().apply {
            blendMode = BlendMode.SRC_IN
            colorFilter = BlendModeColorFilter(color, BlendMode.SRC_IN)
        })
        return out
    }

    fun outline(source: Bitmap, thickness: Float, color: Int): Bitmap {
        val pad = thickness.toInt() + 4
        val out = Bitmap.createBitmap(source.width + pad * 2, source.height + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val t = thickness.coerceAtLeast(1f)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            blendMode = BlendMode.SRC_IN
            colorFilter = BlendModeColorFilter(color, BlendMode.SRC_IN)
        }
        // Draw the silhouette several times around the source to build an outline.
        val steps = max(8, (t * 4).toInt())
        for (i in 0 until steps) {
            val a = 2 * Math.PI * i / steps
            val ox = (cos(a) * t).toFloat()
            val oy = (sin(a) * t).toFloat()
            canvas.drawBitmap(source, pad + ox, pad + oy, ring)
        }
        canvas.drawBitmap(source, pad.toFloat(), pad.toFloat(), null)
        return out
    }

    fun emboss(source: Bitmap, amount: Float, angle: Float): Bitmap {
        val rad = Math.toRadians(angle.toDouble())
        val dx = cos(rad).toFloat() * amount
        val dy = sin(rad).toFloat() * amount
        val w = source.width
        val h = source.height
        val src = IntArray(w * h)
        source.getPixels(src, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val x1 = (x + dx).toInt().coerceIn(0, w - 1)
                val y1 = (y + dy).toInt().coerceIn(0, h - 1)
                val a = src[y * w + x]
                val b = src[y1 * w + x1]
                val lumA = (ColorUtils.red(a) * 0.299f + ColorUtils.green(a) * 0.587f + ColorUtils.blue(a) * 0.114f)
                val lumB = (ColorUtils.red(b) * 0.299f + ColorUtils.green(b) * 0.587f + ColorUtils.blue(b) * 0.114f)
                val v = (128f + (lumA - lumB)).toInt().coerceIn(0, 255)
                out[y * w + x] = ColorUtils.argb(ColorUtils.alpha(a), v, v, v)
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    fun bevel(source: Bitmap, size: Float, amount: Float, angle: Float): Bitmap {
        val embossed = emboss(source, size.coerceIn(1f, 20f) * 0.6f, angle)
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(embossed, 0f, 0f, Paint().apply {
            alpha = (amount.coerceIn(0f, 1f) * 200).toInt()
            blendMode = BlendMode.OVERLAY
        })
        embossed.recycle()
        return out
    }

    // -----------------------------------------------------------------------
    // Colour adjustments
    // -----------------------------------------------------------------------

    fun hueSaturation(source: Bitmap, hueShift: Float, saturation: Float): Bitmap = mapPixels(source) { c ->
        val hsv = ColorUtils.toHsv(c)
        ColorUtils.fromHsv(
            hsv[0] + hueShift,
            (hsv[1] * (1f + saturation)).coerceIn(0f, 1f),
            hsv[2],
            ColorUtils.alpha(c),
        )
    }

    fun brightnessContrast(source: Bitmap, brightness: Float, contrast: Float): Bitmap {
        val b = brightness * 255f
        val c = 1f + contrast
        return mapPixels(source) { color ->
            fun ch(v: Int) = (((v - 128) * c + 128 + b)).toInt().coerceIn(0, 255)
            ColorUtils.argb(
                ColorUtils.alpha(color),
                ch(ColorUtils.red(color)),
                ch(ColorUtils.green(color)),
                ch(ColorUtils.blue(color)),
            )
        }
    }

    fun colorBalance(source: Bitmap, warm: Float): Bitmap = mapPixels(source) { c ->
        ColorUtils.argb(
            ColorUtils.alpha(c),
            (ColorUtils.red(c) + warm * 40).toInt().coerceIn(0, 255),
            ColorUtils.green(c),
            (ColorUtils.blue(c) - warm * 40).toInt().coerceIn(0, 255),
        )
    }

    fun levels(source: Bitmap, black: Float, white: Float, gamma: Float): Bitmap {
        val bl = (black.coerceIn(0f, 0.99f) * 255f)
        val wh = (white.coerceIn(0.01f, 1f) * 255f).coerceAtLeast(bl + 1f)
        val g = gamma.coerceIn(0.1f, 4f)
        val lut = IntArray(256) { i ->
            val norm = ((i - bl) / (wh - bl)).coerceIn(0f, 1f)
            (norm.pow(1f / g) * 255f).toInt().coerceIn(0, 255)
        }
        return mapPixels(source) { c ->
            ColorUtils.argb(
                ColorUtils.alpha(c),
                lut[ColorUtils.red(c)],
                lut[ColorUtils.green(c)],
                lut[ColorUtils.blue(c)],
            )
        }
    }

    private fun Float.pow(exp: Float): Float = Math.pow(toDouble(), exp.toDouble()).toFloat()

    fun curves(source: Bitmap, amount: Float): Bitmap {
        val lut = IntArray(256) { i ->
            val t = i / 255f
            val s = t + amount * 0.35f * kotlin.math.sin(Math.PI.toFloat() * t) * (0.5f - t) * 2f
            (s * 255f).toInt().coerceIn(0, 255)
        }
        return mapPixels(source) { c ->
            ColorUtils.argb(ColorUtils.alpha(c), lut[ColorUtils.red(c)], lut[ColorUtils.green(c)], lut[ColorUtils.blue(c)])
        }
    }

    fun threshold(source: Bitmap, value: Float): Bitmap {
        val t = (value.coerceIn(0f, 1f) * 255f)
        return mapPixels(source) { c ->
            val lum = ColorUtils.red(c) * 0.299f + ColorUtils.green(c) * 0.587f + ColorUtils.blue(c) * 0.114f
            val v = if (lum >= t) 255 else 0
            ColorUtils.argb(ColorUtils.alpha(c), v, v, v)
        }
    }

    fun posterize(source: Bitmap, levels: Int): Bitmap {
        val n = levels.coerceIn(2, 32)
        val step = 255f / (n - 1)
        return mapPixels(source) { c ->
            fun q(v: Int) = ((v / step).toInt() * step).toInt().coerceIn(0, 255)
            ColorUtils.argb(ColorUtils.alpha(c), q(ColorUtils.red(c)), q(ColorUtils.green(c)), q(ColorUtils.blue(c)))
        }
    }

    fun gradientMap(source: Bitmap, shadow: Int, highlight: Int): Bitmap = mapPixels(source) { c ->
        val lum = (ColorUtils.red(c) * 0.299f + ColorUtils.green(c) * 0.587f + ColorUtils.blue(c) * 0.114f) / 255f
        ColorUtils.withAlphaF(ColorUtils.lerp(shadow, highlight, lum), ColorUtils.alpha(c) / 255f)
    }

    fun chromaticAberration(source: Bitmap, shift: Float, amount: Float): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val r = tintChannel(source, 0xFFFF0000.toInt())
        val g = tintChannel(source, 0xFF00FF00.toInt())
        val b = tintChannel(source, 0xFF0000FF.toInt())
        val dx = shift * amount
        canvas.drawBitmap(r, -dx, 0f, Paint().apply { blendMode = BlendMode.PLUS })
        canvas.drawBitmap(g, 0f, 0f, Paint().apply { blendMode = BlendMode.PLUS })
        canvas.drawBitmap(b, dx, 0f, Paint().apply { blendMode = BlendMode.PLUS })
        r.recycle(); g.recycle(); b.recycle()
        return out
    }

    private fun tintChannel(source: Bitmap, mask: Int): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, 0f, 0f, Paint().apply {
            blendMode = BlendMode.SRC_IN
            colorFilter = BlendModeColorFilter(mask, BlendMode.MULTIPLY)
        })
        return out
    }

    // -----------------------------------------------------------------------
    // Distortion
    // -----------------------------------------------------------------------

    fun displacement(source: Bitmap, amount: Float, scale: Float, seed: Long): Bitmap {
        val w = source.width
        val h = source.height
        val src = IntArray(w * h)
        source.getPixels(src, 0, w, 0, 0, w, h)
        val rng = Random(seed)
        val gridW = max(2, (w / scale.coerceAtLeast(2f)).toInt() + 2)
        val gridH = max(2, (h / scale.coerceAtLeast(2f)).toInt() + 2)
        val field = FloatArray(gridW * gridH * 2) { rng.nextFloat() * 2f - 1f }
        fun sampleField(x: Float, y: Float, channel: Int): Float {
            val gx = (x / scale).coerceIn(0f, gridW - 1.001f)
            val gy = (y / scale).coerceIn(0f, gridH - 1.001f)
            val x0 = gx.toInt()
            val y0 = gy.toInt()
            val fx = gx - x0
            val fy = gy - y0
            fun at(cx: Int, cy: Int) = field[((cy.coerceIn(0, gridH - 1) * gridW) + cx.coerceIn(0, gridW - 1)) * 2 + channel]
            val top = at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx
            val bottom = at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx
            return top * (1 - fy) + bottom * fy
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = sampleField(x.toFloat(), y.toFloat(), 0) * amount
                val dy = sampleField(x.toFloat(), y.toFloat(), 1) * amount
                val sx = (x + dx).toInt().coerceIn(0, w - 1)
                val sy = (y + dy).toInt().coerceIn(0, h - 1)
                out[y * w + x] = src[sy * w + sx]
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    fun ripple(source: Bitmap, amplitude: Float, wavelength: Float): Bitmap {
        val w = source.width
        val h = source.height
        val src = IntArray(w * h)
        source.getPixels(src, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val cx = w / 2f
        val cy = h / 2f
        val lambda = wavelength.coerceAtLeast(4f)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val d = sqrt(((x - cx) * (x - cx) + (y - cy) * (y - cy)).toDouble()).toFloat()
                val offset = amplitude * kotlin.math.sin(2 * Math.PI * d / lambda).toFloat()
                val sx = (cx + (x - cx) * (1f + offset / 400f)).toInt().coerceIn(0, w - 1)
                val sy = (cy + (y - cy) * (1f + offset / 400f)).toInt().coerceIn(0, h - 1)
                out[y * w + x] = src[sy * w + sx]
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    fun wave(source: Bitmap, amplitude: Float, wavelength: Float, angleDeg: Float): Bitmap {
        val w = source.width
        val h = source.height
        val src = IntArray(w * h)
        source.getPixels(src, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val rad = Math.toRadians(angleDeg.toDouble())
        val ax = cos(rad).toFloat()
        val ay = sin(rad).toFloat()
        val lambda = wavelength.coerceAtLeast(4f)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val proj = x * ax + y * ay
                val offset = amplitude * kotlin.math.sin(2 * Math.PI * proj / lambda).toFloat()
                val sx = (x - ay * offset).toInt().coerceIn(0, w - 1)
                val sy = (y + ax * offset).toInt().coerceIn(0, h - 1)
                out[y * w + x] = src[sy * w + sx]
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    fun distortion(source: Bitmap, amount: Float, radius: Float, seed: Long): Bitmap =
        displacement(source, amount * 30f, radius, seed)

    // -----------------------------------------------------------------------

    private inline fun mapPixels(source: Bitmap, crossinline block: (Int) -> Int): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) pixels[i] = block(pixels[i])
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Non-destructive preview: applies the effect to a downscaled copy for speed. */
    fun preview(source: Bitmap, params: EffectParams, maxDim: Int = 512): Bitmap {
        val scale = min(1f, maxDim.toFloat() / max(source.width, source.height))
        val small = if (scale < 1f) Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        ) else source
        val scaled = params.copy(radius = params.radius * scale, size = params.size * scale, amount = params.amount)
        val result = apply(small, scaled)
        if (small !== source) small.recycle()
        return result
    }
}
