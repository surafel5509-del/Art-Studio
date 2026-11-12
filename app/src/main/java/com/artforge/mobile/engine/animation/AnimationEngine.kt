package com.artforge.mobile.engine.animation

import com.artforge.mobile.core.model.AnimTrack
import com.artforge.mobile.core.model.AnimationConfig
import com.artforge.mobile.core.model.FrameMeta
import com.artforge.mobile.core.model.Interpolation
import com.artforge.mobile.core.model.Keyframe
import com.artforge.mobile.core.model.Layer
import com.artforge.mobile.core.model.Marker
import com.artforge.mobile.core.model.OnionSkinConfig
import com.artforge.mobile.core.model.Pt
import com.artforge.mobile.core.model.Scene
import com.artforge.mobile.engine.draw.Compositor
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Pure timeline maths for the animation studio: frame/exposure queries, scene
 * resolution, keyframe evaluation and onion-skin selection. Kept free of Android
 * types so the whole animation model is unit-testable on the JVM.
 */
object AnimationEngine {

    /** The exposure sheet: which drawing occupies each timeline frame, per layer. */
    data class ExposureCell(
        val frameIndex: Int,
        val frameId: String,
        val layerId: String,
        val isKeyframe: Boolean,
        val isHold: Boolean,
        val label: String,
        val duration: Int,
    )

    val FPS_OPTIONS = listOf(6, 8, 12, 15, 24, 25, 30, 48, 60)

    fun clampFrame(frame: Int, config: AnimationConfig): Int = frame.coerceIn(0, max(0, config.frameCount - 1))

    fun secondsPerFrame(fps: Int): Double = 1.0 / fps.coerceAtLeast(1)

    fun frameToMillis(frame: Int, fps: Int): Long = (frame * 1000.0 / fps.coerceAtLeast(1)).toLong()

    fun millisToFrame(ms: Long, fps: Int): Int = (ms * fps / 1000L).toInt()

    /** Applies the current scene bounds to a raw timeline frame. */
    fun sceneFor(config: AnimationConfig, frame: Int): Scene? =
        config.scenes.firstOrNull { frame >= it.startFrame && frame <= it.endFrame }

    fun sceneFrameRange(config: AnimationConfig, sceneIndex: Int): IntRange {
        val scene = config.scenes.getOrNull(sceneIndex) ?: return 0 until max(0, config.frameCount - 1)
        return scene.startFrame..scene.endFrame
    }

    fun markersAt(config: AnimationConfig, frame: Int): List<Marker> = config.markers.filter { it.frame == frame }

    // -----------------------------------------------------------------------
    // Exposure sheet construction
    // -----------------------------------------------------------------------

    /**
     * Builds the exposure sheet rows for the timeline. A hold frame is a timeline
     * frame covered by a drawing that started earlier, which is exactly what the
     * compositor's offset-based lookup produces.
     */
    fun exposureSheet(config: AnimationConfig, layers: List<Layer>): List<ExposureCell> {
        val cells = ArrayList<ExposureCell>()
        for (layer in layers) {
            if (layer.frames.isEmpty()) continue
            val sorted = layer.frames.sortedBy { it.offset }
            for (i in sorted.indices) {
                val ref = sorted[i]
                val start = floor(ref.offset).toInt()
                val nextStart = sorted.getOrNull(i + 1)?.let { floor(it.offset).toInt() } ?: config.frameCount
                val end = max(start, nextStart - 1)
                for (f in start..end) {
                    if (f < 0 || f >= config.frameCount) continue
                    cells.add(
                        ExposureCell(
                            frameIndex = f,
                            frameId = ref.frameId,
                            layerId = layer.id,
                            isKeyframe = f == start,
                            isHold = f != start,
                            label = config.frames.firstOrNull { it.id == ref.frameId }?.label.orEmpty(),
                            duration = end - start + 1,
                        ),
                    )
                }
            }
        }
        return cells
    }

    fun frameMeta(config: AnimationConfig, frameId: String): FrameMeta? = config.frames.firstOrNull { it.id == frameId }

    /** True when [frame] holds the drawing of an earlier frame. */
    fun isHoldFrame(config: AnimationConfig, layer: Layer, frame: Int): Boolean {
        val ref = layer.frames.firstOrNull { floor(it.offset).toInt() == frame } ?: return true
        return ref.offset != floor(ref.offset) && ref.offset > frame
    }

    fun drawingFrames(layer: Layer): List<Int> = layer.frames.map { floor(it.offset).toInt() }.sorted()

    /** Previous/next *drawing* (not raw frame) used by frame-step navigation. */
    fun previousDrawing(layer: Layer, frame: Int): Int? =
        layer.frames.map { floor(it.offset).toInt() + if (it.offset < 0) -1 else 0 }
            .map { it.coerceAtLeast(0) }
            .filter { it < frame }
            .maxOrNull()

    fun nextDrawing(layer: Layer, frame: Int): Int? =
        layer.frames.map { floor(it.offset).toInt() }
            .filter { it > frame }
            .minOrNull()

