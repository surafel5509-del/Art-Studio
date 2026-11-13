package com.artforge.mobile.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artforge.mobile.ArtForgeApplication
import com.artforge.mobile.core.model.ProjectKind
import com.artforge.mobile.ui.studio.StudioViewModel
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Isolates the studio's state machine from its layout: drives [StudioViewModel] exactly as
 * the screen does (create a project, then observe state) and asserts the UI reaches an idle
 * state. A failure here means the view model never settles, not that a composable is slow.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29], qualifiers = "w411dp-h891dp-xhdpi")
class StudioIdleTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun openingAProjectReachesAStableState() {
        val app = ApplicationProvider.getApplicationContext<ArtForgeApplication>()
        val vm = StudioViewModel(app)
        val created = CountDownLatch(1)

        rule.setContent {
            val state by vm.state.collectAsState()
            Text(
                if (state.document == null) "no-document" else "document:${state.document!!.layers.size}",
            )
        }

        rule.waitForIdle()
        vm.createProject(
            kind = ProjectKind.Illustration,
            title = "Idle probe",
            width = 256,
            height = 256,
            fps = 24,
            frameCount = 24,
            transparent = false,
            backgroundColor = 0xFFFFFFFF.toInt(),
            onCreated = { created.countDown() },
        )
        assertTrue("project was never created", created.await(20, TimeUnit.SECONDS))

        // This is the assertion that failed at the Activity level; here it names the cause.
        rule.waitForIdle()
        rule.onNodeWithText("document:", substring = true).assertExists()
    }
}
