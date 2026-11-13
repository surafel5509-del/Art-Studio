package com.artforge.mobile.ui

import androidx.compose.ui.input.key.Key
import com.artforge.mobile.core.model.ToolId
import com.artforge.mobile.ui.studio.StudioCommand
import com.artforge.mobile.ui.studio.StudioShortcutActions
import com.artforge.mobile.ui.studio.run as runCommand
import com.artforge.mobile.ui.studio.studioCommandFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the documented shortcut table. The key table is a pure function, so this
 * needs no Android key-event plumbing and runs as a plain JVM test.
 */
class StudioShortcutTest {

    private class Recorder {
        val fired = mutableListOf<String>()
        val actions = StudioShortcutActions(
            undo = { fired += "undo" },
            redo = { fired += "redo" },
            save = { fired += "save" },
            open = { fired += "open" },
            selectTool = { fired += "tool:$it" },
            previousFrame = { fired += "prev" },
            nextFrame = { fired += "next" },
            togglePlayback = { fired += "play" },
            duplicateFrame = { fired += "duplicate" },
        )
    }

    private fun fire(rec: Recorder, key: Key, ctrl: Boolean = false, shift: Boolean = false): Boolean {
        val command = studioCommandFor(key, ctrl, shift) ?: return false
        rec.actions.runCommand(command)
        return true
    }

    @Test
    fun `ctrl chords map to undo redo save and open`() {
        val rec = Recorder()
        fire(rec, Key.Z, ctrl = true)
        fire(rec, Key.Z, ctrl = true, shift = true)
        fire(rec, Key.Y, ctrl = true)
        fire(rec, Key.S, ctrl = true)
        fire(rec, Key.O, ctrl = true)
        assertEquals(listOf("undo", "redo", "redo", "save", "open"), rec.fired)
    }

    @Test
    fun `tool letters select brush eraser eyedropper and hand`() {
        val rec = Recorder()
        fire(rec, Key.B)
        fire(rec, Key.E)
        fire(rec, Key.I)
        fire(rec, Key.Spacebar)
        assertEquals(
            listOf(
                "tool:${ToolId.Brush}",
                "tool:${ToolId.Eraser}",
                "tool:${ToolId.Eyedropper}",
                "tool:${ToolId.Hand}",
            ),
            rec.fired,
        )
    }

    @Test
    fun `frame and playback keys drive the timeline`() {
        val rec = Recorder()
        fire(rec, Key.DirectionLeft)
        fire(rec, Key.DirectionRight)
        fire(rec, Key.F5)
        fire(rec, Key.F6)
        assertEquals(listOf("prev", "next", "play", "duplicate"), rec.fired)
    }

    @Test
    fun `unbound keys do nothing`() {
        val rec = Recorder()
        // Q is not a shortcut, and undo requires Ctrl so a bare Z must not edit.
        assertNull(studioCommandFor(Key.Q, ctrl = false, shift = false))
        assertNull(studioCommandFor(Key.Z, ctrl = false, shift = false))
        // Shift alone must not turn B into a tool switch.
        assertNull(studioCommandFor(Key.B, ctrl = false, shift = true))
        assertEquals(false, fire(rec, Key.Q))
        assertEquals(emptyList<String>(), rec.fired)
    }

    @Test
    fun `command mapping is stable for every documented shortcut`() {
        assertEquals(StudioCommand.Undo, studioCommandFor(Key.Z, true, false))
        assertEquals(StudioCommand.Redo, studioCommandFor(Key.Z, true, true))
        assertEquals(StudioCommand.Save, studioCommandFor(Key.S, true, false))
        assertEquals(StudioCommand.Open, studioCommandFor(Key.O, true, false))
        assertEquals(StudioCommand.Brush, studioCommandFor(Key.B, false, false))
        assertEquals(StudioCommand.Eraser, studioCommandFor(Key.E, false, false))
        assertEquals(StudioCommand.Eyedropper, studioCommandFor(Key.I, false, false))
        assertEquals(StudioCommand.Hand, studioCommandFor(Key.Spacebar, false, false))
        assertEquals(StudioCommand.PreviousFrame, studioCommandFor(Key.DirectionLeft, false, false))
        assertEquals(StudioCommand.NextFrame, studioCommandFor(Key.DirectionRight, false, false))
        assertEquals(StudioCommand.TogglePlayback, studioCommandFor(Key.F5, false, false))
        assertEquals(StudioCommand.DuplicateFrame, studioCommandFor(Key.F6, false, false))
    }
}
