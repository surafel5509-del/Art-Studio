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

/**
 * Regression test for the black canvas: a portrait 2000x3000 document (6 MP, the default
 * "Portrait 2:3" preset) must produce a visible composite. The studio previously rendered
 * nothing for it while smaller documents worked.
 */
@RunWith(AndroidJUnit4::class)
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
        assertEquals(2000, state.canvasWidth)
        assertEquals(3000, state.canvasHeight)
        // The composited image must have real size, not a 1x1 fallback.
        val image = state.composite!!
        assertEquals(2000, image.width)
        assertEquals(3000, image.height)
        vm.releaseResourcesForTest()
    }

    private fun assertEquals(expected: Int, actual: Int) =
        org.junit.Assert.assertEquals(expected, actual)
}
