package com.artforge.mobile.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artforge.mobile.ArtForgeApplication
import com.artforge.mobile.core.model.ProjectKind
import com.artforge.mobile.ui.studio.StudioViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.graphics.asAndroidBitmap

/**
 * Regression test for the black canvas: a portrait 2000x3000 document (6 MP, the default
 * "Portrait 2:3" preset) must produce a visible composite. The studio previously rendered
 * nothing for it while smaller documents worked.
 */
@RunWith(AndroidJUnit4::class)
// NATIVE graphics is required: Robolectric's legacy Canvas is a no-op, so without this a
// "the canvas is blank" assertion passes for the wrong reason and a real draw is invisible.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29])
class LargeDocumentRenderTest {

    private val app = ApplicationProvider.getApplicationContext<ArtForgeApplication>()

    private fun awaitMain(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }

    @Test
    fun portraitPresetProducesAVisibleComposite() {
        val vm = StudioViewModel(app)
        var created: String? = null
        vm.createProject(
            kind = ProjectKind.Illustration,
            title = "Portrait 2:3",
            width = 2000,
            height = 3000,
            fps = 24,
            frameCount = 1,
            transparent = false,
            backgroundColor = 0xFFFFFFFF.toInt(),
            onCreated = { created = it },
        )
        awaitMain { created != null }
        awaitMain { vm.state.value.document != null }

        val state = vm.state.value
        assertFalse("render reported: ${state.statusMessage}", state.statusMessage?.startsWith("Display error") == true)
        assertNotNull("a 2000x3000 document produced no composite", state.composite)
        org.junit.Assert.assertEquals(2000, state.canvasWidth)
        org.junit.Assert.assertEquals(3000, state.canvasHeight)
        // The composite must actually look like a white sheet: if the layer draw fails the
        // bitmap stays transparent and the user sees the black canvas background.
        val image = state.composite!!
        val probe = image.asAndroidBitmap()
        val centre = probe.getPixel(probe.width / 2, probe.height / 2)
        org.junit.Assert.assertTrue(
            "canvas centre is transparent: the layer never drew (centre=#%08X)".format(centre),
            android.graphics.Color.alpha(centre) > 0,
        )

        // The display bitmap is capped so a big document cannot exhaust the heap; the
        // aspect ratio must be preserved so the sheet is not stretched.
        val aspect = image.width.toFloat() / image.height.toFloat()
        org.junit.Assert.assertEquals("aspect ratio", 2000f / 3000f, aspect, 0.01f)
        org.junit.Assert.assertNotNull("no display bitmap", vm.currentDisplayForTest())

        // Zoom to fit must map the whole sheet inside the viewport.
        val t = com.artforge.mobile.ui.studio.CanvasTransform(
            viewportWidth = 1080f,
            viewportHeight = 1920f,
            documentWidth = 2000f,
            documentHeight = 3000f,
            view = com.artforge.mobile.ui.studio.CanvasView(),
        )
        org.junit.Assert.assertTrue("sheet wider than viewport", t.displayWidth <= 1080f + 0.5f)
        org.junit.Assert.assertTrue("sheet taller than viewport", t.displayHeight <= 1920f + 0.5f)
        vm.releaseResourcesForTest()
    }
}
