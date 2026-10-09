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
 private val hashes=mapOf(
  "encoder.onnx" to "97f28d8295d231b7da721ded06fa8c034787df2122cd8f85b904abdc4d15bd53",
  "decoder.onnx" to "7e4e602c31130176b086cd4bf31d6d08d44a6a367617e910870e0ea3e5408574",
  "tokenizer.json" to "981cd3d9fef6bb4dda8082afda716b65deb6717cb3ef35ac0b57002c09dec2bb")
 fun ready()=File(model,"verified").isFile && hashes.keys.all { File(model,it).isFile }
 fun install(input:InputStream) {
  val stage=File(root,"staging");stage.deleteRecursively();check(stage.mkdirs())
  try {
   val found=mutableSetOf<String>();var total=0L
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
     hashes[entry.name]?.let { require(digest.digest().joinToString("") { "%02x".format(it) }==it) { "Языковой пакет повреждён" } }
    }
   }
   require(found.containsAll(hashes.keys)) { "В пакете нет всех файлов модели" }
   File(stage,"verified").writeText(OfflineTranslator.MODEL_ID)
   val old=File(root,"previous");old.deleteRecursively()
   if(model.exists())check(model.renameTo(old))
   if(!stage.renameTo(model)) { old.renameTo(model);error("Не удалось установить пакет") };old.deleteRecursively()
  } finally { stage.deleteRecursively() }
 }
 fun removeModel() { model.deleteRecursively() }
 fun bytes()=model.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
