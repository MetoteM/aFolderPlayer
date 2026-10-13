package dev.alex.folderplayer

import android.app.Activity
import android.content.Intent
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Unified access; removing a history link does not delete a music file or its local lyrics. */
internal object TextHistory {
 fun show(host:Activity) {
  data class Record(val id:String,val label:String,val query:String,val open:()->Unit)
  val records=mutableListOf<Record>();val prefs=host.getSharedPreferences("text-history",0)
  val lyrics=LyricsStore(host)
  for(track in Library(host).tracks())if(lyrics.exists(track.uri))records.add(Record("local:"+track.uri,"Оригинал · ${track.title} — ${track.artist}",track.title+" "+track.artist) {
   host.startActivity(Intent(host,SongTextActivity::class.java).putExtra("songTitle",track.title).putExtra("artist",track.artist).putExtra("source",lyrics.read(track.uri).orEmpty()).putExtra("localUri",track.uri))
  })
  for(entry in RecognizedTextStore(host).saved())records.add(Record("recognized:"+LyricsDocument.key(entry.cacheTitle+"\n"+entry.cacheArtist),"Распознано · ${entry.title} — ${entry.artist}",entry.title+" "+entry.artist) {
   host.startActivity(Intent(host,SongTextActivity::class.java).putExtra("songTitle",entry.title).putExtra("artist",entry.artist).putExtra("cacheTitle",entry.cacheTitle).putExtra("cacheArtist",entry.cacheArtist))
  })
  val archive=AtomicFile(File(host.filesDir,"full-document-history.json"))
  fun documents():JSONArray { val raw=try { archive.openRead().use { it.readBytes().toString(Charsets.UTF_8) } }catch(_:java.io.FileNotFoundException){null};val legacy=if(raw==null)File(host.filesDir,"full-document-last.json").takeIf { it.exists() }?.readText() else null;return DocumentHistory.load(raw,legacy) }
  val all=runCatching { documents() }.getOrDefault(JSONArray())
  for(i in all.length()-1 downTo 0) { val row=all.getJSONObject(i);val id=row.getString("id");val text=row.optString("source");records.add(Record("translation:"+id,"Перевод · "+text.replace('\n',' ').take(70),text+"\n"+row.optString("translation")) {
   host.startActivity(Intent(host,DocumentActivity::class.java).putExtra("historyId",id))
  }) }
  fun write(rows:JSONArray):Boolean=runCatching { val out=archive.startWrite();try { out.write(DocumentHistory.serialize(rows).toByteArray());archive.finishWrite(out) }catch(e:Exception){archive.failWrite(out);throw e};true }.getOrDefault(false)
  val removed=mutableMapOf<String,Pair<JSONObject,Int>>()
  HistoryDialog.show(host,"История текстов",{ records.filter { !prefs.getBoolean(it.id,false) }.map { HistoryDialog.Entry(it.id,it.label,it.query) } },{ id -> records.firstOrNull { it.id==id }?.open?.invoke() },{ id ->
   if(id.startsWith("translation:")) { val rows=documents();val key=id.removePrefix("translation:");val index=(0 until rows.length()).firstOrNull { rows.getJSONObject(it).getString("id")==key };if(index!=null)removed[id]=rows.getJSONObject(index) to index;if(index==null || !write(DocumentHistory.remove(rows,key)))false else prefs.edit().putBoolean(id,true).commit() }
   else prefs.edit().putBoolean(id,true).commit()
  },{ id ->
   val saved=removed[id];if(saved!=null && !write(DocumentHistory.restore(documents(),saved.first,saved.second)))false else prefs.edit().remove(id).commit()
  })
 }
}
