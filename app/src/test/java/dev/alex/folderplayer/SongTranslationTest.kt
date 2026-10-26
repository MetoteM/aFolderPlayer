package dev.alex.folderplayer

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class SongTranslationTest {
 private val songs=linkedMapOf(
  "Ich liebe dich. Du hast mich gefragt. Mein Herz und meine Augen." to "opus-de-ru-v1",
  "Je t'aime. Nous sommes dans la nuit, mon amour toujours avec toi." to "opus-fr-ru-v1",
  "Yo te quiero. Mi corazón siempre contigo, nunca sin tu amor." to "opus-es-ru-v1",
  "I love you. You are always on my mind and in my heart." to "opus-en-ru-v2",
  "我爱你，你是我的爱。我们在这里。" to "opus-zh-ru-v1"
 )
 @Test fun mixedPlaylistSelectsEachLanguageWithoutChangingGlobalPackage() {
  val app=RuntimeEnvironment.getApplication();val prefs=app.getSharedPreferences("extensions",0);val installed=TranslationClient.MODELS.keys.toSet()
  for(global in installed) {
   prefs.edit().putString("model",global).putString("modelTitle","Global").commit()
   for((source,model) in songs)assertEquals("source=$source global=$global",SongTranslation.Decision.Ready(model),SongTranslation.decide(source,installed))
   assertEquals(global,prefs.getString("model",null));assertEquals("Global",prefs.getString("modelTitle",null))
  }
 }
 @Test fun missingLanguageNeverFallsBackToAnotherLanguageAndEnglishLegacyIsAllowed() {
  assertEquals(SongTranslation.Decision.Missing("opus-de-ru-v1"),SongTranslation.decide(songs.keys.first(),setOf("opus-en-ru-v2","opus-fr-ru-v1")))
  assertEquals(SongTranslation.Decision.Ready(TranslationClient.MODEL),SongTranslation.decide(songs.keys.elementAt(3),setOf(TranslationClient.MODEL)))
 }
 @Test fun ambiguousMixedAndUnsupportedTextsRequireChoiceAndRussianIsNotTranslated() {
  for(text in listOf("La la la... no.","Dreams.","Ich liebe dich und du hast mein Herz. Je t'aime mon amour avec toi dans la nuit.","Não quero você, coração e saudade.","東京の夜、君を愛している。"))assertEquals(text,SongTranslation.Decision.Choose,SongTranslation.decide(text,TranslationClient.MODELS.keys))
  assertEquals(SongTranslation.Decision.Russian,SongTranslation.decide("Это оригинальный русский текст песни.",emptySet()))
 }
 @Test fun perSongChoicePersistsIsContentSpecificAndDoesNotTouchGlobalSettings() {
  val app=RuntimeEnvironment.getApplication();val global=app.getSharedPreferences("extensions",0);global.edit().putString("model","opus-fr-ru-v1").commit()
  SongTranslationChoice(app).save("song-a","Ambiguous words","opus-de-ru-v1")
  val saved=SongTranslationChoice(app);assertEquals("opus-de-ru-v1",saved.read("song-a","Ambiguous words"));assertNull(saved.read("song-b","Ambiguous words"));assertNull(saved.read("song-a","Changed source"));assertEquals("opus-fr-ru-v1",global.getString("model",null))
  assertEquals(SongTranslation.Decision.Ready("opus-de-ru-v1"),SongTranslation.decide("Ambiguous words",TranslationClient.MODELS.keys,saved.read("song-a","Ambiguous words")))
  saved.clear("song-a","Ambiguous words");assertNull(saved.read("song-a","Ambiguous words"))
 }
}
