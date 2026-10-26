package dev.alex.folderplayer
import org.junit.Assert.*
import org.junit.Test
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[28])
class LyricsMatchTest {
 private fun result(title:String="Cure",artist:String="Metallica",album:String="Load",duration:Int=294,text:String="слова",timed:Boolean=false,id:Int=1):LyricsSearch.Result {
  val o=org.json.JSONObject().put("id",id).put("trackName",title).put("artistName",artist).put("albumName",album).put("duration",duration).put(if(timed)"syncedLyrics" else "plainLyrics",if(timed)"[00:00.000]$text" else text)
  return LyricsSearch.Result(o)
 }
 @Test fun exactUniqueMetadataAndDurationAutomaticallySelectsTimedVersion() {
  val plain=result();val timed=result(timed=true,id=2)
  assertSame(timed,LyricsMatch.automatic(listOf(plain,timed),"Cure","Metallica","Load (LP)",294000))
 }
 @Test fun wrongArtistTitleAndLiveDurationNeverAutomaticallySave() {
  for(r in listOf(result(artist="Cover band"),result(title="Cure live"),result(duration=350)))assertNull(LyricsMatch.automatic(listOf(r),"Cure","Metallica","Load",294000))
 }
 @Test fun conflictingTextsRequireChoice() { assertNull(LyricsMatch.automatic(listOf(result(text="one"),result(text="different",id=2)),"Cure","Metallica","Load",294000)) }
 @Test fun missingIdentityOrDurationDoesNotGuess() {
  assertNull(LyricsMatch.automatic(listOf(result()),"Cure","","Load",294000));assertNull(LyricsMatch.automatic(listOf(result()),"Cure","Metallica","Load",0))
 }
 @Test fun exactAlbumDisambiguatesDifferentRecordings() {
  val correct=result();assertSame(correct,LyricsMatch.automatic(listOf(correct,result(album="Live",text="другое",id=2)),"Cure","Metallica","Load (LP)",294000))
 }
}
