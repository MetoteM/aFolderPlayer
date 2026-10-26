package dev.alex.folderplayer

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class Id3YearTest {
 private fun tag(version:Int,vararg fields:Pair<String,String>):ByteArray {
  val payload=ByteArrayOutputStream()
  for((id,value) in fields) {
   val data=byteArrayOf(3)+value.toByteArray(Charsets.UTF_8)
   payload.write(id.toByteArray(Charsets.US_ASCII))
   if(version==2) payload.write(byteArrayOf((data.size shr 16).toByte(),(data.size shr 8).toByte(),data.size.toByte()))
   else { val n=data.size;payload.write(if(version==4) byteArrayOf((n shr 21).toByte(),((n shr 14) and 127).toByte(),((n shr 7) and 127).toByte(),(n and 127).toByte()) else byteArrayOf((n shr 24).toByte(),(n shr 16).toByte(),(n shr 8).toByte(),n.toByte()));payload.write(byteArrayOf(0,0)) }
   payload.write(data)
  }
  val b=payload.toByteArray();val n=b.size
  return byteArrayOf(73,68,51,version.toByte(),0,0,(n shr 21).toByte(),((n shr 14) and 127).toByte(),((n shr 7) and 127).toByte(),(n and 127).toByte())+b
 }
 @Test fun allId3VersionsReadYears() {
  assertEquals(1983,Id3YearReader.read(ByteArrayInputStream(tag(2,"TYE" to "1983")),null))
  assertEquals(1984,Id3YearReader.read(ByteArrayInputStream(tag(3,"TYER" to "1984")),null))
  assertEquals(1986,Id3YearReader.read(ByteArrayInputStream(tag(4,"TDRC" to "1986-03-03")),null))
 }
 @Test fun originalReleaseOverridesReissueAndNativeYear() {
  assertEquals(1988,Id3YearReader.read(ByteArrayInputStream(tag(4,"TDRL" to "2020","TDOR" to "1988-09-07")),2020))
  assertEquals(1991,Id3YearReader.read(ByteArrayInputStream(tag(3,"TYER" to "2021","TORY" to "1991")),2021))
  assertEquals(1983,Id3YearReader.read(ByteArrayInputStream(tag(4,"TXXX" to "ORIGINALDATE\u00001983-07-25")),null))
 }
 @Test fun malformedOrAbsentTagsPreserveFallback() {
  assertEquals(1984,Id3YearReader.read(ByteArrayInputStream(byteArrayOf(1,2,3)),1984))
  assertEquals(1986,Id3YearReader.read(ByteArrayInputStream(tag(4,"TDOR" to "unknown","TDRC" to "1986")),null))
  val oversized=byteArrayOf(73,68,51,4,0,0,127,127,127,127)
  assertEquals(1991,Id3YearReader.read(ByteArrayInputStream(oversized),1991))
 }
 @Test fun tagReadStopsBeforeAudio() {
  val b=tag(4,"TDOR" to "1988");val stream=ByteArrayInputStream(b+ByteArray(1024))
  assertEquals(1988,Id3YearReader.read(stream,null));assertEquals(1024,stream.available())
 }
 @Test fun aliasesAndPriorityAreIndependentOfOrder() {
  for(key in listOf("original_date","Original Release Year","ORIGYEAR","TOR")) {
   val years=YearTags();years.add("YEAR","2026");years.add(key,"1983-07-25");years.add("DATE","2020")
   assertEquals(1983,years.resolve(null))
  }
  val years=YearTags();years.add("DATE","bad");years.add("YEAR","19880");assertEquals(1984,years.resolve(1984))
 }
}
