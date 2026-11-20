package app.easyjoy

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Invisible text target for the soft keyboard. Instead of holding text it forwards what is
 * typed as TEXT / KEY packets, so Backspace and Enter work even when "the field" is empty.
 */
class KeyCaptureView(context: Context) : View(context) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Visible-password + no-suggestions keeps IMEs from buffering a composing word.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return Capture(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleKey(event) || super.onKeyDown(keyCode, event)

    private class Capture(v: View) : BaseInputConnection(v, false) {
        private var composing = ""

        private fun emit(s: String) {
            var start = 0
            for (i in s.indices) {
                if (s[i] == '\n') {
                    if (i > start) Connection.text(s.substring(start, i))
                    Connection.key(Protocol.VK_RETURN)
                    start = i + 1
                }
            }
            if (start < s.length) Connection.text(s.substring(start))
        }

        /** Make the PC text match [t] where the previous composing text was [composing]. */
        private fun replaceComposing(t: String) {
            var p = 0
            while (p < composing.length && p < t.length && composing[p] == t[p]) p++
            repeat(composing.length - p) { Connection.key(Protocol.VK_BACK) }
            emit(t.substring(p))
            composing = t
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            replaceComposing(text.toString())
            return true
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            replaceComposing(text.toString())
            composing = ""
            return true
        }

        override fun finishComposingText(): Boolean {
            composing = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength.coerceIn(0, 50)) { Connection.key(Protocol.VK_BACK) }
            composing = composing.dropLast(beforeLength)
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean = handleKey(event)

        override fun performEditorAction(actionCode: Int): Boolean {
            Connection.key(Protocol.VK_RETURN)
            return true
        }
    }

    companion object {
        /** Maps a key event to packets; true if consumed. Only key-down events act. */
        fun handleKey(event: KeyEvent): Boolean {
            val vk = when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> Protocol.VK_BACK
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Protocol.VK_RETURN
                KeyEvent.KEYCODE_TAB -> 0x09
                KeyEvent.KEYCODE_ESCAPE -> 0x1B
                KeyEvent.KEYCODE_DPAD_LEFT -> 0x25
                KeyEvent.KEYCODE_DPAD_UP -> 0x26
                KeyEvent.KEYCODE_DPAD_RIGHT -> 0x27
                KeyEvent.KEYCODE_DPAD_DOWN -> 0x28
                else -> -1
            }
            if (vk >= 0) {
                if (event.action == KeyEvent.ACTION_DOWN) Connection.key(vk)
                return true
            }
            val u = event.unicodeChar
            if (u != 0 && event.action == KeyEvent.ACTION_DOWN && !event.isCtrlPressed) {
                Connection.text(String(Character.toChars(u)))
                return true
            }
            return false
        }
    }
}
