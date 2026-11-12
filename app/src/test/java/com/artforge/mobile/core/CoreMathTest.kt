package com.artforge.mobile.core

import com.artforge.mobile.core.color.ColorUtils
import com.artforge.mobile.core.math.Geometry
import com.artforge.mobile.core.math.Interp
import com.artforge.mobile.core.math.Mat3
import com.artforge.mobile.core.math.StrokeSmoother
import com.artforge.mobile.core.model.Pt
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Colour maths: conversions, hex round-trips, harmonies and contrast. */
@RunWith(AndroidJUnit4::class)
class ColorUtilsTest {

    @Test
    fun `argb packing round trips`() {
        val c = ColorUtils.argb(255, 18, 52, 86)
        assertEquals(255, ColorUtils.alpha(c))
        assertEquals(18, ColorUtils.red(c))
        assertEquals(52, ColorUtils.green(c))
        assertEquals(86, ColorUtils.blue(c))
    }

    @Test
    fun `hex parsing accepts 3 6 and 8 digit forms`() {
        assertEquals(ColorUtils.argb(255, 0x11, 0x22, 0x33), ColorUtils.parseHex("#112233"))
        assertEquals(ColorUtils.argb(255, 255, 0, 0), ColorUtils.parseHex("#f00"))
        assertEquals(ColorUtils.argb(128, 255, 0, 0), ColorUtils.parseHex("#80FF0000"))
        assertNull(ColorUtils.parseHex("nope"))
        assertNull(ColorUtils.parseHex("#12345"))
    }

    @Test
    fun `hex output matches input`() {
        val c = ColorUtils.argb(255, 0x6C, 0x8C, 0xFF)
        assertEquals("#6C8CFF", ColorUtils.toHex(c))
        assertEquals(c, ColorUtils.parseHex(ColorUtils.toHex(c)))
    }

    @Test
    fun `hsv round trip is stable`() {
        val colors = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF808080.toInt())
        for (c in colors) {
            val hsv = ColorUtils.toHsv(c)
            val back = ColorUtils.fromHsv(hsv[0], hsv[1], hsv[2])
            assertEquals(c, back)
        }
    }

    @Test
    fun `hsl round trip is stable`() {
        val c = ColorUtils.argb(255, 120, 40, 200)
        val hsl = ColorUtils.toHsl(c)
        val back = ColorUtils.fromHsl(hsl[0], hsl[1], hsl[2])
        assertTrue(Math.abs(ColorUtils.red(back) - ColorUtils.red(c)) <= 1)
        assertTrue(Math.abs(ColorUtils.green(back) - ColorUtils.green(c)) <= 1)
        assertTrue(Math.abs(ColorUtils.blue(back) - ColorUtils.blue(c)) <= 1)
    }

    @Test
    fun `cmyk round trip`() {
        val c = ColorUtils.argb(255, 30, 140, 210)
        val cmyk = ColorUtils.toCmyk(c)
        val back = ColorUtils.fromCmyk(cmyk[0], cmyk[1], cmyk[2], cmyk[3])
        assertTrue(Math.abs(ColorUtils.red(back) - ColorUtils.red(c)) <= 2)
    }

    @Test
    fun `complementary hue is 180 degrees away`() {
        val base = ColorUtils.fromHsv(30f, 1f, 1f)
        val comp = ColorUtils.complementary(base)[1]
        val hue = ColorUtils.toHsv(comp)[0]
        assertTrue(kotlin.math.abs(hue - 210f) < 2f)
    }

    @Test
    fun `triadic returns three distinct hues`() {
        val base = ColorUtils.fromHsv(10f, 0.8f, 0.9f)
        val tri = ColorUtils.triadic(base)
        assertEquals(3, tri.distinct().size)
    }

    @Test
    fun `black and white have maximum contrast`() {
        val ratio = ColorUtils.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertTrue(ratio > 20f)
    }

    @Test
    fun `pigment mixing of blue and yellow yields green-ish`() {
        val mixed = ColorUtils.mixPigment(0xFF1040C0.toInt(), 0xFFF0D000.toInt(), 0.5f)
        assertTrue("green should dominate: ${ColorUtils.toHex(mixed)}", ColorUtils.green(mixed) >= ColorUtils.red(mixed))
    }

    @Test
    fun `alpha blending respects opacity`() {
        val half = ColorUtils.withAlphaF(0xFF000000.toInt(), 0.5f)
        assertEquals(128, ColorUtils.alpha(half))
        val lerped = ColorUtils.lerp(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f)
        assertEquals(128, ColorUtils.red(lerped))
    }
}

