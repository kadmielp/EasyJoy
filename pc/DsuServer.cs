using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;

namespace EasyJoy;

// Cemuhook DSU server (https://v1993.github.io/cemuhook-protocol/) on 127.0.0.1:26760.
// Emulators (Eden, Cemu, Dolphin...) subscribe here and get each phone's gyro/accelerometer.
// Only motion is served: buttons and sticks keep coming from the virtual Xbox pad.
public sealed class DsuServer : IDisposable
{
    public const int Port = 26760;
    public const int Slots = 4;
    const ushort Version = 1001;
    const uint MsgVersion = 0x100000, MsgPortInfo = 0x100001, MsgPadData = 0x100002;
    static readonly TimeSpan ClientTimeout = TimeSpan.FromSeconds(6); // Eden re-subscribes every 3 s

    readonly UdpClient _udp;
    readonly CancellationTokenSource _cts = new();
    readonly object _lock = new();
    readonly uint _serverId = (uint)Random.Shared.Next();
    readonly Dictionary<IPEndPoint, Client> _clients = [];
    readonly bool[] _active = new bool[Slots];

    public DsuServer(int port = Port)
    {
        _udp = new UdpClient(AddressFamily.InterNetwork);
        const int SIO_UDP_CONNRESET = -1744830452;
        _udp.Client.IOControl(SIO_UDP_CONNRESET, [0, 0, 0, 0], null);
        _udp.Client.Bind(new IPEndPoint(IPAddress.Loopback, port));
        _ = Task.Run(ReceiveLoop);
    }

    /// True while some emulator has asked for pad data recently.
    public bool HasClients
    {
        get
        {
            lock (_lock)
            {
                var now = DateTime.UtcNow;
                return _clients.Values.Any(c => now - c.LastRequest <= ClientTimeout);
            }
        }
    }

    /// Marks a slot as having (or no longer having) a phone with motion.
    public void SetActive(int slot, bool active)
    {
        lock (_lock) _active[slot] = active;
    }

    public void SendMotion(int slot, in MotionSample m)
    {
        lock (_lock)
        {
            _active[slot] = true;
            var now = DateTime.UtcNow;
            foreach (var (ep, c) in _clients.ToList())
            {
                if (now - c.LastRequest > ClientTimeout) { _clients.Remove(ep); continue; }
                if (!c.Wants(slot)) continue;
                Send(PadData(slot, ++c.PacketNumber, m), ep);
            }
        }
    }

    async Task ReceiveLoop()
    {
        while (!_cts.IsCancellationRequested)
        {
            UdpReceiveResult r;
            try { r = await _udp.ReceiveAsync(_cts.Token); }
            catch (OperationCanceledException) { return; }
            catch (ObjectDisposedException) { return; }
            catch (SocketException) { continue; }

            try { lock (_lock) Handle(r.Buffer, r.RemoteEndPoint); }
            catch (Exception) { /* ignore malformed requests */ }
        }
    }

    void Handle(byte[] d, IPEndPoint from)
    {
        if (d.Length < 20 || d[0] != 'D' || d[1] != 'S' || d[2] != 'U' || d[3] != 'C') return;
        int length = BinaryPrimitives.ReadUInt16LittleEndian(d.AsSpan(6));
        if (16 + length > d.Length) return;
        uint crc = BinaryPrimitives.ReadUInt32LittleEndian(d.AsSpan(8));
        var copy = d[..(16 + length)];
        copy[8] = copy[9] = copy[10] = copy[11] = 0;
        if (Crc32(copy) != crc) return;

        switch (BinaryPrimitives.ReadUInt32LittleEndian(d.AsSpan(16)))
        {
            case MsgVersion:
            {
                var p = Message(MsgVersion, 2);
                BinaryPrimitives.WriteUInt16LittleEndian(p.AsSpan(20), Version);
                Send(Finish(p), from);
                break;
            }
            case MsgPortInfo:
            {
                if (d.Length < 24) return;
                // An emulator polling for controllers counts as listening: Dolphin asks for pad data only
                // after a slot shows as connected, and a slot connects only once the phone streams motion.
                if (!_clients.TryGetValue(from, out var poller)) _clients[from] = poller = new Client();
                poller.LastRequest = DateTime.UtcNow;
                int count = Math.Clamp(BinaryPrimitives.ReadInt32LittleEndian(d.AsSpan(20)), 0, 4);
                for (int i = 0; i < count && 24 + i < d.Length; i++)
                {
                    int slot = d[24 + i];
                    if (slot >= Slots) continue;
                    var p = Message(MsgPortInfo, 12);
                    WriteSlotInfo(p, slot);
                    Send(Finish(p), from);
                }
                break;
            }
            case MsgPadData:
            {
                if (d.Length < 22) return;
                if (!_clients.TryGetValue(from, out var c)) _clients[from] = c = new Client();
                c.LastRequest = DateTime.UtcNow;
                byte flags = d[20];
                if (flags == 0) c.AllSlots = true;
                else if ((flags & 1) != 0 && d[21] < Slots) c.SlotMask |= 1 << d[21];
                else c.AllSlots = true; // MAC-based: our MACs are per slot, so just send everything
                break;
            }
        }
    }

