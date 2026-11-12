package com.artforge.mobile.ui

import com.artforge.mobile.core.model.ToolId
import com.artforge.mobile.ui.studio.StudioShortcutActions
import com.artforge.mobile.ui.studio.studioShortcut
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Records which shortcut action fired, to prove each documented key is wired. */
private class Recorder {
    val fired = mutableListOf<String>()
    var tool: ToolId? = null

    val actions = StudioShortcutActions(
        undo = { fired += "undo" },
        redo = { fired += "redo" },
        save = { fired += "save" },
        open = { fired += "open" },
        selectTool = { tool = it; fired += "tool:$it" },
        previousFrame = { fired += "prev" },
        nextFrame = { fired += "next" },
        togglePlayback = { fired += "play" },
        duplicateFrame = { fired += "duplicate" },
    )
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StudioShortcutTest {

    @Test
    fun `ctrl shortcuts drive undo redo save and open`() {
        val rec = Recorder()
        // KeyEvent construction for modifier chords is easier through the
        // platform event, so this test exercises the dispatch table directly.
        assertTrue(dispatch(rec, Key.Z, ctrl = true))
        assertTrue(dispatch(rec, Key.Z, ctrl = true, shift = true))
        assertTrue(dispatch(rec, Key.Y, ctrl = true))
        assertTrue(dispatch(rec, Key.S, ctrl = true))
        assertTrue(dispatch(rec, Key.O, ctrl = true))
        assertEquals(listOf("undo", "redo", "redo", "save", "open"), rec.fired)
    }

    @Test
    fun `tool letters select the matching tool`() {
        val rec = Recorder()
        assertTrue(dispatch(rec, Key.B))
        assertTrue(dispatch(rec, Key.E))
        assertTrue(dispatch(rec, Key.I))
        assertTrue(dispatch(rec, Key.Spacebar))
        assertEquals(
            listOf("tool:${ToolId.Brush}", "tool:${ToolId.Eraser}", "tool:${ToolId.Eyedropper}", "tool:${ToolId.Hand}"),
            rec.fired,
        )
    }

    @Test
    fun `frame and playback keys fire the timeline actions`() {
        val rec = Recorder()
        assertTrue(dispatch(rec, Key.DirectionLeft))
        assertTrue(dispatch(rec, Key.DirectionRight))
        assertTrue(dispatch(rec, Key.F5))
        assertTrue(dispatch(rec, Key.F6))
        assertEquals(listOf("prev", "next", "play", "duplicate"), rec.fired)
    }

    @Test
    fun `unmapped keys and key-up events are ignored`() {
        val rec = Recorder()
        assertFalse(dispatch(rec, Key.Q))
        assertFalse(dispatch(rec, Key.Z)) // Ctrl is required for undo
        // Key-up events never trigger an action, so holding a key cannot repeat-fire.
        assertFalse(dispatch(rec, Key.DirectionRight, action = android.view.KeyEvent.ACTION_UP))
        assertTrue(rec.fired.isEmpty())
    }

    /** Builds a real platform-backed KeyEvent so modifiers are actually observable. */
    private fun dispatch(
        rec: Recorder,
        key: Key,
        ctrl: Boolean = false,
        shift: Boolean = false,
        action: Int = android.view.KeyEvent.ACTION_DOWN,
    ): Boolean {
        val meta = (if (ctrl) android.view.KeyEvent.META_CTRL_ON else 0) or
            (if (shift) android.view.KeyEvent.META_SHIFT_ON else 0)
        val native = android.view.KeyEvent(
            0L, 0L, action, key.keyCode.toInt(), 0, meta,
        )
        return studioShortcut(androidx.compose.ui.input.key.KeyEvent(native), rec.actions)
    }
}
