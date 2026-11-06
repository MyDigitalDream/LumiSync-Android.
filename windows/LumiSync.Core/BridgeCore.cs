using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.RegularExpressions;
namespace LumiSync;

public readonly record struct Rgb(byte R, byte G, byte B);
public sealed record Light(string Id, string Name) { public override string ToString() => $"{Name} ({Id})"; }
public static class Frames
{
    // Chroma Connect callback: five COLORREF values, R/G/B/reserved byte order.
    public static Rgb[] Decode(ReadOnlySpan<byte> bytes)
    {
        if (bytes.Length != 20) throw new ArgumentException("Expected five Chroma colors.");
        return Fill(new Rgb[5], bytes);
    }
    private static Rgb[] Fill(Rgb[] colors, ReadOnlySpan<byte> bytes)
    {
        for (int i = 0; i < 5; i++) colors[i] = new(bytes[i*4], bytes[i*4+1], bytes[i*4+2]);
        return colors;
    }
}
public sealed class LatestFrame
{
    private readonly object gate = new();
    private Rgb[]? colors;
    private long sequence;
    public void Set(Rgb[] value) { if(value.Length != 5) throw new ArgumentException(); lock(gate) { colors = (Rgb[])value.Clone(); sequence++; } }
    public void Clear() { lock(gate) { colors = null; sequence++; } }
    public (Rgb[]? Colors, long Sequence) Read() { lock(gate) return (colors is null ? null : (Rgb[])colors.Clone(), sequence); }
}
public sealed class HomeAssistant : IDisposable
{
    private readonly HttpClient client;
    private readonly Uri server;
    private readonly string token;
    public static Uri ValidateServer(string value)
    {
        if (!Uri.TryCreate(value.Trim(), UriKind.Absolute, out var u) || u.Scheme != "https" ||
            string.IsNullOrEmpty(u.Host) || u.UserInfo.Length > 0 || u.Query.Length > 0 || u.Fragment.Length > 0)
            throw new ArgumentException("Enter your Home Assistant HTTPS address without credentials, query, or fragment.");
        return new Uri(u.AbsoluteUri.TrimEnd('/') + "/");
    }
    public HomeAssistant(string url, string token, HttpMessageHandler? handler = null)
    {
        server = ValidateServer(url);
        if (string.IsNullOrWhiteSpace(token) || token.Any(char.IsWhiteSpace)) throw new ArgumentException("Enter a valid Home Assistant token.");
        this.token = token;
        client = new HttpClient(handler ?? new HttpClientHandler { AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(8), MaxResponseContentBufferSize = 4*1024*1024 };
    }
    private async Task<string> Request(string path, object? body, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(body is null ? HttpMethod.Get : HttpMethod.Post, new Uri(server, path));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        if (body is not null) request.Content = JsonContent.Create(body);
        using var response = await client.SendAsync(request, ct);
        if (!response.IsSuccessStatusCode) throw new InvalidOperationException($"Home Assistant returned HTTP {(int)response.StatusCode}. Check server access and token; redirects are not followed.");
        return await response.Content.ReadAsStringAsync(ct);
    }
    public async Task<Light[]> ListLights(CancellationToken ct)
    {
        using var doc = JsonDocument.Parse(await Request("api/states", null, ct));
        var result = new List<Light>();
        foreach(var e in doc.RootElement.EnumerateArray())
        {
            var id = e.GetProperty("entity_id").GetString() ?? "";
            var state = e.GetProperty("state").GetString();
            if (!Regex.IsMatch(id, @"^light\.[a-z0-9_]+$") || state is "unavailable" or "unknown") continue;
            var a = e.GetProperty("attributes");
            if (!a.TryGetProperty("supported_color_modes", out var modes) || modes.ValueKind != JsonValueKind.Array ||
                !modes.EnumerateArray().Any(m => m.GetString() is "rgb" or "rgbw" or "rgbww" or "hs" or "xy")) continue;
            result.Add(new(id, a.TryGetProperty("friendly_name", out var name) ? name.GetString() ?? id : id));
        }
        return result.OrderBy(x => x.Name).ToArray();
    }
    public async Task Send(string entity, Rgb rgb, int brightness, CancellationToken ct)
    {
        if (!Regex.IsMatch(entity, @"^light\.[a-z0-9_]+$")) throw new ArgumentException("Select a light.");
        if (brightness is < 1 or > 100) throw new ArgumentOutOfRangeException(nameof(brightness));
        if (rgb == new Rgb(0,0,0)) await Request("api/services/light/turn_off", new { entity_id = entity }, ct);
        else await Request("api/services/light/turn_on", new { entity_id = entity, rgb_color = new[]{(int)rgb.R, rgb.G, rgb.B}, brightness_pct = brightness }, ct);
    }
    public void Dispose() => client.Dispose();
}
