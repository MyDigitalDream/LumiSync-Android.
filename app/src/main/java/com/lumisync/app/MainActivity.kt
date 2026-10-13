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
 private val seen=mutableSetOf<String>()
 private val known=setOf("0000ffe1-0000-1000-8000-00805f9b34fb","0000ffe9-0000-1000-8000-00805f9b34fb","0000fff3-0000-1000-8000-00805f9b34fb","0000ff01-0000-1000-8000-00805f9b34fb","6e400002-b5a3-f393-e0a9-e50e24dcca9e")
 private val scanTimeout=Runnable { stopScan(); pickerStatus?.text=if(seen.isEmpty()) "No devices found. Move closer, power on your light, then scan again." else "${seen.size} nearby devices. Choose your LED controller." }
 private val writeTimeout=Runnable { busy=false; status.text="The light did not confirm the request. Try again." }
 private val scannerCallback=object:ScanCallback() {
  override fun onScanResult(callbackType:Int,result:ScanResult) { runOnUiThread {
   if(!scanning || isDestroyed) return@runOnUiThread
   val device=result.device
   try {
    if(!seen.add(device.address)) return@runOnUiThread
    val name=result.scanRecord?.deviceName ?: device.name ?: "Unnamed device"
    pickerStatus?.text="${seen.size} nearby devices · scanning…"
    val signal=if(result.rssi> -65) "Strong signal" else if(result.rssi> -85) "Nearby" else "Weak signal · move closer"
    val b=ui.button("$name\n$signal") { picker?.dismiss(); connect(device,name) }
    pickerList?.addView(b,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=ui.dp(10) })
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
  detail=ui.text("Connect a nearby LED controller to get started.",15f,Palette.muted); ui.space(device,6); device.addView(detail)
  ui.space(device,18)
  select=ui.button("＋  Find a light",true) { requestScan() }; ui.full(device,select)
  ui.space(device,10)
  disconnect=ui.button("Disconnect") { closeConnection(); updateDisconnected("Disconnected. Your light is ready to reconnect.") }
  ui.full(device,disconnect); disconnect.visibility=android.view.View.GONE
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
 }
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
  content.addView(ui.text("Choose your LED controller. Other Bluetooth devices may appear here too.",14f,Palette.muted)); ui.space(content,12)
  pickerStatus=ui.text("Searching…",14f,Palette.accent).also { content.addView(it) }; ui.space(content,12)
  pickerList=ui.column()
  val scroll=ScrollView(this).apply { addView(pickerList) }
  content.addView(scroll,LinearLayout.LayoutParams(-1,ui.dp(260)))
  ui.space(content,12)
  ui.full(content,ui.button("Scan again") { startScan() })
  picker=AlertDialog.Builder(this).setView(content).setNegativeButton("Close",null).create().also {
   it.setOnDismissListener { stopScan(); pickerList=null; pickerStatus=null }
   it.show(); it.window?.setBackgroundDrawable(ui.shape(Palette.card))
  }
  startScan()
 }
 private fun startScan() {
  stopScan(); seen.clear(); pickerList?.removeAllViews()
  try {
   val scanner=adapter?.bluetoothLeScanner ?: throw IllegalStateException()
   scanning=true; pickerStatus?.text="Searching for 10 seconds…"; scanner.startScan(scannerCallback); handler.postDelayed(scanTimeout,10000)
  } catch(_:Exception) { scanning=false; pickerStatus?.text="Could not scan. Check Bluetooth and Nearby Devices permission." }
 }
 private fun stopScan() { handler.removeCallbacks(scanTimeout); if(scanning) { scanning=false; try { adapter?.bluetoothLeScanner?.stopScan(scannerCallback) } catch(_:Exception) {} } }
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
  controls.setReady(false); title.text=if(connectedName.isBlank()) "Add your first light" else connectedName
  detail.text="Not connected"; status.text=message; select.text="＋  Find a light"; disconnect.visibility=android.view.View.GONE
 }
 private fun connect(device:BluetoothDevice,name:String) {
  stopScan(); closeConnection(); val session=generation
  connectedName=name; controls.setReady(false); title.text=name; detail.text="Connecting…"; status.text="Stay close to your light. This may take a few seconds."; select.text="Choose another light"
  disconnect.visibility=android.view.View.VISIBLE
  try {
   gatt=device.connectGatt(this,false,object:BluetoothGattCallback() {
    override fun onConnectionStateChange(g:BluetoothGatt,code:Int,state:Int) { runOnUiThread {
     if(session!=generation) { g.close(); return@runOnUiThread }
     if(code==BluetoothGatt.GATT_SUCCESS && state==BluetoothProfile.STATE_CONNECTED) {
      detail.text="Finding controls…"
      try { if(!g.discoverServices()) { closeConnection(); updateDisconnected("Could not find controls. Try reconnecting.") } } catch(_:Exception) { closeConnection(); updateDisconnected("Bluetooth permission changed. Connect again.") }
     } else { closeConnection(); updateDisconnected("Connection ended ($code). Keep the light nearby and reconnect.") }
    } }
    override fun onServicesDiscovered(g:BluetoothGatt,code:Int) { runOnUiThread {
     if(session!=generation) return@runOnUiThread
     if(code!=BluetoothGatt.GATT_SUCCESS) { closeConnection(); updateDisconnected("Could not read this light's controls. Try again."); return@runOnUiThread }
     writeChar=g.services.flatMap { it.characteristics }.firstOrNull { c -> c.uuid.toString() in known && c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0 }
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
   handler.postDelayed({ if(session==generation && writeChar==null) { closeConnection(); updateDisconnected("Could not connect to a supported light. Try again or choose another device.") } },15000)
  } catch(_:Exception) { closeConnection(); updateDisconnected("Could not connect. Check Bluetooth and try again.") }
 }
 private fun send(bytes:ByteArray) {
  val c=writeChar; val g=gatt
  if(c==null || g==null) { status.text="Connect a light first."; return }
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
