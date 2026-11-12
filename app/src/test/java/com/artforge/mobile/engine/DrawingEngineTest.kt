package com.artforge.mobile.engine

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artforge.mobile.core.color.ColorUtils
import com.artforge.mobile.core.model.BrushSettings
import com.artforge.mobile.core.model.DabShape
import com.artforge.mobile.core.model.Pt
import com.artforge.mobile.core.model.ToolId
import com.artforge.mobile.engine.draw.DrawingEngine
import com.artforge.mobile.engine.draw.FrameStore
import com.artforge.mobile.engine.draw.StrokePoint
import com.artforge.mobile.engine.draw.SymmetryBuilder
import com.artforge.mobile.engine.draw.UndoManager
import com.artforge.mobile.core.model.SymmetryMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Robolectric tests for the real drawing engine: strokes actually change pixels,
 * pressure changes stroke width, symmetry mirrors, fills stay inside regions and
 * undo restores exact pixels.
 */
@RunWith(AndroidJUnit4::class)
class DrawingEngineTest {

    private lateinit var store: FrameStore
    private lateinit var engine: DrawingEngine
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "frames_test")
        tempDir.deleteRecursively()
        store = FrameStore(tempDir, maxCacheBytes = 8L * 1024 * 1024)
        engine = DrawingEngine(store)
    }

    @After
    fun tearDown() {
        engine.cancelStroke()
        store.release()
        tempDir.deleteRecursively()
    }

    private fun blank(w: Int = 200, h: Int = 200): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    private fun countOpaque(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { ColorUtils.alpha(it) > 8 }
    }

    private fun stroke(
        target: Bitmap,
        from: Pt,
        to: Pt,
        // A fully hard brush keeps the test deterministic: soft brushes rely on
        // BlurMaskFilter, which is a rendering detail rather than stroke geometry.
        settings: BrushSettings = BrushSettings(size = 12f, spacing = 0.1f, hardness = 1f),
        color: Int = Color.BLACK,
        tool: ToolId = ToolId.Brush,
        steps: Int = 12,
    ) {
        engine.beginStroke(target, tool, color, settings, StrokePoint(from.x, from.y, 1f), seed = 42L)
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            engine.moveTo(StrokePoint(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t, 1f))
        }
        engine.endStroke()
    }

    @Test
    fun `a stroke paints pixels along its path`() {
        val bitmap = blank()
        stroke(bitmap, Pt(20f, 100f), Pt(180f, 100f))
        assertTrue("stroke should paint", countOpaque(bitmap) > 500)
        assertTrue(ColorUtils.alpha(bitmap.getPixel(100, 100)) > 200)
        assertEquals(0, ColorUtils.alpha(bitmap.getPixel(100, 20)))
    }

    @Test
    fun `pressure changes the stroke width`() {
        val thin = blank()
        val thick = blank()
        val settings = BrushSettings(size = 24f, pressureSize = 1f, spacing = 0.1f, hardness = 1f)

        engine.beginStroke(thin, ToolId.Brush, Color.BLACK, settings, StrokePoint(20f, 100f, 0.1f))
        for (i in 1..10) engine.moveTo(StrokePoint(20f + i * 16f, 100f, 0.1f))
        engine.endStroke()
        assertTrue("light pressure must still paint (${countOpaque(thin)})", countOpaque(thin) > 200)

        engine.beginStroke(thick, ToolId.Brush, Color.BLACK, settings, StrokePoint(20f, 100f, 1f))
        for (i in 1..10) engine.moveTo(StrokePoint(20f + i * 16f, 100f, 1f))
        engine.endStroke()

        val thinCount = countOpaque(thin)
        val thickCount = countOpaque(thick)
        assertTrue("full pressure must paint (${countOpaque(thick)})", thickCount > 200)
        assertTrue("thick ($thickCount) should cover more than thin ($thinCount)", thickCount > thinCount * 2)
    }

    @Test
    fun `eraser removes pixels`() {
        val bitmap = blank()
        stroke(bitmap, Pt(20f, 100f), Pt(180f, 100f), color = Color.RED)
        val before = countOpaque(bitmap)
        stroke(
            bitmap, Pt(20f, 100f), Pt(180f, 100f),
            settings = BrushSettings(size = 40f, hardness = 1f),
            color = Color.BLACK,
            tool = ToolId.Eraser,
        )
        val after = countOpaque(bitmap)
        assertTrue("eraser must reduce coverage ($before -> $after)", after < before)
    }

    @Test
    fun `symmetry mirrors strokes across both axes`() {
        val bitmap = blank()
        val symmetry = SymmetryBuilder(SymmetryMode.Both, axisX = 100f, axisY = 100f)
        engine.beginStroke(
            bitmap, ToolId.Brush, Color.BLACK,
            BrushSettings(size = 10f, spacing = 0.1f),
            StrokePoint(30f, 30f, 1f), seed = 7L,
        )
        engine.moveTo(StrokePoint(50f, 40f, 1f))
        val bounds = engine.endStroke()
        engine.renderShape(
            bitmap, ToolId.Rectangle, Pt(20f, 20f), Pt(60f, 60f),
            Color.BLACK, 2f, false, symmetry = symmetry,
        )
        // Both mirrored quadrants must contain painted pixels.
        assertTrue(ColorUtils.alpha(bitmap.getPixel(30, 30)) > 0)
        assertTrue(ColorUtils.alpha(bitmap.getPixel(170, 30)) > 0)
        assertTrue(ColorUtils.alpha(bitmap.getPixel(30, 170)) > 0)
        assertTrue(ColorUtils.alpha(bitmap.getPixel(170, 170)) > 0)
        assertNotNull(bounds)
    }

    @Test
    fun `flood fill stops at a drawn boundary`() {
        val bitmap = blank(120, 120)
        engine.renderShape(bitmap, ToolId.Rectangle, Pt(20f, 20f), Pt(100f, 100f), Color.BLACK, 4f, false)
        val filled = engine.floodFill(bitmap, 5, 5, Color.RED, tolerance = 8)
        assertNotNull(filled)
        assertEquals(Color.RED, bitmap.getPixel(5, 5))
        assertEquals(Color.BLACK, bitmap.getPixel(60, 20))
        // The interior must remain untouched because the boundary blocks the fill.
        assertEquals(0, ColorUtils.alpha(bitmap.getPixel(60, 60)))
    }

    @Test
    fun `shapes render at the expected location`() {
        val bitmap = blank()
        engine.renderShape(bitmap, ToolId.Ellipse, Pt(50f, 50f), Pt(150f, 150f), Color.BLUE, 3f, true)
        assertTrue(ColorUtils.alpha(bitmap.getPixel(100, 100)) > 200)
        assertEquals(0, ColorUtils.alpha(bitmap.getPixel(10, 10)))
    }

    @Test
    fun `gradient fills linearly between endpoints`() {
        val bitmap = blank(100, 20)
        engine.drawGradient(
            bitmap,
            com.artforge.mobile.core.model.GradientSpec(
                stops = listOf(
                    com.artforge.mobile.core.model.GradientStop(0f, 0xFF000000.toInt()),
                    com.artforge.mobile.core.model.GradientStop(1f, 0xFFFFFFFF.toInt()),
                ),
            ),
            Pt(0f, 10f), Pt(100f, 10f),
        )
        val left = ColorUtils.red(bitmap.getPixel(2, 10))
        val right = ColorUtils.red(bitmap.getPixel(97, 10))
        assertTrue("gradient should brighten left→right ($left → $right)", right > left + 60)
    }

    @Test
    fun `square dab shape stays crisp`() {
        val bitmap = blank(120, 120)
        stroke(
            bitmap, Pt(30f, 60f), Pt(90f, 60f),
            settings = BrushSettings(size = 16f, hardness = 1f, shape = DabShape.Square, spacing = 0.5f),
        )
        assertTrue(countOpaque(bitmap) > 200)
    }

    @Test
    fun `undo patch restores exact pixels`() {
        val bitmap = blank()
        val undo = UndoManager()
        val patch = undo.captureRegion("test", "layer", "frame", bitmap, com.artforge.mobile.core.model.RectF2(0f, 0f, 200f, 200f))
        stroke(bitmap, Pt(20f, 20f), Pt(180f, 180f))
        assertTrue(countOpaque(bitmap) > 0)
        patch.applyTo(bitmap)
        assertEquals(0, countOpaque(bitmap))
        assertTrue(undo.canUndo || undo.undoDepth() == 0)
    }

    @Test
    fun `undo stack respects capacity and redo invalidation`() {
        val undo = UndoManager(maxSteps = 3)
        val bitmap = blank(10, 10)
        repeat(5) { i ->
            undo.push(undo.captureRegion("s$i", "l", "f", bitmap, com.artforge.mobile.core.model.RectF2(0f, 0f, 10f, 10f)))
        }
        assertEquals(3, undo.undoDepth())
        val popped = undo.popUndo()
        assertNotNull(popped)
        assertTrue(undo.canRedo)
        undo.push(undo.captureRegion("new", "l", "f", bitmap, com.artforge.mobile.core.model.RectF2(0f, 0f, 10f, 10f)))
        assertFalse(undo.canRedo)
    }

    @Test
    fun `frame store persists and reloads drawings`() {
        val projectId = "proj"
        val frameId = "f1"
        store.registerFrame(projectId, frameId)
        val bitmap = store.createBlankFrame(projectId, frameId, 64, 64)
        stroke(bitmap, Pt(10f, 10f), Pt(50f, 50f))
        val painted = countOpaque(bitmap)
        store.flushFrame(projectId, frameId)
        store.release()

        val reloaded = store.load(projectId, frameId, 64, 64)
        assertEquals(painted, countOpaque(reloaded))
    }

    @Test
    fun `duplicating a frame copies pixels independently`() {
        val projectId = "proj2"
        store.registerFrame(projectId, "src")
        val source = store.createBlankFrame(projectId, "src", 48, 48)
        stroke(source, Pt(5f, 5f), Pt(40f, 40f))
        store.registerFrame(projectId, "dst")
        store.duplicateFrame(projectId, "src", "dst", 48, 48)
        val copy = store.load(projectId, "dst", 48, 48)
        assertEquals(countOpaque(source), countOpaque(copy))
        engine.beginStroke(copy, ToolId.Eraser, Color.BLACK, BrushSettings(size = 30f), StrokePoint(20f, 20f, 1f))
        engine.moveTo(StrokePoint(40f, 40f, 1f))
        engine.endStroke()
        assertTrue("original must be unchanged", countOpaque(source) > countOpaque(copy))
    }

    @Test
    fun `selection masks combine and feather`() {
        val rect = com.artforge.mobile.engine.draw.SelectionEngine.fromRect(
            50, 50, com.artforge.mobile.core.model.RectF2(10f, 10f, 30f, 30f),
        )
        val ellipse = com.artforge.mobile.engine.draw.SelectionEngine.fromEllipse(
            50, 50, com.artforge.mobile.core.model.RectF2(20f, 20f, 40f, 40f),
        )
        val union = com.artforge.mobile.engine.draw.SelectionEngine.combine(
            rect, ellipse, com.artforge.mobile.engine.draw.SelectionOp.Add,
        ) ?: rect
        assertTrue("union ($union) should grow", union.countSelected() > rect.countSelected())
        val inverted = rect.invert()
        assertEquals(50 * 50 - rect.countSelected(), inverted.countSelected())
        val feathered = com.artforge.mobile.engine.draw.SelectionEngine.feather(rect, 3)
        assertTrue(feathered.countSelected() >= rect.countSelected())
    }

    @Test
    fun `magic wand selects a contiguous region`() {
        val bitmap = blank(60, 60)
        engine.renderShape(bitmap, ToolId.Rectangle, Pt(0f, 0f), Pt(59f, 29f), Color.BLACK, 1f, true)
        val mask = com.artforge.mobile.engine.draw.SelectionEngine.magicWand(
            60, 60, bitmap, 30, 10, tolerance = 10, contiguous = true,
        )
        // Upper half painted black, lower half transparent: the wand picks the top region only.
        val covered = mask.countSelected()
        assertTrue("wand should select something ($covered)", covered > 100)
        assertTrue("wand must not select the whole canvas ($covered)", covered < 60 * 60)
    }
}
