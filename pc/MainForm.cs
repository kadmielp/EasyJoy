using System.Diagnostics;
using System.Drawing.Drawing2D;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace EasyJoy;

public sealed class MainForm : Form
{
    const string DriverUrl = "https://github.com/nefarius/ViGEmBus/releases/latest";
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    static readonly Color Bg = Color.FromArgb(22, 24, 29);
    static readonly Color Fg = Color.FromArgb(225, 228, 235);
    static readonly Color Muted = Color.FromArgb(145, 151, 165);
    static readonly Color Line = Color.FromArgb(42, 46, 55);
    static readonly Color Accent = Color.FromArgb(96, 170, 255);
    static readonly Color Good = Color.FromArgb(95, 208, 104);
    static readonly Color Bad = Color.FromArgb(255, 120, 110);
    const int ContentWidth = 392; // logical px
    const int RowHeight = 38;     // phone list row, logical px
    const string EmptyText = "Nenhum ainda. Abra o EasyJoy no celular.";

    readonly Server _server;
    readonly GamepadHost _gamepads;
    readonly NotifyIcon _tray;
    readonly ListBox _phones;
    readonly System.Windows.Forms.Timer _refresh;
    readonly Font _rowName = new("Segoe UI Semibold", 10f);
    readonly Font _rowSmall = new("Segoe UI", 8.75f);
    bool _exiting;
    bool _trayHintShown;

