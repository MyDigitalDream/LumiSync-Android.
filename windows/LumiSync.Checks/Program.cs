using LumiSync;
using System.Net;
using System.Text.Json;
int checks=0;
void Check(bool ok,string label) { if(!ok) throw new Exception(label); checks++; Console.WriteLine("PASS "+label); }
void Reject(Action action,string label) { bool rejected=false; try { action(); } catch(ArgumentException) { rejected=true; } Check(rejected,label); }
var bytes=Enumerable.Range(0,20).Select(i=>(byte)i).ToArray();
var colors=Frames.Decode(bytes);
Check(colors[0]==new Rgb(0,1,2)&&colors[4]==new Rgb(16,17,18),"Five zones decode in RGB order, ignoring reserved bytes");
Reject(()=>Frames.Decode(new byte[19]),"Reject truncated frame");
var latest=new LatestFrame(); latest.Set(colors); colors[0]=new Rgb(255,255,255);
Check(latest.Read().Colors![0]==new Rgb(0,1,2),"Callback buffer is copied");
for(int i=0;i<10000;i++) latest.Set(Frames.Decode(bytes));
Check(latest.Read().Sequence==10001,"Latest-only store coalesces burst frames"); latest.Clear(); Check(latest.Read().Colors is null,"Non-live status clears pending output");
foreach(var bad in new[]{"http://example.com","https://user:pass@example.com","https://example.com/?token=x","https://example.com/#fragment"}) Reject(()=>HomeAssistant.ValidateServer(bad),"Reject unsafe server "+bad.Split(':')[0]);
Check(HomeAssistant.ValidateServer("https://example.com/ha").AbsoluteUri=="https://example.com/ha/","Preserve server path prefix");
var fake=new Fake(); using var ha=new HomeAssistant("https://example.com/ha","test-token",fake);
fake.Response="""[{"entity_id":"light.color","state":"on","attributes":{"friendly_name":"Color bulb","supported_color_modes":["rgb"]}},{"entity_id":"light.white","state":"on","attributes":{"supported_color_modes":["color_temp"]}},{"entity_id":"light.offline","state":"unavailable","attributes":{"supported_color_modes":["rgb"]}}]""";
var lights=await ha.ListLights(default); Check(lights.Length==1&&lights[0].Id=="light.color","Filter unavailable and non-color lights");
Check(fake.Url=="https://example.com/ha/api/states"&&fake.Auth=="Bearer test-token","Authenticated request stays on configured endpoint");
fake.Response="[]"; await ha.Send("light.color",new Rgb(12,34,56),40,default);
using(var body=JsonDocument.Parse(fake.Body!)) Check(body.RootElement.GetProperty("rgb_color")[2].GetInt32()==56&&body.RootElement.GetProperty("brightness_pct").GetInt32()==40,"Serialize RGB and brightness");
await ha.Send("light.color",new Rgb(0,0,0),40,default); Check(fake.Url.EndsWith("/light/turn_off"),"Black frame switches off light");
fake.Code=HttpStatusCode.Redirect; bool denied=false; try { await ha.ListLights(default); } catch(InvalidOperationException) { denied=true; } Check(denied,"Redirect response fails closed");
using var cancelled=new CancellationTokenSource(); cancelled.Cancel(); bool stopped=false;try { await ha.Send("light.color",new Rgb(1,2,3),40,cancelled.Token); }catch(OperationCanceledException){stopped=true;}Check(stopped,"Cancellation prevents output");
Console.WriteLine($"{checks} checks passed. No hardware claims.");
sealed class Fake:HttpMessageHandler
{
 public string Response="[]",Url="",Auth=""; public string? Body; public HttpStatusCode Code=HttpStatusCode.OK;
 protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken ct)
 { ct.ThrowIfCancellationRequested();Url=request.RequestUri!.AbsoluteUri;Auth=request.Headers.Authorization?.ToString()??"";Body=request.Content is null?null:await request.Content.ReadAsStringAsync(ct);return new(Code){Content=new StringContent(Response)}; }
}
