package com.lumisync.app

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.*
import java.util.UUID

@Suppress("DEPRECATION")
class MainActivity : Activity() {
 private var adapter: BluetoothAdapter? = null
 private val handler = Handler(Looper.getMainLooper())
 private lateinit var status: TextView
 private lateinit var devices: LinearLayout
 private var gatt: BluetoothGatt? = null
 private var writeChar: BluetoothGattCharacteristic? = null
 private var connectedName = ""
 private val seen = mutableSetOf<String>()
 private val stopScan = Runnable {
  try { adapter?.bluetoothLeScanner?.stopScan(scannerCallback) } catch (_: SecurityException) {}
  status.text = "Scan complete. Tap your LED controller."
 }
 private val candidates = listOf("FFE1", "FFE9", "FFF3", "FF01", "6E400002")
 private val scannerCallback = object : ScanCallback() {
  override fun onScanResult(callbackType: Int, result: ScanResult) {
   val d = result.device
   if (!seen.add(d.address)) return
   val name = try { d.name ?: result.scanRecord?.deviceName ?: "Unnamed BLE device" } catch (_: SecurityException) { "BLE device" }
   runOnUiThread { addButton("$name  •  ${d.address}") { connect(d, name) } }
  }
  override fun onScanFailed(errorCode: Int) { runOnUiThread { status.text = "Scan failed: $errorCode" } }
 }
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
  adapter = manager.adapter
  val scroll = ScrollView(this)
  val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32,40,32,32); setBackgroundColor(0xFF101722.toInt()) }
  scroll.addView(root); setContentView(scroll)
  fun heading(s: String) { root.addView(TextView(this).apply { text=s; textSize=23f; setTextColor(-1); setPadding(0,12,0,18) }) }
  heading("LumiSync  •  Bluetooth")
  status = TextView(this).apply { text="Grant Bluetooth permissions, then scan."; setTextColor(0xFFCAD5E6.toInt()); textSize=16f }
  root.addView(status)
  devices = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
  addButton(root,"Scan for lights") { requestScan() }
  root.addView(devices)
  heading("Selected light")
  addButton(root,"On") { send(byteArrayOf(0x7E,0x04,0x04,0x01,0x00,0x01,0xFF.toByte(),0x00,0xEF.toByte())) }
  addButton(root,"Off") { send(byteArrayOf(0x7E,0x04,0x04,0x00,0x00,0x01,0xFF.toByte(),0x00,0xEF.toByte())) }
  root.addView(TextView(this).apply { text="RGB (experimental; controller dependent)"; setTextColor(-1) })
  val rgb = listOf(Triple("Red",255,0), Triple("Green",0,255), Triple("Blue",0,0))
  rgb.forEachIndexed { i, p -> addButton(root,p.first) { val r=if(i==0)255 else 0; val g=if(i==1)255 else 0; val b=if(i==2)255 else 0; send(byteArrayOf(0x7E,0x07,0x05,r.toByte(),g.toByte(),b.toByte(),0x00,0xFF.toByte(),0xEF.toByte())) } }
  heading("GE Cync • Wi-Fi")
  addButton(root,"Open Cync controls") { startActivity(android.content.Intent(this, CyncActivity::class.java)) }
  root.addView(TextView(this).apply { text="Cync controls use your Home Assistant server with its Cync integration. Set up your lights in Cync first. Direct Cync login is not implemented."; setTextColor(0xFFCAD5E6.toInt()); textSize=15f })
 }
 private fun addButton(label:String, click:()->Unit) = addButton(devices,label,click)
 private fun addButton(parent:LinearLayout,label:String,click:()->Unit) { parent.addView(Button(this).apply { text=label; isAllCaps=false; setOnClickListener { click() }; layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT) }) }
 private fun permitted():Boolean = if(Build.VERSION.SDK_INT>=31) checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED else checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
 private fun requestScan() {
  if(!permitted()) { val permissions=if(Build.VERSION.SDK_INT>=31) arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION); requestPermissions(permissions,7); return }
  if(adapter?.isEnabled != true) { status.text="Enable Bluetooth in Android settings."; return }
  handler.removeCallbacks(stopScan); adapter?.bluetoothLeScanner?.stopScan(scannerCallback); seen.clear(); devices.removeAllViews(); status.text="Scanning for 10 seconds…"
  try { adapter?.bluetoothLeScanner?.startScan(scannerCallback); handler.postDelayed(stopScan,10000) } catch(e:Exception) { status.text="Scan error: ${e.message}" }
 }
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) { super.onRequestPermissionsResult(requestCode,permissions,grantResults); if(requestCode==7 && permitted()) requestScan() else status.text="Bluetooth permission required." }
 private fun connect(device:BluetoothDevice,name:String) {
  try { handler.removeCallbacks(stopScan); adapter?.bluetoothLeScanner?.stopScan(scannerCallback); gatt?.close(); writeChar=null; connectedName=name; status.text="Connecting to $name…"; gatt=device.connectGatt(this,false,object:BluetoothGattCallback(){
   override fun onConnectionStateChange(g:BluetoothGatt,code:Int,state:Int) { if(code==BluetoothGatt.GATT_SUCCESS && state==BluetoothProfile.STATE_CONNECTED) { if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT<31) g.discoverServices() } else runOnUiThread { writeChar=null; status.text="Disconnected: $connectedName (status $code)" } }
   override fun onServicesDiscovered(g:BluetoothGatt,code:Int) { if(code != BluetoothGatt.GATT_SUCCESS) { runOnUiThread { status.text="Service discovery failed: $code" }; return }; val chars=g.services.flatMap { it.characteristics }; val found=chars.firstOrNull { c -> candidates.any { c.uuid.toString().uppercase().contains(it) } && (c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))!=0 }; runOnUiThread { writeChar=found; status.text=if(found==null) "Connected, but no known write characteristic. Model-specific protocol needed." else "Connected: $connectedName (${found.uuid}). Controls are experimental." } }
  }) } catch(e:Exception) { status.text="Connect error: ${e.message}" }
 }
 private fun send(bytes:ByteArray) { val c=writeChar; val g=gatt; if(c==null || g==null) { status.text="Connect a supported Bluetooth light first."; return }; try { c.value=bytes; c.writeType=if(c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE!=0) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; val ok=g.writeCharacteristic(c); status.text=if(ok) "Command sent to $connectedName (effect unverified)" else "Bluetooth write failed" } catch(e:Exception) { status.text="Write error: ${e.message}" } }
 override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); try { adapter?.bluetoothLeScanner?.stopScan(scannerCallback); gatt?.close() } catch(_:Exception) {} }
}
