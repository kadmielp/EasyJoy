package app.easyjoy

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Gamepad screen for the Full / Simple / Wheel bases. Draws on Canvas and tracks every pointer by id,
 * so fingers can slide between buttons and a finger that began on a stick owns it until lifted.
 *
 * Positions come from the default layout for the base, overridden by a saved [Layout] if given.
 * In edit mode, touches select / drag controls instead of playing.
 */
class GamepadView(context: Context, val base: String, initial: Layout?) : View(context) {

    /** Tap on the small "⋯" button: the activity shows Menu / Editar, so a stray tap changes nothing. */
    var onOptions: (() -> Unit)? = null
    /** Called in edit mode when the selection or its visibility changes. */
    var onSelectionChanged: (() -> Unit)? = null

    private companion object { const val DPAD_BITS = 0x000F }

    private enum class Kind { RECT, CIRCLE, STICK, DPAD }

    private class Elem(
        val id: String,
        val kind: Kind,
        val label: String,
        val mask: Int = 0,
        val trigger: Int = 0, // 0 none, 1 LT, 2 RT
        val color: Int = 0,
    ) {
        var cx = 0f
        var cy = 0f
        var w = 0f
        var h = 0f
        var visible = true
        var pressed = false
        // stick state
        var owner = -1
        var nx = 0f // raw normalised, clamped to the unit disc, y up positive
        var ny = 0f
        var outX = 0f // after dead zone
        var outY = 0f
        val r get() = min(w, h) / 2
        fun contains(x: Float, y: Float, slack: Float = 0f) =
            abs(x - cx) <= w / 2 + slack && abs(y - cy) <= h / 2 + slack
    }

    private val green = 0xFF107C10.toInt()
    private val grey = 0xFF8A8F9C.toInt()

    private val elems = ArrayList<Elem>()
    private fun el(id: String) = elems.firstOrNull { it.id == id }

    var tiltEnabled = initial?.tilt ?: (base == Layouts.WHEEL)
        private set
    private var custom: List<Control>? = initial?.controls

    var editing = false
        private set
    private var selected: Elem? = null
    private var dragging = false
    private var dragOffX = 0f
    private var dragOffY = 0f
    private var snapshot: List<Control>? = null
    private var snapshotTilt = false

    private val optionsRect = RectF()
    private val pts = HashMap<Int, FloatArray>()

    private var sent: IntArray? = null
    private var cur = IntArray(7)
    private var prevPressBits = 0
    private var centerWasPressed = false
    private val handler = Handler(Looper.getMainLooper())
    private var active = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFD24D.toInt()
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    init {
        setBackgroundColor(0xFF101114.toInt())
        val wheel = base == Layouts.WHEEL
        if (base != Layouts.SIMPLE) elems += Elem("LT", Kind.RECT, if (wheel) "Freio" else "LT", trigger = 1)
        elems += Elem("LB", Kind.RECT, "LB", Protocol.BTN_LB)
        elems += Elem("RB", Kind.RECT, "RB", Protocol.BTN_RB)
        if (base != Layouts.SIMPLE) elems += Elem("RT", Kind.RECT, if (wheel) "Acelerar" else "RT", trigger = 2)
        elems += Elem("Y", Kind.CIRCLE, "Y", Protocol.BTN_Y, color = 0xFFF2C21A.toInt())
        elems += Elem("X", Kind.CIRCLE, "X", Protocol.BTN_X, color = 0xFF2F7CF6.toInt())
        elems += Elem("B", Kind.CIRCLE, "B", Protocol.BTN_B, color = 0xFFE23B3B.toInt())
        elems += Elem("A", Kind.CIRCLE, "A", Protocol.BTN_A, color = 0xFF3DB43D.toInt())
        elems += Elem("Back", Kind.RECT, "Back", Protocol.BTN_BACK)
        elems += Elem("Start", Kind.RECT, "Start", Protocol.BTN_START)
        elems += Elem("Home", Kind.RECT, "Home", Protocol.BTN_GUIDE, color = green)
        if (base != Layouts.WHEEL) elems += Elem("LS", Kind.STICK, "L")
        if (base == Layouts.FULL) elems += Elem("RS", Kind.STICK, "R")
        if (base != Layouts.SIMPLE) elems += Elem("DPAD", Kind.DPAD, "")
        elems += Elem("CENTER", Kind.RECT, "Centralizar")
    }

