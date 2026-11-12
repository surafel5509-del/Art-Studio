package com.artforge.mobile.engine.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.artforge.mobile.core.color.ColorUtils
import com.artforge.mobile.core.model.ComicBubble
import com.artforge.mobile.core.model.ComicPage
import com.artforge.mobile.core.model.ComicPanel
import com.artforge.mobile.core.model.Pt
import com.artforge.mobile.core.model.RectF2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Comic & manga studio: page templates, panel layout generation, speech/thought
 * bubbles with real tails, screentones, halftone shading, speed lines, perspective
 * guide rendering and page numbering.
 */
object ComicEngine {

    enum class PageTemplate(val label: String, val aspect: Float) {
        UsComic("US Comic (2:3)", 2f / 3f),
        MangaB5("Manga B5", 0.707f),
        A4("A4 Portrait", 210f / 297f),
        A4Landscape("A4 Landscape", 297f / 210f),
        Square("Webtoon Square", 1f),
        Vertical("Webtoon Vertical", 0.5625f),
    }

    enum class LayoutPreset(val label: String, val rows: List<Int>) {
        Splash("Splash (1)", listOf(1)),
        TwoStack("Two Stacked", listOf(1, 1)),
        ThreeStack("Three Stacked", listOf(1, 1, 1)),
        TwoByTwo("2 × 2 Grid", listOf(2, 2)),
        ThreeByTwo("3 × 2 Grid", listOf(3, 3)),
        HeroTop("Hero + 2", listOf(1, 2)),
        HeroBottom("2 + Hero", listOf(2, 1)),
        MangaFour("Manga 4 Panel", listOf(1, 2, 1)),
        SixGrid("Six Grid", listOf(2, 2, 2)),
    }

    /** Generates a panel layout for a page of the given size. */
    fun generateLayout(
        template: LayoutPreset,
        pageWidth: Float,
        pageHeight: Float,
        gutter: Float = 16f,
        margin: Float = 32f,
        idPrefix: String = "panel",
    ): List<ComicPanel> {
        val contentW = pageWidth - margin * 2
        val contentH = pageHeight - margin * 2
        val rows = template.rows
        val rowHeight = (contentH - gutter * (rows.size - 1)) / rows.size
        val panels = ArrayList<ComicPanel>()
        var y = margin
        var index = 0
        rows.forEach { columns ->
            val panelW = (contentW - gutter * (columns - 1)) / columns
            var x = margin
            repeat(columns) {
                panels.add(
                    ComicPanel(
                        id = "${idPrefix}_$index",
                        x = x,
                        y = y,
                        width = panelW,
                        height = rowHeight,
                    ),
                )
                index++
                x += panelW + gutter
            }
            y += rowHeight + gutter
        }
        return panels
    }

    /** Rough webtoon layout: full-width vertical strips. */
    fun generateWebtoonLayout(pageWidth: Float, stripHeight: Float, count: Int, margin: Float = 24f): List<ComicPanel> =
        (0 until count).map { i ->
            ComicPanel(
                id = "strip_$i",
                x = margin,
                y = margin + i * (stripHeight + 24f),
                width = pageWidth - margin * 2,
                height = stripHeight,
            )
        }

