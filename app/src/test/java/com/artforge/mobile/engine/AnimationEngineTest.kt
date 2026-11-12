package com.artforge.mobile.engine

import com.artforge.mobile.core.model.AnimTrack
import com.artforge.mobile.core.model.AnimatableValue
import com.artforge.mobile.core.model.AnimationConfig
import com.artforge.mobile.core.model.FrameMeta
import com.artforge.mobile.core.model.FrameRef
import com.artforge.mobile.core.model.Interpolation
import com.artforge.mobile.core.model.Keyframe
import com.artforge.mobile.core.model.Layer
import com.artforge.mobile.core.model.LayerKind
import com.artforge.mobile.core.model.OnionSkinConfig
import com.artforge.mobile.engine.animation.AnimationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Timeline maths, exposure-sheet behaviour and keyframe evaluation. */
class AnimationEngineTest {

    private fun layerWithFrames(vararg offsets: Pair<String, Int>) = Layer(
        id = "layer1",
        name = "Anim",
        kind = LayerKind.Animation,
        frames = offsets.map { FrameRef(it.first, it.second.toDouble()) },
        activeFrameId = offsets.firstOrNull()?.first,
    )

    private fun config(frameCount: Int = 24) = AnimationConfig(
        fps = 24,
        frameCount = frameCount,
        frames = (0 until frameCount).map { FrameMeta("frame_$it") },
        scenes = listOf(AnimationEngine.defaultScene(0, frameCount)),
    )

    @Test
    fun `frame is clamped to the timeline`() {
        val cfg = config(12)
        assertEquals(0, AnimationEngine.clampFrame(-5, cfg))
        assertEquals(11, AnimationEngine.clampFrame(99, cfg))
        assertEquals(5, AnimationEngine.clampFrame(5, cfg))
    }

    @Test
    fun `frame and millisecond conversions round trip`() {
        assertEquals(1000L, AnimationEngine.frameToMillis(24, 24))
        assertEquals(24, AnimationEngine.millisToFrame(1000L, 24))
        assertEquals(12, AnimationEngine.millisToFrame(500L, 24))
    }

    @Test
    fun `exposure sheet marks holds correctly`() {
        val layer = layerWithFrames("a" to 0, "b" to 4)
        val sheet = AnimationEngine.exposureSheet(config(10), listOf(layer))
        val a = sheet.filter { it.frameId == "a" }
        assertEquals(4, a.size)
        assertTrue(a.first().isKeyframe)
        assertTrue(a.drop(1).all { it.isHold })
        val b = sheet.filter { it.frameId == "b" }
        assertEquals(6, b.size)
        assertEquals(4, b.first().frameIndex)
    }

    @Test
    fun `inserting frames shifts later exposures`() {
        val layer = layerWithFrames("a" to 0, "b" to 4)
        val updated = AnimationEngine.insertFrames(layer, 2, 3)
        assertEquals(listOf(0.0, 7.0), updated.frames.map { it.offset })
    }

    @Test
    fun `deleting a frame slot pulls exposures back`() {
        val layer = layerWithFrames("a" to 0, "b" to 5)
        val updated = AnimationEngine.deleteFrameSlot(layer, 2, 2)
        assertEquals(listOf(0.0, 3.0), updated.frames.map { it.offset })
    }

    @Test
    fun `extending exposure pushes following frames`() {
        val layer = layerWithFrames("a" to 0, "b" to 2)
        val updated = AnimationEngine.extendExposure(layer, 0, 5)
        assertEquals(5.0, updated.frames.first { it.frameId == "b" }.offset, 0.001)
    }

    @Test
    fun `onion skin respects counts and tint colours`() {
        val layer = layerWithFrames("a" to 0, "b" to 1, "c" to 2, "d" to 3)
        val ghosts = AnimationEngine.onionGhosts(
            layer, 2,
            OnionSkinConfig(enabled = true, previousFrames = 2, nextFrames = 1, opacity = 0.4f),
        )
        assertEquals(2, ghosts.size)
        assertTrue(ghosts.any { it.frameId == "d" })
        assertTrue(ghosts.any { it.frameId == "a" || it.frameId == "b" })
        val disabled = AnimationEngine.onionGhosts(layer, 2, OnionSkinConfig(enabled = false))
        assertTrue(disabled.isEmpty())
    }

