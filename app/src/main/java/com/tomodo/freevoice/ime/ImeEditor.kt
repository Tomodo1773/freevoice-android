package com.tomodo.freevoice.ime

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

internal sealed interface ImeEnterCommand {
    data object NewLine : ImeEnterCommand
    data class EditorAction(val actionId: Int) : ImeEnterCommand
}

internal fun resolveImeEnterCommand(imeOptions: Int): ImeEnterCommand {
    if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return ImeEnterCommand.NewLine
    return when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
        EditorInfo.IME_ACTION_GO,
        EditorInfo.IME_ACTION_SEARCH,
        EditorInfo.IME_ACTION_SEND,
        EditorInfo.IME_ACTION_NEXT,
        EditorInfo.IME_ACTION_DONE,
        EditorInfo.IME_ACTION_PREVIOUS,
        -> ImeEnterCommand.EditorAction(action)
        else -> ImeEnterCommand.NewLine
    }
}

/** Small adapter around the currently active editor connection. */
internal class ImeEditor(
    private val connection: () -> InputConnection?,
    private val editorInfo: () -> EditorInfo?,
) {
    fun insertSpace() {
        connection()?.commitText(" ", 1)
    }

    fun deleteBackward() {
        val active = connection() ?: return
        val deleted = if (!active.getSelectedText(0).isNullOrEmpty()) {
            active.commitText("", 1)
        } else {
            active.deleteSurroundingTextInCodePoints(1, 0)
        }
        if (!deleted) active.sendKeyPress(KeyEvent.KEYCODE_DEL)
    }

    fun enter() {
        val active = connection() ?: return
        val command = resolveImeEnterCommand(editorInfo()?.imeOptions ?: EditorInfo.IME_ACTION_NONE)
        val handled = when (command) {
            ImeEnterCommand.NewLine -> active.commitText("\n", 1)
            is ImeEnterCommand.EditorAction -> active.performEditorAction(command.actionId)
        }
        if (!handled) active.sendKeyPress(KeyEvent.KEYCODE_ENTER)
    }

    private fun InputConnection.sendKeyPress(keyCode: Int) {
        sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
