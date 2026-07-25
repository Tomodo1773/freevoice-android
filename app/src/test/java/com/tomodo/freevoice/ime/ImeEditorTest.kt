package com.tomodo.freevoice.ime

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class ImeEditorTest {
    private data class Call(val name: String, val arguments: List<Any?>)

    private class FakeConnection {
        val calls = mutableListOf<Call>()
        var selectedText: CharSequence? = null

        val value: InputConnection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
        ) { _, method, arguments ->
            calls += Call(method.name, arguments?.toList().orEmpty())
            when (method.name) {
                "getSelectedText" -> selectedText
                "commitText", "deleteSurroundingTextInCodePoints",
                "performEditorAction", "sendKeyEvent",
                -> true
                else -> defaultValue(method.returnType)
            }
        } as InputConnection

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            else -> null
        }
    }

    @Test
    fun `space commits one ASCII space at cursor`() {
        val connection = FakeConnection()
        val editor = ImeEditor(connection = { connection.value }, editorInfo = { null })

        editor.insertSpace()

        assertEquals(
            Call("commitText", listOf(" ", 1)),
            connection.calls.single { it.name == "commitText" },
        )
    }

    @Test
    fun `backspace requests one Unicode code point before cursor`() {
        val connection = FakeConnection()
        val editor = ImeEditor(connection = { connection.value }, editorInfo = { null })

        editor.deleteBackward()

        assertEquals(
            Call("deleteSurroundingTextInCodePoints", listOf(1, 0)),
            connection.calls.single { it.name == "deleteSurroundingTextInCodePoints" },
        )
    }

    @Test
    fun `backspace replaces selected text with empty string`() {
        val connection = FakeConnection().apply { selectedText = "選択中" }
        val editor = ImeEditor(connection = { connection.value }, editorInfo = { null })

        editor.deleteBackward()

        assertEquals(
            Call("commitText", listOf("", 1)),
            connection.calls.single { it.name == "commitText" },
        )
        assertEquals(
            0,
            connection.calls.count { it.name == "deleteSurroundingTextInCodePoints" },
        )
    }

    @Test
    fun `unspecified and none enter actions insert newline`() {
        assertEquals(
            ImeEnterCommand.NewLine,
            resolveImeEnterCommand(EditorInfo.IME_ACTION_UNSPECIFIED),
        )
        assertEquals(
            ImeEnterCommand.NewLine,
            resolveImeEnterCommand(EditorInfo.IME_ACTION_NONE),
        )
    }

    @Test
    fun `supported editor actions are preserved`() {
        val actions = listOf(
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_PREVIOUS,
        )

        actions.forEach { action ->
            assertEquals(
                ImeEnterCommand.EditorAction(action),
                resolveImeEnterCommand(action),
            )
        }
    }

    @Test
    fun `unrelated option flags do not hide an editor action`() {
        val options = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI

        assertEquals(
            ImeEnterCommand.EditorAction(EditorInfo.IME_ACTION_SEND),
            resolveImeEnterCommand(options),
        )
    }

    @Test
    fun `no enter action flag inserts newline even when low bits contain action`() {
        val options = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION

        assertEquals(ImeEnterCommand.NewLine, resolveImeEnterCommand(options))
    }

    @Test
    fun `unknown action inserts newline`() {
        val unknownAction = EditorInfo.IME_MASK_ACTION

        assertEquals(ImeEnterCommand.NewLine, resolveImeEnterCommand(unknownAction))
    }
}
