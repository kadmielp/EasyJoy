package app.easyjoy

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class Pc(val name: String, val ip: String, val lastSeen: Long)

/**
 * Single connection manager: owns the UDP socket, discovery, ping/pong and every send.
 * All network I/O happens on background threads. Reference counted via acquire()/release().
 */
object Connection {
    // Observable state (readable from Compose and from custom Views).
    var targetIp by mutableStateOf<String?>(null)
        private set
    var connected by mutableStateOf(false)
        private set
    var driverMissing by mutableStateOf(false)
        private set
    var discovered by mutableStateOf<List<Pc>>(emptyList())
        private set

    private var appContext: Context? = null
    private var refs = 0
    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    private var sendExec: ExecutorService? = null
    private var tickThread: Thread? = null

    @Volatile private var lastPong = 0L
    private val seq = AtomicInteger(0)
    private val eventId = AtomicInteger(0)
    private val deviceName: String = Build.MODEL ?: "Android"

    @Synchronized
    fun acquire(context: Context) {
        appContext = context.applicationContext
        if (refs++ > 0) return
        Settings.init(context)
        if (targetIp == null) targetIp = Settings.lastIp
        start()
    }

    @Synchronized
    fun release() {
        if (refs == 0 || --refs > 0) return
        stop()
    }

