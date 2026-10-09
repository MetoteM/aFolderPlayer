package dev.alex.folderplayer

import android.app.Service
import android.content.Intent
import android.os.ParcelFileDescriptor
import dev.alex.afptranslation.ITranslationCallback
import dev.alex.afptranslation.ITranslationService
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Bound local component. No INTERNET permission, and no automatic startup. */
class TranslationService:Service() {
 private val worker=Executors.newSingleThreadExecutor { task -> Thread(task,"AFP-translate").apply { priority=Thread.MIN_PRIORITY } }
 private val jobs=ConcurrentHashMap<String,AtomicBoolean>()
 private val store by lazy { TranslationStore(this) }
 private val binder=object:ITranslationService.Stub() {
  override fun apiVersion()=1
  override fun modelReady(id:String)=id==OfflineTranslator.MODEL_ID && store.ready()
  override fun modelBytes(id:String)=if(id==OfflineTranslator.MODEL_ID)store.bytes() else 0
  override fun installModel(id:String,file:ParcelFileDescriptor,callback:ITranslationCallback) {
   require(id.length<=128)
   val owned=ParcelFileDescriptor.dup(file.fileDescriptor);file.close()
   worker.execute {
    try { ParcelFileDescriptor.AutoCloseInputStream(owned).use { store.install(it) };callback.complete(id,"") }
    catch(e:Exception) { runCatching { callback.failed(id,e.message ?: "Не удалось установить пакет") } }
   }
  }
  override fun translate(id:String,model:String,text:String,callback:ITranslationCallback) {
   require(id.length<=128 && model==OfflineTranslator.MODEL_ID && text.length<=20000)
   val cancel=AtomicBoolean(false);jobs.put(id,cancel)?.set(true)
   worker.execute {
    try {
     check(store.ready()) { "Языковой пакет не установлен" }
     val result=OfflineTranslator(store.model).use { engine -> engine.translate(text,{ cancel.get() }) { done,total -> if(!cancel.get())runCatching { callback.progress(id,done,total) } } }
     check(result.length<=200000) { "Перевод слишком большой" };if(!cancel.get())callback.complete(id,result)
    } catch(e:Throwable) { if(!cancel.get())runCatching { callback.failed(id,if(e is OutOfMemoryError)"Недостаточно памяти для перевода" else e.message ?: "Не удалось перевести") } }
    finally { jobs.remove(id,cancel) }
   }
  }
  override fun cancel(id:String) { jobs[id]?.set(true) }
  override fun removeModel(id:String) { require(id==OfflineTranslator.MODEL_ID);jobs.values.forEach { it.set(true) };worker.submit { store.removeModel() }.get(30,java.util.concurrent.TimeUnit.SECONDS) }
 }
 override fun onBind(intent:Intent)=binder
 override fun onDestroy() { jobs.values.forEach { it.set(true) };worker.shutdownNow();super.onDestroy() }
}
