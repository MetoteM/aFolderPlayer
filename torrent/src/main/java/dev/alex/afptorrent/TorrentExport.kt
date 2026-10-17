package dev.alex.afptorrent

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InterruptedIOException
import java.util.UUID

internal object TorrentExport {
    fun copy(context:Context,root:File,tree:String,taskId:String,files:List<TorrentFile>,existing:Map<Int,String>,cancelled:()->Boolean,completed:(Int,String)->Unit) {
        val parent=DocumentFile.fromTreeUri(context,Uri.parse(tree)) ?: error("Выбери папку заново")
        check(parent.canWrite()) { "Нет доступа к папке назначения" }
        val albumName="AFP-${taskId.take(8)}"
        val album=parent.findFile(albumName) ?: parent.createDirectory(albumName) ?: error("Не удалось создать папку альбома")
        for(f in files) {
            if(f.index in existing)continue
            if(cancelled())throw InterruptedIOException()
            val source=TorrentRules.file(root,f.path);check(source.isFile && source.length()==f.size) { "Файл ещё не записан полностью" }
            var folder=album
            val components=f.path.split('/')
            components.dropLast(1).forEach { part->folder=folder.findFile(part) ?: folder.createDirectory(part) ?: error("Не удалось создать подпапку") }
            var name=components.last();var n=1
            while(folder.findFile(name)!=null){name=components.last().substringBeforeLast('.',components.last())+" (${n++})."+components.last().substringAfterLast('.')}
            val temp=folder.createFile("application/octet-stream",".afp-${UUID.randomUUID()}.partial") ?: error("Не удалось создать файл")
            var committed=false
            try {
                context.contentResolver.openOutputStream(temp.uri,"wt")!!.use { output ->
                    source.inputStream().use { input ->
                        val bytes=ByteArray(128*1024);var copied=0L
                        while(true){if(cancelled())throw InterruptedIOException();val count=input.read(bytes);if(count<0)break;output.write(bytes,0,count);copied+=count}
                        check(copied==f.size)
                    }
                }
                check(temp.length()==f.size) { "Папка не подтвердила размер сохранённого файла" }
                if(cancelled())throw InterruptedIOException()
                check(temp.renameTo(name)) { "Не удалось завершить сохранение файла" }
                committed=true;completed(f.index,temp.uri.toString())
            } finally { if(!committed)temp.delete() }
        }
    }
}