    // ---- layout ------------------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Mid-edit, carry the unsaved positions over to the new size instead of reverting them.
        val pending = if (editing && oldw > 0 && oldh > 0) {
            elems.map { Control(it.id, it.cx / oldw, it.cy / oldh, it.w / oldh, it.h / oldh, it.visible) }
        } else null
        layoutNow()
        pending?.forEach { c ->
            el(c.id)?.let { it.cx = c.x * w; it.cy = c.y * h; it.w = c.w * h; it.h = c.h * h; it.visible = c.visible }
        }
    }

    /** Where the edit toolbar currently sits; the edit hint is drawn on the other side. */
    var toolbarAtBottom = false
        set(v) { field = v; invalidate() }

    /** True if the selected control is in the top half (the toolbar should then go to the bottom). */
    val selectedInTopHalf: Boolean get() = selected?.let { it.cy < height / 2f } ?: false

    private fun layoutNow() {
        val W = width.toFloat()
        val H = height.toFloat()
        if (W <= 0f || H <= 0f) return
        optionsRect.set(W / 2 - H * 0.06f, H * 0.02f, W / 2 + H * 0.06f, H * 0.11f)
        applyDefaults(W, H)
        custom?.forEach { c ->
            el(c.id)?.let {
                it.cx = c.x * W; it.cy = c.y * H; it.w = c.w * H; it.h = c.h * H; it.visible = c.visible
            }
        }
        invalidate()
    }

    /** Default positions. Sizes follow the global size setting; positions shift only as needed to fit. */
    private fun applyDefaults(W: Float, H: Float) {
        fun rect(id: String, x: Float, y: Float, w: Float, h: Float) = el(id)?.let {
            it.cx = x + w / 2; it.cy = y + h / 2; it.w = w; it.h = h; it.visible = true
        }
        fun round(id: String, cx: Float, cy: Float, r: Float) = el(id)?.let {
            it.cx = cx; it.cy = cy; it.w = 2 * r; it.h = 2 * r; it.visible = true
        }
        fun band(want: Float, lo: Float, hi: Float) = if (lo > hi) (lo + hi) / 2 else want.coerceIn(lo, hi)
        fun abxy(cx: Float, cy: Float, d: Float, r: Float) {
            round("Y", cx, cy - d, r); round("A", cx, cy + d, r)
            round("X", cx - d, cy, r); round("B", cx + d, cy, r)
        }

        val s = Settings.buttonScale.coerceIn(Settings.MIN_SCALE, Settings.MAX_SCALE)
        val edge = H * 0.02f
        val tw = W * 0.16f * s

        when (base) {
            Layouts.SIMPLE -> {
                val th = H * 0.17f
                rect("LB", W * 0.03f, H * 0.04f, tw, th)
                rect("RB", W * 0.97f - tw, H * 0.04f, tw, th)
                val top = H * 0.04f + th
                val r = H * 0.26f * s
                round("LS", max(W * 0.20f, r + W * 0.02f), band(H * 0.60f, top + edge + r, H - edge - r), r)
                val d = H * 0.19f * s; val br = H * 0.12f * s
                abxy(min(W * 0.80f, W - W * 0.02f - d - br), band(H * 0.58f, top + edge + d + br, H - edge - d - br), d, br)
            }
            Layouts.WHEEL -> {
                val th = H * 0.14f
                rect("LB", W * 0.03f, H * 0.04f, tw, th)
                rect("RB", W * 0.97f - tw, H * 0.04f, tw, th)
                // Big pedals: brake under the left thumb, throttle under the right.
                val pw = W * 0.17f * s
                val pTop = H * 0.04f + th + edge
                rect("LT", W * 0.03f, pTop, pw, H - edge - pTop)
                rect("RT", W * 0.97f - pw, pTop, pw, H - edge - pTop)
                val half = H * 0.12f * s
                round("DPAD", max(W * 0.30f, W * 0.03f + pw + W * 0.02f + half), H * 0.70f, half)
                val d = H * 0.11f * s; val br = H * 0.07f * s
                abxy(min(W * 0.66f, W * 0.97f - pw - W * 0.02f - d - br), H * 0.70f, d, br)
                rect("CENTER", W * 0.5f - W * 0.06f, H * 0.80f, W * 0.12f, H * 0.10f)
            }
            else -> {
                val th = H * 0.15f
                rect("LT", W * 0.03f, H * 0.03f, tw, th)
                rect("LB", W * 0.03f, H * 0.20f, tw, th)
                rect("RT", W * 0.97f - tw, H * 0.03f, tw, th)
                rect("RB", W * 0.97f - tw, H * 0.20f, tw, th)
                val top = H * 0.20f + th

                val lr = H * 0.16f * s
                val lcx = max(W * 0.15f, lr + W * 0.02f)
                round("LS", lcx, band(H * 0.58f, top + edge + lr, H - edge - lr), lr)

                val half = H * 0.14f * s
                round("DPAD", max(W * 0.33f, lcx + lr + half + W * 0.02f), min(H * 0.80f, H - edge - half), half)

                val d = H * 0.15f * s; val br = H * 0.09f * s
                val cx = min(W * 0.83f, W - W * 0.02f - d - br)
                abxy(cx, band(H * 0.60f, top + edge + d + br, H - edge - d - br), d, br)

                val rr = H * 0.14f * s
                round("RS", min(W * 0.66f, cx - d - br - rr - W * 0.015f), min(H * 0.78f, H - edge - rr), rr)
            }
        }
        // In the wheel layout the pedals take the room these would grow into.
        val sw = W * 0.09f * (if (base == Layouts.WHEEL) 1f else s)
        val sh = H * 0.11f
        rect("Back", W * 0.5f - sw - W * 0.02f, H * 0.44f, sw, sh)
        rect("Start", W * 0.5f + W * 0.02f, H * 0.44f, sw, sh)
        rect("Home", W * 0.5f - sw / 2, H * 0.29f, sw, sh)
        if (base != Layouts.WHEEL) {
            // Available for tilt steering in any layout; hidden until the user turns tilt on.
            rect("CENTER", W * 0.5f - W * 0.06f, H * 0.86f, W * 0.12f, H * 0.10f)
            el("CENTER")?.visible = false
        }
    }

    private fun currentControls(): List<Control> {
        val W = width.toFloat()
        val H = height.toFloat()
        return elems.map { Control(it.id, it.cx / W, it.cy / H, it.w / H, it.h / H, it.visible) }
    }

    private fun inDpad(x: Float, y: Float): Boolean {
        val d = el("DPAD") ?: return false
        return d.visible && abs(x - d.cx) <= d.w / 2 * 1.15f && abs(y - d.cy) <= d.h / 2 * 1.15f
    }

    /** The button a touch at (x, y) presses, with a little forgiveness around each one. */
    private fun hitButton(x: Float, y: Float): Elem? {
        var best: Elem? = null
        var bestD = Float.MAX_VALUE
        for (b in elems) {
            if (!b.visible || b.kind == Kind.STICK || b.kind == Kind.DPAD) continue
            val d = hypot(x - b.cx, y - b.cy)
            val hit = if (b.kind == Kind.CIRCLE) d <= b.r * 1.25f else b.contains(x, y, min(b.w, b.h) * 0.1f)
            if (hit && d < bestD) { best = b; bestD = d }
        }
        return best
    }

    private fun sticks() = elems.filter { it.kind == Kind.STICK && it.visible }

    // ---- editing -----------------------------------------------------------------------------

    fun startEditing() {
        if (editing) return
        snapshot = currentControls()
        snapshotTilt = tiltEnabled
        clearTouches()
        editing = true
        syncTilt()
        select(null)
        recompute(true)
    }

    /** Leaves edit mode, keeping the changes (save) or restoring what was there before. */
    fun finishEditing(keep: Boolean) {
        if (!editing) return
        if (keep) {
            custom = currentControls()
        } else {
            custom = snapshot
            tiltEnabled = snapshotTilt
            layoutNow()
        }
        editing = false
        dragging = false
        select(null)
        syncTilt()
        recompute(true)
    }

    fun layoutToSave(name: String) = Layout(name, base, tiltEnabled, currentControls())

    val selectedLabel: String? get() = selected?.let { if (it.kind == Kind.DPAD) "Direcional" else if (it.kind == Kind.STICK) "Analógico ${it.label}" else it.label }
    val selectedVisible: Boolean get() = selected?.visible ?: true

    fun resizeSelected(factor: Float) {
        val s = selected ?: return
        val nw = s.w * factor
        val nh = s.h * factor
        if (min(nw, nh) < height * 0.06f || nw > width * 0.6f || nh > height * 0.95f) return
        s.w = nw; s.h = nh
        clampInside(s)
        invalidate()
    }

    fun toggleSelectedVisible() {
        val s = selected ?: return
        s.visible = !s.visible
        invalidate()
        onSelectionChanged?.invoke()
    }

    /** Turns tilt steering on/off for this layout; turning it on also shows the "Centralizar" button. */
    fun toggleTilt(): Boolean {
        tiltEnabled = !tiltEnabled
        if (tiltEnabled) el("CENTER")?.visible = true
        invalidate()
        return tiltEnabled
    }

    fun resetToDefault() {
        custom = null
        tiltEnabled = base == Layouts.WHEEL
        layoutNow()
        select(null)
    }

    private fun select(e: Elem?) {
        selected = e
        invalidate()
        onSelectionChanged?.invoke()
    }

    private fun clampInside(s: Elem) {
        // Snap to an edge when close, so a control can sit exactly against it (e.g. in a corner).
        val snap = height * 0.03f
        if (s.cx - s.w / 2 < snap) s.cx = s.w / 2
        if (s.cx + s.w / 2 > width - snap) s.cx = width - s.w / 2
        if (s.cy - s.h / 2 < snap) s.cy = s.h / 2
        if (s.cy + s.h / 2 > height - snap) s.cy = height - s.h / 2
        s.cx = s.cx.coerceIn(s.w / 2, max(s.w / 2, width - s.w / 2))
        s.cy = s.cy.coerceIn(s.h / 2, max(s.h / 2, height - s.h / 2))
    }

    private fun editTouch(e: MotionEvent) {
        val x = e.getX(e.actionIndex)
        val y = e.getY(e.actionIndex)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val slack = height * 0.02f
                val hit = elems.filter { it.contains(x, y, slack) }.minByOrNull { hypot(x - it.cx, y - it.cy) }
                select(hit)
                if (hit != null) {
                    dragging = true
                    dragOffX = x - hit.cx
                    dragOffY = y - hit.cy
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val s = selected
                if (dragging && s != null) {
                    s.cx = e.getX(0) - dragOffX
                    s.cy = e.getY(0) - dragOffY
                    clampInside(s)
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                onSelectionChanged?.invoke() // the control may have moved to the other half: toolbar follows
            }
        }
    }

    // ---- touch -------------------------------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editing) {
            editTouch(e)
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i)
                val y = e.getY(i)
                if (optionsRect.contains(x, y)) {
                    onOptions?.invoke()
                    return true
                }
                pts[id] = floatArrayOf(x, y)
                // A touch on the d-pad or a button never grabs a stick, even inside the stick's wide zone.
                val onOther = inDpad(x, y) || hitButton(x, y) != null
                for (s in sticks()) {
                    if (onOther && hypot(x - s.cx, y - s.cy) > s.r) continue
                    if (s.owner == -1 && hypot(x - s.cx, y - s.cy) <= s.r * 1.5f) {
                        s.owner = id
                        break
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    pts[e.getPointerId(i)]?.let { it[0] = e.getX(i); it[1] = e.getY(i) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                pts.remove(id)
                for (s in elems) if (s.owner == id) s.owner = -1
            }
            MotionEvent.ACTION_CANCEL -> clearTouches()
        }
        recompute(true)
        return true
    }

    private fun clearTouches() {
        pts.clear()
        for (s in elems) s.owner = -1
    }

    private fun recompute(sendIfChanged: Boolean) {
        for (b in elems) b.pressed = false

        if (editing) {
            val neutral = IntArray(7)
            cur = neutral
            if (sendIfChanged && active && !neutral.contentEquals(sent)) transmit(neutral)
            invalidate()
            return
        }

        for (s in elems) {
            if (s.kind != Kind.STICK) continue
            val p = if (s.owner >= 0 && s.visible) pts[s.owner] else null
            if (p == null) {
                s.owner = -1
                s.nx = 0f; s.ny = 0f; s.outX = 0f; s.outY = 0f
                continue
            }
            var nx = (p[0] - s.cx) / s.r
            var ny = -(p[1] - s.cy) / s.r
            val mag = hypot(nx, ny)
            if (mag > 1f) { nx /= mag; ny /= mag }
            s.nx = nx; s.ny = ny
            val m = min(mag, 1f)
            if (m < 0.10f) {
                s.outX = 0f; s.outY = 0f
            } else {
                val k = (m - 0.10f) / 0.90f / m
                s.outX = nx * k; s.outY = ny * k
            }
        }

        val ls = el("LS")
        val rs = el("RS")
        var lx = ls?.outX ?: 0f
        var ly = ls?.outY ?: 0f
        // Tilt drives the left stick's X whenever the thumb isn't on the left stick.
        if (tiltEnabled && (ls == null || ls.owner < 0)) {
            lx = Tilt.steering()
            ly = 0f
        }

        var dp = 0
        if (base == Layouts.SIMPLE) {
            if (lx > 0.5f) dp = dp or Protocol.BTN_RIGHT
            if (lx < -0.5f) dp = dp or Protocol.BTN_LEFT
            if (ly > 0.5f) dp = dp or Protocol.BTN_UP
            if (ly < -0.5f) dp = dp or Protocol.BTN_DOWN
        }

        for ((id, p) in pts) {
            if (elems.any { it.kind == Kind.STICK && it.owner == id }) continue
            if (inDpad(p[0], p[1])) {
                val d = el("DPAD")!!
                val dx = p[0] - d.cx
                val dy = p[1] - d.cy
                val t = d.w / 2 * 0.30f
                if (dx > t) dp = dp or Protocol.BTN_RIGHT
                if (dx < -t) dp = dp or Protocol.BTN_LEFT
                if (dy > t) dp = dp or Protocol.BTN_DOWN
                if (dy < -t) dp = dp or Protocol.BTN_UP
                continue
            }
            hitButton(p[0], p[1])?.pressed = true
        }
        // Volume keys light up the on-screen control they stand for.
        var externalDpad = 0
        for (t in external.values) {
            externalDpad = externalDpad or (t.mask and DPAD_BITS)
            for (b in elems) {
                if (b.kind == Kind.STICK) continue
                if ((t.mask != 0 && b.mask and t.mask != 0) || (t.trigger != 0 && b.trigger == t.trigger)) b.pressed = true
            }
        }
        dpadMask = dp or externalDpad

        // "Centralizar": the current tilt becomes straight ahead.
        val centerPressed = el("CENTER")?.pressed == true
        if (centerPressed && !centerWasPressed) {
            Settings.updateTiltCenter(Tilt.angle)
            Settings.buzz(40)
        }
        centerWasPressed = centerPressed

        var mask = dp
        var lt = 0
        var rt = 0
        var bits = dp
        for (b in elems) {
            if (!b.pressed) continue
            mask = mask or b.mask
            if (b.trigger == 1) { lt = 255; bits = bits or (1 shl 20) }
            if (b.trigger == 2) { rt = 255; bits = bits or (1 shl 21) }
            bits = bits or b.mask
        }
        for (t in external.values) {
            mask = mask or t.mask
            bits = bits or t.mask
            if (t.trigger == 1) lt = 255
            if (t.trigger == 2) rt = 255
        }
        if (bits and prevPressBits.inv() != 0) Settings.buzz(15)
        prevPressBits = bits

        val state = intArrayOf(
            mask, lt, rt,
            toAxis(lx), toAxis(ly),
            toAxis(rs?.outX ?: 0f), toAxis(rs?.outY ?: 0f),
        )
        cur = state
        if (sendIfChanged && active && !state.contentEquals(sent)) transmit(state)
        invalidate()
    }

    private var dpadMask = 0

    /** Buttons held by physical keys (volume up/down), keyed by key code. */
    private val external = mutableMapOf<Int, Settings.VolumeTarget>()

    fun setExternal(keyCode: Int, target: Settings.VolumeTarget, down: Boolean) {
        if (down) external[keyCode] = target else external.remove(keyCode)
        recompute(true)
    }

    private fun toAxis(v: Float): Int = (v * 32767f).roundToInt().coerceIn(-32768, 32767)

    private fun transmit(s: IntArray) {
        sent = s
        Connection.sendGamepad(s[0], s[1], s[2], s[3], s[4], s[5], s[6])
    }

    // ---- lifecycle: periodic 50 ms send ------------------------------------------------------

    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            recompute(false)
            transmit(cur)
            handler.postDelayed(this, 50)
        }
    }

    fun start() {
        if (active) return
        active = true
        sent = null
        syncTilt()
        handler.post(ticker)
    }

    fun stop() {
        active = false
        handler.removeCallbacks(ticker)
        Tilt.stop()
        clearTouches()
        external.clear()
        prevPressBits = 0
        recompute(false)
        sent = null
    }

    private fun syncTilt() {
        val activity = context as? Activity
        if (active && tiltEnabled && !editing && activity != null) Tilt.start(activity) { recompute(true) } else Tilt.stop()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    // ---- drawing -----------------------------------------------------------------------------

    override fun onDraw(c: Canvas) {
        val W = width.toFloat()
        val H = height.toFloat()

        if (!editing) {
            drawPill(c, optionsRect, "⋯", H)
            // Connection: a small dot on the button; words only when something is wrong.
            val ok = Connection.connected
            fill.color = if (ok) 0xFF3DDC57.toInt() else 0xFFE5484D.toInt()
            c.drawCircle(optionsRect.right - H * 0.012f, optionsRect.top + H * 0.012f, H * 0.012f, fill)
            if (!ok) {
                text.color = 0xFFFF8A8E.toInt()
                text.textSize = H * 0.038f
                c.drawText("desconectado", W / 2, H * 0.16f, text)
            }
            if (ok && Connection.driverMissing) {
                text.color = 0xFFFFB454.toInt()
                text.textSize = H * 0.035f
                c.drawText("Driver de controle não instalado no PC", W / 2, H * 0.245f, text)
            }
        } else {
            text.color = 0xFF9AA0AE.toInt()
            text.textSize = H * 0.038f
            c.drawText("Toque num controle para selecionar · arraste para mover", W / 2, if (toolbarAtBottom) H * 0.06f else H * 0.97f, text)
        }

        if (tiltEnabled) drawTiltIndicator(c, W, H)

        for (b in elems) {
            if (!b.visible && !editing) continue
            val faded = !b.visible
            if (faded) c.saveLayerAlpha(0f, 0f, W, H, 70)
            when (b.kind) {
                Kind.STICK -> drawStick(c, b)
                Kind.DPAD -> drawDpad(c, b)
                else -> drawBtn(c, b, H)
            }
            if (faded) c.restore()
            if (editing && b === selected) {
                dashed.strokeWidth = max(2f, H * 0.006f)
                dashed.pathEffect = DashPathEffect(floatArrayOf(H * 0.02f, H * 0.012f), 0f)
                val pad = H * 0.015f
                c.drawRect(b.cx - b.w / 2 - pad, b.cy - b.h / 2 - pad, b.cx + b.w / 2 + pad, b.cy + b.h / 2 + pad, dashed)
            }
        }
    }

    private fun drawPill(c: Canvas, r: RectF, label: String, H: Float) {
        fill.color = 0xFF23262D.toInt()
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
        stroke.color = 0xFF3A3E48.toInt()
        stroke.strokeWidth = max(1f, H * 0.006f)
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, stroke)
        text.color = Color.WHITE
        text.textSize = H * 0.05f
        c.drawText(label, r.centerX(), r.centerY() + text.textSize * 0.35f, text)
    }

    /** Small bar showing current steering, above the "Centralizar" button when it is shown. */
    private fun drawTiltIndicator(c: Canvas, W: Float, H: Float) {
        val center = el("CENTER")
        val y = if (center != null && center.visible) center.cy - center.h / 2 - H * 0.05f else H * 0.66f
        val halfW = W * 0.045f
        stroke.color = 0xFF3A3E48.toInt()
        stroke.strokeWidth = max(2f, H * 0.012f)
        stroke.strokeCap = Paint.Cap.ROUND
        c.drawLine(W / 2 - halfW, y, W / 2 + halfW, y, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
        fill.color = 0xFF3A3E48.toInt()
        c.drawRect(W / 2 - 1.5f, y - H * 0.02f, W / 2 + 1.5f, y + H * 0.02f, fill)
        val steer = if (editing) 0f else Tilt.steering()
        fill.color = 0xFF4F9DFF.toInt()
        c.drawCircle(W / 2 + steer * halfW, y, H * 0.022f, fill)
    }

    private fun drawBtn(c: Canvas, b: Elem, H: Float) {
        if (b.kind == Kind.CIRCLE) {
            val r = if (b.pressed) b.r * 1.07f else b.r
            if (b.pressed) {
                fill.color = b.color
                c.drawCircle(b.cx, b.cy, r, fill)
                text.color = 0xFF101114.toInt()
            } else {
                fill.color = 0xFF22252C.toInt()
                c.drawCircle(b.cx, b.cy, r, fill)
                stroke.color = b.color
                stroke.strokeWidth = max(1f, H * 0.008f)
                c.drawCircle(b.cx, b.cy, r, stroke)
                text.color = b.color
            }
            text.textSize = b.r * 0.95f
            c.drawText(b.label, b.cx, b.cy + text.textSize * 0.35f, text)
        } else {
            val rect = RectF(b.cx - b.w / 2, b.cy - b.h / 2, b.cx + b.w / 2, b.cy + b.h / 2)
            val rad = min(b.w, b.h) * 0.25f
            fill.color = if (b.pressed) 0xFF4F9DFF.toInt() else 0xFF2A2D35.toInt()
            c.drawRoundRect(rect, rad, rad, fill)
            stroke.color = if (b.pressed) 0xFFBFDDFF.toInt() else if (b.color != 0) b.color else 0xFF3A3E48.toInt()
            stroke.strokeWidth = max(1f, H * 0.006f)
            c.drawRoundRect(rect, rad, rad, stroke)
            text.color = if (b.pressed) 0xFF101114.toInt() else 0xFFD5D8E0.toInt()
            text.textSize = min(b.h * 0.42f, b.w * 0.34f)
            val tw = text.measureText(b.label)
            if (tw > b.w * 0.85f) text.textSize *= b.w * 0.85f / tw
            c.drawText(b.label, b.cx, b.cy + text.textSize * 0.35f, text)
        }
    }

    private fun drawDpad(c: Canvas, d: Elem) {
        val a = min(d.w, d.h) / 2
        val t = a * 0.62f // arm thickness
        val rect = RectF()
        fun arm(l: Float, tp: Float, r: Float, b: Float, on: Boolean) {
            rect.set(l, tp, r, b)
            fill.color = if (on) 0xFF4F9DFF.toInt() else 0xFF2A2D35.toInt()
            c.drawRoundRect(rect, t * 0.25f, t * 0.25f, fill)
        }
        val cx = d.cx
        val cy = d.cy
        val m = if (editing) 0 else dpadMask
        arm(cx - t / 2, cy - a, cx + t / 2, cy - t / 2 + 1, m and Protocol.BTN_UP != 0)
        arm(cx - t / 2, cy + t / 2 - 1, cx + t / 2, cy + a, m and Protocol.BTN_DOWN != 0)
        arm(cx - a, cy - t / 2, cx - t / 2 + 1, cy + t / 2, m and Protocol.BTN_LEFT != 0)
        arm(cx + t / 2 - 1, cy - t / 2, cx + a, cy + t / 2, m and Protocol.BTN_RIGHT != 0)
        fill.color = 0xFF2A2D35.toInt()
        c.drawRect(cx - t / 2, cy - t / 2, cx + t / 2, cy + t / 2, fill)
    }

    private fun drawStick(c: Canvas, s: Elem) {
        val r = s.r
        fill.color = 0xFF191B21.toInt()
        c.drawCircle(s.cx, s.cy, r, fill)
        stroke.color = if (s.owner >= 0) 0xFF4F9DFF.toInt() else 0xFF3A3E48.toInt()
        stroke.strokeWidth = max(1f, r * 0.04f)
        c.drawCircle(s.cx, s.cy, r, stroke)
        val kr = r * 0.46f
        val kx = s.cx + s.nx * (r - kr * 0.4f)
        val ky = s.cy - s.ny * (r - kr * 0.4f)
        fill.color = if (s.owner >= 0) 0xFF4F9DFF.toInt() else 0xFF4A4E5A.toInt()
        c.drawCircle(kx, ky, kr, fill)
        stroke.color = if (s.owner >= 0) 0xFFBFDDFF.toInt() else 0xFF6A6F7C.toInt()
        c.drawCircle(kx, ky, kr, stroke)
    }
}
