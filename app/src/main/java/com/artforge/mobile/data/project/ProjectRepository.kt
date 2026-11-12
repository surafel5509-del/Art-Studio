package com.artforge.mobile.data.project

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.artforge.mobile.core.model.AnimationConfig
import com.artforge.mobile.core.model.DocumentSettings
import com.artforge.mobile.core.model.FrameMeta
import com.artforge.mobile.core.model.Layer
import com.artforge.mobile.core.model.LayerKind
import com.artforge.mobile.core.model.PixelConfig
import com.artforge.mobile.core.model.ProjectDocument
import com.artforge.mobile.core.model.ProjectKind
import com.artforge.mobile.core.model.ProjectSummary
import com.artforge.mobile.core.util.ForgeLog
import com.artforge.mobile.data.db.ForgeDatabase
import com.artforge.mobile.data.db.ProjectEntity
import com.artforge.mobile.data.db.RecoveryEntity
import com.artforge.mobile.data.db.VersionEntity
import com.artforge.mobile.engine.draw.FrameStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Owns the on-disk `.artforge` files, the frame store, autosave/crash recovery,
 * version history and import/export of project packages.
 */
class ProjectRepository(
    private val context: Context,
    private val db: ForgeDatabase,
    private val frameStore: FrameStore,
) {

    private val projectsDir: File get() = File(context.filesDir, "projects").apply { mkdirs() }
    private val exportsDir: File get() = File(context.cacheDir, "exports").apply { mkdirs() }
    private val audioDir: File get() = File(context.filesDir, "audio").apply { mkdirs() }
    private val backupsDir: File get() = File(context.filesDir, "backups").apply { mkdirs() }
    private val versionsDir: File get() = File(context.filesDir, "versions").apply { mkdirs() }

    val projectDao get() = db.projectDao()
    val recoveryDao get() = db.recoveryDao()
    val versionDao get() = db.versionDao()

    fun newProjectId(): String = UUID.randomUUID().toString()

    // -----------------------------------------------------------------------
    // Creation
    // -----------------------------------------------------------------------

    /**
     * Creates a new project document with sensible defaults per [kind]: an
     * animation project gets one layer with an animated frame track, a comic gets
     * page templates, a pixel project gets a small tile canvas.
     */
    fun createDocument(
        kind: ProjectKind,
        title: String,
        width: Int,
        height: Int,
        fps: Int = 24,
        frameCount: Int = 24,
        transparent: Boolean = false,
        backgroundColor: Int = 0xFFFFFFFF.toInt(),
    ): ProjectDocument {
        val now = System.currentTimeMillis()
        val baseLayer = Layer(
            id = UUID.randomUUID().toString(),
            name = "Layer 1",
            kind = LayerKind.Raster,
        )
        val settings = DocumentSettings(
            width = width,
            height = height,
            backgroundColor = backgroundColor,
            transparentBackground = transparent,
        )
        return when (kind) {
            ProjectKind.Animation -> {
                val frameId = UUID.randomUUID().toString()
                val animLayer = baseLayer.copy(
                    name = "Anim Layer 1",
                    kind = LayerKind.Animation,
                    frames = listOf(com.artforge.mobile.core.model.FrameRef(frameId, 0.0)),
                    activeFrameId = frameId,
                )
                ProjectDocument(
                    id = newProjectId(),
                    title = title,
                    kind = kind,
                    settings = settings,
                    layers = listOf(animLayer),
                    activeLayerId = animLayer.id,
                    animation = AnimationConfig(
                        fps = fps,
                        frameCount = frameCount,
                        frames = listOf(FrameMeta(frameId, 1, "", true)),
                        scenes = listOf(com.artforge.mobile.engine.animation.AnimationEngine.defaultScene(0, frameCount)),
                    ),
                    createdAt = now,
                    modifiedAt = now,
                )
            }
            ProjectKind.PixelArt -> {
                val frameId = UUID.randomUUID().toString()
                ProjectDocument(
                    id = newProjectId(),
                    title = title,
                    kind = kind,
                    settings = settings.copy(width = width, height = height, transparentBackground = true),
                    layers = listOf(baseLayer.copy(name = "Pixels", activeFrameId = frameId)),
                    activeLayerId = baseLayer.id,
                    pixel = PixelConfig(tileWidth = width, tileHeight = height),
                    createdAt = now,
                    modifiedAt = now,
                )
            }
            ProjectKind.Comic -> {
                val frameId = UUID.randomUUID().toString()
                ProjectDocument(
                    id = newProjectId(),
                    title = title,
                    kind = kind,
                    settings = settings,
                    layers = listOf(baseLayer.copy(name = "Page 1", activeFrameId = frameId)),
                    activeLayerId = baseLayer.id,
                    pages = listOf(com.artforge.mobile.core.model.ComicPage(index = 0)),
                    createdAt = now,
                    modifiedAt = now,
                )
            }
            else -> {
                ProjectDocument(
                    id = newProjectId(),
                    title = title,
                    kind = kind,
                    settings = settings,
                    layers = listOf(baseLayer),
                    activeLayerId = baseLayer.id,
                    createdAt = now,
                    modifiedAt = now,
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    fun projectFile(id: String): File = File(projectsDir, "$id.artforge")

    private fun projectWorkDir(id: String): File = File(projectsDir, id).apply { mkdirs() }

    /** Runs [block] inside the frame store's dirty tracking for a project. */
    fun registerProject(doc: ProjectDocument) {
        frameStore.setProjectHint(doc.id)
        doc.layers.forEach { layer ->
            layer.frames.forEach { frameStore.registerFrame(doc.id, it.frameId) }
            layer.activeFrameId?.let { frameStore.registerFrame(doc.id, it) }
            layer.maskFrameId?.let { frameStore.registerFrame(doc.id, it) }
        }
    }

    /** Ensures every referenced frame exists on disk (blank if never painted). */
    suspend fun ensureFrames(doc: ProjectDocument) = withContext(Dispatchers.IO) {
        registerProject(doc)
        val ids = ProjectSerializer.collectFrameIds(doc)
        for (id in ids) {
            if (!frameStore.hasFrameOnDisk(doc.id, id)) {
                frameStore.createBlankFrame(doc.id, id, doc.settings.width, doc.settings.height)
                frameStore.flushFrame(doc.id, id)
            } else {
                frameStore.load(doc.id, id, doc.settings.width, doc.settings.height)
            }
        }
    }

    /** Writes the project package and updates the metadata row. */
    suspend fun saveProject(doc: ProjectDocument, thumbnail: Bitmap? = null, versioned: Boolean = false) = withContext(Dispatchers.IO) {
        registerProject(doc)
        frameStore.flushAll(doc.id)

        val frameIds = ProjectSerializer.collectFrameIds(doc)
        val manifest = ProjectSerializer.toManifest(doc, frameIds, audioFilesFor(doc.id))
        val target = projectFile(doc.id)
        val temp = File(target.parentFile, "${target.name}.tmp")

        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                val manifestEntry = ZipEntry("manifest.json")
                zip.putNextEntry(manifestEntry)
                zip.write(ProjectSerializer.encodeManifest(manifest).toByteArray())
                zip.closeEntry()

                for (frameId in frameIds) {
                    val file = frameStore.frameFile(doc.id, frameId)
                    if (!file.exists()) continue
                    zip.putNextEntry(ZipEntry("frames/$frameId.png"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }

                for (audio in audioFilesFor(doc.id)) {
                    val file = File(audioDir, "${doc.id}/$audio")
                    if (!file.exists()) continue
                    zip.putNextEntry(ZipEntry("audio/$audio"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }

                if (thumbnail != null) {
                    zip.putNextEntry(ZipEntry("thumbnail.png"))
                    thumbnail.compress(Bitmap.CompressFormat.PNG, 90, zip)
                    zip.closeEntry()
                }
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "save failed for ${doc.id}", t)
            temp.delete()
            throw t
        }

        val thumbnailFile = saveThumbnail(doc.id, thumbnail)
        projectDao.upsert(
            ProjectEntity(
                id = doc.id,
                title = doc.title,
                kind = doc.kind.name,
                width = doc.settings.width,
                height = doc.settings.height,
                frameCount = doc.animation.frameCount,
                layerCount = doc.layers.size,
                createdAt = doc.createdAt,
                modifiedAt = System.currentTimeMillis(),
                lastOpenedAt = System.currentTimeMillis(),
                thumbnailPath = thumbnailFile?.absolutePath,
                filePath = target.absolutePath,
            ),
        )
        recoveryDao.clear(doc.id)
        if (versioned) saveVersion(doc)
        target
    }

    private fun saveThumbnail(projectId: String, thumbnail: Bitmap?): File? {
        if (thumbnail == null) return File(File(projectsDir, projectId), "thumb.png").takeIf { it.exists() }
        val dir = projectWorkDir(projectId)
        val file = File(dir, "thumb.png")
        return try {
            file.outputStream().use { thumbnail.compress(Bitmap.CompressFormat.PNG, 88, it) }
            file
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "thumbnail write failed", t)
            null
        }
    }

    private fun audioFilesFor(projectId: String): List<String> =
        File(audioDir, projectId).listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()

    /** Loads a project package back into memory and primes the frame store. */
    suspend fun loadProject(id: String): ProjectDocument? = withContext(Dispatchers.IO) {
        val file = projectFile(id)
        if (!file.exists()) return@withContext null
        try {
            // Extract frames into the frame store's working directory.
            val frameDir = File(File(context.filesDir, "frames"), id).apply { mkdirs() }
            var manifest: ArtforgeManifest? = null
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when {
                        entry.name == "manifest.json" -> {
                            manifest = ProjectSerializer.decodeManifest(zip.readBytes().toString(Charsets.UTF_8))
                        }
                        entry.name.startsWith("frames/") && entry.name.endsWith(".png") -> {
                            val name = entry.name.removePrefix("frames/")
                            File(frameDir, name).outputStream().use { zip.copyTo(it) }
                        }
                        entry.name.startsWith("audio/") -> {
                            val name = entry.name.removePrefix("audio/")
                            val audioFile = File(File(audioDir, id).apply { mkdirs() }, name)
                            audioFile.outputStream().use { zip.copyTo(it) }
                        }
                        entry.name == "thumbnail.png" -> {
                            val dir = projectWorkDir(id)
                            File(dir, "thumb.png").outputStream().use { zip.copyTo(it) }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val m = manifest ?: return@withContext null
            val migrated = ProjectSerializer.migrate(m)
            val doc = ProjectSerializer.toDocument(migrated)
            registerProject(doc)
            projectDao.touch(doc.id, System.currentTimeMillis())
            doc
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "load failed for $id", t)
            null
        }
    }

    /** Imports an external `.artforge` file (from SAF) into local storage. */
    suspend fun importProject(uri: Uri, copyTo: File? = null): ProjectDocument? = withContext(Dispatchers.IO) {
        try {
            val incoming = File.createTempFile("import", ".artforge", context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                incoming.outputStream().use { out -> input.copyTo(out) }
            } ?: return@withContext null

            // Peek the manifest to learn the project id, then place the package properly.
            var id: String? = null
            ZipInputStream(incoming.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "manifest.json") {
                        id = ProjectSerializer.decodeManifest(zip.readBytes().toString(Charsets.UTF_8)).id
                        break
                    }
                    zip.closeEntry()
                }
            }
            if (id == null) {
                incoming.delete()
                return@withContext null
            }
            val target = projectFile(id)
            // Never overwrite silently: suffix a copy if the id already exists.
            val finalTarget = if (target.exists()) {
                val newId = UUID.randomUUID().toString()
                File(projectsDir, "$newId.artforge").also { t ->
                    t.outputStream().use { out -> incoming.inputStream().use { it.copyTo(out) } }
                    rewriteManifestId(t, newId)
                }
            } else {
                target.also { t -> incoming.copyTo(t, overwrite = true) }
            }
            incoming.delete()
            val newId = finalTarget.nameWithoutExtension
            loadProject(newId)
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "import failed", t)
            null
        }
    }

    /** Rewrites the id inside an imported package so it becomes a distinct project. */
    private fun rewriteManifestId(packageFile: File, newId: String) {
        try {
            val entries = LinkedHashMap<String, ByteArray>()
            ZipInputStream(packageFile.inputStream()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    entries[e.name] = zip.readBytes()
                    zip.closeEntry()
                }
            }
            val manifestBytes = entries["manifest.json"] ?: return
            val manifest = ProjectSerializer.decodeManifest(manifestBytes.toString(Charsets.UTF_8))
            val updated = ProjectSerializer.encodeManifest(manifest.copy(id = newId, title = "${manifest.title} (imported)"))
            entries["manifest.json"] = updated.toByteArray()
            ZipOutputStream(packageFile.outputStream()).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "manifest rewrite failed", t)
        }
    }

    /** Exports the project package to a user-chosen SAF location. */
    suspend fun exportProject(doc: ProjectDocument, target: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            saveProject(doc)
            val file = projectFile(doc.id)
            context.contentResolver.openOutputStream(target)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: return@withContext false
            true
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "project export failed", t)
            false
        }
    }

    // -----------------------------------------------------------------------
    // Recovery and versions
    // -----------------------------------------------------------------------

    /** Autosave: writes a recovery snapshot without touching the main package. */
    suspend fun autosave(doc: ProjectDocument) = withContext(Dispatchers.IO) {
        try {
            frameStore.flushAll(doc.id)
            val manifest = ProjectSerializer.toManifest(doc, ProjectSerializer.collectFrameIds(doc), audioFilesFor(doc.id))
            recoveryDao.save(
                RecoveryEntity(
                    projectId = doc.id,
                    savedAt = System.currentTimeMillis(),
                    documentJson = ProjectSerializer.encodeManifest(manifest),
                    autoSave = true,
                ),
            )
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "autosave failed", t)
        }
    }

    suspend fun recoveryFor(projectId: String) = withContext(Dispatchers.IO) {
        val entity = recoveryDao.find(projectId) ?: return@withContext null
        val manifest = runCatching { ProjectSerializer.decodeManifest(entity.documentJson) }.getOrNull()
            ?: return@withContext null
        RecoveryInfo(entity.savedAt, ProjectSerializer.toDocument(manifest))
    }

    data class RecoveryInfo(val savedAt: Long, val document: ProjectDocument)

    fun observeRecoveries(): Flow<List<RecoveryEntity>> = recoveryDao.observeAll()

    suspend fun discardRecovery(projectId: String) = withContext(Dispatchers.IO) { recoveryDao.clear(projectId) }

    /** Stores a version snapshot (full package copy) for the history panel. */
    suspend fun saveVersion(doc: ProjectDocument, label: String = "Auto") = withContext(Dispatchers.IO) {
        try {
            val dir = File(versionsDir, doc.id).apply { mkdirs() }
            val stamp = System.currentTimeMillis()
            val snapshot = File(dir, "$stamp.artforge")
            projectFile(doc.id).copyTo(snapshot, overwrite = true)
            versionDao.insert(
                VersionEntity(
                    projectId = doc.id,
                    savedAt = stamp,
                    label = label,
                    snapshotPath = snapshot.absolutePath,
                    sizeBytes = snapshot.length(),
                ),
            )
            versionDao.prune(doc.id, keep = 12)
            // Keep at most 12 snapshot files, matching the database prune above.
            dir.listFiles()
                ?.sortedByDescending { it.name }
                ?.drop(12)
                ?.forEach { stale -> stale.delete() }
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "version snapshot failed", t)
        }
    }

    suspend fun listVersions(projectId: String) = withContext(Dispatchers.IO) { versionDao.list(projectId) }

    /** Restores a snapshot into the live project (current state is versioned first). */
    suspend fun restoreVersion(doc: ProjectDocument, version: VersionEntity): ProjectDocument? = withContext(Dispatchers.IO) {
        try {
            saveVersion(doc, "Before restore")
            val snapshot = File(version.snapshotPath)
            if (!snapshot.exists()) return@withContext null
            snapshot.copyTo(projectFile(doc.id), overwrite = true)
            loadProject(doc.id)
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "restore failed", t)
            null
        }
    }

    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) {
        projectFile(id).delete()
        frameStore.deleteProject(id)
        File(File(context.filesDir, "frames"), id).deleteRecursively()
        File(projectsDir, id).deleteRecursively()
        File(audioDir, id).deleteRecursively()
        File(versionsDir, id).deleteRecursively()
        db.projectDao().delete(id)
    }

    suspend fun duplicateProject(doc: ProjectDocument, newTitle: String): ProjectDocument = withContext(Dispatchers.IO) {
        val copy = doc.copy(
            id = newProjectId(),
            title = newTitle,
            createdAt = System.currentTimeMillis(),
            modifiedAt = System.currentTimeMillis(),
        )
        registerProject(copy)
        // Copy every drawing's pixels to the new project id.
        doc.layers.forEach { layer ->
            val ids = (layer.frames.map { it.frameId } + listOfNotNull(layer.activeFrameId, layer.maskFrameId)).distinct()
            ids.forEach { frameId ->
                frameStore.registerFrame(doc.id, frameId)
                frameStore.duplicateFrame(doc.id, frameId, frameId, doc.settings.width, doc.settings.height)
            }
        }
        frameStore.flushAll(copy.id)
        saveProject(copy)
        copy
    }

    suspend fun rename(id: String, title: String) = withContext(Dispatchers.IO) {
        projectDao.rename(id, title, System.currentTimeMillis())
    }

    fun observeRecent(): Flow<List<ProjectSummary>> = projectDao.observeRecent().map { list -> list.map { it.toSummary() } }

    fun observeFavorites(): Flow<List<ProjectSummary>> = projectDao.observeFavorites().map { list -> list.map { it.toSummary() } }

    suspend fun search(query: String): List<ProjectSummary> = withContext(Dispatchers.IO) {
        projectDao.search(query).map { it.toSummary() }
    }

    // -----------------------------------------------------------------------
    // Backups
    // -----------------------------------------------------------------------

    /** Copies the live package into the local backup folder (offline safety net). */
    suspend fun createBackup(doc: ProjectDocument): File? = withContext(Dispatchers.IO) {
        try {
            saveProject(doc)
            val dir = File(backupsDir, doc.id).apply { mkdirs() }
            val target = File(dir, "${System.currentTimeMillis()}.artforge")
            projectFile(doc.id).copyTo(target, overwrite = true)
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }
            target
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "backup failed", t)
            null
        }
    }

    suspend fun listBackups(projectId: String): List<File> = withContext(Dispatchers.IO) {
        File(backupsDir, projectId).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun audioDirectory(projectId: String): File = File(audioDir, projectId).apply { mkdirs() }

    fun exportDirectory(): File = exportsDir

    companion object {
        private const val TAG = "ProjectRepository"

        fun summaryOf(entity: ProjectEntity): ProjectSummary = ProjectSummary(
            id = entity.id,
            title = entity.title,
            kind = runCatching { ProjectKind.valueOf(entity.kind) }.getOrDefault(ProjectKind.Illustration),
            width = entity.width,
            height = entity.height,
            frameCount = entity.frameCount,
            layerCount = entity.layerCount,
            modifiedAt = entity.modifiedAt,
            createdAt = entity.createdAt,
            thumbnailPath = entity.thumbnailPath,
            filePath = entity.filePath,
        )
    }
}

private fun ProjectEntity.toSummary(): ProjectSummary = ProjectSummary(
    id = id,
    title = title,
    kind = runCatching { ProjectKind.valueOf(kind) }.getOrDefault(ProjectKind.Illustration),
    width = width,
    height = height,
    frameCount = frameCount,
    layerCount = layerCount,
    modifiedAt = modifiedAt,
    createdAt = createdAt,
    thumbnailPath = thumbnailPath,
    filePath = filePath,
)