    private fun openSocket(port: Int): DatagramSocket {
        val s = DatagramSocket(null)
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(port))
        } catch (e: Exception) {
            s.close()
            throw e
        }
        return s
    }

    private fun start() {
        val s = try {
            try {
                openSocket(Protocol.PORT)
            } catch (_: Exception) {
                openSocket(0) // port busy: replies go to the source port anyway
            }
        } catch (_: Exception) {
            return
        }
        s.broadcast = true
        socket = s
        running = true
        lastPong = 0L
        sendExec = Executors.newSingleThreadExecutor()
        Thread({ receiveLoop(s) }, "ej-recv").apply { isDaemon = true; start() }
        tickThread = Thread({ tickLoop() }, "ej-tick").apply { isDaemon = true; start() }
    }

    private fun stop() {
        running = false
        tickThread?.interrupt()
        val s = socket
        socket = null
        val exec = sendExec
        sendExec = null
        // Close the socket only after already-queued packets (BYE, mouse-up) have gone out.
        try {
            exec?.execute { try { s?.close() } catch (_: Exception) {} } ?: s?.close()
        } catch (_: Exception) {
            try { s?.close() } catch (_: Exception) {}
        }
        exec?.shutdown()
        connected = false
    }

    // ---- connection target -------------------------------------------------------------------

    fun connect(ip: String, name: String? = null) {
        val known = name ?: discovered.firstOrNull { it.ip == ip }?.name
        Settings.updateLast(ip, known)
        lastPong = 0L
        connected = false
        driverMissing = false
        targetIp = ip
    }

    fun disconnect() {
        Settings.updateLast(null, null)
        targetIp = null
        connected = false
        driverMissing = false
        lastPong = 0L
    }

    fun targetName(): String? {
        val ip = targetIp ?: return null
        return discovered.firstOrNull { it.ip == ip }?.name
            ?: Settings.lastName?.takeIf { Settings.lastIp == ip }
    }

    // ---- background loops --------------------------------------------------------------------

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(1500)
        val pkt = DatagramPacket(buf, buf.size)
        while (running && !s.isClosed) {
            try {
                pkt.length = buf.size
                s.receive(pkt)
                val ip = pkt.address?.hostAddress ?: continue
                val len = pkt.length
                val name = Protocol.parseAnnounce(buf, len)
                if (name != null) {
                    onAnnounce(ip, name)
                    continue
                }
                val status = Protocol.parsePong(buf, len)
                if (status != null && ip == targetIp) {
                    lastPong = System.currentTimeMillis()
                    driverMissing = status == 1
                    connected = true
                }
            } catch (_: Exception) {
                if (!running) break
            }
        }
    }

    private fun onAnnounce(ip: String, name: String) {
        val now = System.currentTimeMillis()
        val list = discovered.filter { it.ip != ip } + Pc(name, ip, now)
        discovered = list.sortedBy { it.name.lowercase() }
        if (ip == targetIp && Settings.lastIp == ip && Settings.lastName != name) Settings.updateLast(ip, name)
    }

    private fun tickLoop() {
        var lastDiscover = 0L
        var lastPing = 0L
        while (running) {
            val now = System.currentTimeMillis()
            if (now - lastDiscover >= 2000) {
                lastDiscover = now
                sendDiscover()
            }
            val ip = targetIp
            if (ip != null && now - lastPing >= 1000) {
                lastPing = now
                send(Protocol.ping(deviceName))
            }
            val ok = ip != null && lastPong != 0L && now - lastPong <= 3000
            if (connected != ok) connected = ok
            if (discovered.any { now - it.lastSeen > 7000 }) {
                discovered = discovered.filter { now - it.lastSeen <= 7000 }
            }
            try { Thread.sleep(200) } catch (_: InterruptedException) { }
        }
    }

    private fun sendDiscover() {
        val s = socket ?: return
        val data = Protocol.discover()
        val targets = LinkedHashSet<InetAddress>()
        try { targets.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
        subnetBroadcast()?.let { targets.add(it) }
        for (a in targets) {
            try { s.send(DatagramPacket(data, data.size, a, Protocol.PORT)) } catch (_: Exception) {}
        }
    }

    private fun subnetBroadcast(): InetAddress? {
        return try {
            val cm = appContext?.getSystemService(ConnectivityManager::class.java) ?: return null
            val net = cm.activeNetwork ?: return null
            val lp = cm.getLinkProperties(net) ?: return null
            for (la in lp.linkAddresses) {
                val addr = la.address
                if (addr is Inet4Address && la.prefixLength in 1..30) {
                    var ip = 0
                    for (b in addr.address) ip = (ip shl 8) or (b.toInt() and 0xFF)
                    val mask = -1 shl (32 - la.prefixLength)
                    val bc = ip or mask.inv()
                    return InetAddress.getByAddress(
                        byteArrayOf((bc ushr 24).toByte(), (bc ushr 16).toByte(), (bc ushr 8).toByte(), bc.toByte())
                    )
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // ---- sending -----------------------------------------------------------------------------

    private fun send(data: ByteArray) {
        val ip = targetIp ?: return
        val exec = sendExec ?: return
        val s = socket ?: return // captured now, so packets queued before stop() still use it
        try {
            exec.execute {
                try {
                    s.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), Protocol.PORT))
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun nextEventId() = eventId.incrementAndGet() and 0xFFFF

    private fun sendThrice(make: (Int) -> ByteArray) {
        val data = make(nextEventId())
        repeat(3) { send(data) }
    }

    fun sendGamepad(buttons: Int, lt: Int, rt: Int, lx: Int, ly: Int, rx: Int, ry: Int) =
        send(Protocol.gamepad(seq.incrementAndGet() and 0xFFFF, buttons, lt, rt, lx, ly, rx, ry))

    fun sendBye() {
        send(Protocol.bye())
        send(Protocol.bye())
    }

    fun mouseMove(dx: Int, dy: Int) = send(Protocol.mouseMove(dx, dy))

    fun mouseButton(button: Int, action: Int) = sendThrice { Protocol.mouseButton(it, button, action) }

    fun scroll(vertical: Int, horizontal: Int) = send(Protocol.scroll(vertical, horizontal))

    fun key(vk: Int) = sendThrice { Protocol.key(it, vk) }

    /** Sends text, splitting long strings into chunks that fit one datagram. */
    fun text(text: String) {
        if (text.isEmpty()) return
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val cb = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + cb > 400) {
                val chunk = sb.toString()
                sendThrice { Protocol.text(it, chunk) }
                sb.setLength(0)
                bytes = 0
            }
            sb.appendCodePoint(cp)
            bytes += cb
            i += Character.charCount(cp)
        }
        if (sb.isNotEmpty()) {
            val chunk = sb.toString()
            sendThrice { Protocol.text(it, chunk) }
        }
    }
}
