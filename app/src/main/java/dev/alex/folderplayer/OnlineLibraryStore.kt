package dev.alex.folderplayer

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** Private metadata only. OAuth credentials and temporary audio URLs never enter these files. */
internal class OnlineLibraryStore(private val context:Context) {
    private val prefs=context.getSharedPreferences("online_library",Context.MODE_PRIVATE)
    fun session()=prefs.getString("session","").orEmpty()
    fun setSession(key:String) {
        if(key!=session()) {
            context.filesDir.listFiles()?.filter { it.name.startsWith("ym-library-") }?.forEach { it.delete() }
            prefs.edit().putString("session",key).apply()
        }
    }
    fun write(key:String,data:JSONObject) {
        require(key.matches(Regex("[a-z0-9_-]{1,60}")))
        val text=data.toString();require(text.length<=8_000_000)
        val file=AtomicFile(File(context.filesDir,"ym-library-$key.json"))
        val out=file.startWrite()
        try { out.write(text.toByteArray());file.finishWrite(out) } catch(e:Exception) { file.failWrite(out);throw e }
    }
    fun read(key:String):JSONObject?=runCatching {
        require(key.matches(Regex("[a-z0-9_-]{1,60}")))
        val file=File(context.filesDir,"ym-library-$key.json")
        require(file.length()<=8_000_000)
        JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
    }.getOrNull()
    fun saveQueue(rows:JSONArray):String {
        val id=UUID.randomUUID().toString()
        write("queue",JSONObject().put("id",id).put("tracks",rows))
        return id
    }
    fun queue(id:String):String? = read("queue")?.takeIf { it.optString("id")==id }?.optJSONArray("tracks")?.toString()
}