    // -----------------------------------------------------------------------
    // Frame list operations (exposure sheet edits)
    // -----------------------------------------------------------------------

    /** Inserts a gap of [count] frames at [atFrame] by shifting later exposures. */
    fun insertFrames(layer: Layer, atFrame: Int, count: Int): Layer {
        if (count <= 0) return layer
        val updated = layer.frames.map { ref ->
            if (floor(ref.offset) >= atFrame) ref.copy(offset = ref.offset + count) else ref
        }
        return layer.copy(frames = updated)
    }

    fun deleteFrameSlot(layer: Layer, atFrame: Int, count: Int = 1): Layer {
        val removed = layer.frames.filter { floor(it.offset) >= atFrame && floor(it.offset) < atFrame + count }
        val kept = layer.frames.filterNot { removed.contains(it) }
        val shifted = kept.map { ref ->
            if (floor(ref.offset) >= atFrame + count) ref.copy(offset = ref.offset - count) else ref
        }
        return layer.copy(frames = shifted.sortedBy { it.offset })
    }

    fun reorderFrame(config: AnimationConfig, layer: Layer, fromFrame: Int, toFrame: Int): Pair<AnimationConfig, Layer> {
        val moved = layer.frames.firstOrNull { floor(it.offset).toInt() == fromFrame } ?: return config to layer
        val without = layer.frames.filterNot { it === moved }
        val shiftedOffsets = without.map { ref ->
            when {
                floor(ref.offset).toInt() > fromFrame && floor(ref.offset).toInt() < toFrame ->
                    ref.copy(offset = (floor(ref.offset).toInt() - 1).toDouble())
                floor(ref.offset).toInt() <= fromFrame && floor(ref.offset).toInt() >= toFrame ->
                    ref.copy(offset = (floor(ref.offset).toInt() + 1).toDouble())
                else -> ref
            }
        }
        val newLayer = layer.copy(frames = (shiftedOffsets + moved.copy(offset = toFrame.toDouble())).sortedBy { it.offset })
        return config to newLayer
    }

    /** Changes how many frames a drawing stays on screen. */
    fun extendExposure(layer: Layer, atFrame: Int, newDuration: Int): Layer {
        val sorted = layer.frames.sortedBy { it.offset }
        val idx = sorted.indexOfFirst { floor(it.offset).toInt() == atFrame }
        if (idx < 0) return layer
        val current = sorted[idx]
        val nextStart = sorted.getOrNull(idx + 1)?.let { floor(it.offset).toInt() } ?: (atFrame + 1)
        val currentDuration = max(1, nextStart - atFrame)
        val delta = newDuration - currentDuration
        if (delta == 0) return layer
        val adjusted = sorted.mapIndexed { i, ref ->
            when {
                i == idx -> ref
                i > idx -> ref.copy(offset = max(atFrame.toDouble() + newDuration, ref.offset + delta))
                else -> ref
            }
        }
        return layer.copy(frames = adjusted)
    }

    // -----------------------------------------------------------------------
    // Onion skin
    // -----------------------------------------------------------------------

    /**
     * Ghost drawings to render behind the current frame. Only *drawing* frames are
     * ghosted (not holds), otherwise a 12-frame hold would produce identical ghosts.
     */
    fun onionGhosts(
        layer: Layer,
        currentFrame: Int,
        config: OnionSkinConfig,
    ): List<Compositor.GhostFrame> {
        if (!config.enabled || layer.frames.isEmpty()) return emptyList()
        val drawings = layer.frames.sortedBy { it.offset }.filter { floor(it.offset).toInt() != currentFrame }
        val out = ArrayList<Compositor.GhostFrame>()
        val prev = drawings.filter { floor(it.offset).toInt() < currentFrame }
            .sortedByDescending { it.offset }
            .take(config.previousFrames.coerceIn(0, 8))
        prev.forEachIndexed { i, ref ->
            val falloff = 1f - (i.toFloat() / max(1f, config.previousFrames.toFloat())) * 0.6f
            out.add(Compositor.GhostFrame(ref.frameId, config.previousTint, config.opacity * falloff))
        }
        val next = drawings.filter { floor(it.offset).toInt() > currentFrame }
            .sortedBy { it.offset }
            .take(config.nextFrames.coerceIn(0, 8))
        next.forEachIndexed { i, ref ->
            val falloff = 1f - (i.toFloat() / max(1f, config.nextFrames.toFloat())) * 0.6f
            out.add(Compositor.GhostFrame(ref.frameId, config.nextTint, config.opacity * falloff))
        }
        return out
    }

    // -----------------------------------------------------------------------
    // Keyframes
    // -----------------------------------------------------------------------

    /** Evaluates the value of [track]'s [channel] at [frame]. */
    fun evaluate(track: AnimTrack, channel: String, frame: Int, fallback: Float = 0f): Float {
        val anim = track.channels.firstOrNull { it.channel == channel } ?: return fallback
        return evaluateKeyframes(anim.keyframes, frame, fallback)
    }

