package app.easyjoy

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Hosts a gamepad layout: landscape, fullscreen immersive, screen kept on. Also the layout editor. */
class ControllerActivity : Activity() {
    private lateinit var pad: GamepadView
    private lateinit var toolbar: LinearLayout
    private lateinit var btnSmaller: TextView
    private lateinit var btnBigger: TextView
    private lateinit var btnHide: TextView
    private lateinit var btnTilt: TextView
    private var layoutName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val saved = Layouts.get(intent.getStringExtra(EXTRA_LAYOUT))
        layoutName = saved?.name
        val base = saved?.base ?: intent.getStringExtra(EXTRA_MODE) ?: Layouts.FULL
        pad = GamepadView(this, base, saved)
        pad.onOptions = { showOptions() }
        pad.onSelectionChanged = { refreshToolbar() }

        val root = FrameLayout(this)
        root.addView(pad, FrameLayout.LayoutParams(-1, -1))
        toolbar = buildToolbar()
        root.addView(toolbar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(6)
        })
        setContentView(root)
        hideSystemBars()

        editOnly = intent.getBooleanExtra(EXTRA_EDIT, false)
        if (editOnly) enterEditWhenLaidOut()
    }

    /** Opened from the Layouts tab: start in the editor and close when editing ends. */
    private var editOnly = false

    private fun enterEditWhenLaidOut() {
        if (pad.width > 0 && pad.height > 0) enterEdit() else pad.post { enterEditWhenLaidOut() }
    }

    // ---- edit toolbar ------------------------------------------------------------------------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun toolButton(label: String, accent: Int = 0xFF2A2D35.toInt(), onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(accent)
            setStroke(dp(1), 0xFF3A3E48.toInt())
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4); marginEnd = dp(4) }
    }

    private fun buildToolbar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        btnSmaller = toolButton("−") { pad.resizeSelected(0.9f) }
        btnBigger = toolButton("+") { pad.resizeSelected(1.1f) }
        btnHide = toolButton("Ocultar") { pad.toggleSelectedVisible() }
        btnTilt = toolButton("Volante") { toggleTilt() }
        addView(btnSmaller)
        addView(btnBigger)
        addView(btnHide)
        addView(btnTilt)
        addView(toolButton("Padrão") { pad.resetToDefault(); refreshToolbar() })
        addView(toolButton("Cancelar") { exitEdit(keep = false) })
        addView(toolButton("Salvar", 0xFF107C10.toInt()) { askName() })
    }

    private fun refreshToolbar() {
        val has = pad.selectedLabel != null
        for (b in listOf(btnSmaller, btnBigger, btnHide)) {
            b.isEnabled = has
            b.alpha = if (has) 1f else 0.4f
        }
        btnHide.text = if (has && !pad.selectedVisible) "Mostrar" else "Ocultar"
        btnTilt.text = if (pad.tiltEnabled) "Volante: ligado" else "Volante: desligado"
        placeToolbar(bottom = pad.selectedInTopHalf)
    }

    /** The toolbar sits on the side away from the selected control, so any spot (even corners) stays reachable. */
    private fun placeToolbar(bottom: Boolean) {
        val lp = toolbar.layoutParams as FrameLayout.LayoutParams
        val gravity = (if (bottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL
        if (lp.gravity == gravity) return
        lp.gravity = gravity
        lp.topMargin = if (bottom) 0 else dp(6)
        lp.bottomMargin = if (bottom) dp(6) else 0
        toolbar.layoutParams = lp
        pad.toolbarAtBottom = bottom
    }

    private fun showOptions() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Voltar ao menu", "Editar layout", "Continuar jogando")) { _, which ->
                when (which) {
                    0 -> finish()
                    1 -> enterEdit()
                }
                hideSystemBars()
            }
            .setOnCancelListener { hideSystemBars() }
            .show()
    }

    private fun enterEdit() {
        pad.startEditing()
        toolbar.visibility = View.VISIBLE
        refreshToolbar()
    }

    private fun exitEdit(keep: Boolean) {
        pad.finishEditing(keep)
        toolbar.visibility = View.GONE
        if (editOnly) finish()
    }

    private fun toggleTilt() {
        if (!pad.tiltEnabled && !Tilt.available(this)) {
            Toast.makeText(this, "Este celular não tem sensor de movimento.", Toast.LENGTH_LONG).show()
            return
        }
        pad.toggleTilt()
        refreshToolbar()
    }

    private fun askName() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(layoutName ?: "Meu ${Layouts.baseLabel(pad.base)}")
            selectAll()
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Salvar layout")
            .setMessage("Nome do layout (um nome que já existe é substituído):")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Digite um nome.", Toast.LENGTH_SHORT).show()
                } else {
                    Layouts.save(pad.layoutToSave(name))
                    layoutName = name
                    exitEdit(keep = true)
                    Toast.makeText(this, "Layout \"$name\" salvo. Ele aparece no menu.", Toast.LENGTH_SHORT).show()
                }
                hideSystemBars()
            }
            .setNegativeButton("Voltar") { _, _ -> hideSystemBars() }
            .show()
    }

    // ---- volume keys as gamepad buttons (Ajustes) ----------------------------------------------

    private fun volumeTarget(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> Settings.volumeTarget(Settings.volumeUp)
        KeyEvent.KEYCODE_VOLUME_DOWN -> Settings.volumeTarget(Settings.volumeDown)
        else -> null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val t = volumeTarget(keyCode)
        if (t == null || pad.editing) return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) pad.setExternal(keyCode, t, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val t = volumeTarget(keyCode)
        if (t == null || pad.editing) return super.onKeyUp(keyCode, event)
        pad.setExternal(keyCode, t, false)
        return true
    }

    @Deprecated("Back in edit mode cancels the edit instead of leaving the screen.")
    override fun onBackPressed() {
        if (pad.editing) exitEdit(keep = false) else super.onBackPressed()
    }

    // ---- window / lifecycle ------------------------------------------------------------------

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        Connection.acquire(this)
    }

    override fun onResume() {
        super.onResume()
        pad.start()
    }

    override fun onPause() {
        pad.stop()
        Connection.sendGamepad(0, 0, 0, 0, 0, 0, 0) // neutral state first, in case BYE is lost
        Connection.sendBye()
        super.onPause()
    }

    override fun onStop() {
        Connection.release()
        super.onStop()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_LAYOUT = "layout"
        const val EXTRA_EDIT = "edit"
        const val MODE_FULL = Layouts.FULL
        const val MODE_SIMPLE = Layouts.SIMPLE
        const val MODE_WHEEL = Layouts.WHEEL
    }
}
