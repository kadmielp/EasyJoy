package app.easyjoy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private val h = intArrayOf(0x45, 0x4A, 0x01)

    private fun pkt(type: Int, vararg body: Int) = b(*h, type, *body)

    @Test fun discover() = assertArrayEquals(pkt(0x01), Protocol.discover())

    @Test fun bye() = assertArrayEquals(pkt(0x30), Protocol.bye())

    @Test fun ping() = assertArrayEquals(pkt(0x05, 0x50, 0x69, 0x78), Protocol.ping("Pix"))

    @Test fun gamepad() {
        val p = Protocol.gamepad(0x0102, 0x1001, 255, 0, 32767, -32768, -1, 256)
        assertEquals(18, p.size)
        assertArrayEquals(
            pkt(0x10, 0x02, 0x01, 0x01, 0x10, 0xFF, 0x00, 0xFF, 0x7F, 0x00, 0x80, 0xFF, 0xFF, 0x00, 0x01),
            p
        )
    }

    @Test fun motion() {
        val p = Protocol.motion(0x0102030405060708, 1f, -1f, 0f, 0.5f, 2f, -2f)
        assertEquals(36, p.size)
        assertArrayEquals(
            pkt(
                0x11,
                0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01,
                0x00, 0x00, 0x80, 0x3F, 0x00, 0x00, 0x80, 0xBF, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x3F, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00, 0xC0,
            ),
            p
        )
    }

    @Test fun pongMotionFlag() {
        assertEquals(false, Protocol.pongMotionWanted(pkt(0x06, 0x00), 5))
        assertEquals(true, Protocol.pongMotionWanted(pkt(0x06, 0x00, 0x01), 6))
        assertEquals(false, Protocol.pongMotionWanted(pkt(0x06, 0x01, 0x00), 6))
        assertEquals(false, Protocol.pongMotionWanted(pkt(0x02, 0x00, 0x01), 6))
    }

    @Test fun gamepadSeqWraps() {
        val p = Protocol.gamepad(0x10000 + 5, Protocol.BTN_Y, 0, 0, 0, 0, 0, 0)
        assertEquals(5, p[4].toInt()); assertEquals(0, p[5].toInt())
        assertEquals(0x00, p[6].toInt()); assertEquals(0x80, p[7].toInt() and 0xFF)
    }

    @Test fun mouseMove() = assertArrayEquals(pkt(0x20, 0x0A, 0x00, 0xF6, 0xFF), Protocol.mouseMove(10, -10))

    @Test fun mouseButton() =
        assertArrayEquals(pkt(0x21, 0x34, 0x12, 0x01, 0x02), Protocol.mouseButton(0x1234, 1, 2))

    @Test fun scroll() = assertArrayEquals(pkt(0x22, 0x78, 0x00, 0x88, 0xFF), Protocol.scroll(120, -120))

    @Test fun text() = assertArrayEquals(pkt(0x23, 0x01, 0x00, 0x61, 0xC3, 0xA7), Protocol.text(1, "aç"))

    @Test fun key() = assertArrayEquals(pkt(0x24, 0xFF, 0xFF, 0x0D), Protocol.key(0xFFFF, 0x0D))

    @Test fun parseAnnounce() {
        val p = pkt(0x02, 0x50, 0x43, 0x2D, 0x31)
        assertEquals("PC-1", Protocol.parseAnnounce(p, p.size))
        assertNull(Protocol.parseAnnounce(pkt(0x06, 0), 5))
        assertNull(Protocol.parseAnnounce(b(0x45, 0x4A, 0x02, 0x02, 0x41), 5))
    }

    @Test fun parsePong() {
        assertEquals(0, Protocol.parsePong(pkt(0x06, 0), 5))
        assertEquals(1, Protocol.parsePong(pkt(0x06, 1), 5))
        assertNull(Protocol.parsePong(pkt(0x02, 0x41), 5))
        assertNull(Protocol.parsePong(b(0x45), 1))
    }
}
