package app.easyjoy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/** Touchpad / mouse surface with a bottom bar (Esquerdo, Direito, Teclado). */
class TouchpadView(context: Context) : View(context) {

    var onMenu: (() -> Unit)? = null
    var onKeyboard: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val slop = 10f * d
    private val handler = Handler(Looper.getMainLooper())

    private val menuRect = RectF()
    private val padRect = RectF()
    private val leftBtn = RectF()
    private val rightBtn = RectF()
    private val kbBtn = RectF()
    private var barTop = 0f

    private enum class Role { PAD, LEFT, RIGHT, KEYBOARD }
    private val roles = HashMap<Int, Role>()

    private class PadPtr(val sx: Float, val sy: Float, val downTime: Long) {
        var lx = sx
        var ly = sy
    }

    private val padPtrs = LinkedHashMap<Int, PadPtr>()

    private enum class Mode { IDLE, PENDING, MOVE, DRAG, TWO, IGNORE }
    private var mode = Mode.IDLE
    private var twoDownTime = 0L
    private var twoMoved = false
    private var pinching = false
    private var lastSpread = 0f
    private var zoomAcc = 0f

    private var accX = 0f
    private var accY = 0f
    private var scrollAccV = 0f
    private var scrollAccH = 0f

    // Double-tap-and-drag: a tap, then a touch that moves within this time and distance, drags.
    private var lastTapUp = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var dragArmed = false

    // Scroll inertia: finger velocity (px/ms) while scrolling, continued after the fingers lift.
    private var velX = 0f
    private var velY = 0f
    private var lastScrollTime = 0L

    private var leftHeld = false
    private var rightHeld = false
    private var kbPressed = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    init {
        setBackgroundColor(0xFF0E0F12.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val W = w.toFloat()
        val H = h.toFloat()
        menuRect.set(W / 2 - 52 * d, 6 * d, W / 2 + 52 * d, 42 * d)
        val barH = 56 * d
        barTop = H - barH - 12 * d
        padRect.set(8 * d, 50 * d, W - 8 * d, barTop - 8 * d)
        val gap = 8 * d
        val avail = W - 16 * d - gap * 2
        val bw = avail * 0.38f
        val kw = avail - bw * 2
        val top = H - barH - 8 * d
        leftBtn.set(8 * d, top, 8 * d + bw, top + barH)
        rightBtn.set(leftBtn.right + gap, top, leftBtn.right + gap + bw, top + barH)
        kbBtn.set(rightBtn.right + gap, top, rightBtn.right + gap + kw, top + barH)
    }

    // ---- touch -------------------------------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                onDown(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                onUp(e.getPointerId(i), e.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        invalidate()
        return true
    }

    private fun onDown(id: Int, x: Float, y: Float, now: Long) {
        stopFling()
        if (menuRect.contains(x, y)) {
            onMenu?.invoke()
            return
        }
        if (y >= barTop - 4 * d) {
            val role = when {
                x < leftBtn.right + 4 * d -> Role.LEFT
                x < rightBtn.right + 4 * d -> Role.RIGHT
                else -> Role.KEYBOARD
            }
            roles[id] = role
            Settings.buzz(15)
            when (role) {
                Role.LEFT -> { leftHeld = true; Connection.mouseButton(Protocol.MOUSE_LEFT, Protocol.ACTION_DOWN) }
                Role.RIGHT -> { rightHeld = true; Connection.mouseButton(Protocol.MOUSE_RIGHT, Protocol.ACTION_DOWN) }
                else -> kbPressed = true
            }
            return
        }
        if (!padRect.contains(x, y)) return
        roles[id] = Role.PAD
        padPtrs[id] = PadPtr(x, y, now)
        when {
            padPtrs.size == 1 -> {
                mode = Mode.PENDING
                accX = 0f; accY = 0f
                dragArmed = now - lastTapUp < DOUBLE_TAP_MS && hypot(x - lastTapX, y - lastTapY) < 48 * d
            }
            padPtrs.size == 2 && (mode == Mode.PENDING || mode == Mode.MOVE) -> {
                dragArmed = false
                val first = padPtrs.values.first()
                twoDownTime = if (mode == Mode.PENDING) first.downTime else Long.MIN_VALUE / 2
                twoMoved = false
                pinching = false
                zoomAcc = 0f
                scrollAccV = 0f; scrollAccH = 0f
                velX = 0f; velY = 0f
                lastScrollTime = now
                mode = Mode.TWO
            }
        }
    }

