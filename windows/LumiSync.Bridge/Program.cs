using System.Net.Http;
using System.Text.Json;
namespace LumiSync;
internal static class Program
{
    [STAThread] static void Main(string[] args)
    {
        ApplicationConfiguration.Initialize();
        if(args.Contains("--smoke-test"))
        {
            using var form = new BridgeForm();
            form.Show(); Application.DoEvents();
            using var bitmap = new Bitmap(form.Width, form.Height);
            form.DrawToBitmap(bitmap, form.ClientRectangle);
            bitmap.Save("bridge-preview.png");
            if(form.Text != "LumiSync • PC Bridge Preview 0.1") throw new Exception("Unexpected window");
            form.Close(); return;
        }
        Application.Run(new BridgeForm());
    }
}
internal sealed class BridgeForm : Form
{
    private readonly FlowLayoutPanel layout = new() { Dock = DockStyle.Fill, FlowDirection=FlowDirection.TopDown, WrapContents=false, AutoScroll=true, Padding=new Padding(24) };
    private readonly TextBox appId = new() { Width=640, PlaceholderText="Razer-issued LumiSync application ID" };
    private readonly TextBox url = new() { Width=640, PlaceholderText="https://your-home-assistant-address" };
    private readonly TextBox token = new() { Width=640, UseSystemPasswordChar=true, PlaceholderText="Home Assistant long-lived access token (memory only)" };
    private readonly ComboBox lights = new() { Width=640, DropDownStyle=ComboBoxStyle.DropDownList };
    private readonly ComboBox slot = new() { Width=200, DropDownStyle=ComboBoxStyle.DropDownList };
    private readonly NumericUpDown brightness = new() { Minimum=1, Maximum=100, Value=50, Width=100 };
    private readonly Label source = new() { Width=660, Height=48 };
    private readonly Label output = new() { Width=660, Height=48, Text="Output stopped. Choose your matching Cync light after connecting." };
    private readonly Panel[] swatches = Enumerable.Range(0,5).Select(_=>new Panel { Width=116, Height=36, BackColor=Color.FromArgb(35,44,60), Margin=new Padding(3) }).ToArray();
    private readonly ChromaReceiver receiver = new();
    private readonly System.Windows.Forms.Timer timer = new() { Interval=250 };
    private HomeAssistant? ha;
    private CancellationTokenSource cancellation = new();
    private bool busy, syncing, closing;
    private long lastSequence=-1;
    private Rgb? lastColor;
    private DateTime nextSend;
    private Button load = null!, sync = null!, test = null!;
    public BridgeForm()
    {
        Text="LumiSync • PC Bridge Preview 0.1"; ClientSize=new Size(740,900); MinimumSize=new Size(740,600);
        BackColor=Color.FromArgb(12,18,29); ForeColor=Color.FromArgb(227,235,246); Font=new Font("Segoe UI",10);
        Controls.Add(layout);
        Heading("LumiSync",24);
        Note("PC BRIDGE • EXPERIMENTAL PREVIEW",Color.Turquoise);
        Note("Live sync needs a Razer-issued application ID and Home Assistant. Google Home linking alone does not connect this bridge. Hardware emulation is not included.",Color.LightGoldenrodYellow,58);
        Heading("1  Chroma source",14);
        Button("Check local Chroma SDK", async ()=> {
            using var client = new HttpClient(new HttpClientHandler { AllowAutoRedirect=false }) { Timeout=TimeSpan.FromSeconds(3) };
            using var doc=JsonDocument.Parse(await client.GetStringAsync("http://localhost:54235/razer/chromasdk"));
            var version=doc.RootElement.TryGetProperty("version",out var v) ? v.GetString() : "unknown";
            source.Text=$"REST SDK {version} responds. Receiving effects still requires Chroma Connect registration.";
        });
        layout.Controls.Add(appId);
        Button("Connect Chroma receiver", ()=> {
            receiver.Start(appId.Text); source.Text=receiver.Status; appId.Enabled=false; return Task.CompletedTask;
        });
        source.Text=receiver.Status; layout.Controls.Add(source);
        var palette=new FlowLayoutPanel { Width=660, Height=48 }; palette.Controls.AddRange(swatches); layout.Controls.Add(palette);
        Note("Preview shows received Chroma frames only. Test colors never appear here.",Color.Silver,30);
        Heading("2  Connect your lights",14);
        Note("Use your existing Home Assistant server with the Cync integration. This does not sign in to Google Home or GE Cync directly.",Color.Silver,42);
        layout.Controls.Add(url); layout.Controls.Add(token);
        load=Button("Load color lights", async ()=> {
            if(busy) return; StopOutput(); busy=true; load.Enabled=false;
            HomeAssistant? candidate=null;
            try {
                candidate=new HomeAssistant(url.Text,token.Text.Trim());
                var found=await candidate.ListLights(cancellation.Token);
                if(closing) { candidate.Dispose(); return; }
                ha?.Dispose(); ha=candidate; candidate=null;
                lights.Items.Clear(); lights.Items.AddRange(found); lights.SelectedIndex=-1;
                output.Text=found.Length>0 ? "Choose the Cync light you want to sync. All available color lights are listed." : "No available color-capable lights found in Home Assistant.";
                url.Enabled=token.Enabled=false; token.Clear();
            } finally { candidate?.Dispose(); busy=false; if(!closing) load.Enabled=true; }
        });
        layout.Controls.Add(lights);
        var settings = new FlowLayoutPanel { Width=660, Height=38 };
        settings.Controls.Add(new Label { Text="Chroma zone", AutoSize=true, Padding=new Padding(0,5,0,0) });
        slot.Items.AddRange(new object[]{"1 • Primary","2","3","4","5"}); slot.SelectedIndex=0; settings.Controls.Add(slot);
        settings.Controls.Add(new Label { Text="Brightness %",AutoSize=true,Padding=new Padding(12,5,0,0) });settings.Controls.Add(brightness);layout.Controls.Add(settings);
        lights.SelectedIndexChanged+=(_,_)=>StopOutput(); slot.SelectedIndexChanged+=(_,_)=>StopOutput(); brightness.ValueChanged+=(_,_)=>StopOutput();
        test=Button("Test selected light: teal",async ()=> {
            if(ha is null || lights.SelectedItem is not Light light) throw new ArgumentException("Connect and select a light first.");
            if(busy || syncing) return; busy=true;
            try { await ha.Send(light.Id,new Rgb(0,180,150),(int)brightness.Value,cancellation.Token); if(!closing) output.Text="Test command accepted by Home Assistant. Confirm the physical light turned teal. This is not Chroma sync."; }
            finally { busy=false; }
        });
        sync=Button("Start live sync",()=> {
            if(ha is null || lights.SelectedItem is not Light) throw new ArgumentException("Connect and select a light first.");
            if(receiver.Frames.Read().Colors is null) throw new InvalidOperationException("No Chroma frames received. A registered application ID and enabled Chroma Connect integration are required.");
            syncing=true; lastSequence=-1; lastColor=null; nextSend=DateTime.MinValue;
            output.Text="Live output enabled. Changes are limited to once per second; Cync/cloud latency may be longer.";
            return Task.CompletedTask;
        });
        Button("Stop sync",()=> { StopOutput(); return Task.CompletedTask; });
        Button("Disconnect and clear session",()=> { StopOutput(); ha?.Dispose(); ha=null; lights.Items.Clear();token.Clear();url.Enabled=token.Enabled=true;receiver.Stop();appId.Enabled=true;source.Text=receiver.Status;return Task.CompletedTask; });
        layout.Controls.Add(output);
        Note("Stop leaves the light at its last color. A request already accepted by Home Assistant may still finish. No startup service, driver, or firewall changes are installed.",Color.Silver,56);
        timer.Tick+=async (_,_)=>await Tick(); timer.Start();
        FormClosing+=(_,_)=> { closing=true; timer.Stop(); cancellation.Cancel(); receiver.Stop(); ha?.Dispose(); };
    }
    private void Heading(string text,float size) => layout.Controls.Add(new Label { Text=text, Width=660, Height=(int)(size*2+8), Font=new Font("Segoe UI",size,FontStyle.Bold), Margin=new Padding(0,8,0,4) });
    private void Note(string text,Color color,int height=28) => layout.Controls.Add(new Label { Text=text, Width=660, Height=height, ForeColor=color });
    private Button Button(string text,Func<Task> action)
    {
        var b=new Button { Text=text,AutoSize=true,MinimumSize=new Size(230,36),FlatStyle=FlatStyle.Flat,BackColor=Color.FromArgb(24,44,58),ForeColor=Color.White,Margin=new Padding(0,3,0,3) };
        b.Click+=async (_,_)=> { try { await action(); } catch(Exception e) { if(!closing) { StopOutput(); output.Text=Friendly(e); source.Text=receiver.Status; } } };
        layout.Controls.Add(b); return b;
    }
    private static string Friendly(Exception e) => e switch {
        DllNotFoundException=>"Chroma Connect receiver DLL is missing from Windows System32. REST SDK availability alone is insufficient; install/repair Razer's Chroma Connect component.",
        BadImageFormatException=>"Chroma Connect DLL is incompatible. This preview requires Windows x64.",
        EntryPointNotFoundException=>"Installed Chroma Connect API is incompatible with this experimental receiver.",
        HttpRequestException=>"Network/TLS connection failed. Check your server address and trusted HTTPS certificate.",
        OperationCanceledException=>"Request cancelled or timed out.",
        JsonException=>"The service returned an unexpected response.",
        ArgumentException or InvalidOperationException=>e.Message,
        _=>"The operation failed. Stop and reconnect before retrying."
    };
    private void StopOutput()
    {
        syncing=false; lastColor=null; lastSequence=-1;
        cancellation.Cancel(); cancellation.Dispose(); cancellation=new();
        if(!closing) output.Text="Sync stopped. No further color updates will be sent.";
    }
    private async Task Tick()
    {
        if(closing) return;
        var frame=receiver.Frames.Read();
        if(receiver.Started) source.Text=receiver.Status;
        for(int i=0;i<5;i++) swatches[i].BackColor=frame.Colors is null ? Color.FromArgb(35,44,60) : Color.FromArgb(frame.Colors[i].R,frame.Colors[i].G,frame.Colors[i].B);
        test.Enabled=!busy&&!syncing&&ha is not null&&lights.SelectedItem is Light;
        sync.Enabled=!busy&&!syncing&&ha is not null&&lights.SelectedItem is Light&&frame.Colors is not null;
        if(!syncing||busy||ha is null||lights.SelectedItem is not Light light||frame.Colors is null||DateTime.UtcNow<nextSend||frame.Sequence==lastSequence) return;
        var color=frame.Colors[slot.SelectedIndex]; lastSequence=frame.Sequence;
        if(color==lastColor) return;
        busy=true; nextSend=DateTime.UtcNow.AddSeconds(1); var ct=cancellation.Token;
        try { await ha.Send(light.Id,color,(int)brightness.Value,ct); if(!closing&&!ct.IsCancellationRequested) { lastColor=color; output.Text="Chroma color command accepted by Home Assistant. Physical timing depends on your lights."; } }
        catch(Exception e) { if(!closing&&!ct.IsCancellationRequested) { StopOutput(); output.Text=Friendly(e); } }
        finally { busy=false; }
    }
}
