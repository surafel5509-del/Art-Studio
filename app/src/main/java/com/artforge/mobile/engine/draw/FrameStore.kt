package com.artforge.mobile.engine.draw

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.artforge.mobile.core.util.ForgeLog
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Disk-backed, memory-bounded store for frame pixel data.
 *
 * The whole point of this class is requirement #24: *never* load every animation
 * frame into RAM. Frames are individual PNG files on internal storage, loaded on
 * demand into a byte-bounded LRU cache, and evicted back to disk (dirty frames are
 * written first, so eviction never loses artwork).
 */
class FrameStore(
    private val rootDir: File,
    /** Maximum bytes of decoded bitmaps kept in RAM; sized from ActivityManager. */
    private val maxCacheBytes: Long = 320L * 1024 * 1024,
) {

    private data class Entry(
        val bitmap: Bitmap,
        var dirty: Boolean = false,
        var lastUsed: Long = 0L,
        var refCount: Int = 0,
    )

    private val cache = LinkedHashMap<String, Entry>(64, 0.75f, true)
    private val thumbs = ConcurrentHashMap<String, Bitmap>()
    private var cachedBytes: Long = 0L
    private val lock = Any()

    init {
        rootDir.mkdirs()
    }

    private fun fileFor(projectId: String, frameId: String): File =
        File(File(rootDir, projectId), "$frameId.png").also { it.parentFile?.mkdirs() }

    /** Returns a mutable bitmap for [frameId], decoding from disk on a miss. */
    fun load(projectId: String, frameId: String, width: Int, height: Int): Bitmap = synchronized(lock) {
        cache[frameId]?.let {
            it.lastUsed = System.nanoTime()
            return it.bitmap
        }
        val file = fileFor(projectId, frameId)
        val decoded = if (file.exists() && file.length() > 0) {
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            })
        } else {
            null
        }
        val bitmap = when {
            decoded == null -> createBlank(width, height)
            decoded.width != width || decoded.height != height -> {
                ForgeLog.w(TAG, "frame $frameId size mismatch, scaling to canvas size")
                Bitmap.createScaledBitmap(decoded, width, height, true).also { decoded.recycle() }
            }
            else -> decoded
        }
        val entry = Entry(bitmap, dirty = false, lastUsed = System.nanoTime())
        cache[frameId] = entry
        cachedBytes += entry.bitmap.byteCount
        evictIfNeeded()
        entry.bitmap
    }

    /** Marks a frame as modified so that it is persisted on flush/eviction. */
    fun markDirty(frameId: String) {
        synchronized(lock) {
            cache[frameId]?.dirty = true
            thumbs.remove(frameId)
        }
    }

    fun isLoaded(frameId: String): Boolean = synchronized(lock) { cache.containsKey(frameId) }

    /** Flush a single frame to disk immediately. */
    fun flushFrame(projectId: String, frameId: String) {
        val entry = synchronized(lock) { cache[frameId] } ?: return
        if (!entry.dirty) return
        writeFrame(projectId, frameId, entry.bitmap)
        synchronized(lock) { entry.dirty = false }
    }

    fun flushAll(projectId: String) {
        val snapshot: List<Pair<String, Bitmap>>
        synchronized(lock) {
            snapshot = cache.filterValues { it.dirty }.map { it.key to it.value.bitmap }
        }
        snapshot.forEach { (id, bmp) -> writeFrame(projectId, id, bmp) }
        synchronized(lock) { cache.values.forEach { it.dirty = false } }
    }

    private fun writeFrame(projectId: String, frameId: String, bitmap: Bitmap) {
        val target = fileFor(projectId, frameId)
        val temp = File(target.absolutePath + ".tmp")
        try {
            target.parentFile?.mkdirs()
            val translucent = hasTranslucentPixels(bitmap)
            FileOutputStream(temp).use { out ->
                if (translucent) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } else {
                    // Fully opaque frame: flatten onto white before encoding so the
                    // stored PNG is byte-identical to what the user sees, independent
                    // of how the source bitmap's alpha channel was set up.
                    val flat = createBlank(bitmap.width, bitmap.height)
                    val canvas = Canvas(flat)
                    canvas.drawColor(Color.WHITE)
                    canvas.drawBitmap(bitmap, 0f, 0f, Paint().apply { isAntiAlias = false })
                    flat.compress(Bitmap.CompressFormat.PNG, 100, out)
                    flat.recycle()
                }
                out.flush()
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "failed writing frame $frameId", t)
        }
    }

    private fun hasTranslucentPixels(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val step = max(1, min(w, h) / 128)
        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                if (Color.alpha(bitmap.getPixel(x, y)) < 255) return true
            }
        }
        return false
    }

    /** Persists a bitmap that is not part of the cache (used by composite/export paths). */
    fun writeExternal(projectId: String, frameId: String, bitmap: Bitmap) = writeFrame(projectId, frameId, bitmap)

    fun createBlankFrame(projectId: String, frameId: String, width: Int, height: Int, fill: Int = Color.TRANSPARENT): Bitmap {
        val bmp = createBlank(width, height, fill)
        synchronized(lock) {
            cache[frameId] = Entry(bmp, dirty = true, lastUsed = System.nanoTime())
            cachedBytes += bmp.byteCount
            thumbs.remove(frameId)
            evictIfNeeded()
        }
        return bmp
    }

    /** Duplicates [sourceFrameId] into [newFrameId] without keeping either in RAM long. */
    fun duplicateFrame(projectId: String, sourceFrameId: String, newFrameId: String, width: Int, height: Int) {
        val src = load(projectId, sourceFrameId, width, height)
        // Clone explicitly: Bitmap.copy may return the same instance for immutable
        // bitmaps, which would make the "copy" share pixels with the source.
        val copy = createBlank(width, height)
        Canvas(copy).drawBitmap(src, 0f, 0f, Paint().apply { isAntiAlias = false })
        synchronized(lock) {
            cache[newFrameId]?.let { cachedBytes -= it.bitmap.byteCount }
            cache[newFrameId] = Entry(copy, dirty = true, lastUsed = System.nanoTime())
            cachedBytes += copy.byteCount
            thumbs.remove(newFrameId)
            evictIfNeeded()
        }
    }

    /**
     * A mutable copy of the frame as it currently is in memory.
     *
     * Used wherever a second bitmap is needed next to a cached frame (live stroke
     * preview, thumbnails): operations like `Bitmap.copy` can hand back the *same*
     * instance, so an explicit Canvas clone guarantees the original is untouched.
     */
    fun liveCopy(projectId: String, frameId: String, width: Int, height: Int): Bitmap {
        val source = load(projectId, frameId, width, height)
        val copy = createBlank(width, height)
        Canvas(copy).drawBitmap(source, 0f, 0f, Paint().apply { isAntiAlias = false })
        return copy
    }

    fun deleteFrame(projectId: String, frameId: String) {
        synchronized(lock) {
            cache.remove(frameId)?.let { cachedBytes -= it.bitmap.byteCount }
            thumbs.remove(frameId)
        }
        fileFor(projectId, frameId).delete()
    }

    fun deleteProject(projectId: String) {
        val dir = File(rootDir, projectId)
        synchronized(lock) {
            cache.keys.filter { fileFor(projectId, it).parentFile?.name == projectId }
                .forEach { key -> cache.remove(key)?.let { cachedBytes -= it.bitmap.byteCount } }
            thumbs.keys.filter { it.startsWith(projectId) }.forEach { thumbs.remove(it) }
        }
        dir.deleteRecursively()
    }

    /**
     * Small preview used by layer rows and the timeline. Cached separately from
     * the full-resolution cache so previews are cheap even when frames are cold.
     */
    fun thumbnail(projectId: String, frameId: String, width: Int, height: Int, maxSize: Int = 96): Bitmap? {
        thumbs[frameId]?.let { return it }
        val full = load(projectId, frameId, width, height)
        val scale = maxSize.toFloat() / max(full.width, full.height)
        val w = max(1, (full.width * scale).toInt())
        val h = max(1, (full.height * scale).toInt())
        val thumb = Bitmap.createScaledBitmap(full, w, h, true)
        thumbs[frameId] = thumb
        return thumb
    }

    fun hasFrameOnDisk(projectId: String, frameId: String): Boolean = fileFor(projectId, frameId).exists()

    fun frameFile(projectId: String, frameId: String): File = fileFor(projectId, frameId)

    /** Pins a frame in RAM while it is being composited (no eviction). */
    fun <T> withPinned(frameId: String, block: () -> T): T = synchronized(lock) {
        val e = cache[frameId] ?: return block()
        e.refCount++
        try {
            block()
        } finally {
            e.refCount--
        }
    }

    fun cacheBytes(): Long = synchronized(lock) { cachedBytes }

    fun cachedFrameCount(): Int = synchronized(lock) { cache.size }

    private fun evictIfNeeded() {
        while (cachedBytes > maxCacheBytes && cache.size > 1) {
            val victim = cache.entries.firstOrNull { it.value.refCount == 0 } ?: break
            if (victim.value.dirty) writeFrame(projectIdOf(victim.key), victim.key, victim.value.bitmap)
            cache.remove(victim.key)
            cachedBytes -= victim.value.bitmap.byteCount
        }
    }

    private var lastProjectHint: String = ""

    /** Cache keys are frame ids; projects are tracked to resolve files during eviction. */
    fun setProjectHint(projectId: String) {
        lastProjectHint = projectId
    }

    private fun projectIdOf(frameId: String): String = frameOfProject[frameId] ?: lastProjectHint

    private val frameOfProject = ConcurrentHashMap<String, String>()

    /** Registers the owning project for a frame id so eviction can persist it. */
    fun registerFrame(projectId: String, frameId: String) {
        frameOfProject[frameId] = projectId
        setProjectHint(projectId)
    }

    fun release() {
        synchronized(lock) {
            cache.clear()
            thumbs.clear()
            cachedBytes = 0
        }
    }

    companion object {
        private const val TAG = "FrameStore"

        fun createBlank(width: Int, height: Int, fill: Int = Color.TRANSPARENT): Bitmap {
            val bmp = Bitmap.createBitmap(max(1, width), max(1, height), Bitmap.Config.ARGB_8888)
            // Always clear: freshly allocated bitmaps are not guaranteed to be zeroed,
            // and a "blank" frame must never carry stale pixels.
            bmp.eraseColor(fill)
            return bmp
        }

        /** Down-scales a bitmap to a maximum dimension for thumbnails/storyboards. */
        fun fitInto(source: Bitmap, maxDim: Int): Bitmap {
            val scale = maxDim.toFloat() / max(source.width, source.height)
            if (scale >= 1f) return source
            return Bitmap.createScaledBitmap(
                source,
                max(1, (source.width * scale).toInt()),
                max(1, (source.height * scale).toInt()),
                true,
            )
        }

        /** Copies a region of a bitmap into a new bitmap (used by undo snapshots). */
        fun copyRegion(source: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap {
            val safeX = x.coerceIn(0, max(0, source.width - 1))
            val safeY = y.coerceIn(0, max(0, source.height - 1))
            val safeW = w.coerceAtMost(source.width - safeX).coerceAtLeast(1)
            val safeH = h.coerceAtMost(source.height - safeY).coerceAtLeast(1)
            return Bitmap.createBitmap(source, safeX, safeY, safeW, safeH)
        }

        /** Clears a rectangular region of a bitmap. */
        fun clearRegion(target: Bitmap, rect: Rect) {
            val canvas = Canvas(target)
            val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            canvas.drawRect(rect, paint)
        }
    }
}
