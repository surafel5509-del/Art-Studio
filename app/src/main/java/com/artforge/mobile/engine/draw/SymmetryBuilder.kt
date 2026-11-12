package com.artforge.mobile.engine.draw

import android.graphics.Matrix
import com.artforge.mobile.core.model.SymmetryMode

/**
 * Builds the mirror transforms used by the symmetry / kaleidoscope drawing modes.
 * The engine draws each committed operation once per transform, so a symmetrical
 * brush stroke is six real strokes rather than a visual trick.
 */
class SymmetryBuilder(
    val mode: SymmetryMode,
    /** Number of radial segments for [SymmetryMode.Radial] / [SymmetryMode.Kaleidoscope]. */
    val segments: Int = 8,
    /** Axis position in canvas coordinates (defaults to canvas centre). */
    val axisX: Float? = null,
    val axisY: Float? = null,
    /** 0 = full mirror, 1 = every N-th radial copy only mirrors. */
    val kaleidoscopeMirror: Boolean = true,
) {

    fun mirrorTransforms(width: Float, height: Float): List<Matrix> {
        val cx = axisX ?: (width / 2f)
        val cy = axisY ?: (height / 2f)
        val out = ArrayList<Matrix>()
        when (mode) {
            SymmetryMode.None -> Unit

            SymmetryMode.Horizontal -> {
                // Mirror across the vertical axis (left/right).
                out.add(Matrix().apply {
                    setScale(-1f, 1f, cx, cy)
                })
            }

            SymmetryMode.Vertical -> {
                out.add(Matrix().apply { setScale(1f, -1f, cx, cy) })
            }

            SymmetryMode.Both -> {
                out.add(Matrix().apply { setScale(-1f, 1f, cx, cy) })
                out.add(Matrix().apply { setScale(1f, -1f, cx, cy) })
                out.add(Matrix().apply { setScale(-1f, -1f, cx, cy) })
            }

            SymmetryMode.Radial, SymmetryMode.Kaleidoscope -> {
                val n = segments.coerceIn(2, 64)
                val step = 360f / n
                for (i in 1 until n) {
                    out.add(Matrix().apply {
                        setRotate(step * i, cx, cy)
                        if (kaleidoscopeMirror && i % 2 == 1) {
                            postScale(-1f, 1f, cx, cy)
                        }
                    })
                }
                if (kaleidoscopeMirror) {
                    out.add(Matrix().apply { setScale(-1f, 1f, cx, cy) })
                }
            }
        }
        return out
    }

    /** Mirror positions for point-based operations such as bucket fill seeds. */
    fun mirrors(x: Int, y: Int, width: Int, height: Int): List<Pair<Int, Int>> {
        if (mode == SymmetryMode.None) return listOf(x to y)
        val cx = axisX ?: (width / 2f)
        val cy = axisY ?: (height / 2f)
        val points = ArrayList<Pair<Int, Int>>()
        points.add(x to y)
        for (m in mirrorTransforms(width.toFloat(), height.toFloat())) {
            val pts = floatArrayOf(x.toFloat(), y.toFloat())
            m.mapPoints(pts)
            points.add(pts[0].toInt() to pts[1].toInt())
        }
        return points.distinct()
    }
}
