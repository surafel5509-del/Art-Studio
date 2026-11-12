package com.artforge.mobile.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.artforge.mobile.ArtForgeApplication
import com.artforge.mobile.core.util.ForgeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Background maintenance (WorkManager):
 *  1. clears stale export caches,
 *  2. autosaves projects whose work directory changed after the last save,
 *  3. prunes recovery snapshots older than a week,
 *  4. removes orphaned frame files that no manifest references.
 *
 * Everything runs offline and is safe to interrupt.
 */
class MaintenanceWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as ArtForgeApplication
        try {
            // 1. Export cache
            val exports = File(applicationContext.cacheDir, "exports")
            exports.listFiles()?.forEach { file ->
                if (System.currentTimeMillis() - file.lastModified() > TimeUnit.DAYS.toMillis(2)) file.delete()
            }

            // 2. Autosave projects modified since their last saved package.
            val projectsDir = File(applicationContext.filesDir, "projects")
            projectsDir.listFiles()?.filter { it.extension == "artforge" }?.forEach { pkg ->
                val id = pkg.nameWithoutExtension
                val workDir = File(File(applicationContext.filesDir, "frames"), id)
                if (workDir.exists() && workDir.lastModified() > pkg.lastModified()) {
                    val doc = app.projectRepository.loadProject(id)
                    if (doc != null) {
                        app.projectRepository.autosave(doc)
                        ForgeLog.i(TAG, "autosaved $id")
                    }
                }
            }

            // 3. Prune recovery snapshots older than a week so crashed sessions do
            //    not accumulate forever. Live autosaves are always newer than that.
            val weekAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
            runCatching {
                app.database.recoveryDao().listAll().forEach { entity ->
                    if (entity.savedAt < weekAgo) app.database.recoveryDao().clear(entity.projectId)
                }
            }.onFailure { ForgeLog.w(TAG, "recovery prune skipped: ${it.message}") }

            // 4. Orphaned frames: a frame dir without a matching package is dead weight.
            val framesRoot = File(applicationContext.filesDir, "frames")
            framesRoot.listFiles()?.forEach { dir ->
                if (!File(projectsDir, "${dir.name}.artforge").exists() &&
                    System.currentTimeMillis() - dir.lastModified() > TimeUnit.DAYS.toMillis(3) &&
                    dir.name.length > 30 // never touch in-progress session frames
                ) {
                    dir.deleteRecursively()
                }
            }

            Result.success()
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "maintenance failed", t)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "MaintenanceWorker"
        const val UNIQUE_NAME = "artforge_maintenance"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}

