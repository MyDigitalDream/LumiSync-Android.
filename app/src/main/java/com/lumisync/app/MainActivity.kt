package com.lumisync.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.*


// Every discovery/connection entry point checks runtime permissions; callbacks handle revocation.
@android.annotation.SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class MainActivity : Activity() {
 private var adapter:BluetoothAdapter?=null
 private val handler=Handler(Looper.getMainLooper())
 private lateinit var ui:LumiUi
 private lateinit var title:TextView
 private lateinit var detail:TextView
 private lateinit var status:TextView
 private lateinit var select:Button
 private lateinit var disconnect:Button
 private lateinit var controls:LightControls
 private var gatt:BluetoothGatt?=null
 private var writeChar:BluetoothGattCharacteristic?=null
 private var connectedName=""
 private var scanning=false
 private var busy=false
 private var generation=0
 private var picker:AlertDialog?=null
 private var pickerList:LinearLayout?=null
 private var pickerStatus:TextView?=null
 private data class Found(val device:BluetoothDevice,val evidence:BleDetection.Evidence,val rssi:Int) {
  val match get()=BleDetection.advertised(evidence)
 }
 private val found=linkedMapOf<String,Found>()
 private var showOther=false
 private var currentEvidence=BleDetection.Evidence()
 private var currentMatch=BleDetection.advertised(currentEvidence)
 private var currentAddress=""
 private var discoveredServices=setOf<String>()
 private var discoveredCharacteristics=setOf<String>()
 private lateinit var cyncCard:LinearLayout
 private lateinit var cyncHint:TextView
 private var renderPending=false
 private val renderResults=Runnable { renderPending=false; renderPicker() }
 private val scanTimeout=Runnable { stopScan(); renderPicker() }
 private val writeTimeout=Runnable { busy=false; status.text="The light did not confirm the request. Try again." }
 private val scannerCallback=object:ScanCallback() {
  override fun onScanResult(callbackType:Int,result:ScanResult) { runOnUiThread {
   if(!scanning || isDestroyed) return@runOnUiThread
   val device=result.device
   try {
    val record=result.scanRecord
    val companies=record?.manufacturerSpecificData
    val companyIds=if(companies==null) emptySet() else (0 until companies.size()).map { companies.keyAt(it) }.toSet()
    val next=BleDetection.Evidence(record?.deviceName ?: device.name ?: "",record?.serviceUuids?.map { it.uuid.toString() }?.toSet() ?: emptySet(),companyIds)
    val previous=found[device.address]
    val evidence=previous?.evidence?.merge(next) ?: next
    found[device.address]=Found(device,evidence,result.rssi)
    if(previous==null || previous.evidence!=evidence || signal(previous.rssi)!=signal(result.rssi)) {
     if(!renderPending) { renderPending=true; handler.postDelayed(renderResults,250) }
    }
   } catch(_:SecurityException) { stopScan(); status.text="Bluetooth access was removed. Tap Find a light to grant access." }
  } }
  override fun onScanFailed(errorCode:Int) { runOnUiThread { stopScan(); pickerStatus?.text="Scan could not start ($errorCode). Wait a moment, then try again." } }
 }
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  adapter=(getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
  ui=LumiUi(this)
  val root=ui.screen("Your lights. Your mood.",false,{}, { startActivity(Intent(this,CyncActivity::class.java)) })
  val device=ui.card(root)
  device.addView(ui.text("BLUETOOTH LIGHT",12f,Palette.accent,true)); ui.space(device,10)
  title=ui.text("Add your first light",23f,bold=true); device.addView(title)
  detail=ui.text("Find nearby LED controllers and Cync Bluetooth devices.",15f,Palette.muted); ui.space(device,6); device.addView(detail)
  ui.space(device,18)
  select=ui.button("＋  Find a light",true) { requestScan() }; ui.full(device,select)
  ui.space(device,10)
  disconnect=ui.button("Disconnect") { closeConnection(); updateDisconnected("Disconnected. Your light is ready to reconnect.") }
  ui.full(device,disconnect); disconnect.visibility=android.view.View.GONE
  cyncCard=ui.card(root)
  cyncCard.addView(ui.text("YOUR CONNECTION OPTIONS",12f,Palette.accent,true)); ui.space(cyncCard,10)
  cyncHint=ui.text("",15f,Palette.muted); cyncCard.addView(cyncHint); ui.space(cyncCard,14)
  ui.full(cyncCard,ui.button("Open Cync app",true) { openCyncApp() }); ui.space(cyncCard,10)
  ui.full(cyncCard,ui.button("Link to Home Assistant") {
   startActivity(Intent(this,CyncActivity::class.java).putExtra("ble_address",currentAddress).putExtra("ble_name",connectedName))
  }); ui.space(cyncCard,10)
  ui.full(cyncCard,ui.button("Copy device details") { copyDeviceDetails() })
  cyncCard.visibility=android.view.View.GONE
  status=ui.text("Choose your light above. You can explore colors below.",14f,Palette.muted)
  root.addView(status,LinearLayout.LayoutParams(-1,-2).apply { leftMargin=ui.dp(4); rightMargin=ui.dp(4); bottomMargin=ui.dp(18) })
  controls=LightControls(ui,root,{ on -> send(byteArrayOf(0x7E,0x04,0x04,if(on) 0x01 else 0x00,0x00,0x01,0xFF.toByte(),0x00,0xEF.toByte())) },{ color,intensity ->
   fun channel(v:Int)=(v*intensity/100).toByte()
   send(byteArrayOf(0x7E,0x07,0x05,channel(Color.red(color)),channel(Color.green(color)),channel(Color.blue(color)),0x00,0xFF.toByte(),0xEF.toByte()))
  })
  val footer=ui.card(root)
  footer.addView(ui.text("A little help",16f,bold=true)); ui.space(footer,6)
  footer.addView(ui.text("Keep your light powered on and close by. Bluetooth controls depend on your LED controller model.",14f,Palette.muted))
  ui.space(footer,12); ui.full(footer,ui.button("Connection help") { showHelp() })
  if(intent.getBooleanExtra("scan_lights",false)) { intent.removeExtra("scan_lights"); handler.post { requestScan() } }
 }
 private fun signal(rssi:Int)=if(rssi> -65) "Strong signal" else if(rssi> -85) "Nearby" else "Weak signal · move closer"
 private fun renderPicker() {
  val list=pickerList ?: return
  list.removeAllViews()
  val likely=found.values.count { it.match.likelyLight }
  pickerStatus?.text=if(found.isEmpty()) {
   if(scanning) "Searching for lights…" else "No devices found. Power on your light, move closer and scan again."
  } else "$likely likely lights · ${found.size-likely} other devices"+(if(scanning) " · scanning…" else "")
  val items=found.values.filter { showOther || it.match.likelyLight }.sortedWith(compareBy<Found> { it.match.kind.rank }.thenBy { it.evidence.name.lowercase() })
  if(items.isEmpty() && found.isNotEmpty()) list.addView(ui.text("No known lighting signals yet. Enable ‘Show other Bluetooth devices’ to inspect unnamed or unrecognized lights.",14f,Palette.muted))
  items.forEach { item ->
   val name=item.evidence.name.ifBlank { "Unnamed device" }
   val b=ui.button("$name\n${item.match.label} · ${signal(item.rssi)}") { picker?.dismiss(); connect(item.device,name,item.evidence) }
   list.addView(b,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=ui.dp(10) })
  }
 }
 private fun connectionFailure(message:String) {
  if(currentMatch.needsMeshRoute) showMeshOptions() else updateDisconnected(message)
 }
 private fun showMeshOptions() {
  closeConnection() // release BLE so the Cync app can connect; never send generic LED bytes here
  controls.setReady(false); controls.powerCard.visibility=android.view.View.GONE; controls.colorCard.visibility=android.view.View.GONE
  cyncCard.visibility=android.view.View.VISIBLE; disconnect.visibility=android.view.View.GONE
  title.text=connectedName; detail.text=currentMatch.label; select.text="Find another light"
  cyncHint.text=when(currentMatch.kind) {
   BleDetection.Kind.CYNC_NAME -> "This device advertises a Cync name. Open Cync for Bluetooth control, or link it to a Home Assistant light to control it here."
   BleDetection.Kind.TELINK_MESH -> "This uses a mesh signal found on Cync and other brands. If it is your Cync light, open Cync or link its Home Assistant light below."
   else -> "This is a Bluetooth mesh device. Confirm its brand before choosing an app or linking it to a Home Assistant light."
  }
  status.text="Found nearby. Direct Cync Bluetooth control is not implemented yet; it requires mesh authentication."
 }
 private fun openCyncApp() {
  closeConnection()
  try {
   val launch=packageManager.getLaunchIntentForPackage("com.ge.cbyge")
   if(launch!=null) startActivity(launch) else startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.ge.cbyge")))
  } catch(_:Exception) { status.text="Open the Cync app from your phone's app list." }
 }
 private fun copyDeviceDetails() {
  val report="""LumiSync 0.4 Bluetooth check
Device name: $connectedName
Detection: ${currentMatch.label}
Evidence: ${currentMatch.reason}
Manufacturer IDs: ${currentEvidence.companies.joinToString { "0x%04X".format(it) }.ifBlank { "Not advertised" }}
Advertised services: ${currentEvidence.services.joinToString().ifBlank { "None" }}
Discovered services: ${discoveredServices.joinToString().ifBlank { "Unavailable" }}
Characteristics: ${discoveredCharacteristics.joinToString().ifBlank { "Unavailable" }}
Direct Cync Bluetooth control: not implemented
"""
  (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("LumiSync device details",report))
  Toast.makeText(this,"Device details copied",Toast.LENGTH_SHORT).show()
 }
 override fun onNewIntent(next:Intent) { super.onNewIntent(next); setIntent(next); if(next.getBooleanExtra("scan_lights",false)) { next.removeExtra("scan_lights"); requestScan() } }
 private fun permitted():Boolean=if(Build.VERSION.SDK_INT>=31) checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED else checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
 private fun locationEnabled():Boolean = if(Build.VERSION.SDK_INT>=28) (getSystemService(LOCATION_SERVICE) as android.location.LocationManager).isLocationEnabled else Settings.Secure.getInt(contentResolver,Settings.Secure.LOCATION_MODE,0)!=0
 private fun requestScan() {
  if(adapter==null) { status.text="This phone has no Bluetooth radio. You can still use GE Cync."; return }
  if(!permitted()) { requestPermissions(if(Build.VERSION.SDK_INT>=31) arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),7); return }
  try {
   if(adapter?.isEnabled!=true) { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),8); return }
   if(Build.VERSION.SDK_INT<=30 && !locationEnabled()) {
    status.text="Turn on Location in Android settings to discover Bluetooth devices on this Android version."; startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); return
   }
   showPicker()
  } catch(_:Exception) { status.text="Bluetooth is unavailable. Check Bluetooth in Android settings." }
 }
 private fun showPicker() {
  picker?.dismiss()
  val content=ui.column().apply { setPadding(ui.dp(22),ui.dp(22),ui.dp(22),ui.dp(12)); setBackgroundColor(Palette.card) }
  content.addView(ui.text("Nearby lights",23f,bold=true)); ui.space(content,6)
  content.addView(ui.text("Cync and likely lights appear first. Tap a device to check its connection options.",14f,Palette.muted)); ui.space(content,12)
  pickerStatus=ui.text("Searching…",14f,Palette.accent).also { content.addView(it) }; ui.space(content,12)
  pickerList=ui.column()
  val scroll=ScrollView(this).apply { addView(pickerList) }
  content.addView(scroll,LinearLayout.LayoutParams(-1,ui.dp(260)))
  ui.space(content,12)
  val others=Switch(this).apply {
   text="Show other Bluetooth devices"; setTextColor(Palette.text); isChecked=showOther; minHeight=ui.dp(48)
   setOnCheckedChangeListener { _,checked -> showOther=checked; renderPicker() }
  }
  content.addView(others)
  ui.full(content,ui.button("Scan again") { startScan() })
  picker=AlertDialog.Builder(this).setView(content).setNegativeButton("Close",null).create().also {
   it.setOnDismissListener { stopScan(); pickerList=null; pickerStatus=null }
   it.show(); it.window?.setBackgroundDrawable(ui.shape(Palette.card))
  }
  startScan()
 }
 private fun startScan() {
  stopScan(); found.clear(); pickerList?.removeAllViews()
  try {
   val scanner=adapter?.bluetoothLeScanner ?: throw IllegalStateException()
   scanning=true; pickerStatus?.text="Searching for 10 seconds…"; scanner.startScan(scannerCallback); handler.postDelayed(scanTimeout,10000)
  } catch(_:Exception) { scanning=false; pickerStatus?.text="Could not scan. Check Bluetooth and Nearby Devices permission." }
 }
 private fun stopScan() { handler.removeCallbacks(scanTimeout); handler.removeCallbacks(renderResults); renderPending=false; if(scanning) { scanning=false; try { adapter?.bluetoothLeScanner?.stopScan(scannerCallback) } catch(_:Exception) {} } }
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
  super.onRequestPermissionsResult(requestCode,permissions,grantResults)
  if(requestCode==7) { if(permitted()) requestScan() else { status.text="Nearby Devices permission is needed. Enable it in Android app settings, then try again."; showHelp() } }
 }
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) { super.onActivityResult(requestCode,resultCode,data); if(requestCode==8 && resultCode==RESULT_OK) requestScan() }
 private fun closeConnection() {
  generation++; handler.removeCallbacks(writeTimeout); busy=false; writeChar=null
  val old=gatt; gatt=null; try { old?.disconnect(); old?.close() } catch(_:Exception) {}
 }
 private fun updateDisconnected(message:String) {
  cyncCard.visibility=android.view.View.GONE; controls.powerCard.visibility=android.view.View.VISIBLE
  controls.setReady(false); title.text=if(connectedName.isBlank()) "Add your first light" else connectedName
  detail.text="Not connected"; status.text=message; select.text="＋  Find a light"; disconnect.visibility=android.view.View.GONE
 }
 private fun connect(device:BluetoothDevice,name:String,evidence:BleDetection.Evidence) {
  stopScan(); closeConnection(); val session=generation
  currentEvidence=evidence; currentMatch=BleDetection.advertised(evidence); currentAddress=device.address
  discoveredServices=emptySet(); discoveredCharacteristics=emptySet()
  cyncCard.visibility=android.view.View.GONE; controls.powerCard.visibility=android.view.View.VISIBLE
  connectedName=name; controls.setReady(false); title.text=name; detail.text="Connecting…"; status.text="Stay close to your light. This may take a few seconds."; select.text="Choose another light"
  disconnect.visibility=android.view.View.VISIBLE
  try {
   gatt=device.connectGatt(this,false,object:BluetoothGattCallback() {
    override fun onConnectionStateChange(g:BluetoothGatt,code:Int,state:Int) { runOnUiThread {
     if(session!=generation) { g.close(); return@runOnUiThread }
     if(code==BluetoothGatt.GATT_SUCCESS && state==BluetoothProfile.STATE_CONNECTED) {
      detail.text="Finding controls…"
      try { if(!g.discoverServices()) { closeConnection(); connectionFailure("Could not find controls. Try reconnecting.") } } catch(_:Exception) { closeConnection(); connectionFailure("Bluetooth permission changed. Connect again.") }
     } else { closeConnection(); connectionFailure("Connection ended ($code). Keep the light nearby and reconnect.") }
    } }
    override fun onServicesDiscovered(g:BluetoothGatt,code:Int) { runOnUiThread {
     if(session!=generation) return@runOnUiThread
     if(code!=BluetoothGatt.GATT_SUCCESS) { closeConnection(); connectionFailure("Could not read this light's controls. Try again."); return@runOnUiThread }
     discoveredServices=g.services.map { it.uuid.toString() }.toSet()
     discoveredCharacteristics=g.services.flatMap { it.characteristics }.map { it.uuid.toString() }.toSet()
     currentMatch=BleDetection.discovered(currentEvidence,discoveredServices,discoveredCharacteristics)
     if(currentMatch.needsMeshRoute) { showMeshOptions(); return@runOnUiThread }
     writeChar=if(BleDetection.canUseGeneric(currentMatch,discoveredCharacteristics)) g.services.flatMap { it.characteristics }.firstOrNull { c -> c.uuid.toString() in BleDetection.genericWrites && c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0 } else null
     controls.setReady(writeChar!=null)
     detail.text=if(writeChar==null) "Needs a compatible controller" else "●  Connected · Bluetooth"
     status.text=if(writeChar==null) "This device uses an unknown control method. Try your LED controller or open Connection help." else "Ready. Try Turn on first. Commands are experimental for your controller."
    } }
    override fun onCharacteristicWrite(g:BluetoothGatt,c:BluetoothGattCharacteristic,code:Int) { runOnUiThread {
     if(session!=generation) return@runOnUiThread
     handler.removeCallbacks(writeTimeout); busy=false
     status.text=if(code==BluetoothGatt.GATT_SUCCESS) "Command delivered. Check your light for the change." else "The light rejected the command ($code). Try again."
    } }
   },BluetoothDevice.TRANSPORT_LE)
   handler.postDelayed({ if(session==generation && writeChar==null) { closeConnection(); connectionFailure("Could not connect to a supported light. Try again or choose another device.") } },15000)
  } catch(_:Exception) { closeConnection(); connectionFailure("Could not connect. Check Bluetooth and try again.") }
 }
 private fun send(bytes:ByteArray) {
  val c=writeChar; val g=gatt
  if(c==null || g==null || currentMatch.needsMeshRoute) { status.text="Connect a compatible LED controller first."; return }
  if(busy) { status.text="Sending the previous command. Give it a moment."; return }
  try {
   c.value=bytes; c.writeType=if(c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE!=0) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
   busy=true
   if(g.writeCharacteristic(c)) { status.text="Sending to $connectedName…"; handler.postDelayed(writeTimeout,3000) } else { busy=false; status.text="Could not send. Try again." }
  } catch(_:Exception) { busy=false; closeConnection(); updateDisconnected("Bluetooth access changed. Connect your light again.") }
 }
 private fun showHelp() {
  AlertDialog.Builder(this).setTitle("Connect your Bluetooth light").setMessage("1. Power on the LED controller.\n\n2. Enable Bluetooth and allow Nearby Devices permission. Android 11 and earlier also need Location enabled.\n\n3. Close the original light app if it is using the connection, then scan in LumiSync.\n\nIf your light connects but does not respond, its protocol may differ. Share the controller model and original app name.\n\nSelected: ${connectedName.ifBlank { "None" }}\nControl ID: ${writeChar?.uuid ?: "Not available"}")
   .setPositiveButton("Got it",null).setNeutralButton("App settings") { _,_ -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName"))) }.show()
 }
 override fun onStop() { stopScan(); picker?.dismiss(); super.onStop() }
 override fun onDestroy() { closeConnection(); handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
