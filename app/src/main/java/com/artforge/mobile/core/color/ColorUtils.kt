package com.artforge.mobile.core.color

import android.graphics.Color
import com.artforge.mobile.core.model.Argb
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Colour conversions used by the colour studio. All maths is done on 0..1 floats
 * and is independent from Android except for the packed-int helpers, which are
 * unit tested on the JVM.
 */
object ColorUtils {

    fun argb(a: Int, r: Int, g: Int, b: Int): Argb =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun alpha(c: Argb): Int = (c ushr 24) and 0xFF
    fun red(c: Argb): Int = (c ushr 16) and 0xFF
    fun green(c: Argb): Int = (c ushr 8) and 0xFF
    fun blue(c: Argb): Int = c and 0xFF

    fun withAlpha(c: Argb, a: Int): Argb = (c and 0x00FFFFFF) or ((a.coerceIn(0, 255)) shl 24)

    fun withAlphaF(c: Argb, a: Float): Argb = withAlpha(c, (a.coerceIn(0f, 1f) * 255f).roundToInt())

    fun lerp(a: Argb, b: Argb, t: Float): Argb {
        val f = t.coerceIn(0f, 1f)
        return argb(
            (alpha(a) + (alpha(b) - alpha(a)) * f).roundToInt(),
            (red(a) + (red(b) - red(a)) * f).roundToInt(),
            (green(a) + (green(b) - green(a)) * f).roundToInt(),
            (blue(a) + (blue(b) - blue(a)) * f).roundToInt(),
        )
    }

    // ---- HSV -------------------------------------------------------------

    fun toHsv(c: Argb): FloatArray {
        val out = FloatArray(3)
        Color.RGBToHSV(red(c), green(c), blue(c), out)
        return out // h in [0,360), s/v in [0,1]
    }

