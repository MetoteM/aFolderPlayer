package dev.alex.folderplayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import dev.alex.afptranslation.*
import android.os.ParcelFileDescriptor
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class TranslationClientTest {
 @Test fun noEngineIsAValidOptionalConfiguration() { val client=TranslationClient(RuntimeEnvironment.getApplication());assertFalse(client.installed());try { client.translate("Hello",AtomicBoolean(false)) { _,_ -> };fail() } catch(_:TranslationClient.MissingEngine){} }
 private class Fake:ITranslationService.Stub() {
  var cancelled=false
  override fun apiVersion()=1;override fun modelReady(id:String)=true;override fun modelBytes(id:String)=1L
  override fun installModel(id:String,file:ParcelFileDescriptor,callback:ITranslationCallback){}
  override fun translate(id:String,model:String,text:String,callback:ITranslationCallback){}
  override fun cancel(id:String){cancelled=true};override fun removeModel(id:String){}
 }
 @Test fun callbackResultAndProgressCrossTheLocalProtocol() {
  val client=TranslationClient(RuntimeEnvironment.getApplication());val fake=Fake();var progress=0
  val method=TranslationClient::class.java.declaredMethods.single { it.name=="awaitResult" }.apply { isAccessible=true }
  val start:(String,ITranslationCallback)->Unit={id,cb -> cb.progress(id,1,2);cb.complete(id,"Привет") }
  val result=method.invoke(client,fake,AtomicBoolean(false),{done:Int,_:Int -> progress=done},start)
  assertEquals("Привет",result);assertEquals(1,progress);assertFalse(fake.cancelled)
 }
 @Test fun cancelledCompletedCallbackCannotReturnSuccess() {
  val client=TranslationClient(RuntimeEnvironment.getApplication());val fake=Fake();val cancel=AtomicBoolean(false)
  val method=TranslationClient::class.java.declaredMethods.single { it.name=="awaitResult" }.apply { isAccessible=true }
  val start:(String,ITranslationCallback)->Unit={id,cb -> cancel.set(true);cb.complete(id,"Не сохранять") }
  try { method.invoke(client,fake,cancel,{_:Int,_:Int -> },start);fail("cancelled callback must fail") }
  catch(e:java.lang.reflect.InvocationTargetException) { assertTrue(e.cause is InterruptedException) }
  assertTrue(fake.cancelled)
 }
}