    @Test
    fun `keyframes interpolate linearly and hold`() {
        val keys = listOf(
            Keyframe(0, 0f, Interpolation.Linear),
            Keyframe(10, 100f, Interpolation.Linear),
        )
        assertEquals(50f, AnimationEngine.evaluateKeyframes(keys, 5), 0.01f)
        assertEquals(0f, AnimationEngine.evaluateKeyframes(keys, -3), 0.01f)
        assertEquals(100f, AnimationEngine.evaluateKeyframes(keys, 50), 0.01f)

        val holdKeys = listOf(Keyframe(0, 0f, Interpolation.Hold), Keyframe(10, 100f, Interpolation.Linear))
        assertEquals(0f, AnimationEngine.evaluateKeyframes(holdKeys, 5), 0.01f)
    }

    @Test
    fun `ease functions differ from linear`() {
        val easeIn = AnimationEngine.easeFor(Interpolation.EaseIn, 0.5f)
        val linear = AnimationEngine.easeFor(Interpolation.Linear, 0.5f)
        assertEquals(0.5f, linear, 0.0001f)
        assertTrue(easeIn < linear)
    }

    @Test
    fun `adding a keyframe on an existing frame replaces it`() {
        val keys = listOf(Keyframe(5, 10f))
        val updated = AnimationEngine.addKeyframe(keys, Keyframe(5, 42f))
        assertEquals(1, updated.size)
        assertEquals(42f, updated.first().value, 0.001f)
    }

    @Test
    fun `track evaluation reads the requested channel`() {
        val track = AnimTrack(
            id = "t", name = "track",
            channels = listOf(AnimatableValue("opacity", listOf(Keyframe(0, 0f), Keyframe(10, 1f)))),
        )
        assertEquals(0.5f, AnimationEngine.evaluate(track, "opacity", 5), 0.01f)
        assertEquals(-1f, AnimationEngine.evaluate(track, "missing", 5, -1f), 0.01f)
    }

    @Test
    fun `playback range honours a selection`() {
        val cfg = config(30)
        assertEquals(5..12, AnimationEngine.playbackRange(cfg, 5..12))
        assertEquals(0..29, AnimationEngine.playbackRange(cfg, null))
    }

    @Test
    fun `looping advances wraps and holds`() {
        val range = 0..4
        assertEquals(3, AnimationEngine.loopedNext(2, range, true))
        assertEquals(0, AnimationEngine.loopedNext(4, range, true))
        assertEquals(4, AnimationEngine.loopedNext(4, range, false))
        assertEquals(4, AnimationEngine.loopedPrevious(0, range, true))
        assertEquals(0, AnimationEngine.loopedPrevious(0, range, false))
    }

    @Test
    fun `timecode formats as mm ss ff`() {
        assertEquals("00:01:00", AnimationEngine.framesToTimecode(24, 24))
        assertEquals("00:00:12", AnimationEngine.framesToTimecode(12, 24))
    }

    @Test
    fun `duration is frame count over fps`() {
        assertEquals(1.0, AnimationEngine.durationSeconds(config(24)), 0.001)
    }

    @Test
    fun `inbetweens are evenly spaced`() {
        val inbetweens = AnimationEngine.inbetweenFrames(0, 10, 4)
        assertEquals(4, inbetweens.size)
        assertTrue(inbetweens.all { it in 1 until 10 })
    }

    @Test
    fun `audio sample conversion maps to frames`() {
        assertEquals(24, AnimationEngine.audioSampleToFrame(44100L, 44100, 24, 0))
        assertEquals(30, AnimationEngine.audioSampleToFrame(44100L, 44100, 24, 6))
    }

    @Test
    fun `next and previous drawing skip holds`() {
        val layer = layerWithFrames("a" to 0, "b" to 6)
        assertEquals(6, AnimationEngine.nextDrawing(layer, 2))
        assertEquals(0, AnimationEngine.previousDrawing(layer, 6))
        assertNull(AnimationEngine.nextDrawing(layer, 20))
    }
}
