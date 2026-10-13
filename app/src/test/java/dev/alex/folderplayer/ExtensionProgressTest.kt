package dev.alex.folderplayer

import org.junit.Assert.*
import org.junit.Test

class ExtensionProgressTest {
 @Test fun exactBoundariesAndOverrunsAreClamped() {
  assertEquals(0,ExtensionProgress.percent(0,200_000_000))
  assertEquals(25,ExtensionProgress.percent(50_000_000,200_000_000))
  assertEquals(100,ExtensionProgress.percent(200_000_000,200_000_000))
  assertEquals(100,ExtensionProgress.percent(250_000_000,200_000_000))
  assertEquals(0,ExtensionProgress.percent(-1,200_000_000))
  assertEquals(0,ExtensionProgress.percent(0,0))
  assertEquals(1000,ExtensionProgress.scaled(200_000_000,200_000_000))
  assertEquals(250,ExtensionProgress.scaled(50_000_000,200_000_000))
 }
 @Test fun labelShowsPercentageAndVolumeWithoutDeviceLocale() {
  val value=ExtensionProgress.label("Немецкий",50_000_000,200_000_000)
  assertTrue(value.contains("25%"))
  assertTrue(value.contains("50.0 / 200.0 МБ"))
  assertTrue(value.startsWith("Скачиваем Немецкий"))
 }
}
