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

    private suspend fun step(name: String, block: suspend () -> Unit) {
        val started = System.currentTimeMillis()
        block()
        println("STEP_OK $name in ${System.currentTimeMillis() - started}ms")
    }

    /** Pins down which repository call stalls, so a hang is never a mystery. */
    @Test
    fun repositoryCallsUsedByProjectCreationDoNotStall() {
        val app = ApplicationProvider.getApplicationContext<ArtForgeApplication>()
        val repo = app.projectRepository
        val created = CountDownLatch(1)
        var doc: com.artforge.mobile.core.model.ProjectDocument? = null
        kotlinx.coroutines.runBlocking {
            step("createDocument") {
                doc = repo.createDocument(
                    ProjectKind.Illustration, "Probe", 256, 256, 24, 24, false, 0xFFFFFFFF.toInt(),
                )
            }
            step("registerProject") { repo.registerProject(doc!!) }
            step("ensureFrames") { repo.ensureFrames(doc!!) }
            step("saveProject") { repo.saveProject(doc!!, null) }
            step("loadProject") { repo.loadProject(doc!!.id) }
            step("autosave") { repo.autosave(doc!!) }
            created.countDown()
        }
        assertTrue("repository probe did not finish", created.await(5, TimeUnit.SECONDS))
    }

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