/** Interpolation, smoothing and resampling used by the stroke engine. */
class MathTest {

    @Test
    fun `catmull rom passes through control points`() {
        assertEquals(5f, Interp.catmullRom(0f, 5f, 10f, 15f, 0f), 0.001f)
        assertEquals(10f, Interp.catmullRom(0f, 5f, 10f, 15f, 1f), 0.001f)
    }

    @Test
    fun `easing functions stay in range and are monotonic`() {
        var previous = -1f
        for (i in 0..20) {
            val t = i / 20f
            val v = Interp.easeInOut(t)
            assertTrue(v in 0f..1f)
            assertTrue(v >= previous)
            previous = v
        }
        assertEquals(0f, Interp.easeIn(0f), 0.0001f)
        assertEquals(1f, Interp.easeOut(1f), 0.0001f)
    }

    @Test
    fun `resample produces evenly spaced points`() {
        val points = listOf(Pt(0f, 0f), Pt(100f, 0f), Pt(200f, 0f))
        val resampled = StrokeSmoother.resample(points, 10f)
        assertTrue(resampled.size > 15)
        for (i in 0 until resampled.size - 1) {
            val d = resampled[i].distanceTo(resampled[i + 1])
            assertTrue("spacing $d should be near 10", d in 1f..11f)
        }
    }

    @Test
    fun `stabiliser keeps the final point exact`() {
        val points = listOf(Pt(0f, 0f), Pt(10f, 10f), Pt(40f, 2f), Pt(90f, 60f))
        val stabilized = StrokeSmoother.stabilize(points, 0.8f)
        assertEquals(points.size, stabilized.size)
        assertEquals(points.last(), stabilized.last())
        assertNotEquals(points[1], stabilized[1])
    }

    @Test
    fun `stabiliser with zero strength is identity`() {
        val points = listOf(Pt(1f, 2f), Pt(3f, 4f))
        assertEquals(points, StrokeSmoother.stabilize(points, 0f))
    }

    @Test
    fun `affine matrix composes and inverts`() {
        val m = Mat3.translate(10f, 20f).times(Mat3.scale(2f, 3f))
        val p = m.map(1f, 1f)
        assertEquals(12f, p.x, 0.001f)
        assertEquals(23f, p.y, 0.001f)
        val back = m.invert().map(p.x, p.y)
        assertEquals(1f, back.x, 0.001f)
        assertEquals(1f, back.y, 0.001f)
    }

    @Test
    fun `rotation around a pivot keeps the pivot fixed`() {
        val m = Mat3.rotateAround(90f, 100f, 100f)
        val p = m.map(100f, 100f)
        assertEquals(100f, p.x, 0.01f)
        assertEquals(100f, p.y, 0.01f)
    }

    @Test
    fun `point in polygon handles concave shapes`() {
        val lShape = listOf(
            Pt(0f, 0f), Pt(50f, 0f), Pt(50f, 20f), Pt(20f, 20f), Pt(20f, 50f), Pt(0f, 50f),
        )
        assertTrue(Geometry.pointInPolygon(10f, 10f, lShape))
        assertTrue(Geometry.pointInPolygon(40f, 10f, lShape))
        assertTrue(!Geometry.pointInPolygon(40f, 40f, lShape))
    }

    @Test
    fun `polygon area matches a known square`() {
        val square = listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(10f, 10f), Pt(0f, 10f))
        assertEquals(100f, Geometry.polygonArea(square), 0.01f)
    }

    @Test
    fun `point to segment distance is perpendicular`() {
        val d = Geometry.pointToSegmentDistance(5f, 8f, 0f, 0f, 10f, 0f)
        assertEquals(8f, d, 0.001f)
        val beyond = Geometry.pointToSegmentDistance(20f, 0f, 0f, 0f, 10f, 0f)
        assertEquals(10f, beyond, 0.001f)
    }

    @Test
    fun `regular polygon has the requested number of vertices`() {
        assertEquals(6, Geometry.regularPolygon(0f, 0f, 10f, 10f, 6).size)
        assertEquals(10, Geometry.star(0f, 0f, 10f, 10f, 5, 0.5f).size)
    }

    @Test
    fun `snap rounds to the nearest step`() {
        assertEquals(30f, Geometry.snap(28f, 10f), 0.001f)
        assertEquals(0f, Geometry.snap(4f, 10f), 0.001f)
        assertEquals(7f, Geometry.snap(7f, 0f), 0.001f)
    }
}
