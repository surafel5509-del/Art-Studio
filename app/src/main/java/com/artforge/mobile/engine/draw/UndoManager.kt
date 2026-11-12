package com.artforge.mobile.engine.draw

import android.graphics.Bitmap
import android.graphics.Rect
import com.artforge.mobile.core.model.Pt
import com.artforge.mobile.core.model.RectF2

/**
 * Undo/redo for pixel edits.
 *
 * Storing a whole canvas per step would exhaust memory on large documents, so each
 * entry only keeps the *dirty rectangle* before and after an operation. Structural
 * edits (layer add/delete/reorder) are stored as JSON snapshots instead.
 */
sealed class UndoCommand {
    abstract val label: String
    abstract val layerId: String
    abstract val frameId: String

    /** A pixel patch, captured either before or after an operation. */
    class PixelPatch(
        override val label: String,
        override val layerId: String,
        override val frameId: String,
        val rect: Rect,
        val pixels: IntArray,
        val canvasWidth: Int,
    ) : UndoCommand() {
        fun applyTo(target: Bitmap) {
            target.setPixels(pixels, 0, canvasWidth, rect.left, rect.top, rect.width(), rect.height())
        }
    }

    /** Structural change described by two serialised project states. */
    class Structural(
        override val label: String,
        override val layerId: String,
        override val frameId: String,
        val before: String,
        val after: String,
    ) : UndoCommand()
}

/** Bounded undo stack with coalescing of rapid small edits per frame. */
class UndoManager(private val maxSteps: Int = 80) {

    private val undoStack = ArrayDeque<UndoCommand>()
    private val redoStack = ArrayDeque<UndoCommand>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoLabel: String? get() = undoStack.lastOrNull()?.label
    val redoLabel: String? get() = redoStack.lastOrNull()?.label

    fun push(cmd: UndoCommand) {
        undoStack.addLast(cmd)
        redoStack.clear()
        while (undoStack.size > maxSteps) undoStack.removeFirst()
        totalBytes += estimate(cmd)
        trimToByteBudget()
    }

    fun popUndo(): UndoCommand? = undoStack.removeLastOrNull()?.also { redoStack.addLast(it) }

    fun popRedo(): UndoCommand? = redoStack.removeLastOrNull()?.also { undoStack.addLast(it) }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        totalBytes = 0
    }

    fun undoDepth(): Int = undoStack.size
    fun redoDepth(): Int = redoStack.size

    /** Captures a region of a bitmap for later undo. */
    fun captureRegion(label: String, layerId: String, frameId: String, bitmap: Bitmap, rect: Rect): UndoCommand.PixelPatch {
        val safe = Rect(rect).apply {
            left = left.coerceIn(0, bitmap.width - 1)
            top = top.coerceIn(0, bitmap.height - 1)
            right = right.coerceIn(left + 1, bitmap.width)
            bottom = bottom.coerceIn(top + 1, bitmap.height)
        }
        val w = safe.width()
        val h = safe.height()
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, safe.left, safe.top, w, h)
        return UndoCommand.PixelPatch(label, layerId, frameId, safe, pixels, w)
    }

    fun captureRegion(label: String, layerId: String, frameId: String, bitmap: Bitmap, rect: RectF2): UndoCommand.PixelPatch =
        captureRegion(label, layerId, frameId, bitmap, toRect(rect, bitmap.width, bitmap.height))

    private var totalBytes = 0L
    private val byteBudget = 192L * 1024 * 1024

    private fun estimate(cmd: UndoCommand): Long = when (cmd) {
        is UndoCommand.PixelPatch -> cmd.pixels.size * 4L
        is UndoCommand.Structural -> (cmd.before.length + cmd.after.length) * 2L
    }

    private fun trimToByteBudget() {
        while (totalBytes > byteBudget && undoStack.size > 4) {
            val dropped = undoStack.removeFirst()
            totalBytes -= estimate(dropped)
        }
    }

    fun observedBytes(): Long = totalBytes

    companion object {
        fun toRect(r: RectF2, width: Int, height: Int): Rect = Rect(
            kotlin.math.floor(r.left).toInt().coerceIn(0, width - 1),
            kotlin.math.floor(r.top).toInt().coerceIn(0, height - 1),
            kotlin.math.ceil(r.right).toInt().coerceIn(1, width),
            kotlin.math.ceil(r.bottom).toInt().coerceIn(1, height),
        )

        fun pointBounds(points: List<Pt>, pad: Float): RectF2 {
            if (points.isEmpty()) return RectF2(0f, 0f, 0f, 0f)
            var l = Float.MAX_VALUE
            var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE
            var b = -Float.MAX_VALUE
            for (p in points) {
                if (p.x < l) l = p.x
                if (p.y < t) t = p.y
                if (p.x > r) r = p.x
                if (p.y > b) b = p.y
            }
            return RectF2(l - pad, t - pad, r + pad, b + pad)
        }
    }
}
