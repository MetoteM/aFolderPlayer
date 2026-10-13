package dev.alex.folderplayer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject

internal const val MIN_DURATION_MS = 30_000L
internal fun eligibleDuration(duration: Long) = duration >= MIN_DURATION_MS
internal data class Track(val uri: String, val title: String, val artist: String, val duration: Long, val folder: String, val filename: String="", val album:String="", val albumArtist:String="", val year:Int?=null, val trackNumber:Int?=null, val discNumber:Int?=null, val rootUri:String="") {
 fun item(): MediaItem = MediaItem.Builder().setMediaId(uri).setUri(uri)
  .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album.takeIf { it.isNotBlank() }).setAlbumArtist(albumArtist.takeIf { it.isNotBlank() }).setReleaseYear(year).setTrackNumber(trackNumber).setDiscNumber(discNumber).build()).build()
 fun json() = JSONObject().put("uri",uri).put("title",title).put("artist",artist).put("duration",duration).put("folder",folder).put("filename",filename).put("album",album).put("albumArtist",albumArtist).put("year",year).put("trackNumber",trackNumber).put("discNumber",discNumber).put("rootUri",rootUri)
}
internal class Library(private val context: Context) {
 private val prefs = context.getSharedPreferences("library",Context.MODE_PRIVATE)
 fun roots(): List<String> = strings(prefs.getString("roots","[]")!!)
 fun setRoots(roots: List<String>) { prefs.edit().putString("roots",JSONArray(roots).toString()).apply() }
 fun tracks(): List<Track> = runCatching {
  val a = JSONArray(prefs.getString("tracks","[]")); (0 until a.length()).map { i -> a.getJSONObject(i).let {
   Track(it.getString("uri"),it.getString("title"),it.getString("artist"),it.getLong("duration"),it.getString("folder"),it.optString("filename",""),it.optString("album",""),it.optString("albumArtist",""),it.optInt("year",0).takeIf { n -> n in 1000..9999 },it.optInt("trackNumber",0).takeIf { n -> n>0 },it.optInt("discNumber",0).takeIf { n -> n>0 },it.optString("rootUri",""))
  } }
 }.getOrDefault(emptyList()).let { AlbumYears(context).apply(it) }
 fun scan(): Pair<List<Track>, Int> {
  val found=linkedMapOf<String,Track>(); val visited=hashSetOf<String>(); var errors=0
  val extensions=setOf("mp3","flac","m4a","aac","ogg","opus","wav")
  fun walk(file: DocumentFile, folder: String, root:String) {
   if(!visited.add(file.uri.toString())) return
   if(file.isDirectory) {
    val children=runCatching { file.listFiles() }.getOrElse { errors++; emptyArray() }
    children.forEach { walk(it, "$folder/${file.name.orEmpty()}",root) }; return
   }
   val name=file.name ?: return
   if(name.substringAfterLast('.',"").lowercase() !in extensions) return
   val retriever=MediaMetadataRetriever()
   try {
    retriever.setDataSource(context,file.uri)
    val duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    if(duration==null || duration<=0) { errors++;return }
    if(!eligibleDuration(duration)) return
    val title=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.')
    val artist=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
    found[file.uri.toString()]=Track(file.uri.toString(),title,artist,duration,folder.trimStart('/'),name,
     retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().trim(),
     retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).orEmpty().trim(),
     readAlbumYear(retriever,name,file.uri),
     tagNumber(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
     tagNumber(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),root)
   } catch(_: Exception) { errors++ } finally { runCatching { retriever.release() } }
  }
  for(root in roots()) {
   val tree=DocumentFile.fromTreeUri(context,Uri.parse(root))
   if(tree == null || !tree.canRead()) { errors++; continue }
   walk(tree,"",root)
  }
  val sorted=found.values.sortedBy { it.title.lowercase() }
  prefs.edit().putString("tracks",JSONArray(sorted.map { it.json() }).toString()).apply()
  syncFolderPlaylists(sorted)
  return AlbumYears(context).apply(sorted) to errors
 }
 fun isRandom(source:String)=prefs.contains("randomOrder:$source")
 fun clearRandom(source:String) { prefs.edit().remove("randomOrder:$source").apply() }
 fun ordered(all:List<Track>,source:String,librarySort:Int):List<Track> {
  val ordered=playbackTracks(all,source.takeIf { it.isNotEmpty() }?.let { playlist(it) },librarySort,playlistSort(source))
  val saved=prefs.getString("randomOrder:$source",null) ?: return ordered
  val ranks=strings(saved).withIndex().associate { it.value to it.index }
  return ordered.sortedBy { ranks[it.uri] ?: Int.MAX_VALUE }
 }
 fun randomize(all:List<Track>,source:String,librarySort:Int) { val shuffled=ordered(all,source,librarySort).shuffled();prefs.edit().putString("randomOrder:$source",JSONArray(shuffled.map { it.uri }).toString()).apply() }
 fun playlistSort(name:String):Int = prefs.getInt("playlistSort:$name",SORT_MANUAL).coerceIn(0,3)
 fun setPlaylistSort(name:String,mode:Int) { require(mode in 0..3);prefs.edit().putInt("playlistSort:$name",mode).apply() }
 @Synchronized fun addFolder(name:String,folder:MusicFolder,recursive:Boolean,follow:Boolean=false):Int {
  val existing=playlist(name);val added=playlistOrder(tracks().filter { folder.contains(it,recursive) },SORT_ALBUM).map { it.uri }.filter { it !in existing }
  savePlaylist(name,existing+added)
  val data=folderLinks();val links=data.optJSONArray(name) ?: JSONArray();val updated=JSONArray()
  for(i in 0 until links.length()) {
   val link=links.getJSONObject(i)
   if(link.optString("root")!=folder.root || link.optString("path")!=folder.path)updated.put(link)
  }
  if(follow)updated.put(JSONObject().put("root",folder.root).put("path",folder.path).put("recursive",recursive).put("excluded",JSONArray()))
  data.put(name,updated);prefs.edit().putString("playlistFolders",data.toString()).apply()
  return added.size
 }
 private fun folderLinks()=runCatching { JSONObject(prefs.getString("playlistFolders","{}") ?: "{}") }.getOrDefault(JSONObject())
 /** Append new folder members; manual removals remain excluded and existing order stays intact. */
 @Synchronized internal fun syncFolderPlaylists(all:List<Track>):Int {
  val data=folderLinks();var total=0
  for(name in data.keys().asSequence().toList()) {
   if(name !in playlistNames())continue
   val current=playlist(name);val candidates=linkedSetOf<String>();val links=data.optJSONArray(name) ?: continue
   for(i in 0 until links.length()) {
    val link=links.getJSONObject(i);val folder=MusicFolder(link.getString("root"),link.getString("path"));val excluded=strings(link.optJSONArray("excluded")?.toString() ?: "[]").toSet()
    candidates.addAll(playlistOrder(all.filter { folder.contains(it,link.optBoolean("recursive")) && it.uri !in excluded },SORT_ALBUM).map { it.uri })
   }
   val added=candidates.filter { it !in current };if(added.isNotEmpty()){savePlaylist(name,current+added);total+=added.size}
  }
  return total
 }
 internal fun readAlbumYear(retriever:MediaMetadataRetriever,name:String,uri:Uri):Int? {
  val nativeYear=tagYear(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR))
  val extension=name.substringAfterLast('.',"").lowercase()
  if(extension!="flac" && extension!="mp3") return nativeYear
  // Original-release tags must be inspected even when Android supplies a reissue year.
  return runCatching { context.contentResolver.openInputStream(uri)?.use { input ->
   if(extension=="flac") FlacYearReader.readYear(input) ?: nativeYear else Id3YearReader.read(input,nativeYear)
  } }.getOrNull() ?: nativeYear
 }
 private fun lists() = runCatching { JSONObject(prefs.getString("playlists","{}") ?: "{}") }.getOrDefault(JSONObject())
 fun playlistNames(): List<String> = lists().keys().asSequence().toList().sorted()
 fun playlist(name: String): Set<String> = strings(lists().optJSONArray(name)?.toString() ?: "[]").toSet()
 @Synchronized fun savePlaylist(name: String, uris: Collection<String>) {
  val removed=playlist(name)-uris.toSet();val links=folderLinks();val rows=links.optJSONArray(name)
  if(rows!=null) {
   for(i in 0 until rows.length()) { val row=rows.getJSONObject(i);val excluded=strings(row.optJSONArray("excluded")?.toString() ?: "[]").toSet();row.put("excluded",JSONArray((excluded+removed)-uris.toSet())) }
   prefs.edit().putString("playlistFolders",links.toString()).apply()
  }
  val data=lists().put(name,JSONArray(uris)); prefs.edit().putString("playlists",data.toString()).apply()
 }
 @Synchronized fun renamePlaylist(old: String, name: String): Boolean {
  if(name.isBlank() || name=="Избранное" || old=="Избранное") return false
  val data=lists(); if(data.has(name) || !data.has(old)) return false
  data.put(name,data.getJSONArray(old)); data.remove(old)
  val links=folderLinks();if(links.has(old)){links.put(name,links.getJSONArray(old));links.remove(old);prefs.edit().putString("playlistFolders",links.toString()).apply()}
  val edit=prefs.edit().putString("playlists",data.toString()).putInt("playlistSort:$name",playlistSort(old)).remove("playlistSort:$old");prefs.getString("randomOrder:$old",null)?.let { edit.putString("randomOrder:$name",it) };edit.remove("randomOrder:$old").apply(); return true
 }
 @Synchronized fun removePlaylist(name: String) { val data=lists(); data.remove(name);val links=folderLinks();links.remove(name);prefs.edit().putString("playlistFolders",links.toString()).putString("playlists",data.toString()).remove("playlistSort:$name").remove("randomOrder:$name").apply() }
 fun toggleFavorite(track: Track) {
  val set=playlist("Избранное").toMutableSet(); if(!set.add(track.uri)) set.remove(track.uri); savePlaylist("Избранное",set)
 }
 companion object {
  fun strings(json: String): List<String> = runCatching { val a=JSONArray(json); (0 until a.length()).map { a.getString(it) } }.getOrDefault(emptyList())
 }
}