    fun fromHsv(h: Float, s: Float, v: Float, a: Int = 255): Argb {
        val rgb = Color.HSVToColor(
            a.coerceIn(0, 255),
            floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)),
        )
        return rgb
    }

    // ---- HSL -------------------------------------------------------------

    fun toHsl(c: Argb): FloatArray {
        val r = red(c) / 255f
        val g = green(c) / 255f
        val b = blue(c) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val l = (maxC + minC) / 2f
        val d = maxC - minC
        if (d < 1e-6f) return floatArrayOf(0f, 0f, l)
        val s = if (l > 0.5f) d / (2f - maxC - minC) else d / (maxC + minC)
        val h = when (maxC) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        return floatArrayOf(((h % 360f) + 360f) % 360f, s, l)
    }

    fun fromHsl(h: Float, s: Float, l: Float, a: Int = 255): Argb {
        val hh = ((h % 360f) + 360f) % 360f
        val ss = s.coerceIn(0f, 1f)
        val ll = l.coerceIn(0f, 1f)
        if (ss == 0f) {
            val v = (ll * 255f).roundToInt()
            return argb(a, v, v, v)
        }
        val q = if (ll < 0.5f) ll * (1f + ss) else ll + ss - ll * ss
        val p = 2f * ll - q
        fun hue(t0: Float): Float {
            var t = t0
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 1f / 2f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
        val hh2 = hh / 360f
        return argb(
            a,
            (hue(hh2 + 1f / 3f) * 255f).roundToInt(),
            (hue(hh2) * 255f).roundToInt(),
            (hue(hh2 - 1f / 3f) * 255f).roundToInt(),
        )
    }

    // ---- CMYK ------------------------------------------------------------

    fun toCmyk(c: Argb): FloatArray {
        val r = red(c) / 255f
        val g = green(c) / 255f
        val b = blue(c) / 255f
        val k = 1f - max(r, max(g, b))
        if (k >= 1f) return floatArrayOf(0f, 0f, 0f, 1f)
        val d = 1f - k
        return floatArrayOf((1f - r - k) / d, (1f - g - k) / d, (1f - b - k) / d, k)
    }

    fun fromCmyk(cm: Float, y: Float, k2: Float, k: Float, a: Int = 255): Argb {
        val kk = k.coerceIn(0f, 1f)
        val r = (255f * (1f - cm.coerceIn(0f, 1f)) * (1f - kk)).roundToInt()
        val g = (255f * (1f - y.coerceIn(0f, 1f)) * (1f - kk)).roundToInt()
        val b = (255f * (1f - k2.coerceIn(0f, 1f)) * (1f - kk)).roundToInt()
        return argb(a, r, g, b)
    }

    // ---- Hex -------------------------------------------------------------

    fun toHex(c: Argb, includeAlpha: Boolean = false): String =
        if (includeAlpha) String.format("#%08X", c) else String.format("#%06X", c and 0xFFFFFF)

    fun parseHex(input: String): Argb? {
        val s = input.trim().removePrefix("#")
        return try {
            when (s.length) {
                3 -> {
                    val r = s[0].digitToInt(16) * 17
                    val g = s[1].digitToInt(16) * 17
                    val b = s[2].digitToInt(16) * 17
                    argb(255, r, g, b)
                }
                6 -> (0xFF000000L or s.toLong(16)).toInt()
                8 -> s.toLong(16).toInt()
                else -> null
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    // ---- Harmonies -------------------------------------------------------

    fun complementary(base: Argb): List<Argb> {
        val hsv = toHsv(base)
        return listOf(base, fromHsv(hsv[0] + 180f, hsv[1], hsv[2], alpha(base)))
    }

    fun analogous(base: Argb, step: Float = 30f): List<Argb> {
        val hsv = toHsv(base)
        return listOf(-step, 0f, step).map { fromHsv(hsv[0] + it, hsv[1], hsv[2], alpha(base)) }
    }

    fun triadic(base: Argb): List<Argb> {
        val hsv = toHsv(base)
        return listOf(0f, 120f, 240f).map { fromHsv(hsv[0] + it, hsv[1], hsv[2], alpha(base)) }
    }

    fun splitComplementary(base: Argb): List<Argb> {
        val hsv = toHsv(base)
        return listOf(0f, 150f, 210f).map { fromHsv(hsv[0] + it, hsv[1], hsv[2], alpha(base)) }
    }

    fun tetradic(base: Argb): List<Argb> {
        val hsv = toHsv(base)
        return listOf(0f, 90f, 180f, 270f).map { fromHsv(hsv[0] + it, hsv[1], hsv[2], alpha(base)) }
    }

    /** Perceptual luminance in 0..1 (sRGB weights). */
    fun luminance(c: Argb): Float =
        (0.2126f * srgbToLinear(red(c) / 255f) +
            0.7152f * srgbToLinear(green(c) / 255f) +
            0.0722f * srgbToLinear(blue(c) / 255f))

    fun srgbToLinear(v: Float): Float = if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055) / 1.055).toDouble(), 2.4).toFloat()

    fun linearToSrgb(v: Float): Float = if (v <= 0.0031308f) v * 12.92f else (1.055 * Math.pow(v.toDouble(), 1.0 / 2.4) - 0.055).toFloat()

    fun contrastRatio(a: Argb, b: Argb): Float {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = max(la, lb)
        val lo = min(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    fun isDark(c: Argb): Boolean = luminance(c) < 0.4f

    /**
     * RYB-style "paint" mixing used by the colour mixer: mixes through a
     * subtractive approximation so blue + yellow gives green rather than grey.
     */
    fun mixPigment(a: Argb, b: Argb, t: Float): Argb {
        val f = t.coerceIn(0f, 1f)
        val air = 1f - red(a) / 255f
        val aig = 1f - green(a) / 255f
        val aib = 1f - blue(a) / 255f
        val bir = 1f - red(b) / 255f
        val big = 1f - green(b) / 255f
        val bib = 1f - blue(b) / 255f
        val rr = (air * (1f - f) + bir * f)
        val gg = (aig * (1f - f) + big * f)
        val bb = (aib * (1f - f) + bib * f)
        // Widen the yellow/blue crossover so the result stays saturated.
        val outR = (1f - rr * 0.92f - abs(gg - bb) * 0.04f).coerceIn(0f, 1f)
        val outG = (1f - gg * 0.92f).coerceIn(0f, 1f)
        val outB = (1f - bb * 0.92f - abs(rr - gg) * 0.04f).coerceIn(0f, 1f)
        return argb(
            (alpha(a) * (1f - f) + alpha(b) * f).roundToInt(),
            (outR * 255f).roundToInt(),
            (outG * 255f).roundToInt(),
            (outB * 255f).roundToInt(),
        )
    }
}
