package dev.alex.folderplayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class TranslationStoreTest {
 private fun setup():TranslationStore { val c=RuntimeEnvironment.getApplication();File(c.filesDir,"translation").deleteRecursively();return TranslationStore(c) }
 @Test fun cachedTranslationBelongsToTrackAndExactOriginal() {
  val store=setup();store.save("one","Hello\nworld","Привет\nмир")
  assertEquals("Привет\nмир",store.read("one","Hello\nworld"));assertNull(store.read("two","Hello\nworld"));assertNull(store.read("one","Hello world"))
  store.save("one","New words","Новые слова");assertNull(store.read("one","Hello\nworld"));assertEquals("Новые слова",store.read("one","New words"))
 }
 @Test fun differentLanguageModelsKeepIndependentTranslations() {
  val store=setup();store.save("song","hello","привет");store.save("song","hello","bonjour","en-fr-v1")
  assertEquals("привет",store.read("song","hello"));assertEquals("bonjour",store.read("song","hello","en-fr-v1"));assertNull(store.read("song","changed","en-fr-v1"))
 }
}
