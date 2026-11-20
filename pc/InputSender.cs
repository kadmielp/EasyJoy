using System.Runtime.InteropServices;

namespace EasyJoy;

// Mouse and keyboard injection through SendInput.
public static class InputSender
{
    public const int LeftButton = 0, RightButton = 1, MiddleButton = 2;

    public static void MoveBy(int dx, int dy)
    {
        if (dx == 0 && dy == 0) return;
        Send(Mouse(MOUSEEVENTF_MOVE, dx, dy, 0));
    }

    public static void Button(int button, bool down)
    {
        uint flag = button switch
        {
            RightButton => down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP,
            MiddleButton => down ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP,
            _ => down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP,
        };
        Send(Mouse(flag, 0, 0, 0));
    }

    public static void Click(int button)
    {
        Button(button, true);
        Button(button, false);
    }

    // Values in WHEEL_DELTA units (120 = one notch). Positive vertical = up, positive horizontal = right.
    public static void Scroll(int vertical, int horizontal)
    {
        if (vertical != 0) Send(Mouse(MOUSEEVENTF_WHEEL, 0, 0, unchecked((uint)vertical)));
        if (horizontal != 0) Send(Mouse(MOUSEEVENTF_HWHEEL, 0, 0, unchecked((uint)horizontal)));
    }

    public static void Text(string text)
    {
        var inputs = new List<INPUT>(text.Length * 2);
        foreach (char c in text)
        {
            if (c == '\r') continue;
            if (c == '\n')
            {
                inputs.Add(Keyboard(0x0D, 0, 0));
                inputs.Add(Keyboard(0x0D, 0, KEYEVENTF_KEYUP));
                continue;
            }
            inputs.Add(Keyboard(0, c, KEYEVENTF_UNICODE));
            inputs.Add(Keyboard(0, c, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP));
        }
        if (inputs.Count > 0) Send([.. inputs]);
    }

    public static void Key(byte vk)
    {
        // Arrows and similar navigation keys live on the extended key pad.
        uint ext = vk is >= 0x21 and <= 0x2E ? KEYEVENTF_EXTENDEDKEY : 0;
        Send(Keyboard(vk, 0, ext), Keyboard(vk, 0, ext | KEYEVENTF_KEYUP));
    }

    static void Send(params INPUT[] inputs) =>
        SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());

    static INPUT Mouse(uint flags, int dx, int dy, uint data) => new()
    {
        type = INPUT_MOUSE,
        u = new InputUnion { mi = new MOUSEINPUT { dx = dx, dy = dy, mouseData = data, dwFlags = flags } },
    };

    static INPUT Keyboard(ushort vk, ushort scan, uint flags) => new()
    {
        type = INPUT_KEYBOARD,
        u = new InputUnion { ki = new KEYBDINPUT { wVk = vk, wScan = scan, dwFlags = flags } },
    };

    const uint INPUT_MOUSE = 0, INPUT_KEYBOARD = 1;
    const uint MOUSEEVENTF_MOVE = 0x0001, MOUSEEVENTF_LEFTDOWN = 0x0002, MOUSEEVENTF_LEFTUP = 0x0004,
        MOUSEEVENTF_RIGHTDOWN = 0x0008, MOUSEEVENTF_RIGHTUP = 0x0010, MOUSEEVENTF_MIDDLEDOWN = 0x0020,
        MOUSEEVENTF_MIDDLEUP = 0x0040, MOUSEEVENTF_WHEEL = 0x0800, MOUSEEVENTF_HWHEEL = 0x1000;
    const uint KEYEVENTF_EXTENDEDKEY = 0x0001, KEYEVENTF_KEYUP = 0x0002, KEYEVENTF_UNICODE = 0x0004;

    [StructLayout(LayoutKind.Sequential)]
    struct INPUT { public uint type; public InputUnion u; }

    [StructLayout(LayoutKind.Explicit)]
    struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public IntPtr dwExtraInfo; }

    [StructLayout(LayoutKind.Sequential)]
    struct KEYBDINPUT { public ushort wVk, wScan; public uint dwFlags, time; public IntPtr dwExtraInfo; }

    [DllImport("user32.dll", SetLastError = true)]
    static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);
}
