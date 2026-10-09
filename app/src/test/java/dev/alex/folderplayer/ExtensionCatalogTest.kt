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
}