    fun renderPage(page: ComicPage, width: Int, height: Int, background: Int = Color.WHITE): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(background)
        drawScreentone(canvas, 0f, 0f, width.toFloat(), height.toFloat())
        for (panel in page.panels) drawPanel(canvas, panel)
        for (bubble in page.bubbles) drawBubble(canvas, bubble)
        if (page.index >= 0) drawPageNumber(canvas, page.index + 1, width, height)
        return out
    }

    fun drawPanel(canvas: Canvas, panel: ComicPanel) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = panel.borderWidth
        paint.color = panel.borderColor
        val rect = RectF(panel.x, panel.y, panel.x + panel.width, panel.y + panel.height)
        if (panel.cornerRadius > 0f) {
            canvas.drawRoundRect(rect, panel.cornerRadius, panel.cornerRadius, paint)
        } else {
            canvas.drawRect(rect, paint)
        }
    }

    /**
     * Draws a speech / thought / caption / shout bubble with a real tail pointing
     * at its target. Text is laid out inside the bubble.
     */
    fun drawBubble(canvas: Canvas, bubble: ComicBubble) {
        val path = Path()
        val rect = RectF(bubble.x, bubble.y, bubble.x + bubble.width, bubble.y + bubble.height)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = bubble.fillColor
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = bubble.strokeWidth
            color = bubble.strokeColor
        }

        when (bubble.kind) {
            "thought" -> {
                // Cloud-like bubble from overlapping circles.
                val step = min(bubble.width, bubble.height) / 4f
                var x = bubble.x + step / 2
                while (x < bubble.x + bubble.width) {
                    val r = step * (0.7f + 0.25f * sin(x / step))
                    path.addCircle(x, bubble.y + bubble.height / 2f, r, Path.Direction.CW)
                    x += step * 1.4f
                }
                path.addRoundRect(rect, bubble.height / 2.2f, bubble.height / 2.2f, Path.Direction.CW)
            }
            "caption" -> path.addRect(rect, Path.Direction.CW)
            "shout" -> {
                val cx = rect.centerX()
                val cy = rect.centerY()
                val spikes = 16
                for (i in 0 until spikes * 2) {
                    val angle = PI * 2 * i / (spikes * 2)
                    val rx = bubble.width / 2f * if (i % 2 == 0) 1f else 0.82f
                    val ry = bubble.height / 2f * if (i % 2 == 0) 1f else 0.82f
                    val px = cx + (rx * cos(angle)).toFloat()
                    val py = cy + (ry * sin(angle)).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
            }
            else -> path.addRoundRect(rect, bubble.height / 2f, bubble.height / 2f, Path.Direction.CW)
        }

        // Tail
        val targetX = bubble.tailTargetX
        val targetY = bubble.tailTargetY
        if (targetX != null && targetY != null) {
            val baseCx = rect.centerX()
            val baseCy = rect.centerY()
            val angle = kotlin.math.atan2(targetY - baseCy, targetX - baseCx)
            val baseWidth = min(bubble.width, bubble.height) * 0.22f
            val p1x = baseCx + cos(angle) * (bubble.width / 2f) * 0.85f
            val p1y = baseCy + sin(angle) * (bubble.height / 2f) * 0.85f
            val perpX = cos(angle + PI / 2).toFloat() * baseWidth
            val perpY = sin(angle + PI / 2).toFloat() * baseWidth
            val tail = Path()
            tail.moveTo(p1x + perpX, p1y + perpY)
            tail.quadTo((p1x + targetX) / 2f + perpX * 0.2f, (p1y + targetY) / 2f, targetX, targetY)
            tail.quadTo((p1x + targetX) / 2f - perpX * 0.2f, (p1y + targetY) / 2f, p1x - perpX, p1y - perpY)
            tail.close()
            canvas.drawPath(tail, fill)
            canvas.drawPath(tail, stroke)
        }

        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)

        // Text
        if (bubble.text.isNotBlank()) {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = bubble.strokeColor
                textSize = bubble.fontSize
                textAlign = Paint.Align.CENTER
                typeface = if (bubble.kind == "shout") Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            }
            val padding = bubble.fontSize * 0.8f
            val maxWidth = bubble.width - padding * 2
            val lines = wrapText(bubble.text, textPaint, maxWidth)
            val lineHeight = bubble.fontSize * 1.25f
            val totalHeight = lines.size * lineHeight
            var ty = rect.centerY() - totalHeight / 2f + bubble.fontSize
            lines.forEach { line ->
                canvas.drawText(line, rect.centerX(), ty, textPaint)
                ty += lineHeight
            }
        }
    }

    fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(' ')
        val lines = ArrayList<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) lines.add(current.toString())
                // A single word can still be wider than the bubble: break it by
                // characters so text never spills outside the panel.
                if (paint.measureText(word) > maxWidth) {
                    var chunk = StringBuilder()
                    for (ch in word) {
                        if (chunk.isNotEmpty() && paint.measureText("$chunk$ch") > maxWidth) {
                            lines.add(chunk.toString())
                            chunk = StringBuilder()
                        }
                        chunk.append(ch)
                    }
                    current = chunk
                } else {
                    current = StringBuilder(word)
                }
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    /** Manga screentone: a dot grid at an angle, drawn with real circles. */
    fun drawScreentone(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        dotSpacing: Float = 7f,
        radius: Float = 1.6f,
        color: Int = 0x33000000,
        angleDeg: Float = 45f,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val rad = Math.toRadians(angleDeg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val w = right - left
        val h = bottom - top
        val diag = kotlin.math.sqrt(w * w + h * h)
        var gy = -diag
        while (gy < diag) {
            var gx = -diag
            while (gx < diag) {
                val x = (left + right) / 2f + gx * cosA - gy * sinA
                val y = (top + bottom) / 2f + gx * sinA + gy * cosA
                if (x >= left && x <= right && y >= top && y <= bottom) canvas.drawCircle(x, y, radius, paint)
                gx += dotSpacing
            }
            gy += dotSpacing
        }
    }

    /** Manga speed lines converging on a focus point. */
    fun drawSpeedLines(
        canvas: Canvas,
        focusX: Float,
        focusY: Float,
        width: Float,
        height: Float,
        count: Int = 90,
        color: Int = 0x44000000,
        seed: Long = 7L,
    ) {
        val rng = Random(seed)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
        }
        val maxR = kotlin.math.sqrt(width * width + height * height)
        repeat(count) {
            val angle = rng.nextFloat() * (2 * PI).toFloat()
            val inner = maxR * (0.18f + rng.nextFloat() * 0.25f)
            val outer = maxR
            val spread = (rng.nextFloat() - 0.5f) * 0.02f
            paint.strokeWidth = 1f + rng.nextFloat() * 3f
            canvas.drawLine(
                focusX + cos(angle) * inner,
                focusY + sin(angle) * inner,
                focusX + cos(angle + spread) * outer,
                focusY + sin(angle + spread) * outer,
                paint,
            )
        }
    }

    fun drawPageNumber(canvas: Canvas, pageNumber: Int, width: Int, height: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF444444.toInt()
            textSize = 20f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(pageNumber.toString(), width / 2f, height - 14f, paint)
    }

    /** Hit test for panel resize/move handles. */
    fun panelHitTest(panel: ComicPanel, x: Float, y: Float, tolerance: Float = 12f): PanelPart {
        val right = panel.x + panel.width
        val bottom = panel.y + panel.height
        if (kotlin.math.abs(x - right) <= tolerance && kotlin.math.abs(y - bottom) <= tolerance) return PanelPart.BottomRight
        if (kotlin.math.abs(x - right) <= tolerance && y in panel.y..bottom) return PanelPart.Right
        if (kotlin.math.abs(y - bottom) <= tolerance && x in panel.x..right) return PanelPart.Bottom
        if (x in panel.x..right && y in panel.y..bottom) return PanelPart.Inside
        return PanelPart.None
    }

    enum class PanelPart { None, Inside, Right, Bottom, BottomRight }

    /** Suggests a bubble size for a text block at a given font size. */
    fun autoBubbleSize(text: String, fontSize: Float, maxWidth: Float): Pair<Float, Float> {
        val paint = Paint().apply { textSize = fontSize }
        val lines = wrapText(text, paint, maxWidth)
        val longest = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
        val w = (longest + fontSize * 2f).coerceAtMost(maxWidth + fontSize * 2f)
        val h = (lines.size * fontSize * 1.25f + fontSize * 1.4f)
        return max(60f, w) to max(50f, h)
    }

    fun boundsOf(bubble: ComicBubble): RectF2 = RectF2(bubble.x, bubble.y, bubble.x + bubble.width, bubble.y + bubble.height)

    fun contains(panel: ComicPanel, p: Pt): Boolean =
        p.x >= panel.x && p.x <= panel.x + panel.width && p.y >= panel.y && p.y <= panel.y + panel.height

    /** Splits a panel horizontally or vertically (manga grid editing). */
    fun splitPanel(panel: ComicPanel, vertical: Boolean, ratio: Float = 0.5f, gutter: Float = 12f): List<ComicPanel> {
        val r = ratio.coerceIn(0.1f, 0.9f)
        return if (vertical) {
            val w1 = (panel.width - gutter) * r
            val w2 = panel.width - gutter - w1
            listOf(
                panel.copy(id = "${panel.id}_a", width = w1),
                panel.copy(id = "${panel.id}_b", x = panel.x + w1 + gutter, width = w2),
            )
        } else {
            val h1 = (panel.height - gutter) * r
            val h2 = panel.height - gutter - h1
            listOf(
                panel.copy(id = "${panel.id}_a", height = h1),
                panel.copy(id = "${panel.id}_b", y = panel.y + h1 + gutter, height = h2),
            )
        }
    }

    fun mergePanels(a: ComicPanel, b: ComicPanel): ComicPanel {
        val left = min(a.x, b.x)
        val top = min(a.y, b.y)
        val right = max(a.x + a.width, b.x + b.width)
        val bottom = max(a.y + a.height, b.y + b.height)
        return a.copy(id = "${a.id}_merged", x = left, y = top, width = right - left, height = bottom - top)
    }

    val onePointGuides: List<Pt> = listOf(Pt(0.5f, 0.5f))
    val twoPointGuides: List<Pt> = listOf(Pt(0.1f, 0.5f), Pt(0.9f, 0.5f))
    val threePointGuides: List<Pt> = listOf(Pt(0.15f, 0.55f), Pt(0.85f, 0.55f), Pt(0.5f, 0.05f))

    fun toneColor(base: Int, strength: Float): Int = ColorUtils.withAlphaF(base, strength)
}
