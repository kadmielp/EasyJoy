package app.easyjoy

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Hosts the touchpad View plus the invisible keyboard-capture View. Rotates freely. */
class TouchpadActivity : Activity() {
    private lateinit var pad: TouchpadView
    private lateinit var keys: KeyCaptureView
    private var imeVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0E0F12"))
        pad = TouchpadView(this)
        keys = KeyCaptureView(this)
        root.addView(pad, FrameLayout.LayoutParams(-1, -1))
        root.addView(keys, FrameLayout.LayoutParams(1, 1))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }

        pad.onMenu = { finish() }
        pad.onKeyboard = { toggleKeyboard() }
    }

    private fun toggleKeyboard() {
        val c = WindowInsetsControllerCompat(window, keys)
        if (imeVisible) {
            c.hide(WindowInsetsCompat.Type.ime())
        } else {
            keys.requestFocus()
            c.show(WindowInsetsCompat.Type.ime())
        }
    }

    override fun onStart() {
        super.onStart()
        Connection.acquire(this)
    }

    override fun onPause() {
        pad.releaseAll()
        super.onPause()
    }

    override fun onStop() {
        Connection.release()
        super.onStop()
    }
}
