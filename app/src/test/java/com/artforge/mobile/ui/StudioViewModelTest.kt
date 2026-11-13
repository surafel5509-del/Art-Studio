package com.artforge.mobile.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artforge.mobile.ArtForgeApplication
import com.artforge.mobile.core.model.ProjectKind
import com.artforge.mobile.ui.studio.StudioViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Drives [StudioViewModel] exactly as `StudioScreen` does — create a project, open it,
 * paint, undo, switch frames, save — and asserts on the resulting state.
 *
 * Why not through the composable: the studio's canvas composites large `Bitmap`s through
 * `android.graphics`, which the JVM software rasteriser cannot do fast enough for Compose's
 * idling strategy (it times out rather than reporting a real failure). The view model is
 * where the behaviour lives, so it is tested directly; the composable's layout and
 * navigation are covered by [HomeUiTest] and by the device suite.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class StudioViewModelTest {

    private val app = ApplicationProvider.getApplicationContext<ArtForgeApplication>()

    /** Pumps the main looper until [condition] holds, or fails with a useful message. */
    private fun awaitMain(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        // One last pump so a late-delivered result is never missed.
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }

    private fun createProject(kind: ProjectKind = ProjectKind.Illustration, vm: StudioViewModel): StudioViewModel {
        var createdId: String? = null
        vm.createProject(
            kind = kind,
            title = "VM probe",
            width = 256,
            height = 256,
            fps = 24,
            frameCount = 24,
            transparent = false,
            backgroundColor = 0xFFFFFFFF.toInt(),
            onCreated = { createdId = it },
        )
        awaitMain { createdId != null }
        awaitMain { vm.state.value.document != null }
        return vm
    }

    @Test
    fun creatingAndOpeningAProjectProducesADrawableDocument() {
        val vm = createProject(vm = StudioViewModel(app))
        val state = vm.state.value
        assertNotNull("no document was opened", state.document)
        val doc = state.document!!

        assertFalse("must not stay in the loading state", state.loading)
        assertEquals(ProjectKind.Illustration, doc.kind)
        assertEquals(256, state.canvasWidth)
        assertTrue("a project needs at least one layer", doc.layers.isNotEmpty())
        // The canvas must have something to paint into: this exact guarantee was missing.
        assertNotNull("layer has no drawable frame", doc.layers.first().activeFrameId)
        // A render failure surfaces as a status message rather than an exception, so
        // assert on that first: it distinguishes "the canvas broke" from "the JVM
        // software rasteriser could not draw in this environment".
        assertFalse(
            "the canvas render reported a failure: ${state.statusMessage}",
            state.statusMessage?.startsWith("Display error") == true,
        )
        assertNotNull("no composite was produced", state.composite)
        assertEquals(doc.layers.first().id, state.activeLayerId)
        // The activity would clear the view model; release its jobs explicitly so the
        // autosave coroutine cannot leak between tests.
        vm.releaseResourcesForTest()
    }

    @Test
    fun aStrokeBecomesUndoableAndRedoable() {
        val vm = createProject(vm = StudioViewModel(app))
        val before = vm.state.value
        assertFalse("nothing to undo yet", before.canUndo)

        vm.selectTool(com.artforge.mobile.core.model.ToolId.Brush)
        vm.onPointerDown(com.artforge.mobile.engine.draw.StrokePoint(40f, 40f, 1f), false)
        vm.onPointerMove(com.artforge.mobile.engine.draw.StrokePoint(120f, 120f, 1f), false)
        vm.onPointerUp(com.artforge.mobile.engine.draw.StrokePoint(120f, 120f, 1f))

        awaitMain { vm.state.value.canUndo }
        assertTrue("a completed stroke must be undoable", vm.state.value.canUndo)
        assertTrue("painting must mark the document dirty", vm.state.value.dirty)

        vm.undo()
        awaitMain { !vm.state.value.canUndo }
        assertFalse("undo must clear the undo flag", vm.state.value.canUndo)
        assertTrue("undo must enable redo", vm.state.value.canRedo)

        vm.redo()
        awaitMain { vm.state.value.canUndo }
        assertTrue("redo must restore the undone stroke", vm.state.value.canUndo)
        // The activity would clear the view model; release its jobs explicitly so the
        // autosave coroutine cannot leak between tests.
        vm.releaseResourcesForTest()
    }

    @Test
    fun animationProjectsExposeTheRightFrameCount() {
        val vm = createProject(kind = ProjectKind.Animation, vm = StudioViewModel(app))
        awaitMain { vm.state.value.document != null }
        val doc = vm.state.value.document!!
        assertEquals(ProjectKind.Animation, doc.kind)
        assertEquals(24, doc.animation.frameCount)
        assertEquals(0, vm.state.value.frame)

        vm.nextFrame()
        awaitMain { vm.state.value.frame == 1 }
        assertEquals(1, vm.state.value.frame)

        vm.previousFrame()
        awaitMain { vm.state.value.frame == 0 }
        assertEquals(0, vm.state.value.frame)
        // The activity would clear the view model; release its jobs explicitly so the
        // autosave coroutine cannot leak between tests.
        vm.releaseResourcesForTest()
    }

    @Test
    fun savingAProjectCompletesAndClearsTheDirtyFlag() {
        val vm = createProject(vm = StudioViewModel(app))
        vm.selectTool(com.artforge.mobile.core.model.ToolId.Brush)
        vm.onPointerDown(com.artforge.mobile.engine.draw.StrokePoint(10f, 10f, 1f), false)
        vm.onPointerUp(com.artforge.mobile.engine.draw.StrokePoint(10f, 10f, 1f))
        awaitMain { vm.state.value.dirty }

        vm.saveProject(userInitiated = true)
        awaitMain(30_000) { !vm.state.value.dirty && !vm.state.value.saving }
        assertFalse("save must finish", vm.state.value.saving)
        assertFalse("save must clear the dirty flag", vm.state.value.dirty)
        // The activity would clear the view model; release its jobs explicitly so the
        // autosave coroutine cannot leak between tests.
        vm.releaseResourcesForTest()
    }

    @Test
    fun aCompatibilityProjectStillHasAFrame() {
        // Pixel art and comic projects take different branches in createDocument; every
        // branch must still hand the canvas a frame to draw into.
        listOf(ProjectKind.PixelArt, ProjectKind.Comic, ProjectKind.Illustration).forEach { kind ->
            val vm = createProject(kind = kind, vm = StudioViewModel(app))
            val layer = vm.state.value.document?.layers?.firstOrNull()
            assertNotNull("$kind project has no layer", layer)
            assertNotNull("$kind project has no active frame", layer!!.activeFrameId)
            // The activity would clear the view model; release its jobs explicitly so the
        // autosave coroutine cannot leak between tests.
        vm.releaseResourcesForTest()
        }
    }
}
