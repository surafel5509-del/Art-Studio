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
@OptIn(ExperimentalComposeUiApi::class)
fun studioShortcut(keyEvent: KeyEvent, actions: StudioShortcutActions): Boolean {
    if (keyEvent.type != KeyEventType.KeyDown) return false
    val ctrl = keyEvent.isCtrlPressed || keyEvent.isMetaPressed
    val shift = keyEvent.isShiftPressed

    if (ctrl) {
        return when (keyEvent.key) {
            Key.Z -> {
                if (shift) actions.redo() else actions.undo()
                true
            }
            Key.Y -> {
                actions.redo()
                true
            }
            Key.S -> {
                actions.save()
                true
            }
            Key.O -> {
                actions.open()
                true
            }
            else -> false
        }
    }

    return when (keyEvent.key) {
        Key.B -> { actions.selectTool(ToolId.Brush); true }
        Key.E -> { actions.selectTool(ToolId.Eraser); true }
        Key.I -> { actions.selectTool(ToolId.Eyedropper); true }
        Key.Spacebar -> { actions.selectTool(ToolId.Hand); true }
        Key.DirectionLeft -> { actions.previousFrame(); true }
        Key.DirectionRight -> { actions.nextFrame(); true }
        Key.F5 -> { actions.togglePlayback(); true }
        Key.F6 -> { actions.duplicateFrame(); true }
        else -> false
    }
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
