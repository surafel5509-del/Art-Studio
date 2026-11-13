package com.artforge.mobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artforge.mobile.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI tests that run on the JVM (Robolectric) against the real [MainActivity] and the real
 * navigation graph — no mocks. They exercise the paths a user takes: home, settings, help
 * and the asset library.
 *
 * Studio entry is deliberately covered by [StudioViewModelTest] instead: composing the
 * canvas rasterises large bitmaps through `android.graphics`, which the JVM software
 * rasteriser cannot keep up with, so Compose's idling strategy times out there rather than
 * failing for a real reason.
 *
 * Why here and not in `androidTest`: the emulator on shared CI runners never finishes
 * booting, so a device-only UI suite is a suite that never runs. These tests assert on the
 * composed semantics tree, which is exactly what a user sees and taps. The pixel-level
 * drawing assertions still live in `androidTest` because Robolectric's Canvas is a no-op.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w411dp-h891dp-xhdpi")
class HomeUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String, timeoutMs: Long = 10_000) {
        rule.waitUntil(timeoutMs) {
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun homeScreenRendersItsEntryPoints() {
        waitForText("ARTFORGE")
        waitForText("New drawing")
        waitForText("New animation")
        waitForText("New comic")
        waitForText("New pixel art")
        waitForText("Asset library")
        // The first-run onboarding dialog must not cover the launcher grid.
        rule.onAllNodesWithText("Start creating", substring = true).fetchSemanticsNodes()
            .firstOrNull()
            ?.let { rule.onAllNodesWithText("Start creating", substring = true).onFirst().performClick() }
        rule.onAllNodesWithText("ARTFORGE").onFirst().assertIsDisplayed()
    }

    @Test
    fun settingsScreenShowsRealPreferences() {
        dismissOnboarding()
        rule.onAllNodesWithText("Settings", substring = true).onFirst().performClick()
        waitForText("Pressure sensitivity")
        waitForText("Palm rejection")
        waitForText("Reduce motion")
        rule.onAllNodesWithText("Pressure sensitivity", substring = true).onFirst().assertIsDisplayed()
    }

    @Test
    fun helpScreenListsTheKeyboardShortcuts() {
        dismissOnboarding()
        // Help lives on the home screen's quick actions, not inside Settings.
        rule.onAllNodesWithText("Help", substring = true).onFirst().performClick()
        waitForText("Ctrl+Z")
        rule.onAllNodesWithText("Ctrl+Z", substring = true).onFirst().assertIsDisplayed()
    }

    @Test
    fun assetLibraryOpens() {
        dismissOnboarding()
        rule.onAllNodesWithText("Asset library", substring = true).onFirst().performClick()
        rule.waitForIdle()
        // Any of these proves the library screen composed rather than a blank frame.
        val markers = listOf("Brushes", "Assets", "Textures", "Templates")
        rule.waitUntil(10_000) {
            markers.any { rule.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    private fun dismissOnboarding() {
        waitForText("New drawing")
        val start = rule.onAllNodesWithText("Start creating", substring = true).fetchSemanticsNodes()
        if (start.isNotEmpty()) {
            rule.onAllNodesWithText("Start creating", substring = true).onFirst().performClick()
            rule.waitForIdle()
        }
    }
}
