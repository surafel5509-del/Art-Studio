package com.artforge.mobile

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import com.artforge.mobile.core.util.ForgeLog
import com.artforge.mobile.data.asset.AssetLibrary
import com.artforge.mobile.data.db.ForgeDatabase
import com.artforge.mobile.data.project.ProjectRepository
import com.artforge.mobile.data.settings.SettingsRepository
import com.artforge.mobile.engine.audio.AudioEngine
import com.artforge.mobile.engine.draw.FrameStore
import com.artforge.mobile.work.MaintenanceWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manual dependency container. A full DI framework would add build weight without
 * benefit for a single-module app, so the graph is explicit here and injected
 * through the Application into view models.
 */
class ArtForgeApplication : Application() {

    lateinit var database: ForgeDatabase
        private set
    lateinit var frameStore: FrameStore
        private set
    lateinit var projectRepository: ProjectRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var assetLibrary: AssetLibrary
        private set
    lateinit var audioEngine: AudioEngine
        private set

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Shared clipboard for copy/paste between layers and projects. */
    var clipboard: Bitmap? = null
    var clipboardMask: com.artforge.mobile.engine.draw.SelectionMask? = null

    override fun onCreate() {
        super.onCreate()
        ForgeLog.verbose = BuildConfig.DEBUG
        database = ForgeDatabase.get(this)
        frameStore = FrameStore(
            rootDir = File(filesDir, "frames"),
            maxCacheBytes = memoryBudgetBytes(),
        )
        projectRepository = ProjectRepository(this, database, frameStore)
        settingsRepository = SettingsRepository(this)
        assetLibrary = AssetLibrary(this, database.assetDao())
        audioEngine = AudioEngine(this)
        com.artforge.mobile.ui.common.ShareHelper.install(this)
        registerWorkers()
        seedBuiltInAssets()
    }

    /**
     * Sizes the frame cache from the device heap class: 1/4 of the available heap,
     * clamped to 96 MB..512 MB. This is what keeps large animation projects from
     * holding every frame in RAM.
     */
    private fun memoryBudgetBytes(): Long {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val heapMb = am.memoryClass
        val budgetMb = (heapMb / 4).coerceIn(96, 512)
        ForgeLog.i(TAG, "frame cache budget: ${budgetMb}MB (heap class ${heapMb}MB)")
        return budgetMb.toLong() * 1024 * 1024
    }

    private fun registerWorkers() {
        // Periodic maintenance: prune exports, autosave stale projects, vacuum caches.
        // WorkManager may not be initialised yet (or at all, e.g. under Robolectric or
        // when the initializer is disabled), so a failure here must never crash startup.
        runCatching { MaintenanceWorker.enqueue(this) }
            .onFailure { ForgeLog.w(TAG, "WorkManager unavailable: ${it.message}") }
    }

    private fun seedBuiltInAssets() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                // Seed built-in palettes once.
                BuiltIns.palettes.forEach { palette ->
                    database.paletteDao().upsert(
                        com.artforge.mobile.data.db.PaletteEntity(
                            id = palette.first,
                            payloadJson = palette.second,
                            updatedAt = 0L,
                        ),
                    )
                }
                MaintenanceWorker.enqueue(this@ArtForgeApplication)
            } catch (t: Throwable) {
                ForgeLog.e(TAG, "seeding failed", t)
            }
        }
    }

    companion object {
        private const val TAG = "ArtForgeApp"

        fun from(context: Context): ArtForgeApplication = context.applicationContext as ArtForgeApplication
    }
}

/** Small constants shared across the app. */
object BuiltIns {
    const val PREFS_ONBOARDING = "onboarding_complete"
    val palettes: List<Pair<String, String>> = emptyList()
    val workName = MaintenanceWorker.UNIQUE_NAME
}
