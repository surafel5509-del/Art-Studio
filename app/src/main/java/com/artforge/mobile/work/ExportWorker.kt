package com.artforge.mobile.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.artforge.mobile.ArtForgeApplication
import com.artforge.mobile.core.util.ForgeLog
import com.artforge.mobile.data.export.AnimationExportOptions
import com.artforge.mobile.data.export.ExportEngine
import com.artforge.mobile.data.export.ImageExportOptions
import com.artforge.mobile.engine.draw.Compositor
import java.io.File

/**
 * Background exporter.
 *
 * Long animations (hundreds of frames, video encoding) must not run on the UI
 * thread or inside a composable scope: the export survives rotation, backgrounding
 * and screen-off, and reports progress through `WorkManager` progress data.
 *
 * Input data:
 *  - [KEY_PROJECT_ID]  project to load from disk
 *  - [KEY_KIND]        "image" or "animation"
 *  - [KEY_FORMAT]      [com.artforge.mobile.data.export.ImageFormat] or
 *                      [com.artforge.mobile.data.export.AnimationFormat] name
 *  - [KEY_FPS], [KEY_START], [KEY_END], [KEY_SCALE], [KEY_QUALITY],
 *    [KEY_LOOP], [KEY_COLUMNS], [KEY_BACKGROUND], [KEY_TRANSPARENT],
 *    [KEY_FRAME_INDEX]
 *
 * Output data: [KEY_OUTPUT_PATH] of the encoded file in the app cache, which the
 * caller then publishes through the Storage Access Framework.
 */
class ExportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as ArtForgeApplication
        val projectId = inputData.getString(KEY_PROJECT_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Missing project id"))

        val doc = app.projectRepository.loadProject(projectId)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Project $projectId could not be loaded"))

        setProgress(workDataOf(KEY_PROGRESS to 0.02f))

        val engine = ExportEngine(applicationContext, app.frameStore, Compositor(app.frameStore))
        val nameHint = ExportEngine.sanitizeFileName(doc.title)

        val result = try {
            when (inputData.getString(KEY_KIND) ?: KIND_ANIMATION) {
                KIND_IMAGE -> {
                    val format = runCatching {
                        com.artforge.mobile.data.export.ImageFormat.valueOf(
                            inputData.getString(KEY_FORMAT) ?: "Png",
                        )
                    }.getOrDefault(com.artforge.mobile.data.export.ImageFormat.Png)
                    val options = ImageExportOptions(
                        format = format,
                        quality = inputData.getInt(KEY_QUALITY, 95),
                        scale = inputData.getFloat(KEY_SCALE, 1f),
                        includeBackground = !inputData.getBoolean(KEY_TRANSPARENT, false),
                        frameIndex = inputData.getInt(KEY_FRAME_INDEX, 0),
                        transparent = inputData.getBoolean(KEY_TRANSPARENT, false),
                    )
                    engine.exportImage(doc, options, nameHint = nameHint) { progress ->
                        // Reporting progress is best-effort; the export itself must not fail on it.
                        runCatching { setProgress(workDataOf(KEY_PROGRESS to 0.02f + progress * 0.95f)) }
                    }
                }
                else -> {
                    val format = runCatching {
                        com.artforge.mobile.data.export.AnimationFormat.valueOf(
                            inputData.getString(KEY_FORMAT) ?: "Gif",
                        )
                    }.getOrDefault(com.artforge.mobile.data.export.AnimationFormat.Gif)
                    val options = AnimationExportOptions(
                        format = format,
                        fps = inputData.getInt(KEY_FPS, doc.animation.fps),
                        startFrame = inputData.getInt(KEY_START, 0),
                        endFrame = inputData.getInt(KEY_END, (doc.animation.frameCount - 1).coerceAtLeast(0)),
                        scale = inputData.getFloat(KEY_SCALE, 1f),
                        quality = inputData.getInt(KEY_QUALITY, 90),
                        includeBackground = inputData.getBoolean(KEY_BACKGROUND_ENABLED, true),
                        loop = inputData.getBoolean(KEY_LOOP, true),
                        spriteColumns = inputData.getInt(KEY_COLUMNS, 6),
                        background = inputData.getInt(KEY_BACKGROUND, 0xFFFFFFFF.toInt()),
                    )
                    engine.exportAnimation(doc, options, nameHint = nameHint) { progress ->
                        runCatching { setProgress(workDataOf(KEY_PROGRESS to 0.02f + progress * 0.95f)) }
                    }
                }
            }
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "export failed", t)
            com.artforge.mobile.data.export.ExportResult(false, null, t.message ?: "Export failed")
        }

        return if (result.success && result.file != null) {
            setProgress(workDataOf(KEY_PROGRESS to 1f))
            com.artforge.mobile.data.export.ExportDoneReceiver.record(applicationContext, result.file)
            Result.success(
                workDataOf(
                    KEY_OUTPUT_PATH to result.file.absolutePath,
                    KEY_MESSAGE to result.message,
                ),
            )
        } else {
            if (runAttemptCount < 2 && result.message.contains("memory", ignoreCase = true)) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR to result.message))
            }
        }
    }

    companion object {
        private const val TAG = "ExportWorker"

        const val UNIQUE_NAME = "artforge_export"

        const val KEY_PROJECT_ID = "project_id"
        const val KEY_KIND = "kind"
        const val KEY_FORMAT = "format"
        const val KEY_FPS = "fps"
        const val KEY_START = "start"
        const val KEY_END = "end"
        const val KEY_SCALE = "scale"
        const val KEY_QUALITY = "quality"
        const val KEY_LOOP = "loop"
        const val KEY_COLUMNS = "columns"
        const val KEY_BACKGROUND = "background"
        const val KEY_BACKGROUND_ENABLED = "background_enabled"
        const val KEY_TRANSPARENT = "transparent"
        const val KEY_FRAME_INDEX = "frame_index"

        const val KEY_PROGRESS = "progress"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_MESSAGE = "message"
        const val KEY_ERROR = "error"

        const val KIND_IMAGE = "image"
        const val KIND_ANIMATION = "animation"

        /** Enqueues an image export. Returns the WorkManager request id. */
        fun enqueueImage(
            context: Context,
            projectId: String,
            format: com.artforge.mobile.data.export.ImageFormat,
            scale: Float,
            quality: Int,
            transparent: Boolean,
            frameIndex: Int,
        ) = enqueue(
            context,
            Data.Builder()
                .putString(KEY_PROJECT_ID, projectId)
                .putString(KEY_KIND, KIND_IMAGE)
                .putString(KEY_FORMAT, format.name)
                .putFloat(KEY_SCALE, scale)
                .putInt(KEY_QUALITY, quality)
                .putBoolean(KEY_TRANSPARENT, transparent)
                .putInt(KEY_FRAME_INDEX, frameIndex)
                .build(),
        )

        /** Enqueues an animation export. */
        fun enqueueAnimation(
            context: Context,
            projectId: String,
            format: com.artforge.mobile.data.export.AnimationFormat,
            fps: Int,
            start: Int,
            end: Int,
            scale: Float,
            quality: Int,
            loop: Boolean,
            columns: Int,
            background: Int,
            includeBackground: Boolean,
        ) = enqueue(
            context,
            Data.Builder()
                .putString(KEY_PROJECT_ID, projectId)
                .putString(KEY_KIND, KIND_ANIMATION)
                .putString(KEY_FORMAT, format.name)
                .putInt(KEY_FPS, fps)
                .putInt(KEY_START, start)
                .putInt(KEY_END, end)
                .putFloat(KEY_SCALE, scale)
                .putInt(KEY_QUALITY, quality)
                .putBoolean(KEY_LOOP, loop)
                .putInt(KEY_COLUMNS, columns)
                .putInt(KEY_BACKGROUND, background)
                .putBoolean(KEY_BACKGROUND_ENABLED, includeBackground)
                .build(),
        )

        private fun enqueue(context: Context, data: Data): java.util.UUID {
            val request = OneTimeWorkRequestBuilder<ExportWorker>()
                .setInputData(data)
                .addTag(UNIQUE_NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            return request.id
        }

        /** Cancels any in-flight export (user pressed cancel). */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }

        /** Paths of finished exports still cached, newest first. */
        fun recentExports(context: Context): List<File> =
            File(context.cacheDir, "exports").listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
    }
}
