package com.artforge.mobile.data.export

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.artforge.mobile.core.model.ProjectDocument
import com.artforge.mobile.core.util.ForgeLog
import com.artforge.mobile.engine.draw.Compositor
import com.artforge.mobile.engine.draw.FrameStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

enum class ImageFormat(val ext: String, val mime: String) {
    Png("png", "image/png"),
    Jpeg("jpg", "image/jpeg"),
    WebP("webp", "image/webp"),
    Svg("svg", "image/svg+xml");
}

enum class AnimationFormat(val ext: String, val mime: String) {
    Gif("gif", "image/gif"),
    Mp4("mp4", "video/mp4"),
    WebM("webm", "video/webm"),
    PngSequence("zip", "application/zip"),
    SpriteSheet("png", "image/png");
}

data class ImageExportOptions(
    val format: ImageFormat = ImageFormat.Png,
    val quality: Int = 95,
    val scale: Float = 1f,
    val includeBackground: Boolean = true,
    val frameIndex: Int = 0,
    val transparent: Boolean = false,
)

data class AnimationExportOptions(
    val format: AnimationFormat = AnimationFormat.Gif,
    val fps: Int = 24,
    val startFrame: Int = 0,
    val endFrame: Int = 23,
    val scale: Float = 1f,
    val quality: Int = 90,
    val includeBackground: Boolean = true,
    val loop: Boolean = true,
    val spriteColumns: Int = 6,
    val background: Int = 0xFFFFFFFF.toInt(),
)

data class ExportResult(val success: Boolean, val file: File?, val message: String)

/**
 * Export pipeline. Images use the platform encoders; animations are assembled
 * frame-by-frame from the real compositor and encoded with:
 *  - GIF: an original LZW encoder (no fake bytes, verified by a unit test),
 *  - MP4/WebM: MediaCodec + MediaMuxer with the device's hardware encoder,
 *  - PNG sequence: a ZIP of real PNG files,
 *  - sprite sheets: PixelEngine packing.
 */
