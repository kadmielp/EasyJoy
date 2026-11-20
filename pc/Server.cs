using System.Net;
using System.Net.Sockets;
using System.Text;

namespace EasyJoy;

public sealed record PhoneInfo(string Name, string Address, string Mode);

// Listens on UDP, answers discovery/pings and turns packets into virtual pad / mouse / keyboard input.
public sealed class Server : IDisposable
{
    static readonly TimeSpan ClientTimeout = TimeSpan.FromSeconds(3);
    static readonly TimeSpan HeldButtonTimeout = TimeSpan.FromSeconds(15);
    static readonly TimeSpan DuplicateWindow = TimeSpan.FromSeconds(2);

    readonly GamepadHost _gamepads;
    readonly UdpClient _udp;
    readonly CancellationTokenSource _cts = new();
    readonly Dictionary<IPAddress, Phone> _phones = [];
    readonly object _lock = new();
    readonly System.Threading.Timer _sweeper;
    readonly string _pcName = Environment.MachineName;

    public Server(GamepadHost gamepads, int port = Protocol.Port)
    {
        _gamepads = gamepads;
        _udp = new UdpClient(AddressFamily.InterNetwork);
        // Without this, an ICMP "port unreachable" from a phone that went away makes the next Receive throw.
        const int SIO_UDP_CONNRESET = -1744830452;
        _udp.Client.IOControl(SIO_UDP_CONNRESET, [0, 0, 0, 0], null);
        _udp.Client.Bind(new IPEndPoint(IPAddress.Any, port));
        _sweeper = new System.Threading.Timer(_ => Sweep(), null, 1000, 1000);
        _ = Task.Run(ReceiveLoop);
    }

