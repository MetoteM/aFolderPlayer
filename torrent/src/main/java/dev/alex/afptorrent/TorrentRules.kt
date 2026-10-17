package dev.alex.afptorrent

import java.io.File
import java.util.Locale

internal object TorrentRules {
    const val MAX_METADATA=2*1024*1024
    const val MAX_FILES=200
    val audio=setOf("mp3","flac","wav","ogg","m4a","aac","opus")
    fun safePath(path:String):Boolean = path.isNotBlank() && path.length<=240 &&
        !path.startsWith('/') && '\\' !in path && ':' !in path && path.none { it.code<32 } &&
        path.split('/').all { it.isNotBlank() && it!="." && it!=".." && it.length<=120 }
    fun file(root:File,path:String):File {
        require(safePath(path)) { "Небезопасное имя файла в раздаче" }
        val target=File(root,path).canonicalFile
        require(target.path.startsWith(root.canonicalPath+File.separator))
        return target
    }
    fun music(path:String)=path.substringAfterLast('.',"").lowercase(Locale.ROOT) in audio
    fun allowed(connected:Boolean,wifi:Boolean,metered:Boolean,roaming:Boolean,wifiOnly:Boolean,allowRoaming:Boolean)=
        connected && (!wifiOnly || wifi && !metered) && (!roaming || allowRoaming)
    fun selectedSize(files:List<TorrentFile>,selected:Set<Int>,limit:Long):Long {
        require(selected.isNotEmpty()) { "Выбери хотя бы один аудиофайл" }
        require(selected.all { i -> files.any { it.index==i && it.audio } }) { "Недопустимый выбор файлов" }
        var total=0L
        files.filter { it.index in selected }.forEach { f ->
            require(f.size>0 && f.size<=limit-total) { "Выбрано больше установленного лимита места" };total+=f.size
        }
        return total
    }
}
internal data class TorrentFile(val index:Int,val path:String,val size:Long,val audio:Boolean)
