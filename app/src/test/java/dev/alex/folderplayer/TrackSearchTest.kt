package dev.alex.folderplayer

import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class TrackSearchTest {
 @Test fun cleanupRemovesAdvertisingButKeepsRealNamesAndVersions() {
  assertEquals("Rammstein - Sonne",SongNames.clean("Rammstein - Sonne [320kbps] [site.ru]"))
  for(name in listOf("99 Luftballons","AC/DC","P!nk","Sonne (Live)","Song (Remix)"))assertEquals(name,SongNames.clean(name))
  val track=Track("id","garbage","",200000,"","01__Rammstein - Sonne [320kbps] [site.ru].mp3")
  assertTrue(SongQuery("Sonne","Rammstein") in SongNames.queries(track));assertEquals("Sonne",SongNames.queries(track,SongQuery("Sonne","Rammstein")).first().title)
 }
 private class Reply(url:URL,val body:String):HttpURLConnection(url) {
  override fun connect(){};override fun usingProxy()=false;override fun disconnect(){};override fun getResponseCode()=200;override fun getInputStream()=ByteArrayInputStream(body.toByteArray())
 }
 @Test fun typoFallbackMakesBoundedRequestsAndReturnsCanonicalCandidates() {
  var calls=0;var metadataCalls=0
  val lyrics=LyricsSearch { url -> calls++;Reply(url,if(calls<3)"[]" else """[{"id":1,"trackName":"Sonne","artistName":"Rammstein","albumName":"Mutter","duration":272,"plainLyrics":"A song"}]""") }
  val resolver=TrackSearch(lyrics) { q,_ -> metadataCalls++;assertEquals("SonneX",q.title);listOf(RecordingSearch.Match(SongQuery("Sonne","Rammstein"),272000)) }
  val result=resolver.search(Track("id","SonneX","Rammstein",272000,""),null,LyricsSearch.Cancel())
  assertEquals("Sonne",result.single().title);assertEquals(3,calls);assertEquals(1,metadataCalls)
 }
 @Test fun cancellationPreventsAllNetworkAccess() {
  var calls=0;val resolver=TrackSearch(LyricsSearch { calls++;error("network") }) { _,_ -> error("metadata") };val token=LyricsSearch.Cancel();token.cancel()
  assertThrows(java.io.IOException::class.java) { resolver.search(Track("id","Song","Artist",200000,""),null,token) };assertEquals(0,calls)
 }
 @Test fun streamingResamplerHandlesRatesBoundariesAndNonFiniteSamples() {
  for(rate in listOf(8000,16000,44100,48000)) { val values=mutableListOf<Short>();val converter=PcmResampler(rate);repeat(rate){converter.add(1000.0,{ values.add(it) })};assertTrue(kotlin.math.abs(values.size-16000)<=2);assertTrue(values.all { it==1000.toShort() }) }
  val out=mutableListOf<Short>();PcmResampler(16000).add(Double.NaN,{ out.add(it) });assertEquals(0.toShort(),out.single())
 }
}
