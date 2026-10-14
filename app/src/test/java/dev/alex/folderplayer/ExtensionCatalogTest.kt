package dev.alex.folderplayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.ByteArrayInputStream
import org.json.JSONObject
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ExtensionCatalogTest {
 private fun fixture()=File("../qa/catalog-test-signed.json").readBytes()
 private fun cert()=RuntimeEnvironment.getApplication().assets.open("catalog_certificate.der").use { it.readBytes() }
 @Test fun signedCatalogIsAcceptedAndUnsignedModificationRejected() {
  val c=ExtensionCatalog.verify(fixture(),cert(),1);assertEquals(1,c.version);assertEquals("engine",c.entries.single().id)
  val changed=JSONObject(String(fixture())).put("payload",java.util.Base64.getEncoder().encodeToString("{}".toByteArray())).toString().toByteArray()
  try { ExtensionCatalog.verify(changed,cert(),1);fail() } catch(_:java.io.IOException){}
 }
 @Test fun catalogRollbackIsRejected() { try { ExtensionCatalog.verify(fixture(),cert(),2);fail() } catch(_:java.io.IOException){} }
 @Test fun addressRequiresHttpsAndCannotCarryCredentials() {
  assertEquals("https://example.org/afp/",ExtensionCatalog.base("https://example.org/afp").toString())
  for(value in listOf("http://example.org/","https://user:password@example.org/","https://example.org/?token=1","https://example.org/#part"))try { ExtensionCatalog.base(value);fail(value) } catch(_:java.io.IOException){}
 }
 @Test fun downloadSizeIsBounded() { try { ExtensionCatalog.read(ByteArrayInputStream(ByteArray(21)),20);fail() } catch(_:java.io.IOException){} }

 @Test fun sharedModelUsesPinnedReleaseAndLegacyEntryUsesCatalogBase() {
  val item=JSONObject().put("id","en").put("kind","model").put("title","English").put("file","AFP_English_Russian_v2.zip").put("sha256","a".repeat(64)).put("size",100).put("model","opus-en-ru-v2")
  val base=java.net.URI("https://github.com/MetoteM/aFolderPlayer/releases/latest/download/")
  val legacy=ExtensionCatalog.Entry(item)
  assertEquals(base.resolve(legacy.file),legacy.address(base))
  val url="https://github.com/MetoteM/aFolderPlayer/releases/download/v0.8.0/AFP_English_Russian_v2.zip"
  assertEquals(java.net.URI(url),ExtensionCatalog.Entry(item.put("url",url)).address(base))
  for (bad in listOf(url.replace("https:","http:"), url.replace("MetoteM","Other"), url+"?secret=1",url.replace("AFP_English_Russian_v2.zip","wrong.zip"))) {
   try { ExtensionCatalog.Entry(item.put("url",bad));fail(bad) } catch(_:java.io.IOException) {}
  }
 }
}
