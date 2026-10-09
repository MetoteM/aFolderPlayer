package dev.alex.folderplayer

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import dev.alex.afptranslation.ITranslationCallback
import dev.alex.afptranslation.ITranslationService
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class TranslationClient(private val context:Context) {
 companion object { const val PACKAGE="dev.alex.afptranslate";const val MODEL="opus-en-ru-v1" }
 private val prefs=context.getSharedPreferences("extensions",0)
 val modelId:String get()=prefs.getString("model",MODEL) ?: MODEL
 val modelTitle:String get()=prefs.getString("modelTitle","Английский → русский") ?: "Английский → русский"
 fun selectModel(id:String,title:String) { prefs.edit().putString("model",id).putString("modelTitle",title).apply() }
 class MissingEngine:Exception("Движок перевода не установлен. Открой расширения и установи AFP Translate")
 class MissingModel:Exception("Выбери и установи языковой пакет в расширениях")
 fun installed():Boolean = runCatching { context.packageManager.getApplicationInfo(PACKAGE,0).enabled && context.packageManager.checkSignatures(context.packageName,PACKAGE)==PackageManager.SIGNATURE_MATCH }.getOrDefault(false)
 private fun <T> connected(block:(ITranslationService)->T):T {
  if(!installed())throw MissingEngine()
  val ready=CountDownLatch(1);val remote=AtomicReference<ITranslationService?>()
  val connection=object:ServiceConnection {
   override fun onServiceConnected(name:ComponentName,binder:IBinder) { remote.set(ITranslationService.Stub.asInterface(binder));ready.countDown() }
   override fun onServiceDisconnected(name:ComponentName) { remote.set(null);ready.countDown() }
   override fun onNullBinding(name:ComponentName) { ready.countDown() }
   override fun onBindingDied(name:ComponentName) { remote.set(null);ready.countDown() }
  }
  val bound=context.bindService(Intent().setComponent(ComponentName(PACKAGE,"dev.alex.folderplayer.TranslationService")),connection,Context.BIND_AUTO_CREATE)
  try {
   check(bound && ready.await(15,TimeUnit.SECONDS)) { "Не удалось подключиться к движку перевода" }
   val service=remote.get() ?: error("Движок перевода отключился")
   check(service.apiVersion()==1) { "Нужна совместимая версия AFP Translate" };return block(service)
  } finally { if(bound)runCatching { context.unbindService(connection) } }
 }
 fun modelBytes()=connected { if(it.modelReady(modelId))it.modelBytes(modelId) else -1 }
 fun removeModel()=connected { it.removeModel(modelId) }
 fun install(file:ParcelFileDescriptor)=connected { service -> awaitResult(service,AtomicBoolean(false),{ _,_ -> }) { id,callback -> service.installModel(id,file,callback) };Unit }
 fun translate(text:String,cancel:AtomicBoolean,model:String=modelId,progress:(Int,Int)->Unit):String=connected { service ->
  if(!service.modelReady(model))throw MissingModel()
  awaitResult(service,cancel,progress) { id,callback -> service.translate(id,model,text,callback) }
 }
 private fun awaitResult(service:ITranslationService,cancel:AtomicBoolean,progress:(Int,Int)->Unit,start:(String,ITranslationCallback)->Unit):String {
  val id=UUID.randomUUID().toString();val finished=CountDownLatch(1);val value=AtomicReference<String?>();val failure=AtomicReference<String?>()
  val callback=object:ITranslationCallback.Stub() {
   override fun progress(requestId:String,done:Int,total:Int) { if(requestId==id && !cancel.get())progress(done,total) }
   override fun complete(requestId:String,text:String) { if(requestId==id) { value.set(text);finished.countDown() } }
   override fun failed(requestId:String,message:String) { if(requestId==id) { failure.set(message);finished.countDown() } }
  }
  start(id,callback)
  try {
   val deadline=SystemClock.elapsedRealtime()+30*60*1000
   while(!finished.await(250,TimeUnit.MILLISECONDS)) {
    if(cancel.get() || Thread.currentThread().isInterrupted)throw InterruptedException()
    check(service.asBinder().isBinderAlive) { "Движок перевода отключился" }
    check(SystemClock.elapsedRealtime()<deadline) { "Перевод не завершился вовремя" }
   }
   failure.get()?.let { error(it) };return value.get() ?: error("Нет результата перевода")
  } catch(e:Throwable) { runCatching { service.cancel(id) };throw e }
 }
}