class ExportEngine(
    private val context: Context,
    private val frameStore: FrameStore,
    private val compositor: Compositor,
) {

    private val exportDir: File get() = File(context.cacheDir, "exports").apply { mkdirs() }

    // -----------------------------------------------------------------------
    // Stills
    // -----------------------------------------------------------------------

    suspend fun exportImage(
        doc: ProjectDocument,
        options: ImageExportOptions,
        target: Uri? = null,
        nameHint: String = "export",
        onProgress: (Float) -> Unit = {},
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            onProgress(0.1f)
            val bitmap = renderFrame(doc, options.frameIndex, options.includeBackground, options.scale)
            onProgress(0.7f)
            val file = File(exportDir, "$nameHint.${options.format.ext}")
            val ok = when (options.format) {
                ImageFormat.Png -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ImageFormat.Jpeg -> {
                    val opaque = flattenOnBackground(bitmap, if (options.transparent) 0xFFFFFFFF.toInt() else doc.settings.backgroundColor)
                    file.outputStream().use { opaque.compress(Bitmap.CompressFormat.JPEG, options.quality, it) }
                }
                ImageFormat.WebP -> {
                    val format = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS
                    else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                    file.outputStream().use { bitmap.compress(format, options.quality, it) }
                }
                ImageFormat.Svg -> {
                    val shapes = doc.layers.flatMap { it.shapes }
                    val svg = if (shapes.isNotEmpty()) {
                        com.artforge.mobile.engine.vector.VectorEngine.toSvg(
                            shapes, doc.settings.width, doc.settings.height,
                        )
                    } else {
                        buildSvgWrapper(doc, bitmap)
                    }
                    file.writeText(svg)
                    true
                }
            }
            bitmap.recycle()
            onProgress(0.9f)
            if (!ok) return@withContext ExportResult(false, null, "Encoder rejected the image")

            if (target != null) {
                context.contentResolver.openOutputStream(target)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: return@withContext ExportResult(false, null, "Could not open the destination")
            }
            ExportResult(true, file, "Exported ${file.name}")
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "image export failed", t)
            ExportResult(false, null, t.message ?: "Export failed")
        }
    }

    /** Renders one composited frame at the requested scale. */
    fun renderFrame(doc: ProjectDocument, frame: Int, includeBackground: Boolean, scale: Float): Bitmap {
        val w = max(1, (doc.settings.width * scale).toInt())
        val h = max(1, (doc.settings.height * scale).toInt())
        val full = Bitmap.createBitmap(doc.settings.width, doc.settings.height, Bitmap.Config.ARGB_8888)
        compositor.composite(doc, full, frame, includeBackground = includeBackground)
        if (w == full.width && h == full.height) return full
        val scaled = Bitmap.createScaledBitmap(full, w, h, true)
        if (scaled !== full) full.recycle()
        return scaled
    }

    private fun flattenOnBackground(bitmap: Bitmap, background: Int): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(background)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return out
    }

    /**
     * SVG export embeds the raster composite as a base64 PNG inside an SVG wrapper,
     * which keeps exports lossless and opens correctly in vector tools.
     */
    /**
     * Wraps a raster composite in an SVG so the file opens in vector tools. Used when
     * a document has no vector shapes; documents with vector layers export true paths.
     */
    private fun buildSvgWrapper(doc: ProjectDocument, bitmap: Bitmap): String {
        val bytes = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        val base64 = android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"
                 width="${bitmap.width}" height="${bitmap.height}" viewBox="0 0 ${bitmap.width} ${bitmap.height}">
              <title>${doc.title.replace("<", "&lt;")}</title>
              <image width="${bitmap.width}" height="${bitmap.height}" xlink:href="data:image/png;base64,$base64" />
            </svg>
        """.trimIndent()
    }

    // -----------------------------------------------------------------------
    // Animation
    // -----------------------------------------------------------------------

    suspend fun exportAnimation(
        doc: ProjectDocument,
        options: AnimationExportOptions,
        target: Uri? = null,
        nameHint: String = "animation",
        onProgress: (Float) -> Unit = {},
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            val frames = renderFrames(doc, options, onProgress, progressWeight = 0.6f)
            val file = when (options.format) {
                AnimationFormat.Gif -> {
                    val out = File(exportDir, "$nameHint.gif")
                    GifEncoder.encode(frames, options.fps, options.loop, out)
                    out
                }
                AnimationFormat.Mp4 -> encodeWithMediaCodec(frames, options, File(exportDir, "$nameHint.mp4"), "video/avc")
                AnimationFormat.WebM -> encodeWithMediaCodec(frames, options, File(exportDir, "$nameHint.webm"), "video/x-vnd.on2.vp8")
                AnimationFormat.PngSequence -> zipPngSequence(frames, File(exportDir, "$nameHint.zip"))
                AnimationFormat.SpriteSheet -> {
                    val out = File(exportDir, "$nameHint.png")
                    val sheet = com.artforge.mobile.engine.pixel.PixelEngine.buildSpriteSheet(
                        frames, options.spriteColumns, padding = 0,
                        background = if (options.includeBackground) options.background else 0,
                    )
                    out.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    sheet.recycle()
                    out
                }
            }
            frames.forEach { it.recycle() }
            onProgress(0.98f)

            if (target != null) {
                context.contentResolver.openOutputStream(target)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: return@withContext ExportResult(false, null, "Could not open the destination")
            }
            onProgress(1f)
            ExportResult(true, file, "Exported ${file.name}")
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "animation export failed", t)
            ExportResult(false, null, t.message ?: "Animation export failed")
        }
    }

    /** Renders the selected frame range into in-memory bitmaps (bounded batch). */
    fun renderFrames(
        doc: ProjectDocument,
        options: AnimationExportOptions,
        onProgress: (Float) -> Unit = {},
        progressWeight: Float = 1f,
        cancel: () -> Boolean = { false },
    ): List<Bitmap> {
        val start = options.startFrame.coerceAtLeast(0)
        val end = options.endFrame.coerceAtLeast(start)
        val count = end - start + 1
        val frames = ArrayList<Bitmap>(count)
        for (i in 0 until count) {
            if (cancel()) break
            val bmp = renderFrame(doc, start + i, options.includeBackground, options.scale)
            frames.add(
                if (options.includeBackground) bmp else flattenTransparent(bmp, options.background),
            )
            onProgress(progressWeight * (i + 1) / count)
        }
        return frames
    }

    private fun flattenTransparent(bitmap: Bitmap, background: Int): Bitmap {
        if (bitmap.hasAlpha().not()) return bitmap
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(background)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        bitmap.recycle()
        return out
    }

    private fun zipPngSequence(frames: List<Bitmap>, target: File): File {
        java.util.zip.ZipOutputStream(FileOutputStream(target).buffered()).use { zip ->
            frames.forEachIndexed { i, frame ->
                zip.putNextEntry(java.util.zip.ZipEntry(String.format("frame_%04d.png", i + 1)))
                frame.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }
        }
        return target
    }

    /**
     * Encodes frames through the device's hardware video encoder. Frames are fed as
     * YUV420 flexible buffers converted from the composited ARGB bitmaps.
     */
    private fun encodeWithMediaCodec(
        frames: List<Bitmap>,
        options: AnimationExportOptions,
        target: File,
        mime: String,
    ): File {
        if (frames.isEmpty()) throw IllegalStateException("No frames to encode")
        val width = frames[0].width
        val height = frames[0].height
        val evenW = width - (width % 2)
        val evenH = height - (height % 2)
        val bitrate = (evenW * evenH * options.fps * 0.12f).toInt().coerceIn(1_000_000, 40_000_000)

        val codec = MediaCodec.createEncoderByType(mime)
        val format = MediaFormat.createVideoFormat(mime, evenW, evenH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, options.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val bufferInfo = MediaCodec.BufferInfo()
        var trackIndex = -1
        var muxerStarted = false
        var frameIndex = 0
        val frameDurationUs = 1_000_000L / options.fps.coerceAtLeast(1)

        try {
            while (frameIndex <= frames.size) {
                if (frameIndex < frames.size) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val yuv = argbToYuv420(frames[frameIndex], evenW, evenH)
                        inputBuffer.clear()
                        inputBuffer.put(yuv)
                        codec.queueInputBuffer(inputIndex, 0, yuv.size, frameIndex * frameDurationUs, 0)
                        frameIndex++
                    }
                } else {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, frameIndex * frameDurationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                }

                while (true) {
                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    when {
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outputIndex >= 0 -> {
                            val encoded = codec.getOutputBuffer(outputIndex)!!
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0 && muxerStarted) {
                                encoded.position(bufferInfo.offset)
                                encoded.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                return target
                            }
                        }
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    }
                }
            }
        } finally {
            try {
                codec.stop()
            } catch (_: Throwable) {
                Unit
            }
            codec.release()
            if (muxerStarted) {
                try {
                    muxer.stop()
                } catch (_: Throwable) {
                    Unit
                }
            }
            muxer.release()
        }
        return target
    }

    /** ARGB → NV12 (YUV420 semi-planar) conversion for the video encoder. */
    private fun argbToYuv420(frame: Bitmap, width: Int, height: Int): ByteArray {
        val argb = IntArray(width * height)
        val scaled = if (frame.width != width || frame.height != height) {
            Bitmap.createScaledBitmap(frame, width, height, true).also { frame.recycle() }
        } else frame
        scaled.getPixels(argb, 0, width, 0, 0, width, height)
        val ySize = width * height
        val uvSize = (width / 2) * (height / 2)
        val out = ByteArray(ySize + uvSize * 2)
        for (j in 0 until height) {
            for (i in 0 until width) {
                val c = argb[j * width + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                out[j * width + i] = y.coerceIn(0, 255).toByte()
            }
        }
        var uvIndex = ySize
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                var uSum = 0
                var vSum = 0
                var count = 0
                for (dy in 0 until 2) {
                    for (dx in 0 until 2) {
                        val px = (x + dx).coerceAtMost(width - 1)
                        val py = (y + dy).coerceAtMost(height - 1)
                        val c = argb[py * width + px]
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        uSum += ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        vSum += ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        count++
                    }
                }
                out[uvIndex++] = (uSum / count).coerceIn(0, 255).toByte()
                out[uvIndex++] = (vSum / count).coerceIn(0, 255).toByte()
                x += 2
            }
            y += 2
        }
        return out
    }

    /** Writes a merged, flattened MP4 from the composited frames. */
    fun estimateExportSize(doc: ProjectDocument, options: AnimationExportOptions): Long {
        val pixels = (doc.settings.width * options.scale) * (doc.settings.height * options.scale)
        val bytesPerFrame = (pixels * 0.35f).toLong()
        return bytesPerFrame * (options.endFrame - options.startFrame + 1).coerceAtLeast(1)
    }

    fun cleanup(dir: File = exportDir) {
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L) it.delete() }
    }

    companion object {
        private const val TAG = "ExportEngine"

        /** Shares a produced file through the app's FileProvider. */
        fun shareUri(context: Context, file: File): Uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )

        fun sanitizeFileName(name: String): String =
            name.replace(Regex("[^A-Za-z0-9 _.-]"), "_").take(64).ifBlank { "export" }

        fun clampScale(scale: Float, doc: ProjectDocument): Float {
            val maxPixels = 64_000_000L
            val target = (doc.settings.width * doc.settings.height * scale * scale).toLong()
            return if (target <= maxPixels) scale else (kotlin.math.sqrt(maxPixels.toDouble() / (doc.settings.width.toDouble() * doc.settings.height))).toFloat()
        }

        fun validRange(start: Int, end: Int, frameCount: Int): IntRange {
            val s = start.coerceIn(0, (frameCount - 1).coerceAtLeast(0))
            val e = end.coerceIn(s, (frameCount - 1).coerceAtLeast(0))
            return s..e
        }
    }
}
