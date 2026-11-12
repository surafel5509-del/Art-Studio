package com.artforge.mobile.data.asset

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import com.artforge.mobile.core.util.ForgeLog
import com.artforge.mobile.data.db.AssetDao
import com.artforge.mobile.data.db.AssetEntity
import com.artforge.mobile.engine.draw.BrushLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Asset categories supported by the offline asset manager. */
enum class AssetCategory(val key: String, val label: String) {
    Brush("brush", "Brushes"),
    Texture("texture", "Textures"),
    Pattern("pattern", "Patterns"),
    Gradient("gradient", "Gradients"),
    Palette("palette", "Palettes"),
    Background("background", "Backgrounds"),
    Stamp("stamp", "Stamps"),
    Template("template", "Templates"),
    Reference("reference", "References"),
    Animation("animation", "Animation"),
    Font("font", "Fonts");

    companion object {
        fun fromKey(key: String): AssetCategory = entries.firstOrNull { it.key == key } ?: Texture
    }
}

/**
 * Offline-first asset manager: assets are files in app storage with Room metadata.
 * Everything here works without a network; import/export goes through SAF.
 */
class AssetLibrary(
    private val context: Context,
    private val dao: AssetDao,
) {

    private val assetsDir: File get() = File(context.filesDir, "assets").apply { mkdirs() }

    fun observeAll(): Flow<List<AssetEntity>> = dao.observeAll()

    fun dir(): File = assetsDir

    fun fileFor(category: AssetCategory, name: String): File =
        File(File(assetsDir, category.key).apply { mkdirs() }, name)

    /** Copies a picked document into the library and indexes it. */
    suspend fun import(uri: Uri, category: AssetCategory, displayName: String, tags: String = ""): AssetEntity? =
        withContext(Dispatchers.IO) {
            try {
                val name = "${UUID.randomUUID()}_${displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
                val target = fileFor(category, name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                } ?: return@withContext null
                val preview = generatePreview(target, category)
                val entity = AssetEntity(
                    id = UUID.randomUUID().toString(),
                    name = displayName.substringBeforeLast('.').ifBlank { displayName },
                    category = category.key,
                    tags = tags,
                    filePath = target.absolutePath,
                    previewPath = preview?.absolutePath,
                    importedAt = System.currentTimeMillis(),
                    builtIn = false,
                    folder = "Imported",
                )
                dao.upsert(entity)
                entity
            } catch (t: Throwable) {
                ForgeLog.e(TAG, "asset import failed", t)
                null
            }
        }

    /** Registers an in-memory bitmap asset (exported brushes, generated patterns). */
    suspend fun saveBitmap(bitmap: Bitmap, category: AssetCategory, displayName: String, tags: String = ""): AssetEntity? =
        withContext(Dispatchers.IO) {
            try {
                val file = fileFor(category, "${UUID.randomUUID()}_${displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")}.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val thumb = File(File(assetsDir, "previews").apply { mkdirs() }, file.name)
                thumb.outputStream().use { downscale(bitmap, 256).compress(Bitmap.CompressFormat.PNG, 90, it) }
                val entity = AssetEntity(
                    id = UUID.randomUUID().toString(),
                    name = displayName,
                    category = category.key,
                    tags = tags,
                    filePath = file.absolutePath,
                    previewPath = thumb.absolutePath,
                    importedAt = System.currentTimeMillis(),
                    builtIn = false,
                    folder = "Generated",
                )
                dao.upsert(entity)
                entity
            } catch (t: Throwable) {
                ForgeLog.e(TAG, "bitmap asset save failed", t)
                null
            }
        }

    suspend fun export(asset: AssetEntity, target: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val src = File(asset.filePath)
            if (!src.exists()) return@withContext false
            context.contentResolver.openOutputStream(target)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } != null
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "asset export failed", t)
            false
        }
    }

    suspend fun search(query: String): List<AssetEntity> = withContext(Dispatchers.IO) {
        if (query.isBlank()) dao.byCategory(AssetCategory.Texture.key) else dao.search(query)
    }

    suspend fun setFavorite(id: String, favorite: Boolean) = withContext(Dispatchers.IO) { dao.setFavorite(id, favorite) }

    suspend fun delete(asset: AssetEntity) = withContext(Dispatchers.IO) {
        File(asset.filePath).delete()
        File(asset.previewPath ?: "").delete()
        dao.delete(asset.id)
    }

    fun loadBitmap(asset: AssetEntity): Bitmap? = runCatching {
        BitmapFactory.decodeFile(asset.filePath)
    }.getOrNull()

    private fun generatePreview(file: File, category: AssetCategory): File? = runCatching {
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val preview = File(File(assetsDir, "previews").apply { mkdirs() }, file.name)
        downscale(bmp, 256).let { small ->
            preview.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 90, it) }
            if (small !== bmp) small.recycle()
        }
        bmp.recycle()
        preview
    }.getOrNull()

    private fun downscale(source: Bitmap, maxDim: Int): Bitmap {
        val scale = maxDim.toFloat() / maxOf(source.width, source.height)
        return if (scale >= 1f) source else Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    companion object {
        private const val TAG = "AssetLibrary"

        /** Generates the built-in paper/grain textures procedurally (offline, no assets shipped). */
        fun generateBuiltInTextures(size: Int = 512): Map<String, Bitmap> {
            val textures = LinkedHashMap<String, Bitmap>()
            textures["grain_fine"] = noiseTexture(size, 0.12f, 1337L)
            textures["grain_coarse"] = noiseTexture(size, 0.28f, 7331L)
            textures["canvas_weave"] = weaveTexture(size)
            textures["paper_fibers"] = fiberTexture(size)
            textures["halftone_dots"] = dotTexture(size)
            return textures
        }

        private fun noiseTexture(size: Int, strength: Float, seed: Long): Bitmap {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val rng = java.util.Random(seed)
            val pixels = IntArray(size * size)
            for (i in pixels.indices) {
                val v = (128 + ((rng.nextFloat() - 0.5f) * strength * 255)).toInt().coerceIn(0, 255)
                pixels[i] = Color.argb(255, v, v, v)
            }
            bmp.setPixels(pixels, 0, size, 0, 0, size, size)
            return bmp
        }

        private fun weaveTexture(size: Int): Bitmap {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val weave = (kotlin.math.sin(x * 0.9) * 0.5 + kotlin.math.cos(y * 0.9) * 0.5)
                    val v = (200 + weave * 30).toInt().coerceIn(0, 255)
                    pixels[y * size + x] = Color.argb(255, v, v, v)
                }
            }
            bmp.setPixels(pixels, 0, size, 0, 0, size, size)
            return bmp
        }

        private fun fiberTexture(size: Int): Bitmap {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.argb(255, 240, 238, 232))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 1f
                color = Color.argb(30, 90, 90, 90)
            }
            val rng = java.util.Random(4242L)
            repeat(size * 3) {
                val x = rng.nextFloat() * size
                val y = rng.nextFloat() * size
                canvas.drawLine(x, y, x + rng.nextFloat() * 24 - 12, y + rng.nextFloat() * 24 - 12, paint)
            }
            return bmp
        }

        private fun dotTexture(size: Int): Bitmap {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
            val step = 12f
            var y = 0f
            var row = 0
            while (y < size) {
                var x = if (row % 2 == 0) 0f else step / 2f
                while (x < size) {
                    canvas.drawCircle(x, y, 2.4f, paint)
                    x += step
                }
                y += step
                row++
            }
            return bmp
        }

        /** Built-in gradient presets shown in the asset library. */
        fun builtInGradients(): List<Pair<String, IntArray>> = listOf(
            "Sunset" to intArrayOf(0xFF2B1055.toInt(), 0xFF7597DE.toInt(), 0xFFFF8A4C.toInt()),
            "Ocean" to intArrayOf(0xFF003B4A.toInt(), 0xFF0E7C7B.toInt(), 0xFF7FD1AE.toInt()),
            "Ember" to intArrayOf(0xFF1A1A1A.toInt(), 0xFFB83D0F.toInt(), 0xFFFFD166.toInt()),
            "Mint" to intArrayOf(0xFF0B3D2E.toInt(), 0xFF2EC4B6.toInt(), 0xFFE0FBF6.toInt()),
            "Grape" to intArrayOf(0xFF2D0B45.toInt(), 0xFF8A4FFF.toInt(), 0xFFE9C7FF.toInt()),
            "Steel" to intArrayOf(0xFF11151C.toInt(), 0xFF44546A.toInt(), 0xFFBCC8D6.toInt()),
        )

        fun gradientPreview(colors: IntArray, width: Int = 220, height: Int = 64): Bitmap {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, colors, null, Shader.TileMode.CLAMP)
            canvas.drawRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
            return bmp
        }

        /** Default colour palettes shipped with the app. */
        fun builtInPalettes(): List<Pair<String, List<Int>>> = listOf(
            "Forge Core" to listOf(
                0xFF121316.toInt(), 0xFF1B1D22.toInt(), 0xFF2A2D34.toInt(), 0xFF6C8CFF.toInt(),
                0xFFFF8A4C.toInt(), 0xFFF5F1E8.toInt(), 0xFF8FA7C4.toInt(), 0xFF3D2C8D.toInt(),
            ),
            "Skin Tones" to listOf(
                0xFFFFE0C4.toInt(), 0xFFF1C27D.toInt(), 0xFFE0AC69.toInt(), 0xFFC68642.toInt(),
                0xFF8D5524.toInt(), 0xFF5C3317.toInt(),
            ),
            "Comic CMYK" to listOf(
                0xFF00AEEF.toInt(), 0xFFEC008C.toInt(), 0xFFFFF200.toInt(), 0xFF000000.toInt(),
                0xFFFFFFFF.toInt(), 0xFF808080.toInt(),
            ),
            "Pixel 16" to listOf(
                0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFBE2633.toInt(), 0xFFE06F8B.toInt(),
                0xFF493C2B.toInt(), 0xFFA46422.toInt(), 0xFFEB8931.toInt(), 0xFFF7E26B.toInt(),
                0xFF2F484E.toInt(), 0xFF44891A.toInt(), 0xFFA3CE27.toInt(), 0xFF1B2632.toInt(),
                0xFF005784.toInt(), 0xFF31A2F2.toInt(), 0xFFB2DCEF.toInt(), 0xFF8C8C8C.toInt(),
            ),
            "Manga Greys" to listOf(
                0xFF000000.toInt(), 0xFF222222.toInt(), 0xFF444444.toInt(), 0xFF666666.toInt(),
                0xFF888888.toInt(), 0xFFAAAAAA.toInt(), 0xFFCCCCCC.toInt(), 0xFFFFFFFF.toInt(),
            ),
        )

        /** Available brush packs (each maps to a folder in [BrushLibrary]). */
        fun brushPacks(): List<String> = BrushLibrary.folders()

        /** Built-in canvas templates shown on the home screen. */
        fun builtInTemplates(): List<CanvasTemplate> = listOf(
            CanvasTemplate("Illustration 2K", 2048, 2048, "Illustration", "#8FA7C4"),
            CanvasTemplate("Illustration 4K", 4096, 4096, "Illustration", "#8FA7C4"),
            CanvasTemplate("Portrait 2:3", 2000, 3000, "Portrait", "#F1C27D"),
            CanvasTemplate("Landscape 16:9", 3840, 2160, "Landscape", "#0E7C7B"),
            CanvasTemplate("Square Post", 1080, 1080, "Social", "#FF8A4C"),
            CanvasTemplate("Story 9:16", 1080, 1920, "Social", "#8A4FFF"),
            CanvasTemplate("A4 Print", 2480, 3508, "Print", "#F5F1E8"),
            CanvasTemplate("A3 Print", 3508, 4961, "Print", "#F5F1E8"),
            CanvasTemplate("A5 Print", 1748, 2480, "Print", "#F5F1E8"),
            CanvasTemplate("Animation HD", 1920, 1080, "Animation", "#6C8CFF"),
            CanvasTemplate("Sprite 64", 64, 64, "Pixel", "#A3CE27"),
            CanvasTemplate("Sprite 128", 128, 128, "Pixel", "#31A2F2"),
            CanvasTemplate("Comic Page", 2100, 3150, "Comic", "#2A2D34"),
            CanvasTemplate("Manga Page B5", 2150, 3035, "Comic", "#444444"),
            CanvasTemplate("Webtoon Strip", 1080, 4000, "Comic", "#1B1D22"),
        )
    }
}

data class CanvasTemplate(
    val name: String,
    val width: Int,
    val height: Int,
    val category: String,
    val accent: String,
)
