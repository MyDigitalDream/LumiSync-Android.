package com.lumisync.app

import java.util.Locale

/** Advertising is a hint, not proof of brand or permission to send control bytes. */
internal object BleDetection {
 const val TELINK_COMPANY=0x0211
 const val TELINK_SERVICE="00010203-0405-0607-0809-0a0b0c0d1910"
 const val TELINK_CONTROL="00010203-0405-0607-0809-0a0b0c0d1912"
 const val TELINK_PAIR="00010203-0405-0607-0809-0a0b0c0d1914"
 private val meshServices=setOf("00001827-0000-1000-8000-00805f9b34fb","00001828-0000-1000-8000-00805f9b34fb")
 private val ledServices=setOf("0000ffe0-0000-1000-8000-00805f9b34fb","0000fff0-0000-1000-8000-00805f9b34fb")
 val genericWrites=setOf("0000ffe1-0000-1000-8000-00805f9b34fb","0000ffe9-0000-1000-8000-00805f9b34fb","0000fff3-0000-1000-8000-00805f9b34fb","0000ff01-0000-1000-8000-00805f9b34fb","6e400002-b5a3-f393-e0a9-e50e24dcca9e")
 enum class Kind(val rank:Int) { CYNC_NAME(0), TELINK_MESH(1), OTHER_MESH(2), LED(3), OTHER(4) }
 data class Evidence(val name:String="",val services:Set<String> = emptySet(),val companies:Set<Int> = emptySet()) {
  fun merge(next:Evidence)=Evidence(next.name.takeIf { it.isNotBlank() && it!="Unnamed device" } ?: name, services+next.services,companies+next.companies)
 }
 data class Match(val kind:Kind,val label:String,val reason:String) {
  val needsMeshRoute get()=kind in setOf(Kind.CYNC_NAME,Kind.TELINK_MESH,Kind.OTHER_MESH)
  val likelyLight get()=kind!=Kind.OTHER
 }
 fun advertised(e:Evidence):Match {
  val name=e.name.lowercase(Locale.ROOT).trim()
  val uuids=e.services.map { it.lowercase(Locale.ROOT) }.toSet()
  val cync=Regex("(^|[^a-z0-9])(cync|c[ _-]*by[ _-]*ge|c[ -]life|c[ -]sleep)(?=$|[^a-z]|[0-9])").containsMatchIn(name)
  if(cync) return Match(Kind.CYNC_NAME,"Likely GE Cync","Cync/C by GE name advertised. Brand and control support still need verification.")
  if(TELINK_SERVICE in uuids || TELINK_CONTROL in uuids || TELINK_PAIR in uuids || TELINK_COMPANY in e.companies || name=="telink_mesh1")
   return Match(Kind.TELINK_MESH,"Possible Cync / mesh light","Telink mesh signal detected. This protocol is used by Cync and other brands; it does not prove this device is GE Cync.")
  if(uuids.any { it in meshServices }) return Match(Kind.OTHER_MESH,"Bluetooth mesh device","Standard Bluetooth mesh service detected. This does not identify a Cync device.")
  if(uuids.any { it in ledServices || it in genericWrites } || Regex("(^|[^a-z0-9])(elk[ -]bledom|bledom|triones|lotus|led[ _-]?ble)(?=$|[^a-z])").containsMatchIn(name))
   return Match(Kind.LED,"LED controller candidate","A common LED name or service is advertised; command compatibility is unverified.")
  return Match(Kind.OTHER,"Other Bluetooth device","No known lighting signature in this advertisement. Tap to check its services.")
 }
 fun discovered(e:Evidence,services:Set<String>,characteristics:Set<String>):Match {
  val combined=e.merge(Evidence(services=services+characteristics))
  return advertised(combined)
 }
 fun canUseGeneric(match:Match,characteristics:Set<String>):Boolean = !match.needsMeshRoute && characteristics.any { it.lowercase(Locale.ROOT) in genericWrites }
}
