using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using Microsoft.Win32;

namespace EasyJoy;

public sealed class MainForm : Form
{
    const string DriverUrl = "https://github.com/nefarius/ViGEmBus/releases/latest";
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    readonly Server _server;
    readonly GamepadHost _gamepads;
    readonly NotifyIcon _tray;
    readonly ListBox _phones;
    readonly System.Windows.Forms.Timer _refresh;
    bool _exiting;
    bool _trayHintShown;

    public MainForm(Server server, GamepadHost gamepads, Icon icon, bool startHidden)
    {
        _server = server;
        _gamepads = gamepads;

        Text = "EasyJoy";
        Icon = icon;
        Font = new Font("Segoe UI", 10f);
        BackColor = Color.FromArgb(24, 26, 31);
        ForeColor = Color.FromArgb(225, 228, 235);
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleDimensions = new SizeF(96f, 96f);
        AutoScaleMode = AutoScaleMode.Dpi;
        ClientSize = new Size(440, 470);
        Padding = new Padding(24, 18, 24, 18);

        var layout = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            AutoScroll = false,
        };
        Controls.Add(layout);

        layout.Controls.Add(MakeLabel("EasyJoy", 22f, FontStyle.Bold, Color.White, bottom: 2));
        layout.Controls.Add(MakeLabel("Use o celular como controle ou mouse deste PC.", 9.5f, FontStyle.Regular, Color.FromArgb(150, 156, 170), bottom: 16));

        // Driver status
        if (_gamepads.Available)
        {
            layout.Controls.Add(MakeLabel("●  Controle virtual pronto", 10.5f, FontStyle.Bold, Color.FromArgb(95, 208, 104), bottom: 14));
        }
        else
        {
            layout.Controls.Add(MakeLabel("●  Driver de controle (ViGEmBus) não encontrado", 10.5f, FontStyle.Bold, Color.FromArgb(255, 120, 110), bottom: 2));
            layout.Controls.Add(MakeLabel("O modo mouse funciona. Para o modo controle, instale o driver e abra o EasyJoy de novo.", 9f, FontStyle.Regular, Color.FromArgb(150, 156, 170), bottom: 4, maxWidth: 390));
            var link = new LinkLabel { Text = "Baixar o driver ViGEmBus", Font = new Font("Segoe UI", 10f), AutoSize = true, LinkColor = Color.FromArgb(90, 167, 255), Margin = new Padding(0, 0, 0, 14) };
            link.LinkClicked += (_, _) => Open(DriverUrl);
            layout.Controls.Add(link);
        }

        // How to connect
        layout.Controls.Add(MakeLabel("No celular, abra o EasyJoy e toque em:", 9.5f, FontStyle.Regular, Color.FromArgb(150, 156, 170), bottom: 2));
        layout.Controls.Add(MakeLabel(Environment.MachineName, 15f, FontStyle.Bold, Color.White, bottom: 2));
        layout.Controls.Add(MakeLabel("IP: " + string.Join("   ", LocalAddresses()), 9.5f, FontStyle.Regular, Color.FromArgb(150, 156, 170), bottom: 16, maxWidth: 390));

        // Connected phones
        layout.Controls.Add(MakeLabel("Celulares conectados", 10.5f, FontStyle.Bold, Color.White, bottom: 6));
        _phones = new ListBox
        {
            Width = 390,
            Height = 84,
            Font = new Font("Segoe UI", 10f),
            BorderStyle = BorderStyle.None,
            BackColor = Color.FromArgb(34, 37, 44),
            ForeColor = Color.FromArgb(225, 228, 235),
            Margin = new Padding(0, 0, 0, 14),
            SelectionMode = SelectionMode.None,
            IntegralHeight = false,
        };
        layout.Controls.Add(_phones);

        var autostart = new CheckBox
        {
            Text = "Iniciar com o Windows (na bandeja)",
            Font = new Font("Segoe UI", 10f),
            AutoSize = true,
            Checked = IsAutostart(),
            Margin = new Padding(0, 0, 0, 10),
        };
        autostart.CheckedChanged += (_, _) => SetAutostart(autostart.Checked);
        layout.Controls.Add(autostart);

        var firewall = new LinkLabel
        {
            Text = "Celular não encontra o PC? Liberar no Firewall",
            Font = new Font("Segoe UI", 9.5f),
            AutoSize = true,
            MaximumSize = new Size(390, 0),
            LinkColor = Color.FromArgb(90, 167, 255),
            Margin = new Padding(0, 0, 0, 0),
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

    static Label MakeLabel(string text, float size, FontStyle style, Color color, int bottom, int maxWidth = 0) => new()
    {
        Text = text,
        AutoSize = true,
        MaximumSize = maxWidth > 0 ? new Size(maxWidth, 0) : Size.Empty,
        Font = new Font("Segoe UI", size, style),
        ForeColor = color,
        Margin = new Padding(0, 0, 0, bottom),
    };

    void RefreshPhones()
    {
        var phones = _server.Phones;
        var lines = phones.Count == 0
            ? ["  Nenhum ainda. Abra o EasyJoy no celular."]
            : phones.Select(p => $"  {p.Name}  —  {p.Mode}   ({p.Address})").ToList();
        if (!lines.SequenceEqual(_phones.Items.Cast<string>()))
        {
            _phones.BeginUpdate();
            _phones.Items.Clear();
            foreach (var l in lines) _phones.Items.Add(l);
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