    private fun onMove(e: MotionEvent) {
        val cur = HashMap<Int, FloatArray>()
        for (i in 0 until e.pointerCount) {
            val id = e.getPointerId(i)
            if (padPtrs.containsKey(id)) cur[id] = floatArrayOf(e.getX(i), e.getY(i))
        }
        if (cur.isEmpty()) return
        when (mode) {
            Mode.PENDING -> {
                val id = padPtrs.keys.first(); val p = cur[id] ?: return
                val ptr = padPtrs[id] ?: return
                if (hypot(p[0] - ptr.sx, p[1] - ptr.sy) > slop) {
                    if (dragArmed) {
                        mode = Mode.DRAG
                        Connection.mouseButton(Protocol.MOUSE_LEFT, Protocol.ACTION_DOWN)
                        Settings.buzz(35)
                    } else {
                        mode = Mode.MOVE
                    }
                    moveBy(p[0] - ptr.lx, p[1] - ptr.ly)
                    ptr.lx = p[0]; ptr.ly = p[1]
                }
            }
            Mode.MOVE, Mode.DRAG -> {
                val id = padPtrs.keys.first(); val p = cur[id] ?: return
                val ptr = padPtrs[id] ?: return
                moveBy(p[0] - ptr.lx, p[1] - ptr.ly)
                ptr.lx = p[0]; ptr.ly = p[1]
            }
            Mode.TWO -> {
                if (cur.size >= 2 && padPtrs.size >= 2 && onTwoFingers(cur)) return
                var sumDx = 0f
                var sumDy = 0f
                var n = 0
                for ((id, p) in cur) {
                    val ptr = padPtrs[id] ?: continue
                    if (!twoMoved && hypot(p[0] - ptr.sx, p[1] - ptr.sy) > slop) twoMoved = true
                    if (twoMoved) {
                        sumDx += p[0] - ptr.lx
                        sumDy += p[1] - ptr.ly
                        ptr.lx = p[0]; ptr.ly = p[1]
                        n++
                    }
                }
                if (twoMoved && n > 0) {
                    val dx = sumDx / padPtrs.size
                    val dy = sumDy / padPtrs.size
                    scrollBy(dx, dy)
                    val dt = (e.eventTime - lastScrollTime).coerceAtLeast(1L).toFloat()
                    velX = 0.6f * (dx / dt) + 0.4f * velX
                    velY = 0.6f * (dy / dt) + 0.4f * velY
                    lastScrollTime = e.eventTime
                }
            }
            else -> {}
        }
    }

    private fun onUp(id: Int, now: Long) {
        when (roles.remove(id)) {
            Role.LEFT -> {
                if (leftHeld) { leftHeld = false; Connection.mouseButton(Protocol.MOUSE_LEFT, Protocol.ACTION_UP) }
            }
            Role.RIGHT -> {
                if (rightHeld) { rightHeld = false; Connection.mouseButton(Protocol.MOUSE_RIGHT, Protocol.ACTION_UP) }
            }
            Role.KEYBOARD -> {
                kbPressed = false
                onKeyboard?.invoke()
            }
            Role.PAD -> {
                // padPtrs is insertion-ordered: the first entry is the finger that owns a move/drag.
                val wasOwner = padPtrs.keys.firstOrNull() == id
                val ptr = padPtrs.remove(id) ?: return
                when (mode) {
                    Mode.PENDING -> {
                        if (now - ptr.downTime < 250) {
                            Connection.mouseButton(Protocol.MOUSE_LEFT, Protocol.ACTION_CLICK)
                            // A second tap right after is a double-click, not the start of a drag.
                            lastTapUp = if (dragArmed) 0L else now
                            lastTapX = ptr.sx; lastTapY = ptr.sy
                        }
                        mode = Mode.IDLE
                    }
                    Mode.DRAG -> if (wasOwner) {
                        // Only the dragging finger ends the drag; a stray second finger lifting does not.
                        Connection.mouseButton(Protocol.MOUSE_LEFT, Protocol.ACTION_UP)
                        mode = if (padPtrs.isEmpty()) Mode.IDLE else Mode.IGNORE
                    }
                    Mode.TWO -> {
                        if (!twoMoved && !pinching && now - twoDownTime < 350) {
                            Connection.mouseButton(Protocol.MOUSE_RIGHT, Protocol.ACTION_CLICK)
                        } else if (twoMoved && now - lastScrollTime < 80) {
                            startFling() // fingers were still moving when they lifted
                        }
                        mode = if (padPtrs.isEmpty()) Mode.IDLE else Mode.IGNORE
                    }
                    Mode.IGNORE, Mode.MOVE -> if (padPtrs.isEmpty()) mode = Mode.IDLE
                    Mode.IDLE -> {}
                }
                if (padPtrs.isEmpty()) mode = Mode.IDLE
            }
            null -> {}
        }
    }

