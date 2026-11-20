using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;

namespace EasyJoy;

// Owns the connection to the ViGEmBus driver and creates virtual Xbox 360 pads.
public sealed class GamepadHost : IDisposable
{
    readonly ViGEmClient? _client;

    public bool Available => _client != null;
    public string? Error { get; }

    public GamepadHost()
    {
        try { _client = new ViGEmClient(); }
        catch (Exception ex) { Error = ex.Message; }
    }

    public VirtualPad? CreatePad()
    {
        if (_client == null) return null;
        var pad = _client.CreateXbox360Controller();
        pad.AutoSubmitReport = false;
        pad.Connect();
        return new VirtualPad(pad);
    }

    public void Dispose() => _client?.Dispose();
}

public sealed class VirtualPad : IDisposable
{
    readonly IXbox360Controller _pad;

    internal VirtualPad(IXbox360Controller pad) => _pad = pad;

    public void Apply(GamepadState s)
    {
        _pad.SetButtonsFull(s.Buttons);
        _pad.SetSliderValue(Xbox360Slider.LeftTrigger, s.LT);
        _pad.SetSliderValue(Xbox360Slider.RightTrigger, s.RT);
        _pad.SetAxisValue(Xbox360Axis.LeftThumbX, s.LX);
        _pad.SetAxisValue(Xbox360Axis.LeftThumbY, s.LY);
        _pad.SetAxisValue(Xbox360Axis.RightThumbX, s.RX);
        _pad.SetAxisValue(Xbox360Axis.RightThumbY, s.RY);
        _pad.SubmitReport();
    }

    public void Dispose()
    {
        try
        {
            Apply(default);
            _pad.Disconnect();
        }
        catch { /* driver may already be gone */ }
    }
}
