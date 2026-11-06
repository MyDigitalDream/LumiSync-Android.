using System.Runtime.InteropServices;
namespace LumiSync;

// ABI reference: TheDouglife/chroma-rgb-connect, c2766b571338d95697db3a78ee76afae076d90e0.
// No partner application IDs or Razer binaries are distributed by this project.
internal sealed class ChromaReceiver
{
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate long Notification(int kind, IntPtr data);
    [DllImport("RzChromaConnectAPI64.dll", CallingConvention=CallingConvention.Cdecl), DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    private static extern long Init(in Guid appId);
    [DllImport("RzChromaConnectAPI64.dll", CallingConvention=CallingConvention.Cdecl), DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    private static extern long RegisterEventNotification(Notification callback);
    [DllImport("RzChromaConnectAPI64.dll", CallingConvention=CallingConvention.Cdecl), DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    private static extern long UnRegisterEventNotification();
    [DllImport("RzChromaConnectAPI64.dll", CallingConvention=CallingConvention.Cdecl), DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    private static extern long UnInit();
    private readonly Notification callback;
    private volatile bool accepting;
    public readonly LatestFrame Frames = new();
    public bool Started {get; private set;}
    public string Status {get; private set;} = "Not connected. Requires a LumiSync application ID issued by Razer.";
    public ChromaReceiver() => callback = Receive;
    public void Start(string value)
    {
        if(Started) return;
        if(!Guid.TryParse(value.Trim(), out var id) || id == Guid.Empty) throw new ArgumentException("A Razer-issued application ID is required. The REST SDK version is not an application ID.");
        long result = Init(in id);
        if(result != 0) throw new InvalidOperationException($"Chroma registration failed ({result}). Verify the application's Razer registration and Chroma Connect installation.");
        accepting = true;
        result = RegisterEventNotification(callback);
        if(result != 0) { accepting = false; UnInit(); throw new InvalidOperationException($"Chroma event subscription failed ({result})."); }
        Started = true;
        Status = "Registered; waiting for real Chroma frames. Enable the registered integration in Chroma Connect.";
    }
    private long Receive(int kind, IntPtr data)
    {
        try
        {
            if(!accepting) return 0;
            if(kind == 1 && data != IntPtr.Zero)
            {
                var bytes = new byte[20]; Marshal.Copy(data, bytes, 0, 20);
                Frames.Set(LumiSync.Frames.Decode(bytes));
                Status = "Receiving Chroma lighting frames.";
            }
            else if(kind == 2 && data.ToInt64() != 1)
            { Frames.Clear(); Status = "Chroma is not live. Check Chroma Connect and the active effect."; }
        }
        catch { Frames.Clear(); Status = "Unable to read a Chroma frame; output paused."; }
        return 0;
    }
    public void Stop()
    {
        accepting = false; Frames.Clear();
        if(Started) { UnRegisterEventNotification(); UnInit(); Started = false; }
        Status = "Receiver stopped.";
        GC.KeepAlive(callback);
    }
}