    public MainForm(Server server, GamepadHost gamepads, Icon icon, bool startHidden)
    {
        _server = server;
        _gamepads = gamepads;

        Text = "EasyJoy";
        Icon = icon;
        Font = new Font("Segoe UI", 10f);
        BackColor = Bg;
        ForeColor = Fg;
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleDimensions = new SizeF(96f, 96f);
        AutoScaleMode = AutoScaleMode.Dpi;
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        Padding = new Padding(24, 22, 24, 20);

        var layout = Stack();
        layout.Location = new Point(Padding.Left, Padding.Top); // Padding only moves docked controls
        Controls.Add(layout);

        // Header: icon + name
        var header = new FlowLayoutPanel { AutoSize = true, WrapContents = false, Margin = new Padding(0, 0, 0, 18) };
        header.Controls.Add(new PictureBox
        {
            Image = new Icon(icon, new Size(64, 64)).ToBitmap(),
            SizeMode = PictureBoxSizeMode.Zoom,
            Size = new Size(46, 46),
            Margin = new Padding(0, 4, 14, 0),
        });
        var titles = Stack();
        titles.Controls.Add(MakeLabel("EasyJoy", 20f, FontStyle.Bold, Color.White, bottom: 0));
        titles.Controls.Add(MakeLabel("Use o celular como controle ou mouse deste PC.", 9.5f, FontStyle.Regular, Muted, bottom: 0));
        header.Controls.Add(titles);
        layout.Controls.Add(header);

        // Driver status
        if (_gamepads.Available)
        {
            layout.Controls.Add(new Pill("Controle virtual pronto", Good) { Margin = new Padding(0, 0, 0, 20) });
        }
        else
        {
            layout.Controls.Add(new Pill("Driver de controle (ViGEmBus) não encontrado", Bad) { Margin = new Padding(0, 0, 0, 8) });
            layout.Controls.Add(MakeLabel("O modo mouse funciona. Para o modo controle, instale o driver e abra o EasyJoy de novo.", 9f, FontStyle.Regular, Muted, bottom: 4, maxWidth: ContentWidth));
            var link = new LinkLabel { Text = "Baixar o driver ViGEmBus", Font = new Font("Segoe UI", 10f), AutoSize = true, LinkColor = Accent, ActiveLinkColor = Color.White, Margin = new Padding(0, 0, 0, 20) };
            link.LinkClicked += (_, _) => Open(DriverUrl);
            layout.Controls.Add(link);
        }

        // How to connect
        layout.Controls.Add(Section("CONECTAR"));
        var connect = new Card(ContentWidth) { Margin = new Padding(0, 0, 0, 20) };
        connect.Controls.Add(MakeLabel("No celular, abra o EasyJoy e toque em:", 9.5f, FontStyle.Regular, Muted, bottom: 2));
        connect.Controls.Add(MakeLabel(Environment.MachineName, 16f, FontStyle.Bold, Color.White, bottom: 2, maxWidth: ContentWidth - 36));
        connect.Controls.Add(MakeLabel("IP  " + string.Join("   ·   ", LocalAddresses()), 9.5f, FontStyle.Regular, Muted, bottom: 0, maxWidth: ContentWidth - 36));
        layout.Controls.Add(connect);

        // Connected phones
        layout.Controls.Add(Section("CELULARES CONECTADOS"));
        var list = new Card(ContentWidth) { Padding = new Padding(4), Margin = new Padding(0, 0, 0, 18) };
        _phones = new ListBox
        {
            Width = ContentWidth - 8,
            Height = RowHeight * 3,
            BorderStyle = BorderStyle.None,
            BackColor = Card.Fill,
            ForeColor = Fg,
            Margin = Padding.Empty,
            SelectionMode = SelectionMode.None,
            IntegralHeight = false,
            DrawMode = DrawMode.OwnerDrawFixed,
        };
        _phones.DrawItem += DrawPhone;
        list.Controls.Add(_phones);
        layout.Controls.Add(list);

        if (!_server.MotionAvailable)
            layout.Controls.Add(MakeLabel($"Giroscópio para emuladores desligado: a porta {DsuServer.Port} já está em uso por outro programa (DS4Windows, BetterJoy...).",
                9f, FontStyle.Regular, Bad, bottom: 18, maxWidth: ContentWidth));

        layout.Controls.Add(new Panel { Width = ContentWidth, Height = 1, BackColor = Line, Margin = new Padding(0, 0, 0, 14) });

        var autostart = new CheckBox
        {
            Text = "Iniciar com o Windows (na bandeja)",
            Font = new Font("Segoe UI", 10f),
            AutoSize = true,
            Checked = IsAutostart(),
            FlatStyle = FlatStyle.Flat,
            Margin = new Padding(0, 0, 0, 8),
        };
        autostart.FlatAppearance.BorderColor = Color.FromArgb(90, 96, 110);
        autostart.FlatAppearance.CheckedBackColor = Card.Fill;
        autostart.CheckedChanged += (_, _) => SetAutostart(autostart.Checked);
        layout.Controls.Add(autostart);

        const string ask = "Celular não encontra o PC?  ";
        const string action = "Liberar no Firewall";
        var firewall = new LinkLabel
        {
            Text = ask + action,
            LinkArea = new LinkArea(ask.Length, action.Length),
            Font = new Font("Segoe UI", 9.5f),
            AutoSize = true,
            ForeColor = Muted,
            LinkColor = Accent,
            ActiveLinkColor = Color.White,
            LinkBehavior = LinkBehavior.HoverUnderline,
            Margin = Padding.Empty,
        };
        firewall.LinkClicked += (_, _) => AllowFirewall();
        layout.Controls.Add(firewall);

        // Tray
        var menu = new ContextMenuStrip();
        menu.Items.Add("Abrir EasyJoy", null, (_, _) => ShowFromTray());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Sair", null, (_, _) => ExitApp());
        _tray = new NotifyIcon { Icon = icon, Text = "EasyJoy", Visible = true, ContextMenuStrip = menu };
        _tray.DoubleClick += (_, _) => ShowFromTray();
        _tray.MouseClick += (_, e) => { if (e.Button == MouseButtons.Left) ShowFromTray(); };

        _refresh = new System.Windows.Forms.Timer { Interval = 500 };
        _refresh.Tick += (_, _) => RefreshPhones();
        _refresh.Start();

        if (startHidden)
        {
            ShowInTaskbar = false;
            WindowState = FormWindowState.Minimized;
            Load += (_, _) => { Hide(); ShowInTaskbar = true; WindowState = FormWindowState.Normal; };
        }
    }

    static FlowLayoutPanel Stack() => new()
    {
        AutoSize = true,
        AutoSizeMode = AutoSizeMode.GrowAndShrink,
        FlowDirection = FlowDirection.TopDown,
        WrapContents = false,
        Margin = Padding.Empty,
    };

    static Label Section(string text) => MakeLabel(text, 8.25f, FontStyle.Bold, Muted, bottom: 6);

    static Label MakeLabel(string text, float size, FontStyle style, Color color, int bottom, int maxWidth = 0) => new()
    {
        Text = text,
        AutoSize = true,
        MaximumSize = maxWidth > 0 ? new Size(maxWidth, 0) : Size.Empty,
        Font = new Font("Segoe UI", size, style),
        ForeColor = color,
        Margin = new Padding(0, 0, 0, bottom),
    };

    protected override void OnLoad(EventArgs e)
    {
        // Owner-drawn rows are not DPI-scaled by WinForms, so size them in device pixels here.
        _phones.ItemHeight = LogicalToDeviceUnits(RowHeight);
        _phones.Height = _phones.ItemHeight * 3;
        RefreshPhones();
        base.OnLoad(e);
    }