    void WriteSlotInfo(byte[] p, int slot)
    {
        bool on = _active[slot];
        p[20] = (byte)slot;
        p[21] = on ? (byte)2 : (byte)0;          // state: connected
        p[22] = on ? (byte)2 : (byte)0;          // model: full gyro
        p[23] = on ? (byte)2 : (byte)0;          // connection: wireless
        if (on) { p[24] = 0xEA; p[25] = 0x51; p[29] = (byte)(slot + 1); } // stable fake MAC per slot
        p[30] = on ? (byte)0x04 : (byte)0;       // battery: high
    }

    byte[] PadData(int slot, uint packetNumber, in MotionSample m)
    {
        var p = Message(MsgPadData, 80);
        WriteSlotInfo(p, slot);
        p[31] = 1; // connected
        BinaryPrimitives.WriteUInt32LittleEndian(p.AsSpan(32), packetNumber);
        p[40] = p[41] = p[42] = p[43] = 128; // sticks centered
        BinaryPrimitives.WriteUInt64LittleEndian(p.AsSpan(68), m.TimestampUs);
        var f = p.AsSpan(76);
        BinaryPrimitives.WriteSingleLittleEndian(f, m.AX);
        BinaryPrimitives.WriteSingleLittleEndian(f[4..], m.AY);
        BinaryPrimitives.WriteSingleLittleEndian(f[8..], m.AZ);
        BinaryPrimitives.WriteSingleLittleEndian(f[12..], m.Pitch);
        BinaryPrimitives.WriteSingleLittleEndian(f[16..], m.Yaw);
        BinaryPrimitives.WriteSingleLittleEndian(f[20..], m.Roll);
        return Finish(p);
    }

    // Header + message type, with a body of `bodySize` zero bytes after them.
    byte[] Message(uint type, int bodySize)
    {
        var p = new byte[20 + bodySize];
        p[0] = (byte)'D'; p[1] = (byte)'S'; p[2] = (byte)'U'; p[3] = (byte)'S';
        BinaryPrimitives.WriteUInt16LittleEndian(p.AsSpan(4), Version);
        BinaryPrimitives.WriteUInt16LittleEndian(p.AsSpan(6), (ushort)(4 + bodySize));
        BinaryPrimitives.WriteUInt32LittleEndian(p.AsSpan(12), _serverId);
        BinaryPrimitives.WriteUInt32LittleEndian(p.AsSpan(16), type);
        return p;
    }

    static byte[] Finish(byte[] p)
    {
        BinaryPrimitives.WriteUInt32LittleEndian(p.AsSpan(8), Crc32(p));
        return p;
    }

    void Send(byte[] p, IPEndPoint to)
    {
        try { _udp.Send(p, p.Length, to); } catch (SocketException) { }
    }

    static readonly uint[] CrcTable = Enumerable.Range(0, 256).Select(n =>
    {
        uint c = (uint)n;
        for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1;
        return c;
    }).ToArray();

    public static uint Crc32(ReadOnlySpan<byte> data)
    {
        uint c = 0xFFFFFFFF;
        foreach (var b in data) c = CrcTable[(c ^ b) & 0xFF] ^ (c >> 8);
        return c ^ 0xFFFFFFFF;
    }

    public void Dispose()
    {
        _cts.Cancel();
        _udp.Dispose();
    }

    sealed class Client
    {
        public DateTime LastRequest;
        public bool AllSlots;
        public int SlotMask;
        public uint PacketNumber;
        public bool Wants(int slot) => AllSlots || (SlotMask & (1 << slot)) != 0;
    }
}
