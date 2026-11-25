package app.easyjoy

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** EasyJoy UDP protocol (see docs/design.md). Pure Kotlin, no Android dependencies. */
object Protocol {
    const val PORT = 47820

    const val T_DISCOVER = 0x01
    const val T_ANNOUNCE = 0x02
    const val T_PING = 0x05
    const val T_PONG = 0x06
    const val T_GAMEPAD = 0x10
    const val T_MOTION = 0x11
    const val T_MOUSE_MOVE = 0x20
    const val T_MOUSE_BUTTON = 0x21
    const val T_SCROLL = 0x22
    const val T_TEXT = 0x23
    const val T_KEY = 0x24
    const val T_ZOOM = 0x25
    const val T_BYE = 0x30

    // XInput button masks
    const val BTN_UP = 0x0001
    const val BTN_DOWN = 0x0002
    const val BTN_LEFT = 0x0004
    const val BTN_RIGHT = 0x0008
    const val BTN_START = 0x0010
    const val BTN_BACK = 0x0020
    const val BTN_L3 = 0x0040
    const val BTN_R3 = 0x0080
    const val BTN_LB = 0x0100
    const val BTN_RB = 0x0200
    const val BTN_GUIDE = 0x0400
    const val BTN_A = 0x1000
    const val BTN_B = 0x2000
    const val BTN_X = 0x4000
    const val BTN_Y = 0x8000

    const val MOUSE_LEFT = 0
    const val MOUSE_RIGHT = 1
    const val MOUSE_MIDDLE = 2
    const val ACTION_UP = 0
    const val ACTION_DOWN = 1
    const val ACTION_CLICK = 2

    const val VK_BACK = 0x08
    const val VK_RETURN = 0x0D

    private fun buf(type: Int, bodySize: Int): ByteBuffer {
        val b = ByteBuffer.allocate(4 + bodySize).order(ByteOrder.LITTLE_ENDIAN)
        b.put('E'.code.toByte()).put('J'.code.toByte()).put(1).put(type.toByte())
        return b
    }

    fun discover(): ByteArray = buf(T_DISCOVER, 0).array()

    fun ping(deviceName: String): ByteArray {
        val n = deviceName.toByteArray(Charsets.UTF_8)
        return buf(T_PING, n.size).put(n).array()
    }

    fun bye(): ByteArray = buf(T_BYE, 0).array()

    fun gamepad(seq: Int, buttons: Int, lt: Int, rt: Int, lx: Int, ly: Int, rx: Int, ry: Int): ByteArray =
        buf(T_GAMEPAD, 14)
            .putShort(seq.toShort()).putShort(buttons.toShort())
            .put(lt.toByte()).put(rt.toByte())
            .putShort(lx.toShort()).putShort(ly.toShort())
            .putShort(rx.toShort()).putShort(ry.toShort())
            .array()

    /**
     * Motion sample, already in DSU (Cemuhook) axes: accel in g, gyro in deg/s.
     * [timestampUs] is the sensor clock in microseconds.
     */
    fun motion(timestampUs: Long, ax: Float, ay: Float, az: Float, pitch: Float, yaw: Float, roll: Float): ByteArray =
        buf(T_MOTION, 32).putLong(timestampUs)
            .putFloat(ax).putFloat(ay).putFloat(az)
            .putFloat(pitch).putFloat(yaw).putFloat(roll)
            .array()

    fun mouseMove(dx: Int, dy: Int): ByteArray =
        buf(T_MOUSE_MOVE, 4).putShort(dx.toShort()).putShort(dy.toShort()).array()

    fun mouseButton(eventId: Int, button: Int, action: Int): ByteArray =
        buf(T_MOUSE_BUTTON, 4).putShort(eventId.toShort()).put(button.toByte()).put(action.toByte()).array()

    /** Ctrl + wheel on the PC, in wheel units (120 = one step); positive = zoom in. */
    fun zoom(amount: Int): ByteArray = buf(T_ZOOM, 2).putShort(amount.toShort()).array()

    fun scroll(vertical: Int, horizontal: Int): ByteArray =
        buf(T_SCROLL, 4).putShort(vertical.toShort()).putShort(horizontal.toShort()).array()

    fun text(eventId: Int, text: String): ByteArray {
        val t = text.toByteArray(Charsets.UTF_8)
        return buf(T_TEXT, 2 + t.size).putShort(eventId.toShort()).put(t).array()
    }

    fun key(eventId: Int, virtualKey: Int): ByteArray =
        buf(T_KEY, 3).putShort(eventId.toShort()).put(virtualKey.toByte()).array()

    /** Returns the packet type if the header is valid, else -1. */
    fun typeOf(data: ByteArray, len: Int): Int =
        if (len >= 4 && data[0] == 'E'.code.toByte() && data[1] == 'J'.code.toByte() && data[2] == 1.toByte())
            data[3].toInt() and 0xFF
        else -1

    /** PC name from an ANNOUNCE packet, or null if it isn't one. */
    fun parseAnnounce(data: ByteArray, len: Int): String? =
        if (typeOf(data, len) == T_ANNOUNCE) String(data, 4, len - 4, Charsets.UTF_8) else null

    /** PONG flag (bit 0 of the second byte): an emulator is listening for motion. Old PCs don't send it. */
    fun pongMotionWanted(data: ByteArray, len: Int): Boolean =
        typeOf(data, len) == T_PONG && len >= 6 && (data[5].toInt() and 1) != 0

    /** Status byte from a PONG packet (0 ok, 1 driver missing), or null if it isn't one. */
    fun parsePong(data: ByteArray, len: Int): Int? =
        if (typeOf(data, len) == T_PONG) (if (len >= 5) data[4].toInt() and 0xFF else 0) else null
}