    /**
     * Pinch vs. two-finger scroll, decided once per gesture: fingers spreading/closing more than the
     * pair moves = pinch (zoom), otherwise scroll. Returns true when the move was used as a pinch.
     */
    private fun onTwoFingers(cur: Map<Int, FloatArray>): Boolean {
        val ids = padPtrs.keys.take(2)
        val a = cur[ids[0]] ?: return false
        val b = cur[ids[1]] ?: return false
        val pa = padPtrs[ids[0]]!!
        val pb = padPtrs[ids[1]]!!
        val spread = hypot(a[0] - b[0], a[1] - b[1])
        if (!pinching && !twoMoved) {
            val startSpread = hypot(pa.sx - pb.sx, pa.sy - pb.sy)
            val change = kotlin.math.abs(spread - startSpread)
            val shift = hypot((a[0] + b[0] - pa.sx - pb.sx) / 2, (a[1] + b[1] - pa.sy - pb.sy) / 2)
            if (change > slop * 2 && change > shift * 1.5f) {
                pinching = true
                lastSpread = spread
            }
        }
        if (!pinching) return false
        // One zoom step (120) per ~45 dp of spread change; spreading = zoom in.
        zoomAcc += (spread - lastSpread) * 120f / (45f * d)
        lastSpread = spread
        val steps = (zoomAcc / 120f).toInt()
        if (steps != 0) {
            zoomAcc -= steps * 120f
            Connection.zoom(steps * 120)
            Settings.buzz(10)
        }
        return true
    }

    /** Release anything held; called on cancel and when the screen is paused. */
    fun releaseAll() {
        stopFling()
        if (mode == Mode.DRAG) Connection.mouseButton(Protocol.MOUSE_LEFT, Protocol.ACTION_UP)
        if (leftHeld) Connection.mouseButton(Protocol.MOUSE_LEFT, Protocol.ACTION_UP)
        if (rightHeld) Connection.mouseButton(Protocol.MOUSE_RIGHT, Protocol.ACTION_UP)
        leftHeld = false; rightHeld = false; kbPressed = false
        padPtrs.clear()
        roles.clear()
        mode = Mode.IDLE
        invalidate()
    }

    private fun moveBy(dx: Float, dy: Float) {
        val dist = hypot(dx, dy) / d
        val gain = Settings.sensitivity * (1f + min(1.5f, dist * 0.05f))
        accX += dx * gain
        accY += dy * gain
        val ix = accX.toInt()
        val iy = accY.toInt()
        accX -= ix
        accY -= iy
        if (ix != 0 || iy != 0) {
            Connection.mouseMove(ix.coerceIn(-32768, 32767), iy.coerceIn(-32768, 32767))
        }
    }

