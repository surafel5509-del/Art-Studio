package com.artforge.mobile.engine.animation

import com.artforge.mobile.core.model.AnimationConfig
import com.artforge.mobile.core.model.Layer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

/** Playback state machine for the timeline; drives the canvas via [frame]. */
class TimelineController(
    private val scope: CoroutineScope,
    private val onFrameChanged: (Int) -> Unit,
) {
    data class PlaybackState(
        val isPlaying: Boolean = false,
        val loop: Boolean = true,
        val range: IntRange = 0..0,
        val speed: Float = 1f,
        val pingPong: Boolean = false,
    )

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var job: Job? = null

    fun setRange(range: IntRange) {
        _state.value = _state.value.copy(range = range)
    }

    fun setLoop(loop: Boolean) {
        _state.value = _state.value.copy(loop = loop)
    }

    fun setSpeed(speed: Float) {
        _state.value = _state.value.copy(speed = speed.coerceIn(0.1f, 4f))
    }

    fun setPingPong(enabled: Boolean) {
        _state.value = _state.value.copy(pingPong = enabled)
    }

    fun play(from: Int, fps: Int, config: AnimationConfig, selection: IntRange? = null) {
        stop()
        val range = AnimationEngine.playbackRange(config, selection)
        val start = if (from in range) from else range.first
        _state.value = _state.value.copy(isPlaying = true, range = range, loop = config.loop)
        job = scope.launch {
            var current = start
            var direction = 1
            while (isActive && _state.value.isPlaying) {
                onFrameChanged(current)
                val frameMs = (1000.0 / fps.coerceAtLeast(1) / _state.value.speed).toLong().coerceAtLeast(4L)
                delay(frameMs)
                if (_state.value.pingPong) {
                    if (current + direction > range.last || current + direction < range.first) {
                        direction = -direction
                        if (!_state.value.loop && current + direction !in range) break
                    }
                    current = (current + direction).coerceIn(range.first, range.last)
                } else {
                    current = AnimationEngine.loopedNext(current, range, _state.value.loop)
                    if (!_state.value.loop && current == range.last) {
                        onFrameChanged(current)
                        break
                    }
                }
            }
            _state.value = _state.value.copy(isPlaying = false)
        }
    }

    fun pause() {
        _state.value = _state.value.copy(isPlaying = false)
        job?.cancel()
        job = null
    }

    fun stop() {
        _state.value = _state.value.copy(isPlaying = false)
        job?.cancel()
        job = null
    }

    fun toggle(from: Int, fps: Int, config: AnimationConfig, selection: IntRange? = null) {
        if (_state.value.isPlaying) pause() else play(from, fps, config, selection)
    }

    fun release() = stop()
}

/** Frame operations the studio performs on the active layer's exposure track. */
object FrameOperations {

    /**
     * Ensures the layer has a drawing at [frame]. When it is a hold frame, the
     * existing drawing is *duplicated* into a new one so editing affects only the
     * new exposure (the standard animation workflow).
     */
    fun ensureDrawingAt(layer: Layer, frame: Int, newFrameId: String, duplicateOnHold: Boolean = true): Triple<Layer, String, Boolean> {
        val sorted = layer.frames.sortedBy { it.offset }
        val exact = sorted.firstOrNull { kotlin.math.abs(it.offset - frame) < 0.001 }
        if (exact != null && !duplicateOnHold) {
            return Triple(layer.copy(activeFrameId = exact.frameId), exact.frameId, false)
        }
        if (exact != null) {
            return Triple(layer.copy(activeFrameId = exact.frameId), exact.frameId, false)
        }
        if (!duplicateOnHold) {
            val ref = com.artforge.mobile.core.model.FrameRef(newFrameId, frame.toDouble())
            return Triple(
                layer.copy(frames = (sorted + ref).sortedBy { it.offset }, activeFrameId = newFrameId),
                newFrameId,
                true,
            )
        }
        // Insert a duplicated drawing at this frame, pushing nothing else.
        val ref = com.artforge.mobile.core.model.FrameRef(newFrameId, frame.toDouble())
        return Triple(
            layer.copy(frames = (sorted + ref).sortedBy { it.offset }, activeFrameId = newFrameId),
            newFrameId,
            true,
        )
    }

    fun addBlankFrame(layer: Layer, frame: Int, newFrameId: String): Layer {
        val ref = com.artforge.mobile.core.model.FrameRef(newFrameId, frame.toDouble())
        return layer.copy(
            frames = (layer.frames + ref).sortedBy { it.offset },
            activeFrameId = newFrameId,
        )
    }

    fun clearFrame(layer: Layer, frameId: String): Layer =
        layer.copy(frames = layer.frames.filterNot { it.frameId == frameId }, activeFrameId = layer.frames.firstOrNull()?.frameId)

    fun updateActive(layer: Layer, frameId: String): Layer = layer.copy(activeFrameId = frameId)
}
