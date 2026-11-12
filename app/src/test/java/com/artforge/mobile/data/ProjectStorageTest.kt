package com.artforge.mobile.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artforge.mobile.core.model.Layer
import com.artforge.mobile.core.model.LayerKind
import com.artforge.mobile.core.model.ProjectDocument
import com.artforge.mobile.core.model.ProjectKind
import com.artforge.mobile.data.db.ForgeDatabase
import com.artforge.mobile.data.project.ProjectRepository
import com.artforge.mobile.data.project.ProjectSerializer
import com.artforge.mobile.engine.draw.FrameStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Round-trip tests for `.artforge` project storage: create → paint → save → load,
 * with layer structure, animation config and pixels preserved.
 */
@RunWith(AndroidJUnit4::class)
class ProjectStorageTest {

    private lateinit var db: ForgeDatabase
    private lateinit var store: FrameStore
    private lateinit var repo: ProjectRepository
    private lateinit var appContext: android.content.Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        db = androidx.room.Room.inMemoryDatabaseBuilder(appContext, ForgeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = FrameStore(File(appContext.cacheDir, "storage_test_frames"), 32L * 1024 * 1024)
        repo = ProjectRepository(appContext, db, store)
    }

    @After
    fun tearDown() {
        store.release()
        db.close()
    }

    @Test
    fun `manifest json round trips a document`() {
        val doc = repo.createDocument(ProjectKind.Animation, "Walk cycle", 512, 512, fps = 12, frameCount = 8)
        val frameIds = ProjectSerializer.collectFrameIds(doc)
        val manifest = ProjectSerializer.toManifest(doc, frameIds, listOf("track.mp3"))
        val json = ProjectSerializer.encodeManifest(manifest)
        val decoded = ProjectSerializer.decodeManifest(json)
        val restored = ProjectSerializer.toDocument(decoded)
        assertEquals(doc.id, restored.id)
        assertEquals(doc.title, restored.title)
        assertEquals(doc.settings.width, restored.settings.width)
        assertEquals(doc.animation.fps, restored.animation.fps)
        assertEquals(doc.layers.size, restored.layers.size)
        // Audio files deliberately do not exist on disk, so the manifest drops them.
        assertEquals(frameIds, decoded.frameIds)
        assertEquals(listOf("track.mp3"), decoded.audioFiles)
    }

    @Test
    fun `image and animation projects seed a drawable frame`() {
        val animation = repo.createDocument(ProjectKind.Animation, "Anim", 256, 256, 24, 12)
        assertEquals(LayerKind.Animation, animation.layers.first().kind)
        assertNotNull(animation.layers.first().activeFrameId)
        assertEquals(1, animation.layers.first().frames.size)

        val comic = repo.createDocument(ProjectKind.Comic, "Comic", 1000, 1400)
        assertEquals(ProjectKind.Comic, comic.kind)
        assertEquals(1, comic.pages.size)
    }

    @Test
    fun `save and load preserves pixels and structure`() = runBlocking {
        val doc = repo.createDocument(ProjectKind.Illustration, "Saved", 128, 128)
        val layer = doc.layers.first()
        val frameId = layer.activeFrameId!!
        repo.registerProject(doc)
        val bitmap = store.createBlankFrame(doc.id, frameId, 128, 128)
        // Paint a rectangle through the real pixel API rather than filling the canvas,
        // so the assertion exercises getPixels/setPixels round-tripping too.
        com.artforge.mobile.engine.pixel.PixelEngine.rect(bitmap, 8, 8, 40, 40, 0xFF3366CC.toInt(), fill = true)
        store.markDirty(frameId)

        val second = Layer(
            id = "second",
            name = "Second",
            activeFrameId = "second_frame",
            frames = listOf(com.artforge.mobile.core.model.FrameRef("second_frame", 0.0)),
        )
        val withSecond = doc.copy(layers = doc.layers + second)
        repo.registerProject(withSecond)
        val secondBmp = store.createBlankFrame(withSecond.id, "second_frame", 128, 128)
        com.artforge.mobile.engine.pixel.PixelEngine.rect(secondBmp, 8, 8, 40, 40, 0xFFFF8800.toInt(), fill = true)
        store.markDirty("second_frame")

        repo.saveProject(withSecond)
        assertTrue(repo.projectFile(withSecond.id).exists())

        store.release()
        val loaded = repo.loadProject(withSecond.id)
        assertNotNull(loaded)
        assertEquals(2, loaded!!.layers.size)
        assertEquals("Second", loaded.layers.last().name)

        val loadedBitmap = store.load(loaded.id, frameId, 128, 128)
        assertEquals(0xFF3366CC.toInt(), loadedBitmap.getPixel(10, 10))
        val loadedSecond = store.load(loaded.id, "second_frame", 128, 128)
        assertEquals(0xFFFF8800.toInt(), loadedSecond.getPixel(20, 20))
    }

