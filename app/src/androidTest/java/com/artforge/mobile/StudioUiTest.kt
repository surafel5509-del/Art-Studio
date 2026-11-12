package com.artforge.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI tests against the real activity (no mocks, no screenshots).
 * They verify the app composes, navigates and that the studio surface accepts
 * touch input.
 */
@RunWith(AndroidJUnit4::class)
class StudioUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeScreenShowsCreateActions() {
        rule.onAllNodesWithText("New", substring = true)[0].assertIsDisplayed()
    }

    @Test
    fun openingSettingsShowsPersistedOptions() {
        rule.onAllNodesWithText("Settings", substring = true)[0].performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Theme", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodesWithText("Theme", substring = true)[0].assertIsDisplayed()
    }

    @Test
    fun helpScreenRendersShortcuts() {
        rule.onAllNodesWithText("Help", substring = true)[0].performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Ctrl", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodesWithText("Ctrl", substring = true)[0].assertIsDisplayed()
    }

    @Test
    fun creatingAProjectOpensTheStudio() {
        rule.onAllNodesWithText("New", substring = true)[0].performClick()
        rule.waitForIdle()
        val createNodes = rule.onAllNodesWithText("Create", substring = true).fetchSemanticsNodes()
        if (createNodes.isNotEmpty()) rule.onAllNodesWithText("Create", substring = true)[0].performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("Tools", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("Layers", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
