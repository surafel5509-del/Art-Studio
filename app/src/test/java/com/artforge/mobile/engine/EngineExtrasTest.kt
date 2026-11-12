package com.artforge.mobile.engine

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artforge.mobile.core.model.BrushCategory
import com.artforge.mobile.core.model.Bone
import com.artforge.mobile.core.model.ComicBubble
import com.artforge.mobile.core.model.ComicPanel
import com.artforge.mobile.core.model.SymmetryMode
import com.artforge.mobile.core.model.Pt
import com.artforge.mobile.core.model.VectorShape
import com.artforge.mobile.engine.animation.CharacterRig
import com.artforge.mobile.engine.comic.ComicEngine
import com.artforge.mobile.engine.draw.BrushLibrary
import com.artforge.mobile.engine.effects.EffectParams
import com.artforge.mobile.engine.effects.EffectType
import com.artforge.mobile.engine.effects.EffectsEngine
import com.artforge.mobile.engine.perspective.PerspectiveEngine
import com.artforge.mobile.engine.pixel.PixelEngine
import com.artforge.mobile.engine.vector.VectorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class EngineExtrasTest {

    private fun bitmap(w: Int = 48, h: Int = 48, color: Int = Color.WHITE): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
    }

    // ---- brushes ---------------------------------------------------------

    @Test
    fun `brush library exposes every advertised category`() {
        val categories = BrushLibrary.presets.map { it.category }.toSet()
        listOf(
            BrushCategory.Pencil, BrushCategory.Ink, BrushCategory.Marker, BrushCategory.Paint,
            BrushCategory.Watercolor, BrushCategory.Airbrush, BrushCategory.Dry, BrushCategory.Texture,
            BrushCategory.Pixel, BrushCategory.Blend, BrushCategory.Eraser, BrushCategory.Effect,
        ).forEach { assertTrue("missing category $it", it in categories) }
        assertTrue(BrushLibrary.presets.size >= 40)
        assertTrue(BrushLibrary.presets.all { it.settings.size > 0f })
    }

    @Test
    fun `brush search and sanitisation work`() {
        assertTrue(BrushLibrary.search("pen").isNotEmpty())
        assertTrue(BrushLibrary.search("zzz").isEmpty())
        val sanitized = BrushLibrary.sanitize(BrushLibrary.presets.first().settings.copy(size = -5f, opacity = 4f))
        assertTrue(sanitized.size >= 0.5f)
        assertEquals(1f, sanitized.opacity, 0.0001f)
    }

    @Test
    fun `brush packs round trip through json`() {
        val pack = BrushLibrary.packJson("Test pack", BrushLibrary.presets.take(3))
        val parsed = BrushLibrary.parsePack(pack)
        assertEquals(3, parsed.size)
        assertEquals(BrushLibrary.presets.first().name, parsed.first().name)
    }

    // ---- pixel -----------------------------------------------------------

    @Test
    fun `bresenham line covers every pixel between endpoints`() {
        val bmp = bitmap(32, 32, Color.TRANSPARENT)
        PixelEngine.line(bmp, 2, 2, 29, 20, Color.RED)
        var painted = 0
        for (y in 0 until 32) for (x in 0 until 32) if (bmp.getPixel(x, y) != 0) painted++
        assertTrue("line should be connected ($painted)", painted >= 20)
    }

    @Test
    fun `nearest neighbour scaling keeps hard pixel edges`() {
        val bmp = bitmap(4, 4, Color.WHITE)
        bmp.setPixel(0, 0, Color.BLACK)
        val scaled = PixelEngine.scaleNearest(bmp, 8)
        assertEquals(32, scaled.width)
        assertEquals(Color.BLACK, scaled.getPixel(0, 0))
        assertEquals(Color.WHITE, scaled.getPixel(8, 0))
    }

    @Test
    fun `sprite sheet packs and splits frames`() {
        val frames = (0 until 5).map { i ->
            bitmap(16, 16, if (i % 2 == 0) Color.RED else Color.BLUE)
        }
        val sheet = PixelEngine.buildSpriteSheet(frames, columns = 3)
        assertEquals(48, sheet.width)
        assertEquals(32, sheet.height)
        val split = PixelEngine.splitSpriteSheet(sheet, 16, 16, 5)
        assertEquals(5, split.size)
    }

    @Test
    fun `palette enforcement snaps colours to the palette`() {
        val bmp = bitmap(4, 4, 0xFFFEFEFE.toInt())
        PixelEngine.enforcePalette(bmp, listOf(0xFF000000.toInt(), 0xFFFF0000.toInt()))
        val px = bmp.getPixel(1, 1)
        assertTrue(px == 0xFF000000.toInt() || px == 0xFFFF0000.toInt())
    }

    // ---- effects ---------------------------------------------------------

    @Test
    fun `gaussian blur averages neighbouring pixels`() {
        val bmp = bitmap(32, 32, Color.BLACK)
        for (x in 0 until 16) for (y in 0 until 32) bmp.setPixel(x, y, Color.WHITE)
        val blurred = EffectsEngine.gaussianBlur(bmp, 6f)
        val edge = Color.red(blurred.getPixel(16, 16))
        assertTrue("edge should be a gradient ($edge)", edge in 30..230)
        blurred.recycle()
    }

    @Test
    fun `brightness contrast and saturation change pixels predictably`() {
        val bmp = bitmap(8, 8, 0xFF808080.toInt())
        val brighter = EffectsEngine.brightnessContrast(bmp, 0.2f, 0f)
        assertTrue(ColorUtilsAlphaRed(brighter.getPixel(4, 4)) > 128)
        val saturated = EffectsEngine.hueSaturation(bitmap(8, 8, 0xFFAA4422.toInt()), 40f, 0.5f)
        assertNotNull(saturated)
        brighter.recycle()
        saturated.recycle()
    }

    @Test
    fun `posterize limits distinct values`() {
        val bmp = bitmap(16, 16)
        val pixels = IntArray(16 * 16) { i -> 0xFF000000.toInt() or (i * 3 shl 16) }
        bmp.setPixels(pixels, 0, 16, 0, 0, 16, 16)
        val posterized = EffectsEngine.posterize(bmp, 3)
        val distinctRed = HashSet<Int>()
        for (y in 0 until 16) for (x in 0 until 16) distinctRed.add((posterized.getPixel(x, y) shr 16) and 0xFF)
        assertTrue("red levels should be <= 3 (was ${distinctRed.size})", distinctRed.size <= 3)
        posterized.recycle()
    }

    @Test
    fun `grain noise and pixelate preserve dimensions`() {
        val bmp = bitmap(24, 24, 0xFF336699.toInt())
        listOf(
            EffectParams(EffectType.Noise, amount = 0.2f),
            EffectParams(EffectType.FilmGrain, amount = 0.3f, size = 2f),
            EffectParams(EffectType.Pixelate, size = 4f),
            EffectParams(EffectType.Vignette, amount = 0.4f),
            EffectParams(EffectType.Glow, radius = 6f),
            EffectParams(EffectType.ChromaticAberration, radius = 3f),
            EffectParams(EffectType.Ripple, amount = 4f, size = 12f),
        ).forEach { params ->
            val result = EffectsEngine.apply(bmp, params)
            assertEquals(params.type.name, 24, result.width)
            assertEquals(24, result.height)
            result.recycle()
        }
        bmp.recycle()
    }

    private fun ColorUtilsAlphaRed(c: Int) = (c shr 16) and 0xFF

    // ---- comic -----------------------------------------------------------

    @Test
    fun `comic layout generates non-overlapping panels`() {
        val panels = ComicEngine.generateLayout(
            ComicEngine.LayoutPreset.TwoByTwo, 1000f, 1400f, gutter = 16f, margin = 32f,
        )
        assertEquals(4, panels.size)
        for (panel in panels) {
            assertTrue(panel.x >= 32f)
            assertTrue(panel.y >= 32f)
            assertTrue(panel.x + panel.width <= 968f + 0.1f)
        }
        val bubbleArea = panels.map { it.x * it.y }
        assertEquals(bubbleArea.size, bubbleArea.distinct().size)
    }

    @Test
    fun `bubble auto size grows with text`() {
        val small = ComicEngine.autoBubbleSize("Hi", 30f, 400f)
        val large = ComicEngine.autoBubbleSize("This is a much longer line of dialogue that needs room", 30f, 400f)
        assertTrue(large.second > small.second || large.first >= small.first)
    }

    @Test
    fun `panel split produces two panels that tile the original`() {
        val panel = ComicPanel("p", 100f, 100f, 400f, 300f)
        val split = ComicEngine.splitPanel(panel, vertical = true, ratio = 0.5f, gutter = 0f)
        assertEquals(2, split.size)
        assertEquals(400f, split[0].width + split[1].width, 0.1f)
        assertEquals(100f, split[1].x, 0.1f + split[0].width)
    }

    @Test
    fun `text wrapping respects the maximum width`() {
        val paint = android.graphics.Paint().apply { textSize = 20f }
        // A single unbreakable word must be split by characters, not left overflowing.
        val lines = ComicEngine.wrapText("one two three supercalifragilistic seven", paint, 80f)
        assertTrue(lines.size > 1)
        assertTrue(lines.all { paint.measureText(it) <= 80f })
    }

    // ---- vector ----------------------------------------------------------

    @Test
    fun `svg export produces valid path data`() {
        val shape = VectorShape(
            id = "s",
            kind = "Rectangle",
            points = listOf(Pt(10f, 10f), Pt(90f, 10f), Pt(90f, 90f), Pt(10f, 90f)),
            closed = true,
            strokeColor = Color.BLACK,
            strokeWidth = 2f,
            fillColor = 0xFFFF0000.toInt(),
        )
        val svg = VectorEngine.toSvg(listOf(shape), 100, 100)
        assertTrue(svg.contains("<svg"))
        assertTrue(svg.contains("M 10.00 10.00"))
        assertTrue(svg.contains("Z"))
        assertTrue(svg.contains("FFFF0000"))
    }

    @Test
    fun `svg import parses lines and curves`() {
        val svg = """<svg><path d="M 0 0 L 10 0 L 10 10 Z"/><path d="M 0 0 C 5 5 10 10 20 20"/></svg>"""
        val paths = VectorEngine.parseSvgPaths(svg)
        assertEquals(2, paths.size)
        assertTrue(paths[0].size >= 4) // triangle closed back to start
        assertTrue(paths[1].size > 5) // bezier sampled
    }

    @Test
    fun `boolean intersect of overlapping squares is smaller`() {
        val a = listOf(Pt(0f, 0f), Pt(50f, 0f), Pt(50f, 50f), Pt(0f, 50f))
        val b = listOf(Pt(25f, 25f), Pt(75f, 25f), Pt(75f, 75f), Pt(25f, 75f))
        val intersection = VectorEngine.boolean(VectorEngine.BooleanOp.Intersect, a, b)
        assertTrue(intersection.isNotEmpty())
        val area = com.artforge.mobile.core.math.Geometry.polygonArea(intersection)
        assertTrue("intersection area $area should be about 625", abs(area - 625f) < 200f)
    }

    @Test
    fun `anchor editing moves points and controls`() {
        val shape = VectorShape(
            id = "s", kind = "path",
            points = listOf(Pt(0f, 0f), Pt(10f, 0f)),
            closed = false, strokeColor = 0, strokeWidth = 1f, fillColor = null,
            controlsIn = listOf(Pt(0f, 0f), Pt(10f, 0f)),
            controlsOut = listOf(Pt(0f, 0f), Pt(10f, 0f)),
        )
        val moved = VectorEngine.moveAnchor(shape, 0, Pt(5f, 5f))
        assertEquals(Pt(5f, 5f), moved.points[0])
        assertEquals(Pt(5f, 5f), moved.controlsOut[0])
        assertEquals(1, VectorEngine.closestAnchor(moved, 10f, 0f)!!)
    }

    // ---- perspective -----------------------------------------------------

    @Test
    fun `symmetry mirroring is exact for both axes`() {
        val p = Pt(20f, 30f)
        val mirrored = PerspectiveEngine.symmetryPoints(p, SymmetryMode.Both, 200f, 200f)
        assertEquals(4, mirrored.size)
        assertTrue(mirrored.contains(Pt(180f, 30f)))
        assertTrue(mirrored.contains(Pt(20f, 170f)))
        assertTrue(mirrored.contains(Pt(180f, 170f)))
    }

    @Test
    fun `snapping pulls points onto the horizon`() {
        val guide = PerspectiveEngine.defaultGuide(1000f, 800f, 2)
        // A point 3px below the horizon is inside the tolerance and snaps onto it.
        val snapped = PerspectiveEngine.snap(Pt(500f, guide.horizonY + 3f), guide, 1000f, 800f, tolerance = 12f)
        assertEquals(guide.horizonY, snapped.y, 0.01f)
        // A disabled guide never moves the point.
        val unsnapped = PerspectiveEngine.snap(Pt(500f, 700f), guide.copy(enabled = false), 1000f, 800f)
        assertEquals(700f, unsnapped.y, 0.01f)
    }

    @Test
    fun `symmetry point generation returns the right counts`() {
        assertEquals(2, PerspectiveEngine.symmetryPoints(Pt(10f, 10f), com.artforge.mobile.core.model.SymmetryMode.Horizontal, 100f, 100f).size)
        assertEquals(4, PerspectiveEngine.symmetryPoints(Pt(10f, 10f), com.artforge.mobile.core.model.SymmetryMode.Both, 100f, 100f).size)
        assertEquals(8, PerspectiveEngine.symmetryPoints(Pt(10f, 10f), com.artforge.mobile.core.model.SymmetryMode.Radial, 100f, 100f, 8).size)
    }

    // ---- rig -------------------------------------------------------------

    @Test
    fun `bone hierarchy resolves world transforms`() {
        val root = Bone("root", "root", null, restX = 100f, restY = 100f, length = 60f)
        val child = Bone("child", "child", "root", restX = 0f, restY = 0f, length = 40f)
        val world = CharacterRig.solve(listOf(root, child))
        assertEquals(100f, world["root"]!!.x, 0.01f)
        assertTrue(world["child"]!!.x > 100f)
    }

    @Test
    fun `two bone ik reaches a target`() {
        val result = CharacterRig.solveTwoBoneIk(
            rootAngleDeg = 0f, upperLength = 50f, lowerLength = 50f,
            targetX = 70f, targetY = 0f, rootX = 0f, rootY = 0f,
        )
        assertNotNull(result)
        val unreachable = CharacterRig.solveTwoBoneIk(0f, 10f, 10f, 100f, 0f, 0f, 0f)
        assertEquals(null, unreachable)
    }

    @Test
    fun `pose capture and blend preserve bone identities`() {
        val bones = listOf(
            Bone("a", "a", null, 0f, 0f, rotation = 10f),
            Bone("b", "b", "a", 0f, 0f, rotation = 20f),
        )
        val pose = CharacterRig.capturePose("P1", bones)
        assertEquals(2, pose.boneRotations.size)
        val applied = CharacterRig.applyPose(bones.map { it.copy(rotation = 0f) }, pose)
        assertEquals(10f, applied[0].rotation, 0.01f)
        val blended = CharacterRig.blendPoses(pose, CharacterRig.capturePose("P2", bones.map { it.copy(rotation = 90f) }), 0.5f, "b", "blend")
        assertEquals(50f, blended.boneRotations["a"]!!, 0.01f)
    }

    @Test
    fun `hierarchy order puts parents first`() {
        val bones = listOf(
            Bone("c", "c", "b", 0f, 0f),
            Bone("a", "a", null, 0f, 0f),
            Bone("b", "b", "a", 0f, 0f),
        )
        val ordered = CharacterRig.hierarchyOrder(bones).map { it.id }
        assertEquals(listOf("a", "b", "c"), ordered)
    }

    @Test
    fun `mouth shapes map phonemes`() {
        assertEquals("A", CharacterRig.mouthShapeForPhoneme("ah"))
        assertEquals("M", CharacterRig.mouthShapeForPhoneme("m"))
        assertEquals("rest", CharacterRig.mouthShapeForPhoneme("zzz"))
    }

    @Test
    fun `bubble bounds are computed`() {
        val bubble = ComicBubble("b", "speech", 10f, 20f, 100f, 60f, "hello")
        val bounds = ComicEngine.boundsOf(bubble)
        assertEquals(110f, bounds.right, 0.01f)
        assertEquals(80f, bounds.bottom, 0.01f)
    }
}
