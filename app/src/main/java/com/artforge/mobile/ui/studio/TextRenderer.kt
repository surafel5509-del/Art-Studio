package com.artforge.mobile.ui.studio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.artforge.mobile.core.color.ColorUtils
import com.artforge.mobile.core.model.TextLayerData
import com.artforge.mobile.engine.comic.ComicEngine

/**
 * Rasterises text layers onto a frame bitmap. Text layers stay editable in the
 * model ([TextLayerData]) and are re-rendered whenever their content changes.
 */
object TextRenderer {

    fun render(target: Bitmap, text: TextLayerData, originX: Float, originY: Float, canvasWidth: Int, canvasHeight: Int): Int {
        if (text.text.isBlank()) return 0
        val canvas = Canvas(target)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = text.color
            textSize = text.fontSize
            letterSpacing = if (text.fontSize > 0) text.letterSpacing / text.fontSize else 0f
            typeface = Typeface.create(text.fontFamily, styleFlags(text.bold, text.italic))
            textAlign = when (text.align) {
                "center" -> Paint.Align.CENTER
                "right", "end" -> Paint.Align.RIGHT
                else -> Paint.Align.LEFT
            }
        }
        val maxWidth = if (text.align == "start") canvasWidth - originX - 8f else minOf(canvasWidth.toFloat(), canvasWidth - 8f)
        val lines = wrap(text.text, paint, maxWidth)
        val lineHeight = text.fontSize * text.lineHeightMultiplier
        var y = originY + text.fontSize
        var drawn = 0
        lines.forEach { line ->
            if (y <= canvasHeight + text.fontSize) {
                canvas.drawText(line, originX, y, paint)
                drawn++
            }
            y += lineHeight
        }
        return drawn
    }

    fun measure(text: TextLayerData): Pair<Float, Float> {
        val paint = Paint().apply {
            textSize = text.fontSize
            typeface = Typeface.create(text.fontFamily, styleFlags(text.bold, text.italic))
        }
        val lines = text.text.split('\n')
        val width = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
        val height = lines.size * text.fontSize * text.lineHeightMultiplier
        return width to height
    }

    private fun wrap(content: String, paint: Paint, maxWidth: Float): List<String> {
        val result = ArrayList<String>()
        content.split('\n').forEach { paragraph ->
            if (paragraph.isEmpty()) {
                result.add("")
                return@forEach
            }
            result.addAll(ComicEngine.wrapText(paragraph, paint, maxWidth.coerceAtLeast(paint.textSize)))
        }
        return result
    }

    private fun styleFlags(bold: Boolean, italic: Boolean): Int {
        var flags = Typeface.NORMAL
        if (bold) flags = flags or Typeface.BOLD
        if (italic) flags = flags or Typeface.ITALIC
        return flags
    }

    /** Renders a text layer preview thumbnail for the layer panel. */
    fun previewBitmap(text: TextLayerData, size: Int = 96): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF1B1D22.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (ColorUtils.luminance(text.color) > 0.5f) text.color else 0xFFFFFFFF.toInt()
            textSize = size * 0.4f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(text.fontFamily, styleFlags(text.bold, text.italic))
        }
        val label = text.text.take(3).ifBlank { "T" }
        canvas.drawText(label, size / 2f, size * 0.62f, paint)
        return bmp
    }
}