    [DllImport("dwmapi.dll")]
    static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        int dark = 1, caption = Bg.R | Bg.G << 8 | Bg.B << 16;
        DwmSetWindowAttribute(Handle, 20, ref dark, 4);    // dark title bar (Windows 10 2004+)
        DwmSetWindowAttribute(Handle, 35, ref caption, 4); // title bar in the window colour (Windows 11)
    }

    void DrawPhone(object? sender, DrawItemEventArgs e)
    {
        if (e.Index < 0) return;
        var g = e.Graphics;
        var b = e.Bounds;
        int u = b.Height, pad = u / 3;
        using (var bg = new SolidBrush(_phones.BackColor)) g.FillRectangle(bg, b);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        const TextFormatFlags mid = TextFormatFlags.VerticalCenter | TextFormatFlags.NoPrefix | TextFormatFlags.SingleLine;

        if (_phones.Items[e.Index] is not PhoneInfo p)
        {
            TextRenderer.DrawText(g, EmptyText, _rowSmall, new Rectangle(b.X + pad, b.Y, b.Width - pad, u), Muted, mid);
            return;
        }
        if (e.Index > 0)
            using (var line = new Pen(Line)) g.DrawLine(line, b.X + pad, b.Y, b.Right - pad, b.Y);

        int d = Math.Max(6, u / 5);
        using (var dot = new SolidBrush(Good)) g.FillEllipse(dot, b.X + pad, b.Y + (u - d) / 2, d, d);
        int x = b.X + pad + d + pad / 2 + 2;

        int ipW = TextRenderer.MeasureText(p.Address, _rowSmall).Width;
        TextRenderer.DrawText(g, p.Address, _rowSmall, new Rectangle(b.Right - pad - ipW, b.Y, ipW, u), Muted, mid);

        var tagColor = p.Mode switch { "Controle" => Accent, "Mouse" => Color.FromArgb(190, 150, 255), _ => Muted };
        var tagText = TextRenderer.MeasureText(p.Mode, _rowSmall);
        int tagW = tagText.Width + u / 4, tagH = tagText.Height + 2;
        const string gyro = "Giroscópio";
        int gyroW = p.Motion ? TextRenderer.MeasureText(gyro, _rowSmall).Width + u / 4 : 0;
        int nameMax = b.Right - pad - ipW - tagW - (p.Motion ? gyroW + pad / 3 : 0) - x - pad;
        int nameW = Math.Min(TextRenderer.MeasureText(p.Name, _rowName).Width, Math.Max(0, nameMax));
        TextRenderer.DrawText(g, p.Name, _rowName, new Rectangle(x, b.Y, nameW, u), Color.White, mid | TextFormatFlags.EndEllipsis);

        var tag = new Rectangle(x + nameW + pad / 2, b.Y + (u - tagH) / 2, tagW, tagH);
        using (var path = Card.Rounded(tag, tagH / 2))
        using (var fill = new SolidBrush(Color.FromArgb(40, tagColor)))
            g.FillPath(fill, path);
        TextRenderer.DrawText(g, p.Mode, _rowSmall, tag, tagColor, mid | TextFormatFlags.HorizontalCenter);

        if (p.Motion)
        {
            var gyroColor = Good;
            var gtag = new Rectangle(tag.Right + pad / 3, tag.Y, gyroW, tagH);
            using (var path = Card.Rounded(gtag, tagH / 2))
            using (var fill = new SolidBrush(Color.FromArgb(40, gyroColor)))
                g.FillPath(fill, path);
            TextRenderer.DrawText(g, gyro, _rowSmall, gtag, gyroColor, mid | TextFormatFlags.HorizontalCenter);
        }
    }

    void RefreshPhones()
    {
        var phones = _server.Phones;
        object[] items = phones.Count == 0 ? [EmptyText] : phones.Cast<object>().ToArray();
        if (!items.SequenceEqual(_phones.Items.Cast<object>()))
        {
            _phones.BeginUpdate();
            _phones.Items.Clear();
            _phones.Items.AddRange(items);
            _phones.EndUpdate();
        }
        _tray.Text = phones.Count == 0 ? "EasyJoy — aguardando celular" : $"EasyJoy — {phones.Count} conectado(s)";
    }

    public void ShowFromTray()
    {
        Show();
        WindowState = FormWindowState.Normal;
        Activate();
    }

    void ExitApp()
    {
        _exiting = true;
        Close();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (!_exiting && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
            if (!_trayHintShown)
            {
                _trayHintShown = true;
                _tray.ShowBalloonTip(3000, "EasyJoy continua rodando", "Ele fica aqui na bandeja. Clique com o botão direito para sair.", ToolTipIcon.Info);
            }
            return;
        }
        _refresh.Stop();
        _tray.Visible = false;
        _tray.Dispose();
        base.OnFormClosing(e);
    }

    static IEnumerable<string> LocalAddresses()
    {
        var result = new List<string>();
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            var props = ni.GetIPProperties();
            if (props.GatewayAddresses.Count == 0) continue; // skip virtual adapters without a route out
            result.AddRange(props.UnicastAddresses
                .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork)
                .Select(a => a.Address.ToString()));
        }
        return result.Count > 0 ? result : ["não encontrado"];
    }

    static bool IsAutostart()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKey);
        return key?.GetValue("EasyJoy") != null;
    }

    static void SetAutostart(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey);
        if (enabled) key.SetValue("EasyJoy", $"\"{Environment.ProcessPath}\" --tray");
        else key.DeleteValue("EasyJoy", false);
    }

    static void AllowFirewall()
    {
        var exe = Environment.ProcessPath;
        // Dismissing Windows' first-run firewall prompt leaves "Block" rules for this exe, and Block beats Allow,
        // so remove every inbound rule for this program before adding ours.
        var args = $"/c netsh advfirewall firewall delete rule name=all dir=in program=\"{exe}\" & " +
                   $"netsh advfirewall firewall add rule name=\"EasyJoy\" dir=in action=allow program=\"{exe}\" protocol=UDP localport={Protocol.Port} profile=private,domain";
        try
        {
            Process.Start(new ProcessStartInfo("cmd.exe", args) { UseShellExecute = true, Verb = "runas", WindowStyle = ProcessWindowStyle.Hidden })?.WaitForExit();
            MessageBox.Show("Pronto. O EasyJoy foi liberado no Firewall para redes privadas.\n\nSe ainda não aparecer, confira se o Wi-Fi deste PC está como rede \"Privada\" nas configurações do Windows.", "EasyJoy");
        }
        catch (System.ComponentModel.Win32Exception)
        {
            // User declined the admin prompt.
        }
    }

    static void Open(string url) => Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
}

