package dev.alex.folderplayer

import java.util.Locale

internal const val SORT_MANUAL=0
internal const val SORT_TITLE=1
internal const val SORT_FILENAME=2
internal const val SORT_ALBUM=3

/** Compare number runs by magnitude, without overflowing or renaming files. */
internal fun naturalCompare(left:String,right:String):Int {
 val a=left.lowercase(Locale.ROOT);val b=right.lowercase(Locale.ROOT);var i=0;var j=0
 while(i<a.length && j<b.length) {
  if(a[i] in '0'..'9' && b[j] in '0'..'9') {
   var ae=i;var be=j
   while(ae<a.length && a[ae] in '0'..'9') ae++
   while(be<b.length && b[be] in '0'..'9') be++
   var az=i;var bz=j
   while(az<ae && a[az]=='0') az++
   while(bz<be && b[bz]=='0') bz++
   val length=(ae-az).compareTo(be-bz);if(length!=0) return length
   val digits=a.substring(az,ae).compareTo(b.substring(bz,be));if(digits!=0) return digits
   i=ae;j=be
  } else { val c=a[i].compareTo(b[j]);if(c!=0) return c;i++;j++ }
 }
 return (a.length-i).compareTo(b.length-j)
}
internal fun tagNumber(value:String?):Int? = value?.trim()?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it>0 }
internal fun tagYear(value:String?):Int? = value?.trim()?.take(4)?.takeIf { it.length==4 && it.all { c -> c in '0'..'9' } }?.toIntOrNull()?.takeIf { it in 1000..9999 }
internal fun filenameTrackNumber(track:Track):Int? {
 fun number(value:String)=Regex("^\\s*([0-9]+)(?=$|[\\s._)\\-])").find(value)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it>0 }
 return number(track.filename.substringBeforeLast('.',track.filename)) ?: number(track.title)
}
internal fun Track.albumName()=album.ifBlank { folder.substringAfterLast('/').ifBlank { "Без альбома" } }
internal fun Track.albumFolder():String = if(album.isNotBlank() && Regex("(?i)^(?:cd|disc|disk|диск)[ _-]*[0-9]+$").matches(folder.substringAfterLast('/'))) folder.substringBeforeLast('/',folder) else folder
private data class AlbumKey(val name:String,val artist:String,val root:String,val folder:String)
private fun Track.albumKey()=AlbumKey(albumName().trim().lowercase(Locale.ROOT),albumArtist.trim().lowercase(Locale.ROOT),rootUri,albumFolder())
internal fun Track.albumIdentity():String = albumKey().let { org.json.JSONArray(listOf(it.name,it.artist,it.root,it.folder)).toString() }
internal fun playlistOrder(tracks:List<Track>,mode:Int):List<Track> = when(mode) {
 SORT_TITLE -> tracks.sortedWith { a,b -> naturalCompare(a.title,b.title) }
 SORT_FILENAME -> tracks.sortedWith { a,b -> naturalCompare(a.filename.ifBlank { a.title },b.filename.ifBlank { b.title }) }
 SORT_ALBUM -> tracks.groupBy { it.albumKey() }.values.sortedWith { a,b ->
  val year=(a.mapNotNull { it.year }.minOrNull() ?: Int.MAX_VALUE).compareTo(b.mapNotNull { it.year }.minOrNull() ?: Int.MAX_VALUE)
  if(year!=0) year else {
   val name=naturalCompare(a.first().albumName(),b.first().albumName())
   if(name!=0) name else {
    val artist=naturalCompare(a.first().albumArtist,b.first().albumArtist)
    if(artist!=0) artist else naturalCompare(a.first().albumFolder(),b.first().albumFolder())
   }
  }
 }.flatMap { album -> album.sortedWith { a,b ->
  val disc=(a.discNumber ?: 1).compareTo(b.discNumber ?: 1)
  if(disc!=0) disc else {
   val number=(a.trackNumber ?: filenameTrackNumber(a) ?: Int.MAX_VALUE).compareTo(b.trackNumber ?: filenameTrackNumber(b) ?: Int.MAX_VALUE)
   if(number!=0) number else {
    val file=naturalCompare(a.filename.ifBlank { a.title },b.filename.ifBlank { b.title })
    if(file!=0) file else naturalCompare(a.title,b.title)
   }
  }
 } }
 else -> tracks
}

internal data class MusicFolder(val root:String,val path:String) {
 fun contains(track:Track,recursive:Boolean)=track.rootUri==root && (track.folder==path || recursive && track.folder.startsWith("$path/"))
}
internal fun musicFolders(tracks:List<Track>):List<MusicFolder> {
 val folders=linkedSetOf<MusicFolder>()
 for(track in tracks) {
  val parts=track.folder.split('/').filter { it.isNotEmpty() }
  for(i in 1..parts.size) folders.add(MusicFolder(track.rootUri,parts.take(i).joinToString("/")))
 }
 return folders.sortedWith { a,b -> naturalCompare(a.path,b.path) }
}
