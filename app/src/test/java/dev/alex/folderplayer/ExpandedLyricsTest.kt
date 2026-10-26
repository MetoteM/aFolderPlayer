package dev.alex.folderplayer

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ExpandedLyricsTest {
 @Test fun emptyExactSearchOffersOtherPerformersAndFiltersUnrelatedTitles(){
  val urls=mutableListOf<String>()
  val client=LyricsSearch { url -> urls.add(url.toString());object:HttpURLConnection(url){
   override fun connect(){};override fun disconnect(){};override fun usingProxy()=false
   override fun getResponseCode()=200
   override fun getInputStream()=(if(url.query.startsWith("q="))"""[
    {"id":1,"trackName":"Ghost Riders in the Sky","artistName":"Other performer","plainLyrics":"Example words"},
    {"id":2,"trackName":"Completely different song","artistName":"Other performer","plainLyrics":"Other words"},
    {"id":3,"trackName":"Ghost Riders in the Sky","artistName":"Other performer","plainLyrics":"Example words"}
   ]""" else "[]").byteInputStream()
  } }
  val rows=client.searchExpanded("Ghost Riders in the Sky","Legendary Shack Shakers",0,LyricsSearch.Cancel())
  assertEquals(1,rows.size);assertEquals("Other performer",rows.single().artist)
  assertTrue(urls.last().contains("?q="));assertTrue(urls.size<=3)
 }
 @Test fun russianSpellingAndServiceAnnotationsDoNotChangeSongIdentity(){
  assertEquals("звезды",LyricsSearch.cleanTitle(" Звёзды (2012 Remaster) "))
  assertEquals("сплин",LyricsSearch.clean("\u200eСплин"))
  assertTrue(LyricsSearch.related("Звёзды","Звезды"))
  assertFalse(LyricsSearch.related("Группа крови","Группа друзей"))
 }
 @Test fun rateLimitDoesNotTriggerMoreRequests(){
  var calls=0
  val client=LyricsSearch { url -> calls++;object:HttpURLConnection(url){
   override fun connect(){};override fun disconnect(){};override fun usingProxy()=false
   override fun getResponseCode()=429;override fun getHeaderField(name:String?)="60"
  } }
  try {client.searchExpanded("Песня","Исполнитель",0,LyricsSearch.Cancel());fail()}catch(expected:java.io.IOException){}
  assertEquals(1,calls)
 }
 @Test fun cancellationPreventsFallbackQueries(){
  val signal=LyricsSearch.Cancel();var calls=0
  val client=LyricsSearch { url -> calls++;object:HttpURLConnection(url){
   override fun connect(){};override fun disconnect(){};override fun usingProxy()=false
   override fun getResponseCode()=200
   override fun getInputStream()=object:java.io.ByteArrayInputStream("[]".toByteArray()){
    override fun close(){super.close();signal.cancel()}
   }
  } }
  try {client.searchExpanded("Песня","Исполнитель",0,signal);fail()}catch(expected:java.io.IOException){}
  assertEquals(1,calls)
 }
}
