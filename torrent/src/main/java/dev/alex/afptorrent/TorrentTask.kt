package dev.alex.afptorrent

import org.json.JSONArray
import org.json.JSONObject

internal class TorrentTask(val id:String) {
    var hash="";var title="Получение описания";var state="metadata";var detail="";var tree="";var wanted=false
    var files=emptyList<TorrentFile>();var selected=emptySet<Int>();var exported=linkedMapOf<Int,String>()
    var done=0L;var total=0L;var down=0;var up=0;var peers=0;var uploaded=0L;var seedSince=0L
    fun json(includeFiles:Boolean=false)=JSONObject().put("id",id).put("hash",hash).put("title",title).put("state",state).put("detail",detail)
        .put("tree",tree).put("wanted",wanted).put("done",done).put("total",total).put("down",down).put("up",up).put("peers",peers)
        .put("uploaded",uploaded).put("seedSince",seedSince).put("selected",JSONArray(selected.sorted()))
        .put("exportedCount",exported.size).also { o ->
            if(includeFiles)o.put("exported",JSONArray(exported.map { JSONObject().put("index",it.key).put("uri",it.value) }))
            if(includeFiles)o.put("files",JSONArray(files.map { JSONObject().put("index",it.index).put("path",it.path).put("size",it.size).put("audio",it.audio) }))
        }
    companion object {
        fun read(o:JSONObject)=TorrentTask(o.getString("id")).apply {
            require(id.matches(Regex("[0-9a-f-]{36}")))
            hash=o.optString("hash");title=o.optString("title");state=o.optString("state","paused");detail=o.optString("detail");tree=o.optString("tree")
            wanted=o.optBoolean("wanted");done=o.optLong("done");total=o.optLong("total");seedSince=o.optLong("seedSince")
            val f=o.optJSONArray("files")?:JSONArray();files=(0 until f.length()).map { f.getJSONObject(it).let { r->TorrentFile(r.getInt("index"),r.getString("path"),r.getLong("size"),r.getBoolean("audio")) } }
            val s=o.optJSONArray("selected")?:JSONArray();selected=(0 until s.length()).map { s.getInt(it) }.toSet()
            val e=o.optJSONArray("exported")?:JSONArray();(0 until e.length()).forEach { e.getJSONObject(it).let { r->exported[r.getInt("index")]=r.getString("uri") } }
            if(state in setOf("metadata","exporting")){wanted=false;state="paused";detail="Продолжи задачу после перезапуска"}
        }
    }
}
