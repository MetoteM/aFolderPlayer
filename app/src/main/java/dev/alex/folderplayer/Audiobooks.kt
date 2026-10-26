package dev.alex.folderplayer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

internal data class BookChapter(val uri:String, val title:String, val duration:Long) {
    fun item(book:Audiobook)=MediaItem.Builder().setMediaId(uri).setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setAlbumTitle(book.title)
            .setArtist(book.title).setExtras(Bundle().apply { putString("bookId",book.id);putLong("onlineDuration",duration) }).build()).build()
}
internal data class Audiobook(val id:String, val root:String, val title:String, val chapters:List<BookChapter>)
internal data class BookPosition(val uri:String, val position:Long, val completed:Boolean=false)
internal data class BookBookmark(val id:String, val uri:String, val position:Long, val title:String)

/** Metadata changes never rewrite listening positions or bookmarks. */
internal class Audiobooks(context:Context) {
    private val prefs=context.getSharedPreferences("audiobooks",0)
    fun books():List<Audiobook> = synchronized(lock) {
        val raw=prefs.getString("books","[]")!!
        if(raw==cachedRaw && cachedBooks!=null)return@synchronized cachedBooks!!
        val parsed=runCatching {
            val rows=JSONArray(raw)
            (0 until rows.length()).map { i ->
                val row=rows.getJSONObject(i);val chapters=row.getJSONArray("chapters")
                Audiobook(row.getString("id"),row.getString("root"),row.getString("title"),
                    (0 until chapters.length()).map { j -> chapters.getJSONObject(j).let { BookChapter(it.getString("uri"),it.getString("title"),it.optLong("duration").coerceAtLeast(0)) } })
            }
        }.getOrDefault(emptyList())
        cachedRaw=raw;cachedBooks=parsed;parsed
    }
    fun book(id:String)=books().find { it.id==id }
    fun put(book:Audiobook) = synchronized(lock) {
        require(book.chapters.isNotEmpty())
        val all=books().filter { it.id!=book.id }+book
        val rows=all.map { b -> JSONObject().put("id",b.id).put("root",b.root).put("title",b.title)
            .put("chapters",JSONArray(b.chapters.map { JSONObject().put("uri",it.uri).put("title",it.title).put("duration",it.duration) })) }
        prefs.edit().putString("books",JSONArray(rows).toString()).apply()
    }
    fun remove(id:String)=synchronized(lock) {
        val remaining=books().filter { it.id!=id }
        val rows=remaining.map { b -> JSONObject().put("id",b.id).put("root",b.root).put("title",b.title)
            .put("chapters",JSONArray(b.chapters.map { JSONObject().put("uri",it.uri).put("title",it.title).put("duration",it.duration) })) }
        prefs.edit().putString("books",JSONArray(rows).toString()).remove("position:$id").remove("speed:$id").remove("marks:$id").apply()
    }
    fun position(book:Audiobook):BookPosition {
        val row=runCatching { JSONObject(prefs.getString("position:${book.id}","{}")!!) }.getOrDefault(JSONObject())
        val valid=book.chapters.any { it.uri==row.optString("uri") }
        val uri=row.optString("uri").takeIf { valid } ?: book.chapters.first().uri
        val duration=book.chapters.first { it.uri==uri }.duration
        val position=(if(valid)row.optLong("position") else 0L).coerceAtLeast(0).let { if(duration>0)it.coerceAtMost(duration) else it }
        return BookPosition(uri,position,valid && row.optBoolean("completed"))
    }
    fun savePosition(id:String,uri:String,position:Long,completed:Boolean=false) = synchronized(lock) {
        val b=book(id) ?: return@synchronized
        val chapter=b.chapters.find { it.uri==uri } ?: return@synchronized
        val pos=position.coerceAtLeast(0).let { if(chapter.duration>0)it.coerceAtMost(chapter.duration) else it }
        val raw=JSONObject().put("uri",uri).put("position",pos).put("completed",completed).toString()
        if(prefs.getString("position:$id",null)!=raw)prefs.edit().putString("position:$id",raw).apply()
    }
    fun speed(id:String)=prefs.getFloat("speed:$id",1f).coerceIn(.5f,2.5f)
    fun setSpeed(id:String,speed:Float) { require(speed.isFinite() && speed in .5f..2.5f);prefs.edit().putFloat("speed:$id",speed).apply() }
    fun bookmarks(id:String):List<BookBookmark> = runCatching {
        val rows=JSONArray(prefs.getString("marks:$id","[]"))
        (0 until rows.length()).map { rows.getJSONObject(it).let { BookBookmark(it.getString("id"),it.getString("uri"),it.getLong("position"),it.getString("title")) } }
    }.getOrDefault(emptyList())
    fun addBookmark(id:String,uri:String,position:Long,title:String) = synchronized(lock) {
        require(book(id)?.chapters?.any { it.uri==uri }==true)
        val marks=bookmarks(id)
        if(marks.any { it.uri==uri && it.position==position })return@synchronized
        require(marks.size<500) { "В книге уже 500 закладок" }
        writeBookmarks(id,marks+BookBookmark(UUID.randomUUID().toString(),uri,position.coerceAtLeast(0),title.take(200)))
    }
    fun removeBookmark(id:String,mark:String)=synchronized(lock) { writeBookmarks(id,bookmarks(id).filter { it.id!=mark }) }
    private fun writeBookmarks(id:String,marks:List<BookBookmark>) {
        prefs.edit().putString("marks:$id",JSONArray(marks.map { JSONObject().put("id",it.id).put("uri",it.uri).put("position",it.position).put("title",it.title) }).toString()).apply()
    }
    companion object {
        private val lock=Any()
        private var cachedRaw:String?=null
        private var cachedBooks:List<Audiobook>?=null
        val extensions=setOf("mp3","m4a","m4b","aac","flac","ogg","opus","wav")
        fun order(chapters:List<BookChapter>)=chapters.sortedWith { a,b -> naturalCompare(a.title,b.title) }
        /** Any unreadable directory aborts the import, preserving the previously imported book. */
        fun scan(context:Context,uri:Uri,folder:Boolean):Audiobook {
            val found=mutableListOf<BookChapter>();val visited=hashSetOf<String>()
            fun audio(document:LibraryDocument,path:String) {
                if(Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException()
                if(document.name.orEmpty().substringAfterLast('.').lowercase() !in extensions)return
                val reader=MediaMetadataRetriever()
                val duration=try {
                    reader.setDataSource(context,document.uri)
                    reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } finally { runCatching { reader.release() } }
                found.add(BookChapter(document.uri.toString(),path+document.name.orEmpty(),duration.coerceAtLeast(0)))
            }
            fun walk(parent:Uri,path:String,depth:Int) {
                if(Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException()
                if(depth>32)throw IOException("Слишком много вложенных папок")
                if(!visited.add(parent.toString()))return
                LibraryDocuments.children(context,parent).forEach { child ->
                    if(child.isDirectory)walk(child.uri,path+child.name.orEmpty()+"/",depth+1) else audio(child,path)
                    if(found.size>20000)throw IOException("В книге слишком много файлов")
                }
            }
            val document=if(folder)DocumentFile.fromTreeUri(context,uri) else DocumentFile.fromSingleUri(context,uri)
            if(document==null || !document.canRead())throw IOException("Нет доступа к книге")
            if(folder)walk(document.uri,"",0) else audio(LibraryDocument(uri,document.name,false,document.length()),"")
            if(found.isEmpty())throw IOException("В выбранном месте нет аудиофайлов")
            return Audiobook(uri.toString(),uri.toString(),document.name.orEmpty().ifBlank { "Аудиокнига" },order(found))
        }
    }
}
