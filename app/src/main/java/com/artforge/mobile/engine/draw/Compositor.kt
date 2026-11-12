package com.artforge.mobile.engine.draw

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import com.artforge.mobile.core.model.Layer
import com.artforge.mobile.core.model.LayerKind
import com.artforge.mobile.core.model.ProjectDocument
import com.artforge.mobile.core.model.BlendMode as LayerBlend

/**
 * Composites a project's visible layers for a given animation frame.
 *
 * Handles blend modes, opacity, clipping masks, layer masks, groups, reference
 * layer tinting and onion-skin ghosting. Layers that need isolation (mask, clip,
 * non-normal opacity, a tint or a transform) are drawn into a scratch buffer
 * first, so effects never leak between layers.
 */
class Compositor(
    private val frameStore: FrameStore,
) {

    private var scratch: Bitmap? = null
    private var clipScratch: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** A ghost drawing drawn underneath the active frame for onion skin. */
    data class GhostFrame(val frameId: String, val tint: Int, val alpha: Float)

    fun composite(
        doc: ProjectDocument,
        target: Bitmap,
        atFrame: Int,
        onionGhosts: List<GhostFrame> = emptyList(),
        hiddenLayerIds: Set<String> = emptySet(),
        includeBackground: Boolean = true,
    ): Bitmap {
        val canvas = Canvas(target)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        if (includeBackground && !doc.settings.transparentBackground) {
            canvas.drawColor(doc.settings.backgroundColor)
        }

        onionGhosts.forEach { drawGhost(canvas, doc, it, target.width, target.height) }

        paintRootLayers(canvas, doc, doc.layers, atFrame, target.width, target.height, hiddenLayerIds)
        return target
    }

    /**
     * Paints root layers bottom-to-top. Layers are stored top-to-bottom in the data
     * model (index 0 is the topmost layer, matching the layer panel), so we walk
     * the list backwards and recursion handles groups.
     */
    private fun paintRootLayers(
        canvas: Canvas,
        doc: ProjectDocument,
        layers: List<Layer>,
        atFrame: Int,
        width: Int,
        height: Int,
        hidden: Set<String>,
    ) {
        val topLevel = layers.filter { it.parentId == null }
        for (layer in topLevel.asReversed()) {
            paintLayer(canvas, doc, layer, layers, atFrame, width, height, hidden)
        }
    }

    private fun paintLayer(
        canvas: Canvas,
        doc: ProjectDocument,
        layer: Layer,
        allLayers: List<Layer>,
        atFrame: Int,
        width: Int,
        height: Int,
        hidden: Set<String>,
    ) {
        if (!layer.visible || layer.opacity <= 0.001f || layer.id in hidden) return
        if (layer.kind == LayerKind.Adjustment) return

        // Groups composite their children in isolation, then blend once.
        if (layer.kind == LayerKind.Group) {
            val groupCanvas = Canvas(ensureScratch(width, height).also {
                Canvas(it).drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            })
            val children = allLayers.filter { it.parentId == layer.id }
            for (child in children.asReversed()) {
                paintLayer(groupCanvas, doc, child, allLayers, atFrame, width, height, hidden)
            }
            drawIsolated(canvas, ensureScratch(width, height), layer)
            return
        }

        val frameId = resolveFrame(layer, atFrame) ?: return
        val bitmap = frameStore.load(doc.id, frameId, width, height)

        val needsIsolation = layer.maskFrameId != null ||
            layer.opacity < 0.999f ||
            !layer.transform.isIdentity ||
            layer.referenceTint != null ||
            layer.kind == LayerKind.Reference

        if (!needsIsolation) {
            drawBitmap(canvas, bitmap, layer, tint = null, alphaOverride = null)
            return
        }

        val buffer = ensureScratch(width, height)
        Canvas(buffer).drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val bufferCanvas = Canvas(buffer)
        drawBitmap(
            bufferCanvas,
            bitmap,
            layer,
            tint = layer.referenceTint,
            alphaOverride = if (layer.kind == LayerKind.Reference) layer.opacity * 0.6f else null,
        )

        if (layer.maskFrameId != null) {
            val mask = frameStore.load(doc.id, layer.maskFrameId, width, height)
            val maskPaint = Paint().apply { blendMode = BlendMode.DST_IN }
            bufferCanvas.drawBitmap(mask, 0f, 0f, maskPaint)
        }

        if (layer.clippingMask) {
            // The clip base is the first visible layer beneath this one.
            val index = allLayers.indexOf(layer)
            val base = allLayers.drop(index + 1).firstOrNull { it.parentId == layer.parentId && it.visible }
            if (base != null) {
                val baseFrame = resolveFrame(base, atFrame)
                if (baseFrame != null) {
                    val clipBitmap = frameStore.load(doc.id, baseFrame, width, height)
                    Canvas(buffer).drawBitmap(
                        clipBitmap,
                        0f,
                        0f,
                        Paint().apply { blendMode = BlendMode.DST_IN },
                    )
                }
            }
        }

        drawIsolated(canvas, buffer, layer)
    }

    private fun drawBitmap(canvas: Canvas, bmp: Bitmap, layer: Layer, tint: Int?, alphaOverride: Float?) {
        paint.reset()
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        paint.alpha = ((alphaOverride ?: layer.opacity) * 255f).toInt().coerceIn(0, 255)
        paint.blendMode = mapBlend(layer.blendMode)
        paint.colorFilter = tint?.let { BlendModeColorFilter(it, BlendMode.SRC_IN) }

        val t = layer.transform
        if (t.isIdentity) {
            canvas.drawBitmap(bmp, 0f, 0f, paint)
        } else {
            val px = if (t.pivotX == 0f && t.pivotY == 0f) bmp.width / 2f else t.pivotX
            val py = if (t.pivotX == 0f && t.pivotY == 0f) bmp.height / 2f else t.pivotY
            val m = Matrix()
            m.postTranslate(-px, -py)
            m.postScale(t.scaleX, t.scaleY)
            m.postRotate(t.rotationDeg)
            m.postTranslate(px + t.translateX, py + t.translateY)
            canvas.drawBitmap(bmp, m, paint)
        }
        paint.blendMode = BlendMode.SRC_OVER
        paint.colorFilter = null
    }

    private fun drawIsolated(canvas: Canvas, buffer: Bitmap, layer: Layer) {
        paint.reset()
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        paint.alpha = 255
        paint.blendMode = mapBlend(layer.blendMode)
        canvas.drawBitmap(buffer, 0f, 0f, paint)
        paint.blendMode = BlendMode.SRC_OVER
    }

    private fun drawGhost(canvas: Canvas, doc: ProjectDocument, ghost: GhostFrame, width: Int, height: Int) {
        val bmp = frameStore.load(doc.id, ghost.frameId, width, height)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        p.alpha = (ghost.alpha * 255f).toInt().coerceIn(0, 255)
        p.colorFilter = BlendModeColorFilter(ghost.tint, BlendMode.SRC_IN)
        canvas.drawBitmap(bmp, 0f, 0f, p)
    }

    /**
     * Resolves which drawing a layer shows at global timeline [atFrame]. Frame refs
     * carry an offset in frames, so a "hold" is the gap to the next offset rather
     * than duplicated pixel data.
     */
    fun resolveFrame(layer: Layer, atFrame: Int): String? {
        if (layer.frames.isEmpty()) return layer.activeFrameId
        val sorted = layer.frames.sortedBy { it.offset }
        var chosen: String? = null
        for (ref in sorted) {
            if (ref.offset <= atFrame + 0.0001) chosen = ref.frameId else break
        }
        return chosen ?: sorted.first().frameId
    }

    fun mapBlend(mode: LayerBlend): BlendMode = when (mode) {
        LayerBlend.Normal -> BlendMode.SRC_OVER
        LayerBlend.Multiply -> BlendMode.MULTIPLY
        LayerBlend.Screen -> BlendMode.SCREEN
        LayerBlend.Overlay -> BlendMode.OVERLAY
        LayerBlend.SoftLight -> BlendMode.SOFT_LIGHT
        LayerBlend.HardLight -> BlendMode.HARD_LIGHT
        LayerBlend.Darken -> BlendMode.DARKEN
        LayerBlend.Lighten -> BlendMode.LIGHTEN
        LayerBlend.ColorDodge -> BlendMode.COLOR_DODGE
        LayerBlend.ColorBurn -> BlendMode.COLOR_BURN
        LayerBlend.Difference -> BlendMode.DIFFERENCE
        LayerBlend.Exclusion -> BlendMode.EXCLUSION
        LayerBlend.Hue -> BlendMode.HUE
        LayerBlend.Saturation -> BlendMode.SATURATION
        LayerBlend.Color -> BlendMode.COLOR
        LayerBlend.Luminosity -> BlendMode.LUMINOSITY
    }

    private fun ensureScratch(width: Int, height: Int): Bitmap {
        val current = scratch
        if (current == null || current.width != width || current.height != height || current.isRecycled) {
            current?.recycle()
            scratch = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        return scratch!!
    }

    /** Builds the colour-matrix filter used by adjustment layers. */
    fun adjustmentFilter(brightness: Float, contrast: Float, saturation: Float, hueShift: Float): ColorMatrixColorFilter {
        val cm = ColorMatrix()
        if (saturation != 0f) {
            val s = 1f + saturation
            cm.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        0.213f + 0.787f * s, 0.715f - 0.715f * s, 0.072f - 0.072f * s, 0f, 0f,
                        0.213f - 0.213f * s, 0.715f + 0.285f * s, 0.072f - 0.072f * s, 0f, 0f,
                        0.213f - 0.213f * s, 0.715f - 0.715f * s, 0.072f + 0.928f * s, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        if (brightness != 0f || contrast != 0f) {
            val c = 1f + contrast
            val t = (0.5f - c * 0.5f + brightness) * 255f
            cm.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        c, 0f, 0f, 0f, t,
                        0f, c, 0f, 0f, t,
                        0f, 0f, c, 0f, t,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        if (hueShift != 0f) {
            val cmHue = ColorMatrix()
            cmHue.setRotate(0, hueShift)
            cm.postConcat(cmHue)
        }
        return ColorMatrixColorFilter(cm)
    }

    /** Applies an adjustment filter destructively (used by export and the effects panel). */
    fun applyAdjustment(target: Bitmap, filter: ColorMatrixColorFilter) {
        val src = target.copy(Bitmap.Config.ARGB_8888, false)
        val c = Canvas(target)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        c.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = filter })
        src.recycle()
    }

    /** Renders a down-scaled composite into [target] (used for thumbnails). */
    fun compositeThumbnail(doc: ProjectDocument, target: Bitmap, atFrame: Int, maxDim: Int) {
        val full = Bitmap.createBitmap(doc.settings.width, doc.settings.height, Bitmap.Config.ARGB_8888)
        composite(doc, full, atFrame)
        val scaled = FrameStore.fitInto(full, maxDim)
        val canvas = Canvas(target)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(
            scaled,
            Rect(0, 0, scaled.width, scaled.height),
            RectF(0f, 0f, target.width.toFloat(), target.height.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        if (scaled !== full) scaled.recycle()
        full.recycle()
    }

    fun release() {
        scratch?.recycle()
        scratch = null
        clipScratch?.recycle()
        clipScratch = null
    }
}
