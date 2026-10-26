package dev.alex.folderplayer

import org.json.JSONArray
import org.json.JSONObject

/** Metadata pagination runs off the UI thread; a queue is committed only after completion. */
internal class OnlineCatalogueLoader(private val source:OnlineCatalogue) {
    fun page(operation:String,id:String,offset:Int=0,refresh:Boolean=false):JSONObject = source.request(operation,
        JSONObject().put("id",id).put("offset",offset).put("refresh",refresh).toString())
    fun complete(operation:String,id:String,initial:JSONObject?,active:()->Boolean,progress:(Int,Int)->Unit):JSONObject {
        var data=initial ?: page(operation,id)
        var tracks=JSONArray(data.optJSONArray("tracks")?.toString() ?: "[]")
        var total=data.optInt("total",tracks.length())
        progress(tracks.length(),total)
        val seen=mutableSetOf<Int>()
        while(data.has("next") && !data.isNull("next")) {
            check(active()) { "Подготовка отменена" }
            val offset=data.getInt("next")
            check(offset>=0 && seen.add(offset)) { "Сервис повторил страницу плейлиста" }
            check(tracks.length()<20000) { "Список больше 20 000 треков" }
            data=page(operation,id,offset)
            tracks=OnlineSection.merge(tracks,data.getJSONArray("tracks"))
            check(tracks.length()<=20000 && tracks.toString().length<=8_000_000) { "Список слишком большой" }
            total=data.optInt("total",tracks.length())
            progress(tracks.length(),total)
        }
        check(active()) { "Подготовка отменена" }
        return JSONObject().put("tracks",tracks).put("total",total).put("next",JSONObject.NULL)
    }
}
