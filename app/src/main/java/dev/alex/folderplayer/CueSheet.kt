package dev.alex.folderplayer

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset

internal data class CueEntry(val file:String,val number:Int,var title:String="",var artist:String="",var start:Long?=null)
internal data class CueSheet(val title:String,val artist:String,val year:Int?,val entries:List<CueEntry>) {
    companion object {
        const val MAX_BYTES=1024*1024
        fun decode(bytes:ByteArray):String {
            require(bytes.size<=MAX_BYTES) { "CUE больше 1 МиБ" }
            val charset=when {
                bytes.size>=2 && bytes[0]==0xff.toByte() && bytes[1]==0xfe.toByte()->Charsets.UTF_16LE
                bytes.size>=2 && bytes[0]==0xfe.toByte() && bytes[1]==0xff.toByte()->Charsets.UTF_16BE
                else->Charsets.UTF_8
            }
            return runCatching { charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
                .getOrElse { String(bytes,Charset.forName("windows-1251")) }.removePrefix("\uFEFF")
        }
        fun parse(text:String):CueSheet {
            require(text.length<=MAX_BYTES)
            var album="";var artist="";var year:Int?=null;var file="";var track:CueEntry?=null
            val tracks=mutableListOf<CueEntry>()
            fun value(s:String)=s.trim().let { if(it.startsWith('"'))it.removePrefix("\"").substringBeforeLast('"') else it }
            for(raw in text.removePrefix("\uFEFF").lineSequence()) {
                val line=raw.trim();val command=line.substringBefore(' ').substringBefore('\t').uppercase(java.util.Locale.ROOT)
                val rest=line.drop(command.length).trim()
                when(command) {
                    "FILE"->{
                        file=if(rest.startsWith('"'))rest.drop(1).substringBefore('"') else rest.substringBeforeLast(' ',"")
                        require(file.isNotBlank());track=null
                    }
                    "TRACK"->{
                        val parts=rest.split(Regex("\\s+"));require(parts.size>=2)
                        val n=parts[0].toIntOrNull();require(n!=null && n in 1..999 && file.isNotBlank())
                        require(parts[1].equals("AUDIO",true)) { "CUE содержит дорожку данных" }
                        require(tracks.size<1000 && tracks.none { it.number==n }) { "Повторяющиеся номера CUE" }
                        track=CueEntry(file,n);tracks.add(track)
                    }
                    "TITLE"->if(track==null)album=value(rest) else track.title=value(rest)
                    "PERFORMER"->if(track==null)artist=value(rest) else track.artist=value(rest)
                    "REM"->if(rest.startsWith("DATE ",true))year=tagYear(value(rest.drop(5)))
                    "INDEX"->{
                        val parts=rest.split(Regex("\\s+"))
                        if(parts.firstOrNull()=="01") {
                            val active=track ?: error("INDEX без TRACK");require(active.start==null && parts.size==2)
                            val time=requireNotNull(Regex("([0-9]{1,6}):([0-5][0-9]):([0-7][0-9])").matchEntire(parts[1])) { "Некорректный INDEX 01" }
                            val (m,s,f)=time.destructured;require(f.toInt()<75)
                            active.start=(m.toLong()*60+s.toLong())*1000+f.toLong()*1000/75
                        }
                    }
                }
            }
            require(tracks.isNotEmpty() && tracks.all { it.start!=null }) { "В CUE нет INDEX 01 для всех треков" }
            return CueSheet(album,artist,year,tracks)
        }
    }
}

/** Paths are resolved only against already-scanned files inside the same SAF root. */
internal object CueTracks {
    fun expand(sheet:CueSheet,cueUri:String,folder:String,root:String,audio:List<Track>):List<Track> {
        val resolved=sheet.entries.map { entry ->
            val path=entry.file.replace('\\','/').removePrefix("./")
            require(!path.startsWith('/') && ':' !in path && path.split('/').all { it.isNotBlank() && it!=".." && it!="." }) { "Небезопасный FILE в CUE" }
            val wanted=folder.trimEnd('/')+"/"+path
            val candidates=audio.filter { it.rootUri==root }
            val exact=candidates.filter { (it.folder+"/"+it.filename).equals(wanted,true) }
            // CD rip sheets often keep FILE "album.wav" after lossless FLAC encoding.
            val matches=if(exact.isNotEmpty())exact else if(path.endsWith(".wav",true))candidates.filter {
                (it.folder+"/"+it.filename).substringBeforeLast('.').equals(wanted.substringBeforeLast('.'),true)
            } else emptyList()
            require(matches.size==1) { "Аудиофайл из CUE не найден однозначно: ${entry.file}" }
            matches.single()
        }
        val result=mutableListOf<Track>()
        for((file,indices) in resolved.indices.groupBy { resolved[it] }) {
            val starts=indices.map { sheet.entries[it].start!! }
            require(starts.zipWithNext().all { (a,b)->a<b } && starts.all { it>=0 && it<file.duration }) { "Границы CUE выходят за аудиофайл" }
            for((position,index) in indices.withIndex()) {
                val entry=sheet.entries[index];val start=starts[position];val end=starts.getOrNull(position+1) ?: file.duration
                result.add(file.copy(uri=file.sourceUri+"#afp-cue=$start",title=entry.title.ifBlank { "Трек ${entry.number}" },
                    artist=entry.artist.ifBlank { sheet.artist.ifBlank { file.artist } },duration=end-start,
                    album=sheet.title.ifBlank { file.album },albumArtist=sheet.artist.ifBlank { file.albumArtist },year=sheet.year ?: file.year,
                    trackNumber=entry.number,sourceUri=file.sourceUri,cueStartMs=start,cueEndMs=end,cueSheetUri=cueUri))
            }
        }
        return result.sortedBy { it.trackNumber }
    }
}
