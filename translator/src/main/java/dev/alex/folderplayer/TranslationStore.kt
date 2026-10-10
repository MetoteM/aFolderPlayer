package dev.alex.folderplayer

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** One audited, pinned offline package. No arbitrary downloaded models or executable files. */
internal class TranslationStore(context:Context) {
 private val root=File(context.filesDir,"translation").apply { mkdirs() }
 val model=File(root,OfflineTranslator.MODEL_ID)
 companion object { const val MODEL_V2="opus-en-ru-v2";fun supported(id:String)=id==OfflineTranslator.MODEL_ID || id==MODEL_V2 }
 fun model(id:String)=File(root,id)
 private val hashes=mapOf(
  "encoder.onnx" to "97f28d8295d231b7da721ded06fa8c034787df2122cd8f85b904abdc4d15bd53",
  "decoder.onnx" to "7e4e602c31130176b086cd4bf31d6d08d44a6a367617e910870e0ea3e5408574",
  "tokenizer.json" to "981cd3d9fef6bb4dda8082afda716b65deb6717cb3ef35ac0b57002c09dec2bb")
 fun ready(id:String=OfflineTranslator.MODEL_ID)=supported(id) && File(model(id),"verified").isFile && hashes.keys.all { File(model(id),it).isFile }
 private fun supported(id:String)=Companion.supported(id)
 fun install(input:InputStream) {
  val stage=File(root,"staging");stage.deleteRecursively();check(stage.mkdirs())
  try {
   val found=mutableSetOf<String>();var total=0L;var installedId=OfflineTranslator.MODEL_ID
   ZipInputStream(input).use { zip ->
    while(true) {
     val entry=zip.nextEntry ?: break
     require(!entry.isDirectory && entry.name in hashes.keys+setOf("NOTICE.txt","LICENSE.txt","manifest.json") && found.add(entry.name)) { "Не тот языковой пакет" }
     val digest=MessageDigest.getInstance("SHA-256");var size=0L
     File(stage,entry.name).outputStream().use { out ->
      val buffer=ByteArray(65536)
      while(true) { val n=zip.read(buffer);if(n<0)break;size+=n;total+=n
       require(total<=120_000_000 && size<=if(entry.name.endsWith(".onnx"))65_000_000 else if(entry.name=="tokenizer.json")8_000_000 else 65536) { "Языковой пакет слишком большой" }
       out.write(buffer,0,n);digest.update(buffer,0,n)
      }
     }
     hashes[entry.name]?.let { expected ->
      val actual=digest.digest().joinToString("") { "%02x".format(it) }
      if(entry.name=="tokenizer.json" && actual=="ab59614552269971b70f0bd5e966fe5adacf472cf9e08baf6841d41def76da35")installedId=MODEL_V2
      else require(actual==expected) { "Языковой пакет повреждён" }
     }
    }
   }
   require(found.containsAll(hashes.keys)) { "В пакете нет всех файлов модели" }
   val target=model(installedId)
   File(stage,"verified").writeText(installedId)
   val old=File(root,"previous");old.deleteRecursively()
   if(target.exists())check(target.renameTo(old))
   if(!stage.renameTo(target)) { old.renameTo(target);error("Не удалось установить пакет") };old.deleteRecursively()
  } finally { stage.deleteRecursively() }
 }
 fun removeModel(id:String=OfflineTranslator.MODEL_ID) { require(supported(id));model(id).deleteRecursively() }
 fun bytes(id:String=OfflineTranslator.MODEL_ID)=model(id).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
