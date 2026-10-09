package dev.alex.folderplayer

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class MusicBrainzSearchTest {
 private val id="11111111-2222-3333-4444-555555555555"
 private val body get()="""{"release-groups":[{"id":"$id","title":"Album","first-release-date":"1988-09-07","primary-type":"Album","secondary-types":["Live"],"artist-credit":[{"name":"Алекс","joinphrase":" & "},{"artist":{"name":"Other"}}]}]}"""
 private class Fake(url:URL,var body:ByteArray,val code:Int=200,val retry:String?=null,val gzip:Boolean=false):HttpURLConnection(url) {
  var closed=false
  override fun connect(){};override fun usingProxy()=false;override fun disconnect(){closed=true}
  override fun getResponseCode()=code
  override fun getInputStream()=ByteArrayInputStream(body)
  override fun getContentEncoding()=if(gzip) "gzip" else null
  override fun getHeaderField(name:String)=if(name=="Retry-After") retry else null
 }
 @Test fun explicitQueryEscapesLuceneAndUrlCharacters() {
  val url=MusicBrainzSearch.url("Кольца \"LP\" + remix","AC/DC")
  assertEquals("https",url.protocol);assertEquals("musicbrainz.eu",url.host)
  val q=URLDecoder.decode(url.query,"UTF-8");assertTrue(q.contains("releasegroup:\"Кольца \\\"LP\\\" \\+ remix\""));assertTrue(q.contains("artist:\"AC\\/DC\""));assertTrue(q.contains("limit=20"))
  assertFalse(MusicBrainzSearch.url("Album","").query.contains("artist"))
 }
 @Test fun parseOriginalDateCreditsTypesDuplicatesAndMissingDates() {
  val result=MusicBrainzSearch.parse(body).single();assertEquals(1988,result.year);assertEquals("Алекс & Other",result.artist);assertEquals("Album · Live",result.type);assertEquals("https://musicbrainz.eu/release-group/$id",result.sourceUrl())
  val mixed="""{"release-groups":[{"id":"$id","title":"A","first-release-date":"1983"},{"id":"$id","title":"duplicate"},{"id":"22222222-2222-3333-4444-555555555555","title":"unknown","first-release-date":null},{"id":"evil/url","title":"bad"}]}"""
  val parsed=MusicBrainzSearch.parse(mixed);assertEquals(2,parsed.size);assertEquals(1983,parsed[0].year);assertNull(parsed[1].year)
 }
 @Test fun boundedGzipRequestHeadersAndImmediateRateLimit() {
  Thread.sleep(1110)
  val bytes=ByteArrayOutputStream();GZIPOutputStream(bytes).use { it.write(body.toByteArray()) };val fake=Fake(URL("https://musicbrainz.eu"),bytes.toByteArray(),gzip=true);var requests=0
  val client=MusicBrainzSearch { requests++;fake }
  assertEquals(1988,client.search("Album","Artist",LyricsSearch.Cancel()).single().year)
  assertTrue(fake.closed);assertFalse(fake.instanceFollowRedirects);assertEquals(10000,fake.connectTimeout);assertEquals(30000,fake.readTimeout);assertTrue(fake.getRequestProperty("User-Agent").startsWith("FolderPlayer/0.5.0"))
  assertThrows(java.io.IOException::class.java) { client.search("Album","Artist",LyricsSearch.Cancel()) };assertEquals(1,requests)
 }
 @Test fun busyRetryAfterPersistsAndCancelledSearchDoesNotOpenConnection() {
  Thread.sleep(1110)
  val fake=Fake(URL("https://musicbrainz.eu"),byteArrayOf(),503,"120");val client=MusicBrainzSearch { fake }
  val now=System.currentTimeMillis();assertThrows(java.io.IOException::class.java) { client.search("Album","Artist",LyricsSearch.Cancel()) };assertTrue(fake.closed);assertTrue(client.cooldown()>=now+120000)
  var requests=0;val restored=MusicBrainzSearch { requests++;fake };restored.restoreCooldown(client.cooldown());assertThrows(java.io.IOException::class.java) { restored.search("A","B",LyricsSearch.Cancel()) };assertEquals(0,requests)
  val token=LyricsSearch.Cancel();token.cancel();assertThrows(java.io.IOException::class.java) { MusicBrainzSearch { requests++;fake }.search("A","B",token) };assertEquals(0,requests)
 }
 @Test fun oversizedAndMalformedResponseAreRejectedAndClosed() {
  Thread.sleep(1110)
  val huge=Fake(URL("https://musicbrainz.eu"),ByteArray(MusicBrainzSearch.MAX_RESPONSE+1));assertThrows(java.io.IOException::class.java) { MusicBrainzSearch { huge }.search("A","B",LyricsSearch.Cancel()) };assertTrue(huge.closed)
  Thread.sleep(1110)
  val malformed=Fake(URL("https://musicbrainz.eu"),"{}".toByteArray());assertThrows(java.io.IOException::class.java) { MusicBrainzSearch { malformed }.search("A","B",LyricsSearch.Cancel()) };assertTrue(malformed.closed)
 }
 @org.junit.Test fun realEuResponseFromRussianProbePreservesFirstReleaseDate() {
  val body=javaClass.classLoader!!.getResourceAsStream("musicbrainz-eu-live.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
  val load=MusicBrainzSearch.parse(body).first { it.title=="Load" && it.artist=="Metallica" }
  assertEquals("1996-05-04",load.date);assertEquals(1996,load.year);assertTrue(load.sourceUrl().startsWith("https://musicbrainz.eu/release-group/"))
 }

}
