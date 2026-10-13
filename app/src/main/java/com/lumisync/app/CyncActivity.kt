package com.lumisync.app

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors

/** Cync is connected in Home Assistant; no GE account credentials enter this app. */
class CyncActivity : Activity() {
 private val worker = Executors.newSingleThreadExecutor()
 private lateinit var status: TextView
 private lateinit var url: EditText
 private lateinit var token: EditText
 private lateinit var lights: LinearLayout
 private lateinit var root: LinearLayout
 private var selected: String? = null
 private var sessionUrl = ""
 private var sessionToken = ""
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,32,24,24) }
  setContentView(ScrollView(this).apply { addView(root) })
  label("GE Cync via Home Assistant",24f)
  label("Add Cync in Home Assistant → Settings → Devices & services. Enter your HTTPS server URL and a Home Assistant long-lived access token. The token is kept only for this screen's session. Never enter your Cync password here.")
  url = field("https://your-home-assistant-server",false)
  token = field("Home Assistant access token",true)
  button(root,"Connect / refresh lights") {
   try {
    val u=URI(url.text.toString().trim().trimEnd('/'))
    require(u.scheme == "https" && !u.host.isNullOrBlank() && u.rawUserInfo == null && u.rawQuery == null && u.rawFragment == null && (u.path.isNullOrEmpty() || u.path == "/")) { "Use an HTTPS server URL without a path or password." }
    val t=token.text.toString().trim()
    require(t.isNotEmpty() && !t.contains('\n') && !t.contains('\r')) { "Enter a valid access token." }
    sessionUrl=u.toString().trimEnd('/'); sessionToken=t; selected=null; lights.removeAllViews()
    perform {
     val states=JSONArray(request("GET","/api/states"))
     val entries=(0 until states.length()).map { states.getJSONObject(it) }.filter { it.getString("entity_id").startsWith("light.") }
     runOnUiThread {
      if(isDestroyed) return@runOnUiThread
      lights.removeAllViews()
      entries.forEach { light ->
       val id=light.getString("entity_id")
       val name=light.optJSONObject("attributes")?.optString("friendly_name",id) ?: id
       button(lights,"$name • ${light.optString("state")}\n$id") { selected=id; status.text="Selected: $name" }
      }
      status.text="${entries.size} light entities found. Select your Cync light. Other Home Assistant lights may also appear."
     }
    }
   } catch(e:Exception) { status.text=e.message ?: "Invalid setup" }
  }
  status=TextView(this).apply { text="Connect to load lights." }; root.addView(status)
  lights=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; root.addView(lights)
  button(root,"On") { control("turn_on") }
  button(root,"Off") { control("turn_off") }
  label("Brightness")
  val brightness=SeekBar(this).apply { max=100; progress=50 }; root.addView(brightness)
  button(root,"Apply brightness") { control("turn_on",JSONObject().put("brightness_pct",brightness.progress)) }
  listOf("Red" to listOf(255,0,0),"Green" to listOf(0,255,0),"Blue" to listOf(0,0,255)).forEach { (name,rgb) ->
   button(root,name) { control("turn_on",JSONObject().put("rgb_color",JSONArray(rgb))) }
  }
  label("Brightness and color require a compatible light. Successful requests confirm server acceptance; verify the light's response.")
 }
 private fun field(hintText:String,password:Boolean):EditText = EditText(this).apply {
  hint=hintText; setSingleLine(true); isSaveEnabled=false
  inputType=if(password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
  root.addView(this)
 }
 private fun label(value:String,size:Float=16f) { root.addView(TextView(this).apply { text=value; textSize=size; setPadding(0,10,0,10) }) }
 private fun button(parent:LinearLayout,value:String,action:()->Unit) { parent.addView(Button(this).apply { text=value; isAllCaps=false; setOnClickListener { action() } }) }
 private fun control(service:String,data:JSONObject=JSONObject()) {
  val id=selected
  if(id==null) { status.text="Connect and select a light first."; return }
  data.put("entity_id",id)
  perform { request("POST","/api/services/light/$service",data.toString()); runOnUiThread { if(!isDestroyed) status.text="Request accepted for $id. Refresh to read its state." } }
 }
 private fun perform(action:()->Unit) {
  status.text="Contacting Home Assistant…"
  worker.execute { try { action() } catch(e:Exception) { runOnUiThread { if(!isDestroyed) status.text=e.message ?: "Connection failed" } } }
 }
 private fun request(method:String,path:String,body:String?=null):String {
  val c=URI(sessionUrl+path).toURL().openConnection() as HttpURLConnection
  try {
   c.instanceFollowRedirects=false; c.connectTimeout=10000; c.readTimeout=15000; c.requestMethod=method
   c.setRequestProperty("Authorization","Bearer $sessionToken"); c.setRequestProperty("Content-Type","application/json")
   if(body!=null) { c.doOutput=true; c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
   val code=c.responseCode
   if(code !in 200..299) throw IllegalStateException(when(code) { 401,403 -> "Access denied. Check your Home Assistant token."; else -> "Home Assistant returned HTTP $code." })
   return c.inputStream.bufferedReader().use { it.readText() }
  } finally { c.disconnect() }
 }
 override fun onDestroy() { worker.shutdownNow(); sessionToken=""; token.text.clear(); super.onDestroy() }
}