    @Test
    fun `project metadata is indexed in room`() = runBlocking {
        val doc = repo.createDocument(ProjectKind.PixelArt, "Sprite", 64, 64)
        repo.registerProject(doc)
        store.createBlankFrame(doc.id, doc.layers.first().activeFrameId!!, 64, 64)
        repo.saveProject(doc)
        val entity = repo.projectDao.findById(doc.id)
        assertNotNull(entity)
        assertEquals("Sprite", entity!!.title)
        assertEquals(64, entity.width)
        repo.rename(doc.id, "Sprite Renamed")
        assertEquals("Sprite Renamed", repo.projectDao.findById(doc.id)!!.title)
        repo.deleteProject(doc.id)
        assertEquals(null, repo.projectDao.findById(doc.id))
    }

    @Test
    fun `autosave snapshot can be recovered`() = runBlocking {
        val doc = repo.createDocument(ProjectKind.Illustration, "Recover me", 64, 64)
        repo.registerProject(doc)
        store.createBlankFrame(doc.id, doc.layers.first().activeFrameId!!, 64, 64)
        repo.autosave(doc)
        val recovered = repo.recoveryFor(doc.id)
        assertNotNull(recovered)
        assertEquals(doc.title, recovered!!.document.title)
        repo.discardRecovery(doc.id)
        assertEquals(null, repo.recoveryFor(doc.id))
    }

    @Test
    fun `duplicated project gets a new id and keeps artwork`() = runBlocking {
        val doc = repo.createDocument(ProjectKind.Illustration, "Original", 64, 64)
        repo.registerProject(doc)
        val frameId = doc.layers.first().activeFrameId!!
        val bitmap = store.createBlankFrame(doc.id, frameId, 64, 64)
        com.artforge.mobile.engine.pixel.PixelEngine.rect(bitmap, 4, 4, 30, 30, 0xFF00FF00.toInt(), fill = true)
        store.markDirty(frameId)
        repo.saveProject(doc)

        val copy = repo.duplicateProject(doc, "Copy")
        assertTrue(copy.id != doc.id)
        assertEquals("Copy", copy.title)
        val copyBitmap = store.load(copy.id, frameId, 64, 64)
        assertEquals(0xFF00FF00.toInt(), copyBitmap.getPixel(10, 10))
    }

    @Test
    fun `schema migration fills in missing frame metadata`() {
        val doc = repo.createDocument(ProjectKind.Animation, "Migration", 64, 64, 24, 4)
        val manifest = ProjectSerializer.toManifest(doc, ProjectSerializer.collectFrameIds(doc))
        // Simulate a v0 package: no per-frame metadata at all.
        val old = manifest.copy(
            schemaVersion = 0,
            animation = manifest.animation.copy(frames = emptyList()),
        )
        val migrated = ProjectSerializer.migrate(old)
        assertTrue(migrated.schemaVersion >= 1)
    }

    @Test
    fun `project summary flows from the database`() = runBlocking {
        val doc = repo.createDocument(ProjectKind.Illustration, "Recent", 100, 50)
        repo.registerProject(doc)
        store.createBlankFrame(doc.id, doc.layers.first().activeFrameId!!, 100, 50)
        repo.saveProject(doc)
        val entity = repo.projectDao.findById(doc.id)
        val summary = ProjectRepository.summaryOf(entity!!)
        assertEquals(ProjectKind.Illustration, summary.kind)
        assertEquals(100, summary.width)
    }

    @Test
    fun `document settings survive serialization including guides`() {
        val doc = repo.createDocument(ProjectKind.Illustration, "Guides", 300, 200).copy(
            symmetry = com.artforge.mobile.core.model.SymmetryMode.Radial,
            perspective = com.artforge.mobile.engine.perspective.PerspectiveEngine.defaultGuide(300f, 200f, 2),
            guides = listOf(com.artforge.mobile.core.model.GuideLine("g1", 0f, 100f, 300f, 100f)),
        )
        val manifest = ProjectSerializer.toManifest(doc, emptyList())
        val restored = ProjectSerializer.toDocument(ProjectSerializer.decodeManifest(ProjectSerializer.encodeManifest(manifest)))
        assertEquals(com.artforge.mobile.core.model.SymmetryMode.Radial, restored.symmetry)
        assertEquals(2, restored.perspective.points.size)
        assertEquals(1, restored.guides.size)
    }
}
