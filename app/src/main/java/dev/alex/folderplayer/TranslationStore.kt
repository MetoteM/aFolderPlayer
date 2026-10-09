package dev.alex.folderplayer

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Translation cache only; executable engine and model weights are not part of the player. */
internal class TranslationStore(context:Context) {
 private val root=File(context.filesDir,"translation").apply { mkdirs() }
 private fun sourceKey(source:String,model:String)=LyricsDocument.key(model+"\n"+source)
 private fun cache(uri:String,model:String)=AtomicFile(File(File(root,"cache").apply { mkdirs() },LyricsDocument.key(uri)+(if(model=="opus-en-ru-v1")"" else "."+LyricsDocument.key(model))+".json"))
 fun read(uri:String,source:String,model:String="opus-en-ru-v1"):String? = try {
  cache(uri,model).openRead().use { val obj=JSONObject(String(it.readBytes(),Charsets.UTF_8));obj.getString("text").takeIf { obj.getString("source")==sourceKey(source,model) } }
 } catch(_:Exception) { null }
 fun save(uri:String,source:String,text:String,model:String="opus-en-ru-v1") {
  val file=cache(uri,model);val out=file.startWrite()
  try { out.write(JSONObject().put("source",sourceKey(source,model)).put("text",text).toString().toByteArray(Charsets.UTF_8));file.finishWrite(out) } catch(e:Exception) { file.failWrite(out);throw e }
 }
}
