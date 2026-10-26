package dev.alex.folderplayer

import android.content.Context
import androidx.media3.common.MediaItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single rule for hiding online tracks from the playback queue. Used by the
 * service on restore, on queue commands and on queue rebuild so all three
 * paths can never disagree about what "hidden" means.
 */
internal fun List<MediaItem>.excludingHidden(hidden: Set<String>): List<MediaItem> =
    filter { OnlinePlayback.trackId(android.net.Uri.parse(it.mediaId)) !in hidden }

/** Local decisions never call like/dislike or playlist-write endpoints. Account-scoped by the store. */
internal class OnlineLibraryActions(context:Context) {
    private val store=OnlineLibraryStore(context)
    private val prefs=context.getSharedPreferences("online_actions",Context.MODE_PRIVATE)
    fun ids(kind:String):Set<String> = (prefs.all[kind] as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
    fun contains(kind:String,id:String)=id in ids(kind)
    fun rows(kind:String):JSONArray=store.read(kind)?.optJSONArray("tracks") ?: JSONArray()
    fun set(kind:String,rows:JSONArray,enabled:Boolean) = synchronized(lock) {
        require(kind in listOf("favorites","hidden"))
        val saved=linkedMapOf<String,JSONObject>()
        val old=this.rows(kind)
        for(i in 0 until old.length())old.optJSONObject(i)?.let { saved[it.optString("id")]=it }
        for(i in 0 until rows.length())rows.optJSONObject(i)?.let {
            val id=it.optString("id");require(id.matches(Regex("[0-9]{1,20}")))
            if(enabled)saved[id]=it else saved.remove(id)
        }
        store.write(kind,JSONObject().put("tracks",JSONArray(saved.values.toList())))
        prefs.edit().putStringSet(kind,saved.keys.toSet()).apply()
    }
    companion object { private val lock=Any() }
}

internal object OnlineRows {
    data class Row(val sourceIndex:Int,val song:JSONObject)
    fun display(rows:JSONArray,query:String,sort:Int,hidden:Set<String>,showHidden:Boolean=false):List<Row> {
        val q=query.trim()
        val result=(0 until rows.length()).mapNotNull { i -> rows.optJSONObject(i)?.let { Row(i,it) } }
            .filter { (showHidden || it.song.optString("id") !in hidden) &&
                (q.isBlank() || (it.song.optString("title")+" "+it.song.optString("artist")).contains(q,true)) }
        return when(sort) {
            1 -> result.sortedWith { a,b -> naturalCompare(a.song.optString("title"),b.song.optString("title")) }
            2 -> result.sortedWith { a,b -> val artist=naturalCompare(a.song.optString("artist"),b.song.optString("artist"));if(artist!=0)artist else naturalCompare(a.song.optString("title"),b.song.optString("title")) }
            3 -> result.sortedBy { it.song.optLong("duration") }
            else -> result
        }
    }
}