    public IReadOnlyList<PhoneInfo> Phones
    {
        get { lock (_lock) return _phones.Values.Select(p => new PhoneInfo(p.Name, p.Address.ToString(), p.Mode)).ToList(); }
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
            catch (Exception) { /* one bad packet must never kill the loop */ }
        }
    }

    void Handle(byte[] data, IPEndPoint from)
    {
        if (!Protocol.TryReadHeader(data, out var type)) return;
        var body = data.AsSpan(Protocol.HeaderSize);

        if (type == PacketType.Discover)
        {
            Reply(Protocol.Announce(_pcName), from);
            return;
        }

        var phone = GetPhone(from.Address);
        phone.LastSeen = DateTime.UtcNow;

        switch (type)
        {
            case PacketType.Ping:
                if (body.Length > 0) phone.Name = Encoding.UTF8.GetString(body);
                Reply(Protocol.Pong(_gamepads.Available), from);
                break;

            case PacketType.Gamepad:
                if (!Protocol.TryParseGamepad(body, out ushort seq, out var state)) return;
                phone.Mode = "Controle";
                if (phone.HasSeq && (short)(seq - phone.LastSeq) <= 0 && DateTime.UtcNow - phone.LastSeqTime < TimeSpan.FromSeconds(1)) return;
                phone.HasSeq = true; phone.LastSeq = seq; phone.LastSeqTime = DateTime.UtcNow;
                phone.Pad ??= _gamepads.CreatePad();
                phone.Pad?.Apply(state);
                break;

            case PacketType.MouseMove:
                if (body.Length < 4) return;
                phone.Mode = "Mouse";
                phone.LastMouseActivity = DateTime.UtcNow;
                InputSender.MoveBy(Protocol.I16(body, 0), Protocol.I16(body, 2));
                break;

            case PacketType.Scroll:
                if (body.Length < 4) return;
                phone.Mode = "Mouse";
                phone.LastMouseActivity = DateTime.UtcNow;
                InputSender.Scroll(Protocol.I16(body, 0), Protocol.I16(body, 2));
                break;

            case PacketType.MouseButton:
            {
                if (body.Length < 4) return;
                ushort id = Protocol.U16(body, 0);
                int button = body[2];
                if (!phone.FirstTime(id) || phone.IsStaleButtonEvent(button, id)) return;
                phone.Mode = "Mouse";
                phone.LastMouseActivity = DateTime.UtcNow;
                switch (body[3])
                {
                    case 0: ReleaseButton(phone, button); break;
                    case 1: InputSender.Button(button, true); phone.HeldButtons.Add(button); break;
                    case 2: InputSender.Click(button); break;
                }
                break;
            }

            case PacketType.Text:
                if (body.Length < 2 || !phone.FirstTime(Protocol.U16(body, 0))) return;
                InputSender.Text(Encoding.UTF8.GetString(body[2..]));
                break;

            case PacketType.Key:
                if (body.Length < 3 || !phone.FirstTime(Protocol.U16(body, 0))) return;
                InputSender.Key(body[2]);
                break;

            case PacketType.Bye:
                ReleaseAll(phone);
                phone.Mode = "Menu";
                break;
        }
    }

    // Windows has one mouse: only send button-up when no other phone still holds that button.
    void ReleaseButton(Phone phone, int button)
    {
        phone.HeldButtons.Remove(button);
        if (!_phones.Values.Any(p => p.HeldButtons.Contains(button)))
            InputSender.Button(button, false);
    }

    void ReleaseAll(Phone phone)
    {
        phone.Pad?.Dispose();
        phone.Pad = null;
        phone.HasSeq = false;
        foreach (var b in phone.HeldButtons.ToList()) ReleaseButton(phone, b);
    }

    Phone GetPhone(IPAddress address)
    {
        if (!_phones.TryGetValue(address, out var phone))
        {
            phone = new Phone(address);
            _phones[address] = phone;
        }
        return phone;
    }

    void Sweep()
    {
        lock (_lock)
        {
            var now = DateTime.UtcNow;
            foreach (var p in _phones.Values.Where(p => now - p.LastSeen > ClientTimeout).ToList())
            {
                ReleaseAll(p);
                _phones.Remove(p.Address);
            }
            // Safety net if every copy of a button-up got lost: nobody holds a drag still for this long.
            foreach (var p in _phones.Values.Where(p => p.HeldButtons.Count > 0 && now - p.LastMouseActivity > HeldButtonTimeout))
                foreach (var b in p.HeldButtons.ToList()) ReleaseButton(p, b);
        }
    }

    void Reply(byte[] packet, IPEndPoint to)
    {
        try { _udp.Send(packet, packet.Length, to); } catch (SocketException) { }
    }

    public void Dispose()
    {
        _cts.Cancel();
        _sweeper.Dispose();
        _udp.Dispose();
        lock (_lock)
        {
            foreach (var p in _phones.Values.ToList()) ReleaseAll(p);
            _phones.Clear();
        }
    }

    sealed class Phone(IPAddress address)
    {
        public IPAddress Address { get; } = address;
        public string Name { get; set; } = address.ToString();
        public string Mode { get; set; } = "Menu";
        public DateTime LastSeen { get; set; } = DateTime.UtcNow;
        public VirtualPad? Pad { get; set; }
        public bool HasSeq { get; set; }
        public ushort LastSeq { get; set; }
        public DateTime LastSeqTime { get; set; }
        public HashSet<int> HeldButtons { get; } = [];
        public DateTime LastMouseActivity { get; set; } = DateTime.UtcNow;

        // Copies of one event arrive within milliseconds; remembering ids only briefly means
        // an app restart (counter back at 0) is never mistaken for duplicates.
        readonly Dictionary<ushort, DateTime> _recentIds = [];
        readonly Dictionary<int, (ushort Id, DateTime At)> _lastButtonEvent = [];

        // One-shot events arrive 3 times; only act on the first copy.
        public bool FirstTime(ushort eventId)
        {
            var now = DateTime.UtcNow;
            foreach (var old in _recentIds.Where(kv => now - kv.Value > DuplicateWindow).Select(kv => kv.Key).ToList())
                _recentIds.Remove(old);
            return _recentIds.TryAdd(eventId, now);
        }

        // Drops a button event that arrives after a newer one for the same button (e.g. a late "down" after "up").
        public bool IsStaleButtonEvent(int button, ushort eventId)
        {
            var now = DateTime.UtcNow;
            if (_lastButtonEvent.TryGetValue(button, out var last) && now - last.At < DuplicateWindow && (short)(eventId - last.Id) < 0)
                return true;
            _lastButtonEvent[button] = (eventId, now);
            return false;
        }
    }
}
