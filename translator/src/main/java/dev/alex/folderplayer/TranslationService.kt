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
  override fun modelReady(id:String)=TranslationStore.supported(id) && store.ready(id)
  override fun modelBytes(id:String)=if(TranslationStore.supported(id))store.bytes(id) else 0
  override fun installModel(id:String,file:ParcelFileDescriptor,callback:ITranslationCallback) {
   require(id.length<=128)
   val owned=ParcelFileDescriptor.dup(file.fileDescriptor);file.close()
   worker.execute {
    try { val installed=ParcelFileDescriptor.AutoCloseInputStream(owned).use { store.install(it) };callback.complete(id,installed) }
    catch(e:Exception) { runCatching { callback.failed(id,e.message ?: "Не удалось установить пакет") } }
   }
  }
  override fun translate(id:String,model:String,text:String,callback:ITranslationCallback) {
   require(id.length<=128 && TranslationStore.supported(model) && text.length<=20000)
   val cancel=AtomicBoolean(false);jobs.put(id,cancel)?.set(true)
   worker.execute {
    try {
     if(cancel.get() || Thread.currentThread().isInterrupted)return@execute
     check(store.ready(model)) { "Языковой пакет не установлен" }
     var result=text
     val stages=ModelPackages.find(model)!!.stages
     for((index,stage) in stages.withIndex()) {
      check(!cancel.get()) { "Отменено" }
      result=OfflineTranslator(java.io.File(store.model(model),stage.directory),stage.padding).use { engine -> engine.translate(result,{ cancel.get() }) { done,total -> if(!cancel.get())runCatching { callback.progress(id,index*total+done,stages.size*total) } } }
     }
     check(result.length<=200000) { "Перевод слишком большой" };if(!cancel.get())callback.complete(id,result)
    } catch(e:Throwable) { if(!cancel.get())runCatching { callback.failed(id,if(e is OutOfMemoryError)"Недостаточно памяти для перевода" else e.message ?: "Не удалось перевести") } }
    finally { jobs.remove(id,cancel) }
   }
  }
  override fun cancel(id:String) { jobs[id]?.set(true) }
  override fun removeModel(id:String) { require(TranslationStore.supported(id));jobs.values.forEach { it.set(true) };worker.submit { store.removeModel(id) }.get(30,java.util.concurrent.TimeUnit.SECONDS) }
 }
 override fun onBind(intent:Intent)=binder
 override fun onDestroy() { jobs.values.forEach { it.set(true) };worker.shutdownNow();super.onDestroy() }
}
