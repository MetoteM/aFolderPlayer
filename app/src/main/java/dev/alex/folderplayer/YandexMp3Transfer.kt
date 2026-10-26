package dev.alex.folderplayer

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.IOException
import java.util.concurrent.CancellationException

/** Bounded byte-for-byte export of the same full MP3 used for playback. */
internal object YandexMp3Transfer {
    const val MAX_BYTES=250_000_000L
    fun copy(input:InputStream,file:File,expected:Long,active:()->Boolean,network:()->Unit={},progress:(Long,Long)->Unit={_,_->}) {
        require(expected<=MAX_BYTES) { "Файл больше 250 МБ" }
        var count=0L
        fun checkActive() { if(!active() || Thread.currentThread().isInterrupted)throw CancellationException("Сохранение отменено") }
        try {
            file.outputStream().use { output ->
                val buffer=ByteArray(32768)
                while(true) {
                    checkActive();network()
                    val n=input.read(buffer);if(n<0)break
                    count+=n;require(count<=MAX_BYTES) { "Файл больше 250 МБ" }
                    output.write(buffer,0,n);progress(count,expected)
                }
            }
            checkActive()
            require(expected<0 || count==expected) { "Трек загружен не полностью" }
            validate(file)
        } catch(e:Exception) { file.delete();throw e }
    }
    private fun validate(file:File) {
        RandomAccessFile(file,"r").use { r ->
            require(r.length()>=4) { "Пустой или повреждённый MP3" }
            var offset=0L
            val first=ByteArray(3);r.readFully(first)
            if(String(first,Charsets.US_ASCII)=="ID3") {
                require(r.length()>=14) { "Повреждённый заголовок MP3" }
                val version=r.readUnsignedByte();r.readUnsignedByte();val flags=r.readUnsignedByte()
                require(version in 2..4) { "Неизвестный заголовок MP3" }
                var size=0L
                repeat(4) { val b=r.readUnsignedByte();require(b<128);size=(size shl 7)+b }
                offset=10+size+if(version==4 && flags and 16!=0)10 else 0
            }
            require(offset+4<=r.length()) { "В файле нет аудиоданных" }
            r.seek(offset);val a=r.readUnsignedByte();val b=r.readUnsignedByte();val c=r.readUnsignedByte()
            require(a==255 && b and 224==224 && b and 24!=8 && b and 6==2 && c shr 4 in 1..14 && c and 12!=12) { "Ответ сервиса не является MP3" }
        }
    }
}
