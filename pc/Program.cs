using System.Net.Sockets;

namespace EasyJoy;

static class Program
{
    const string InstanceName = "EasyJoy.SingleInstance";
    const string ShowEventName = "EasyJoy.ShowWindow";

    [STAThread]
    static void Main(string[] args)
    {
        // Created before the mutex check so a second launch can always signal the first one.
        using var showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);
        using var mutex = new Mutex(true, InstanceName, out bool first);
        if (!first)
        {
            // Already running: ask the existing window to come forward.
            showEvent.Set();
            return;
        }

        ApplicationConfiguration.Initialize();
        using var icon = LoadIcon();
        using var gamepads = new GamepadHost();

        Server server;
        try { server = new Server(gamepads); }
        catch (SocketException ex)
        {
            MessageBox.Show(L.T(
                $"Não foi possível usar a porta {Protocol.Port}. Outro programa pode estar usando ela.",
                $"Couldn't use port {Protocol.Port}. Another program may be using it.",
                $"No se pudo usar el puerto {Protocol.Port}. Otro programa puede estar usándolo.") + $"\n\n{ex.Message}", "EasyJoy", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }

        using (server)
        {
            bool startHidden = args.Contains("--tray");
            var form = new MainForm(server, gamepads, icon, startHidden);

            var watcher = new Thread(() =>
            {
                while (showEvent.WaitOne())
                {
                    if (form.IsDisposed) return;
                    try { form.BeginInvoke(form.ShowFromTray); } catch { return; }
                }
            }) { IsBackground = true };
            watcher.Start();

            Application.Run(form);
        }
    }

    static Icon LoadIcon()
    {
        using var stream = typeof(Program).Assembly.GetManifestResourceStream("easyjoy.ico");
        return stream != null ? new Icon(stream) : (Icon)SystemIcons.Application.Clone();
    }
}
