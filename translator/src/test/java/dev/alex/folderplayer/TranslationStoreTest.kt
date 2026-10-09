package dev.alex.folderplayer

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class TranslationStoreTest {
 private fun setup():TranslationStore { val c=RuntimeEnvironment.getApplication() as Context;File(c.filesDir,"translation").deleteRecursively();return TranslationStore(c) }
 private fun zip(vararg names:String):ByteArray { val out=ByteArrayOutputStream();ZipOutputStream(out).use { zip -> for(name in names) { zip.putNextEntry(ZipEntry(name));zip.write("invalid".toByteArray());zip.closeEntry() } };return out.toByteArray() }
 @Test fun corruptedPackageCannotReplaceInstalledModel() {
  val store=setup();store.model.mkdirs();for(name in listOf("encoder.onnx","decoder.onnx","tokenizer.json","verified"))File(store.model,name).writeText("previous")
  assertTrue(store.ready());try { store.install(ByteArrayInputStream(zip("encoder.onnx")));fail("hash must be checked") } catch(_:IllegalArgumentException){}
  assertEquals("previous",File(store.model,"encoder.onnx").readText());assertTrue(store.ready())
 }
 @Test fun pathTraversalAndUnknownEntriesAreRejected() {
  val store=setup();for(name in listOf("../escaped","/absolute","folder/file","runtime.so")) {
   try { store.install(ByteArrayInputStream(zip(name)));fail(name) } catch(_:IllegalArgumentException){}
   assertFalse(store.ready());assertFalse(File(store.model.parentFile,"staging").exists())
  }
 }
 @Test fun missingFilesAndBrokenCachesDoNotClaimSuccess() {
  val store=setup();try { store.install(ByteArrayInputStream(zip("NOTICE.txt")));fail() } catch(_:IllegalArgumentException){}
  assertFalse(store.ready());
 }
 @Test fun deliveredPackageIsVerifiedAndInstalled() {
  val path=System.getenv("AFP_MODEL_PACKAGE")
  org.junit.Assume.assumeTrue("Set AFP_MODEL_PACKAGE to the distributed ZIP to run model integration",!path.isNullOrBlank())
  val store=setup();File(path!!).inputStream().use { store.install(it) }
  assertTrue(store.ready());assertEquals(51_628_446L,File(store.model,"encoder.onnx").length());assertEquals(58_560_874L,File(store.model,"decoder.onnx").length())
 }

}
