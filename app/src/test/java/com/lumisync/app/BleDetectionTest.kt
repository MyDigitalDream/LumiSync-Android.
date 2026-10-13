package com.lumisync.app

import org.junit.Assert.*
import org.junit.Test

class BleDetectionTest {
 @Test fun cyncNamesAreHints() {
  listOf("Cync", "Cync Bulb", "C by GE", "CbyGE-123", "C-LIFE", "C-SLEEP", "Cync123").forEach {
   assertEquals(it,BleDetection.Kind.CYNC_NAME,BleDetection.advertised(BleDetection.Evidence(name=it)).kind)
  }
 }
 @Test fun unrelatedNamesDoNotBecomeCync() {
  listOf("GE Headphones","CYNCopated speaker","GEAR watch","Generic LED","my cyncopation").forEach {
   assertEquals(it,BleDetection.Kind.OTHER,BleDetection.advertised(BleDetection.Evidence(name=it)).kind)
  }
 }
 @Test fun vendorAloneIsNotBrandProof() {
  val m=BleDetection.advertised(BleDetection.Evidence(companies=setOf(0x0211)))
  assertEquals(BleDetection.Kind.TELINK_MESH,m.kind)
  assertFalse(BleDetection.canUseGeneric(m,BleDetection.genericWrites))
 }
 @Test fun laterPacketsCanIdentifyUnnamedDevicesWithoutLosingEvidence() {
  val first=BleDetection.Evidence(companies=setOf(0x0211))
  val second=first.merge(BleDetection.Evidence(name="Bedroom"))
  val third=second.merge(BleDetection.Evidence(name="Unnamed device",services=setOf(BleDetection.TELINK_SERVICE)))
  assertEquals("Bedroom",third.name); assertTrue(0x0211 in third.companies)
  assertEquals(BleDetection.Kind.TELINK_MESH,BleDetection.advertised(third).kind)
 }
 @Test fun genericWritesAreBlockedForMeshEvenWhenBothAppear() {
  val m=BleDetection.discovered(BleDetection.Evidence(name="Bedroom"),setOf(BleDetection.TELINK_SERVICE),BleDetection.genericWrites+BleDetection.TELINK_PAIR)
  assertFalse(BleDetection.canUseGeneric(m,BleDetection.genericWrites))
 }
 @Test fun sigMeshIsNotCync() {
  val m=BleDetection.advertised(BleDetection.Evidence(services=setOf("00001827-0000-1000-8000-00805f9b34fb")))
  assertEquals(BleDetection.Kind.OTHER_MESH,m.kind)
 }
 @Test fun unknownDevicesCanBeClassifiedAfterServiceDiscovery() {
  val chars=setOf("0000ffe1-0000-1000-8000-00805f9b34fb")
  val m=BleDetection.discovered(BleDetection.Evidence(),emptySet(),chars)
  assertTrue(BleDetection.canUseGeneric(m,chars))
 }
 @Test fun noWriteCharacteristicDoesNotEnableControls() {
  assertFalse(BleDetection.canUseGeneric(BleDetection.advertised(BleDetection.Evidence(name="ELK-BLEDOM")),emptySet()))
 }
 @Test fun uppercaseUuidsAreRecognized() {
  assertEquals(BleDetection.Kind.TELINK_MESH,BleDetection.advertised(BleDetection.Evidence(services=setOf(BleDetection.TELINK_SERVICE.uppercase()))).kind)
 }
}
