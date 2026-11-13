package com.artforge.mobile.ui.studio

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import com.artforge.mobile.core.model.ToolId

/**
 * Physical-keyboard shortcuts for the studio, matching the documented set:
 *
 * | Keys | Action |
 * | --- | --- |
 * | Ctrl+Z | undo |
 * | Ctrl+Shift+Z / Ctrl+Y | redo |
 * | Ctrl+S | save |
 * | Ctrl+O | open the project picker |
 * | B | brush |
 * | E | eraser |
 * | I | eyedropper |
 * | Space | hand (pan) tool |
 * | Left / Right | previous / next frame |
 * | F5 | play / pause |
 * | F6 | duplicate frame |
 *
 * Only unmodified letter keys act as tools, so typing in a text field (which consumes
 * the event first) is never hijacked.
 */
/** One bound command, resolved from a key + modifier state without any Android types. */
enum class StudioCommand {
    Undo, Redo, Save, Open, Brush, Eraser, Eyedropper, Hand, PreviousFrame, NextFrame,
    TogglePlayback, DuplicateFrame,
}

/**
 * Pure key table: (key, ctrl, shift) -> command. Kept free of Compose/Android types so
 * the binding can be unit-tested on the JVM with no key-event plumbing.
 */
fun studioCommandFor(key: Key, ctrl: Boolean, shift: Boolean): StudioCommand? {
    if (ctrl) {
        return when (key) {
            Key.Z -> if (shift) StudioCommand.Redo else StudioCommand.Undo
            Key.Y -> StudioCommand.Redo
            Key.S -> StudioCommand.Save
            Key.O -> StudioCommand.Open
            else -> null
        }
    }
    if (shift) return null
    return when (key) {
        Key.B -> StudioCommand.Brush
        Key.E -> StudioCommand.Eraser
        Key.I -> StudioCommand.Eyedropper
        Key.Spacebar -> StudioCommand.Hand
        Key.DirectionLeft -> StudioCommand.PreviousFrame
        Key.DirectionRight -> StudioCommand.NextFrame
        Key.F5 -> StudioCommand.TogglePlayback
        Key.F6 -> StudioCommand.DuplicateFrame
        else -> null
    }
}

/** Runs [command] against the studio actions. */
fun StudioShortcutActions.run(command: StudioCommand) {
    when (command) {
        StudioCommand.Undo -> undo()
        StudioCommand.Redo -> redo()
        StudioCommand.Save -> save()
        StudioCommand.Open -> open()
        StudioCommand.Brush -> selectTool(ToolId.Brush)
        StudioCommand.Eraser -> selectTool(ToolId.Eraser)
        StudioCommand.Eyedropper -> selectTool(ToolId.Eyedropper)
        StudioCommand.Hand -> selectTool(ToolId.Hand)
        StudioCommand.PreviousFrame -> previousFrame()
        StudioCommand.NextFrame -> nextFrame()
        StudioCommand.TogglePlayback -> togglePlayback()
        StudioCommand.DuplicateFrame -> duplicateFrame()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun studioShortcut(keyEvent: KeyEvent, actions: StudioShortcutActions): Boolean {
    if (keyEvent.type != KeyEventType.KeyDown) return false
    val ctrl = keyEvent.isCtrlPressed || keyEvent.isMetaPressed
    val shift = keyEvent.isShiftPressed
    val command = studioCommandFor(keyEvent.key, ctrl, shift) ?: return false
    actions.run(command)
    return true
}

/** Callbacks the studio supplies to [studioShortcut]. */
class StudioShortcutActions(
    val undo: () -> Unit,
    val redo: () -> Unit,
    val save: () -> Unit,
    val open: () -> Unit,
    val selectTool: (ToolId) -> Unit,
    val previousFrame: () -> Unit,
    val nextFrame: () -> Unit,
    val togglePlayback: () -> Unit,
    val duplicateFrame: () -> Unit,
)

/**
 * Modifier that gives a subtree focus and routes hardware key events to
 * [studioShortcut]. Text fields inside the subtree consume their own keys first, so
 * typing is never hijacked.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.studioShortcuts(actions: StudioShortcutActions): Modifier {
    val focus = androidx.compose.runtime.remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // Best effort: some devices are not ready on first composition, and losing
        // focus must never break the editor.
        runCatching { focus.requestFocus() }
    }
    return this
        .focusRequester(focus)
        .focusable()
        .onKeyEvent { event -> studioShortcut(event, actions) }
}
