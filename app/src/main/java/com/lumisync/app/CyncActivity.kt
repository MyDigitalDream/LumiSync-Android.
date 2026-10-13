package com.lumisync.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.*
import android.content.res.ColorStateList
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors

/** An immutable connection keeps each request's server and credential together. */
internal class CyncClient(private val base:String,private val token:String) {
 fun request(method:String,path:String,body:JSONObject?=null):String {
  val c=URI(base+path).toURL().openConnection() as HttpURLConnection
  try {
   c.instanceFollowRedirects=false; c.connectTimeout=10000; c.readTimeout=15000; c.requestMethod=method
   c.setRequestProperty("Authorization","Bearer $token"); c.setRequestProperty("Content-Type","application/json")
   if(body!=null) { c.doOutput=true; c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
   val code=c.responseCode
   if(code !in 200..299) throw IllegalStateException(when(code) {
    401,403 -> "Access denied. Check your Home Assistant token."
    404 -> "Server or light not found. Check your setup and refresh."
    else -> "Your server returned HTTP $code. Try again."
   })
   return c.inputStream.bufferedReader().use { it.readText() }
  } finally { c.disconnect() }
 }
}
/** Memory only, shared while moving between Bluetooth and Cync. */
internal object CyncSession { var client:CyncClient?=null; var selectedId:String?=null }

class CyncActivity : Activity() {
 private val worker=Executors.newSingleThreadExecutor()
 private lateinit var ui:LumiUi
 private lateinit var setup:LinearLayout
 private lateinit var dashboard:LinearLayout
 private lateinit var status:TextView
 private lateinit var url:EditText
 private lateinit var token:EditText
 private lateinit var connectButton:Button
 private lateinit var deviceName:TextView
 private lateinit var deviceState:TextView
 private lateinit var choose:Button
 private lateinit var refresh:Button
 private lateinit var disconnect:Button
 private lateinit var controls:LightControls
 private lateinit var brightnessCard:LinearLayout
 private lateinit var brightness:SeekBar
 private lateinit var brightnessLabel:TextView
 private lateinit var applyBrightness:Button
 private var lights=listOf<JSONObject>()
 private var busy=false
 private var linkAddress=""
 private var linkName=""
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  ui=LumiUi(this)
  linkAddress=intent.getStringExtra("ble_address") ?: ""
  linkName=intent.getStringExtra("ble_name") ?: "your Bluetooth light"
  if(linkAddress.isNotBlank()) CyncSession.selectedId=getPreferences(MODE_PRIVATE).getString("ble_link_$linkAddress",null)
  val root=ui.screen("Every light, one place.",true,{ finish() },{})
  setup=ui.card(root)
  setup.addView(ui.text("CONNECT GE CYNC",12f,Palette.accent,true)); ui.space(setup,10)
  setup.addView(ui.text("Bring your Cync lights in",24f,bold=true)); ui.space(setup,8)
  ui.full(setup,ui.button("Find Cync nearby") { scanNearby() }); ui.space(setup,12)
  setup.addView(ui.text("LumiSync connects through your Home Assistant server. Set up your lights there once, then control them here.",15f,Palette.muted)); ui.space(setup,16)
  ui.full(setup,ui.button("How to set up Home Assistant") { showSetupHelp() }); ui.space(setup,20)
  url=ui.field(setup,"Server address","https://your-home-assistant-server")
  url.setText(getPreferences(MODE_PRIVATE).getString("server",""))
  token=ui.field(setup,"Access token","Paste your Home Assistant token",true)
  connectButton=ui.button("Connect my lights",true) { connect() }; ui.full(setup,connectButton)
  ui.space(setup,12); setup.addView(ui.text("Your token stays in memory until you disconnect or Android closes the app. Your Cync password is never needed here.",12f,Palette.muted))
  status=ui.text("",14f,Palette.muted)
  root.addView(status,LinearLayout.LayoutParams(-1,-2).apply { leftMargin=ui.dp(4); rightMargin=ui.dp(4); bottomMargin=ui.dp(16) })
  dashboard=ui.column(); root.addView(dashboard)
  val device=ui.card(dashboard)
  device.addView(ui.text("GE CYNC · HOME ASSISTANT",12f,Palette.accent,true)); ui.space(device,10)
  deviceName=ui.text("Choose a light",23f,bold=true); device.addView(deviceName); ui.space(device,6)
  deviceState=ui.text("Connected to your server",14f,Palette.muted); device.addView(deviceState); ui.space(device,18)
  choose=ui.button("Choose a light",true) { pickLight() }; refresh=ui.button("Refresh") { loadLights() }
  ui.pair(device,choose,refresh)
  brightnessCard=ui.card(dashboard)
  brightnessCard.addView(ui.text("Brightness",18f,bold=true)); ui.space(brightnessCard,10)
  brightnessLabel=ui.text("50%",26f,Palette.accent,true); brightnessCard.addView(brightnessLabel)
  brightness=SeekBar(this).apply { max=99; progress=49; progressTintList=ColorStateList.valueOf(Palette.accent); thumbTintList=ColorStateList.valueOf(Palette.accent); contentDescription="Light brightness" }
  brightnessCard.addView(brightness,LinearLayout.LayoutParams(-1,ui.dp(56)))
  brightness.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
   override fun onProgressChanged(s:SeekBar,p:Int,user:Boolean) { brightnessLabel.text="${p+1}%" }
   override fun onStartTrackingTouch(s:SeekBar) {}
   override fun onStopTrackingTouch(s:SeekBar) {}
  })
  applyBrightness=ui.button("Apply brightness") { control("turn_on",JSONObject().put("brightness_pct",brightness.progress+1)) }; ui.full(brightnessCard,applyBrightness)
  controls=LightControls(ui,dashboard,{ on -> control(if(on) "turn_on" else "turn_off") },{ color,intensity ->
   control("turn_on",JSONObject().put("rgb_color",JSONArray(listOf(android.graphics.Color.red(color),android.graphics.Color.green(color),android.graphics.Color.blue(color)))).put("brightness_pct",intensity))
  })
  dashboard.removeView(brightnessCard); dashboard.addView(brightnessCard,2)
  val account=ui.card(dashboard)
  ui.full(account,ui.button("Find Cync nearby") { scanNearby() }); ui.space(account,12)
  account.addView(ui.text("Your connection",16f,bold=true)); ui.space(account,6)
  account.addView(ui.text("The picker includes all your Home Assistant lights. Choose the Cync device you want to control.",14f,Palette.muted)); ui.space(account,12)
  disconnect=ui.button("Disconnect server") { CyncSession.client=null; CyncSession.selectedId=null; lights=emptyList(); token.text.clear(); showSetup(); status.text="Disconnected. Your token has been cleared from the session." }; ui.full(account,disconnect)
  if(CyncSession.client==null) showSetup() else { showDashboard(); loadLights() }
 }
 private fun showSetup() { setup.visibility=View.VISIBLE; dashboard.visibility=View.GONE; status.text=if(linkAddress.isBlank()) "" else "Found $linkName nearby. Connect your server, then choose the matching light to link it." }
 private fun showDashboard() { setup.visibility=View.GONE; dashboard.visibility=View.VISIBLE; updateControls() }
 private fun connect() {
  if(busy) return
  try {
   val base=url.text.toString().trim().trimEnd('/')
   val uri=URI(base)
   require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null && uri.path.isNullOrEmpty()) { "Enter an HTTPS server address, without a path." }
   val credential=token.text.toString().trim()
   require(credential.isNotEmpty() && !credential.contains('\n') && !credential.contains('\r')) { "Paste your Home Assistant access token." }
   val client=CyncClient(base,credential)
   perform({ JSONArray(client.request("GET","/api/states")) }) { states ->
    CyncSession.client=client; getPreferences(MODE_PRIVATE).edit().putString("server",base).apply(); token.text.clear()
    receiveLights(states); showDashboard()
    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(url.windowToken,0)
   }
  } catch(e:Exception) { status.text=e.message ?: "Check your server address and token." }
 }
 private fun loadLights() {
  val client=CyncSession.client ?: return
  perform({ JSONArray(client.request("GET","/api/states")) }) { receiveLights(it) }
 }
 private fun receiveLights(states:JSONArray) {
  lights=(0 until states.length()).map { states.getJSONObject(it) }.filter { it.optString("entity_id").startsWith("light.") }.sortedBy { name(it).lowercase() }
  if(lights.none { it.optString("entity_id")==CyncSession.selectedId }) CyncSession.selectedId=null
  if(lights.size==1 && linkAddress.isBlank()) CyncSession.selectedId=lights[0].getString("entity_id")
  status.text=when {
   lights.isEmpty() -> "No lights found. Add Cync in Home Assistant, then refresh."
   linkAddress.isNotBlank() && CyncSession.selectedId==null -> "Choose the Home Assistant light matching $linkName. LumiSync will remember your choice on this phone."
   linkAddress.isNotBlank() -> "Opened your saved Home Assistant link for $linkName."
   else -> "${lights.size} lights available. Choose your Cync light."
  }
  updateControls()
 }
 private fun name(light:JSONObject)=light.optJSONObject("attributes")?.optString("friendly_name")?.takeIf { it.isNotBlank() } ?: light.optString("entity_id")
 private fun pickLight() {
  if(lights.isEmpty()) { status.text="No lights found yet. Add Cync in Home Assistant, then refresh."; return }
  val labels=lights.map { "${name(it)}\n${it.optString("state")} · ${it.optString("entity_id")}" }.toTypedArray()
  AlertDialog.Builder(this).setTitle("Choose a light").setItems(labels) { _,index ->
   val id=lights[index].getString("entity_id")
   if(linkAddress.isBlank()) { CyncSession.selectedId=id; updateControls(); status.text="Selected ${name(lights[index])}." }
   else AlertDialog.Builder(this).setTitle("Link this light?")
    .setMessage("Use ${name(lights[index])} in Home Assistant for $linkName? Check that these are the same physical light.")
    .setPositiveButton("Link light") { _,_ ->
     CyncSession.selectedId=id; getPreferences(MODE_PRIVATE).edit().putString("ble_link_$linkAddress",id).apply()
     updateControls(); status.text="Linked to ${name(lights[index])}. This choice is saved on your phone."
    }.setNegativeButton("Cancel",null).show()
  }.setNegativeButton("Cancel",null).show()
 }
 private fun selected()=lights.firstOrNull { it.optString("entity_id")==CyncSession.selectedId }
 private fun updateControls() {
  val light=selected(); val state=light?.optString("state")
  val ready=light!=null && state!in listOf("unavailable","unknown") && !busy
  val attrs=light?.optJSONObject("attributes")
  val modes=attrs?.optJSONArray("supported_color_modes")
  val modesList=if(modes==null) emptyList() else (0 until modes.length()).map { modes.optString(it) }
  val rgb=modesList.any { it in listOf("rgb","rgbw","rgbww","hs","xy") }
  val dim=modesList.any { it !in listOf("onoff","unknown") }
  deviceName.text=light?.let { name(it) } ?: "Choose a light"
  deviceState.text=when(state) { "on" -> "●  On · last reported by your server"; "off" -> "○  Off · last reported by your server"; "unavailable","unknown" -> "Unavailable · check your light, then refresh"; else -> "Select a light to unlock its controls" }
  controls.setReady(ready,rgb || light==null)
  brightnessCard.visibility=if(dim) View.VISIBLE else View.GONE
  ui.enable(applyBrightness,ready && dim); ui.enable(brightness,ready && dim)
  if(!busy && attrs?.has("brightness")==true && !attrs.isNull("brightness")) brightness.progress=(attrs.optInt("brightness",128)*100/255-1).coerceIn(0,99)
  ui.enable(choose,!busy && lights.isNotEmpty()); ui.enable(refresh,!busy); ui.enable(disconnect,!busy)
 }
 private fun control(service:String,data:JSONObject=JSONObject()) {
  val client=CyncSession.client ?: return
  val light=selected() ?: return
  val id=light.getString("entity_id")
  data.put("entity_id",id)
  perform({
   client.request("POST","/api/services/light/$service",data)
   try { JSONObject(client.request("GET","/api/states/$id")) } catch(_:Exception) { null }
  }) { updated ->
   if(updated!=null) lights=lights.map { if(it.optString("entity_id")==id) updated else it }
   status.text=if(updated==null) "Command accepted. Refresh to check the latest state." else "Command accepted. State refreshed from your server."
   updateControls()
  }
 }
 private fun <T> perform(work:()->T,done:(T)->Unit) {
  if(busy) return
  busy=true; ui.enable(connectButton,false); url.isEnabled=false; token.isEnabled=false; updateControls(); status.text="Connecting to your server…"
  worker.execute {
   val result=runCatching { work() }
   runOnUiThread {
    if(isDestroyed || isFinishing) return@runOnUiThread
    busy=false; ui.enable(connectButton,true); url.isEnabled=true; token.isEnabled=true
    result.fold({ done(it) },{ e ->
     status.text=when(e) {
      is javax.net.ssl.SSLException -> "Could not verify the server's HTTPS certificate. Use a trusted HTTPS address."
      is java.net.SocketTimeoutException -> "Your server took too long to respond. Check the connection and retry."
      is IllegalStateException -> e.message ?: "Server request failed."
      else -> "Could not reach Home Assistant. Check your server address and network."
     }
    }); updateControls()
   }
  }
 }
 private fun scanNearby() {
  startActivity(android.content.Intent(this,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("scan_lights",true))
  finish()
 }
 private fun showSetupHelp() {
  AlertDialog.Builder(this).setTitle("Set up Cync once").setMessage("1. Set up your lights in the GE Cync app.\n\n2. On a running Home Assistant server, open Settings → Devices & services → Add integration → Cync.\n\n3. Enable trusted HTTPS access to your server.\n\n4. In your Home Assistant profile, create a long-lived access token and paste it into LumiSync.\n\nThis version needs a Home Assistant server. It cannot sign into GE Cync directly.")
   .setPositiveButton("Got it",null).show()
 }
 override fun onDestroy() { worker.shutdownNow(); token.text.clear(); super.onDestroy() }
}
