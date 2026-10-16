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
            context.getSharedPreferences("online_actions",Context.MODE_PRIVATE).edit().clear().apply()
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
        write("pending-$id",JSONObject().put("id",id).put("tracks",rows))
        val old=context.filesDir.listFiles().orEmpty().filter { it.name.startsWith("ym-library-pending-") && it.name.endsWith(".json") }.sortedByDescending { it.lastModified() }
        old.filter { it.name!="ym-library-pending-$id.json" }.drop(19).forEach { it.delete() }
        return id
    }
    fun queue(id:String):String? {
        if(!id.matches(Regex("[0-9a-f-]{36}")))return null
        return (read("pending-$id") ?: read("queue")?.takeIf { it.optString("id")==id })?.optJSONArray("tracks")?.toString()
    }
    fun dropQueue(id:String) { if(id.matches(Regex("[0-9a-f-]{36}")))File(context.filesDir,"ym-library-pending-$id.json").delete() }
    companion object {
        fun playlistKey(reference:String):String = "pl-"+java.security.MessageDigest.getInstance("SHA-256").digest(reference.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }.take(40)
    }
}