    fun evaluateKeyframes(keys: List<Keyframe>, frame: Int, fallback: Float = 0f): Float {
        if (keys.isEmpty()) return fallback
        val sorted = keys.sortedBy { it.frame }
        if (frame <= sorted.first().frame) return sorted.first().value
        if (frame >= sorted.last().frame) return sorted.last().value
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (frame >= a.frame && frame <= b.frame) {
                val span = (b.frame - a.frame).toFloat()
                if (span <= 0f) return b.value
                val t = (frame - a.frame) / span
                val eased = easeFor(a.interpolation, t)
                return a.value + (b.value - a.value) * eased
            }
        }
        return fallback
    }

    fun easeFor(interp: Interpolation, t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return when (interp) {
            Interpolation.Linear -> c
            Interpolation.EaseIn -> c * c
            Interpolation.EaseOut -> 1f - (1f - c).pow(2)
            Interpolation.EaseInOut -> if (c < 0.5f) 2f * c * c else 1f - 2f * (1f - c) * (1f - c)
            Interpolation.Hold -> 0f
            Interpolation.Bezier -> cubicBezierEase(c)
        }
    }

    /** Smoothstep-based bezier approximation used when no custom handles are set. */
    private fun cubicBezierEase(t: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * 0.25f + 3f * u * t * t * 0.75f + t * t * t
    }

    fun addKeyframe(keys: List<Keyframe>, key: Keyframe): List<Keyframe> =
        (keys.filterNot { it.frame == key.frame } + key).sortedBy { it.frame }

    fun removeKeyframe(keys: List<Keyframe>, frame: Int): List<Keyframe> = keys.filterNot { it.frame == frame }

    /** Samples a channel every frame for the curve editor graph. */
    fun sampleCurve(keys: List<Keyframe>, from: Int, to: Int): List<Pt> {
        if (to <= from) return emptyList()
        return (from..to).map { f -> Pt((f - from).toFloat(), evaluateKeyframes(keys, f)) }
    }

    /** Interpolated in-betweens between two pose frames (simple cross-fade description). */
    fun inbetweenFrames(keyA: Int, keyB: Int, count: Int): List<Int> {
        if (count <= 0 || keyB <= keyA) return emptyList()
        val step = (keyB - keyA).toFloat() / (count + 1)
        return (1..count).map { floor(keyA + step * it).toInt() }
    }

    /** Playback frames for "play selection". */
    fun playbackRange(config: AnimationConfig, selection: IntRange?): IntRange {
        if (selection != null && !selection.isEmpty()) return selection
        val scene = sceneFor(config, config.scenes.getOrNull(config.currentSceneIndex)?.startFrame ?: 0)
        return scene?.let { it.startFrame..it.endFrame } ?: 0..max(0, config.frameCount - 1)
    }

    fun loopedNext(current: Int, range: IntRange, loop: Boolean): Int {
        val next = current + 1
        if (next in range) return next
        return if (loop) range.first else range.last
    }

    fun loopedPrevious(current: Int, range: IntRange, loop: Boolean): Int {
        val prev = current - 1
        if (prev in range) return prev
        return if (loop) range.last else range.first
    }

    /** Total film length in seconds, used by the export dialog. */
    fun durationSeconds(config: AnimationConfig): Double = config.frameCount.toDouble() / config.fps.coerceAtLeast(1)

    /** Snaps a frame to the nearest marker, or returns the frame unchanged. */
    fun snapToMarker(config: AnimationConfig, frame: Int): Int {
        val nearest = config.markers.minByOrNull { abs(it.frame - frame) } ?: return frame
        return if (abs(nearest.frame - frame) <= 1) nearest.frame else frame
    }

    fun defaultFrameMeta(index: Int): FrameMeta = FrameMeta(id = "frame_$index", duration = 1)

    fun defaultScene(index: Int, frameCount: Int) = Scene(
        id = "scene_$index",
        name = "Scene ${index + 1}",
        startFrame = 0,
        endFrame = max(0, frameCount - 1),
    )

    /** Converts an audio sample position to a timeline frame. */
    fun audioSampleToFrame(sampleIndex: Long, sampleRate: Int, fps: Int, startFrame: Int): Int =
        startFrame + (sampleIndex * fps / sampleRate.coerceAtLeast(1)).toInt()

    /** Frames between two timecodes at [fps], used by lip-sync tracks. */
    fun timecodeToFrames(seconds: Double, fps: Int): Int = (seconds * fps).toInt()

    fun framesToTimecode(frame: Int, fps: Int): String {
        val totalSeconds = frame / fps.coerceAtLeast(1)
        val f = frame % fps.coerceAtLeast(1)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", minutes, seconds, f)
    }

    fun unionRange(a: IntRange?, b: IntRange?): IntRange? {
        if (a == null) return b
        if (b == null) return a
        return min(a.first, b.first)..max(a.last, b.last)
    }
}
