package dev.alex.folderplayer

import android.content.Context
import org.json.JSONObject

internal data class AlbumYear(val year:Int,val source:String,val mbid:String="",val title:String="",val artist:String="",val date:String="",val overridesTags:Boolean=false) {
 fun json()=JSONObject().put("year",year).put("source",source).put("mbid",mbid).put("title",title).put("artist",artist).put("date",date).put("override",overridesTags)
}
internal data class AlbumGroup(val key:String,val tracks:List<Track>) {
 val track get()=tracks.first()
 val title get()=track.albumName()
 val artist get()=track.albumArtist.ifBlank { tracks.map { it.artist }.distinct().singleOrNull().orEmpty() }
 val year get()=tracks.mapNotNull { it.year }.minOrNull()
}
internal fun albumGroups(tracks:List<Track>)=tracks.groupBy { it.albumIdentity() }.map { AlbumGroup(it.key,it.value) }.sortedWith { a,b -> naturalCompare(a.title,b.title) }
/** Only an explicitly separated prefix or bracketed suffix of the album folder, never file timestamps. */
internal fun folderAlbumYear(track:Track):Int? {
 val name=track.albumFolder().substringAfterLast('/')
 val prefix=Regex("^((?:19|20)[0-9]{2})(?:\\s*[-_.]\\s*|\\s+)(?=\\S)").find(name)?.groupValues?.get(1)
 val suffix=Regex("(?:\\((?:19|20)[0-9]{2}\\)|\\[(?:19|20)[0-9]{2}\\])$").find(name)?.value?.drop(1)?.dropLast(1)
 val years=listOfNotNull(prefix,suffix).distinct();return years.singleOrNull()?.toIntOrNull()
}
internal class AlbumYears(context:Context) {
 private val prefs=context.getSharedPreferences("album-years",Context.MODE_PRIVATE)
 fun get(key:String):AlbumYear?=runCatching {
  val o=JSONObject(prefs.getString(key,null) ?: return null);val year=o.getInt("year");if(year !in 1000..9999)return null
  AlbumYear(year,o.getString("source"),o.optString("mbid",""),o.optString("title",""),o.optString("artist",""),o.optString("date",""),o.optBoolean("override",false))
 }.getOrNull()
 fun save(key:String,value:AlbumYear) { require(value.year in 1000..9999);check(prefs.edit().putString(key,value.json().toString()).commit()) { "Не удалось сохранить год" } }
 fun remove(key:String) { check(prefs.edit().remove(key).commit()) { "Не удалось сбросить год" } }
 fun apply(tracks:List<Track>):List<Track> {
  val entries=tracks.map { it.albumIdentity() }.distinct().associateWith { get(it) }
  return tracks.map { track -> val saved=entries[track.albumIdentity()];track.copy(year=if(saved?.overridesTags==true) saved.year else track.year ?: folderAlbumYear(track) ?: saved?.year) }
 }
}