    /** Natural scrolling, like a laptop touchpad: the page follows the fingers. 120 units per ~50 dp. */
    private fun scrollBy(dx: Float, dy: Float) {
        val k = 120f / (50f * d)
        scrollAccV += dy * k
        val horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)
        if (horizontal) scrollAccH += -dx * k
        val v = scrollAccV.toInt()
        val h = scrollAccH.toInt()
        scrollAccV -= v
        scrollAccH -= h
        if (v != 0 || h != 0) Connection.scroll(v.coerceIn(-32768, 32767), h.coerceIn(-32768, 32767))
    }

    // ---- scroll inertia ------------------------------------------------------------------------

    private val fling = object : Runnable {
        override fun run() {
            velX *= FRICTION
            velY *= FRICTION
            if (hypot(velX, velY) < 0.05f * d) return
            scrollBy(velX * FRAME_MS, velY * FRAME_MS)
            handler.postDelayed(this, FRAME_MS.toLong())
        }
    }

    private fun startFling() {
        handler.removeCallbacks(fling)
        if (hypot(velX, velY) >= 0.15f * d) handler.post(fling)
    }

    private fun stopFling() = handler.removeCallbacks(fling)

    private companion object {
        const val DOUBLE_TAP_MS = 300L
        const val FRAME_MS = 16f
        const val FRICTION = 0.94f
    }

    // ---- periodic repaint for the connection indicator ---------------------------------------

    private val refresh = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 500)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(refresh)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(refresh)
        releaseAll()
        super.onDetachedFromWindow()
    }

    // ---- drawing -----------------------------------------------------------------------------

    override fun onDraw(c: Canvas) {
        val W = width.toFloat()

        // Top strip: indicator (left) + Menu (centre)
        val ok = Connection.connected
        fill.color = if (ok) 0xFF3DDC57.toInt() else 0xFFE5484D.toInt()
        c.drawCircle(18 * d, 24 * d, 6 * d, fill)
        text.textAlign = Paint.Align.LEFT
        text.textSize = 13 * d
        text.color = if (ok) 0xFFB5E8BE.toInt() else 0xFFFF8A8E.toInt()
        c.drawText(if (ok) tr("conectado", "connected", "conectado") else tr("desconectado", "disconnected", "desconectado"), 30 * d, 24 * d + text.textSize * 0.35f, text)
        text.textAlign = Paint.Align.CENTER

        fill.color = 0xFF23262D.toInt()
        c.drawRoundRect(menuRect, 18 * d, 18 * d, fill)
        stroke.color = 0xFF3A3E48.toInt()
        c.drawRoundRect(menuRect, 18 * d, 18 * d, stroke)
        text.color = 0xFFFFFFFF.toInt()
        text.textSize = 15 * d
        c.drawText("Menu", menuRect.centerX(), menuRect.centerY() + text.textSize * 0.35f, text)

        // Pad
        fill.color = 0xFF1B1D23.toInt()
        c.drawRoundRect(padRect, 18 * d, 18 * d, fill)
        stroke.color = if (mode == Mode.DRAG) 0xFF4F9DFF.toInt() else 0xFF2C2F37.toInt()
        stroke.strokeWidth = (if (mode == Mode.DRAG) 3f else 1.5f) * d
        c.drawRoundRect(padRect, 18 * d, 18 * d, stroke)
        text.color = 0xFF646A78.toInt()
        text.textSize = 13 * d
        val cx = padRect.centerX()
        val cy = padRect.centerY()
        val lines = if (mode == Mode.DRAG) listOf(tr("Arrastando...", "Dragging...", "Arrastrando...")) else listOf(
            tr("Arraste para mover", "Drag to move", "Arrastra para mover"),
            tr("Toque = clique esquerdo", "Tap = left click", "Toque = clic izquierdo"),
            tr("Toque com 2 dedos = clique direito", "2-finger tap = right click", "Toque con 2 dedos = clic derecho"),
            tr("2 dedos arrastando = rolagem", "2-finger drag = scroll", "Arrastrar con 2 dedos = desplazar"),
            tr("Pinça com 2 dedos = zoom", "2-finger pinch = zoom", "Pellizcar con 2 dedos = zoom"),
            tr("Toque duas vezes e arraste = arrastar item", "Double-tap and drag = drag item", "Toca dos veces y arrastra = arrastrar elemento"),
        )
        var y = cy - (lines.size - 1) * 10 * d
        for (l in lines) { c.drawText(l, cx, y + text.textSize * 0.35f, text); y += 20 * d }
        if (ok && Connection.driverMissing) {
            text.color = 0xFFFFB454.toInt()
            text.textSize = 12 * d
            c.drawText(tr("Driver de controle não instalado no PC — o modo mouse funciona", "Controller driver not installed on the PC — mouse mode works", "Driver de control no instalado en el PC — el modo ratón funciona"), cx, padRect.top + 22 * d, text)
        }

        // Bottom bar
        drawBar(c, leftBtn, tr("Esquerdo", "Left", "Izquierdo"), leftHeld)
        drawBar(c, rightBtn, tr("Direito", "Right", "Derecho"), rightHeld)
        drawBar(c, kbBtn, tr("Teclado", "Keyboard", "Teclado"), kbPressed)
        stroke.strokeWidth = 1.5f * d
    }

    private fun drawBar(c: Canvas, r: RectF, label: String, pressed: Boolean) {
        fill.color = if (pressed) 0xFF4F9DFF.toInt() else 0xFF2A2D35.toInt()
        c.drawRoundRect(r, 14 * d, 14 * d, fill)
        stroke.strokeWidth = 1.5f * d
        stroke.color = if (pressed) 0xFFBFDDFF.toInt() else 0xFF3A3E48.toInt()
        c.drawRoundRect(r, 14 * d, 14 * d, stroke)
        text.color = if (pressed) 0xFF101114.toInt() else 0xFFE4E6EC.toInt()
        text.textSize = 16 * d
        c.drawText(label, r.centerX(), r.centerY() + text.textSize * 0.35f, text)
    }
}
