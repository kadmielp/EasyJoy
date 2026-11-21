using System.Buffers.Binary;
using System.Text;

namespace EasyJoy;

// Wire format: see docs/design.md. Header 'E' 'J' 0x01 <type>, little-endian body.
public enum PacketType : byte
{
    Discover = 0x01,
    Announce = 0x02,
    Ping = 0x05,
    Pong = 0x06,
    Gamepad = 0x10,
    MouseMove = 0x20,
    MouseButton = 0x21,
    Scroll = 0x22,
    Text = 0x23,
    Key = 0x24,
    Zoom = 0x25,
    Bye = 0x30,
}

public readonly record struct GamepadState(ushort Buttons, byte LT, byte RT, short LX, short LY, short RX, short RY);

public static class Protocol
{
    public const int Port = 47820;
    public const int HeaderSize = 4;

    public static bool TryReadHeader(ReadOnlySpan<byte> data, out PacketType type)
    {
        type = 0;
        if (data.Length < HeaderSize || data[0] != (byte)'E' || data[1] != (byte)'J' || data[2] != 1) return false;
        type = (PacketType)data[3];
        return true;
    }

    public static byte[] Build(PacketType type, ReadOnlySpan<byte> body = default)
    {
        var buf = new byte[HeaderSize + body.Length];
        buf[0] = (byte)'E'; buf[1] = (byte)'J'; buf[2] = 1; buf[3] = (byte)type;
        body.CopyTo(buf.AsSpan(HeaderSize));
        return buf;
    }

    public static byte[] Announce(string pcName) => Build(PacketType.Announce, Encoding.UTF8.GetBytes(pcName));
    public static byte[] Pong(bool gamepadAvailable) => Build(PacketType.Pong, [gamepadAvailable ? (byte)0 : (byte)1]);

    public static ushort U16(ReadOnlySpan<byte> d, int offset) => BinaryPrimitives.ReadUInt16LittleEndian(d[offset..]);
    public static short I16(ReadOnlySpan<byte> d, int offset) => BinaryPrimitives.ReadInt16LittleEndian(d[offset..]);

    // body = data after the header
    public static bool TryParseGamepad(ReadOnlySpan<byte> body, out ushort seq, out GamepadState state)
    {
        seq = 0; state = default;
        if (body.Length < 14) return false;
        seq = U16(body, 0);
        state = new GamepadState(U16(body, 2), body[4], body[5], I16(body, 6), I16(body, 8), I16(body, 10), I16(body, 12));
        return true;
    }
}