/// <summary>Rounded panel that stacks its children; children inherit its fill colour.</summary>
sealed class Card : FlowLayoutPanel
{
    public static readonly Color Fill = Color.FromArgb(31, 34, 41);
    static readonly Color Border = Color.FromArgb(44, 48, 58);

    public Card(int width)
    {
        FlowDirection = FlowDirection.TopDown;
        WrapContents = false;
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        MinimumSize = new Size(width, 0);
        MaximumSize = new Size(width, 0);
        Padding = new Padding(18, 14, 18, 16);
        BackColor = Fill;
        DoubleBuffered = true;
        ResizeRedraw = true;
    }

    protected override void OnPaintBackground(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.Clear(Parent?.BackColor ?? BackColor);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        using var path = Rounded(new Rectangle(0, 0, Width - 1, Height - 1), LogicalToDeviceUnits(10));
        using var fill = new SolidBrush(Fill);
        using var pen = new Pen(Border);
        g.FillPath(fill, path);
        g.DrawPath(pen, path);
    }

    public static GraphicsPath Rounded(Rectangle r, int radius)
    {
        int d = Math.Max(1, Math.Min(radius * 2, Math.Min(r.Width, r.Height)));
        var p = new GraphicsPath();
        p.AddArc(r.X, r.Y, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }
}

/// <summary>Status badge: coloured dot and text on a tinted rounded background.</summary>
sealed class Pill : Control
{
    readonly Color _color;

    public Pill(string text, Color color)
    {
        _color = color;
        Text = text;
        Font = new Font("Segoe UI Semibold", 9.75f);
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer |
                 ControlStyles.ResizeRedraw | ControlStyles.SupportsTransparentBackColor, true);
        BackColor = Color.Transparent;
        AutoSize = true;
    }

    int Unit => Font.Height;

    public override Size GetPreferredSize(Size proposedSize)
    {
        var t = TextRenderer.MeasureText(Text, Font);
        int h = Unit * 17 / 10;
        return new Size(h / 2 + Unit / 2 + Unit / 2 + t.Width + h / 2, h);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        using (var path = Card.Rounded(new Rectangle(0, 0, Width - 1, Height - 1), Height / 2))
        using (var fill = new SolidBrush(Color.FromArgb(34, _color)))
        using (var pen = new Pen(Color.FromArgb(80, _color)))
        {
            g.FillPath(fill, path);
            g.DrawPath(pen, path);
        }
        int d = Unit / 2, x = Height / 2;
        using (var dot = new SolidBrush(_color)) g.FillEllipse(dot, x, (Height - d) / 2f, d, d);
        TextRenderer.DrawText(g, Text, Font, new Rectangle(x + d + Unit / 2, 0, Width, Height), _color,
            TextFormatFlags.VerticalCenter | TextFormatFlags.NoPrefix | TextFormatFlags.SingleLine);
    }
}
