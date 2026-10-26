package dev.alex.folderplayer

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException

/** ID3v2.3 text/APIC, with the original MPEG bytes copied unchanged. */
internal object Mp3Tags {
    private const val LIMIT=16_000_000
    private fun sync(bytes:ByteArray,offset:Int):Int {
        var n=0
        repeat(4) { val b=bytes[offset+it].toInt() and 255;require(b<128);n=(n shl 7)+b }
        return n
    }
    private fun size(n:Int)=byteArrayOf(((n shr 21) and 127).toByte(),((n shr 14) and 127).toByte(),((n shr 7) and 127).toByte(),(n and 127).toByte())
    private fun integer(bytes:ByteArray,offset:Int):Int {
        var n=0L;repeat(4) { n=(n shl 8)+(bytes[offset+it].toInt() and 255) };require(n<=LIMIT);return n.toInt()
    }
    private fun text(value:String)=byteArrayOf(1)+value.take(2000).replace('\u0000',' ').toByteArray(Charsets.UTF_16)
    fun write(source:File,target:File,title:String,artist:String,cover:ByteArray?,active:()->Boolean={ true }):Boolean {
        require(source.canonicalPath!=target.canonicalPath)
        require(cover==null || cover.size<=1_000_000)
        val frames=linkedMapOf<String,ByteArray>();var audioAt=0L
        RandomAccessFile(source,"r").use { r ->
            if(r.length()>=10) {
                val header=ByteArray(10);r.readFully(header)
                if(String(header,0,3,Charsets.US_ASCII)=="ID3") {
                    val version=header[3].toInt() and 255;val flags=header[5].toInt() and 255;val length=sync(header,6)
                    require(length<=LIMIT);audioAt=10L+length+if(version==4 && flags and 16!=0)10 else 0
                    require(audioAt<=r.length())
                    // Preserve ordinary uncompressed v2.3/v2.4 frames. Unsupported tag flags
                    // are not copied into the new tag; MPEG bytes are still preserved.
                    if(version in 3..4 && flags==0) {
                        val old=ByteArray(length);r.readFully(old);var p=0
                        while(p+10<=old.size) {
                            val id=String(old,p,4,Charsets.US_ASCII)
                            if(!id.matches(Regex("[A-Z0-9]{4}")))break
                            val n=if(version==4)sync(old,p+4) else integer(old,p+4)
                            if(n<=0 || n>old.size-p-10)break
                            if(old[p+8].toInt()==0 && old[p+9].toInt()==0) {
                                var body=old.copyOfRange(p+10,p+10+n)
                                if(version==4 && id.startsWith("T") && id!="TXXX" && body.isNotEmpty()) {
                                    val charset=when(body[0].toInt()) { 0->Charsets.ISO_8859_1;1->Charsets.UTF_16;2->Charsets.UTF_16BE;3->Charsets.UTF_8;else->null }
                                    if(charset!=null)body=text(String(body,1,body.size-1,charset).trimEnd('\u0000'))
                                }
                                if(version==3 || id!="APIC" || body.firstOrNull()?.toInt() in listOf(0,1))frames[id]=body
                            }
                            p+=10+n
                        }
                    }
                }
            }
        }
        if(title.isNotBlank())frames["TIT2"]=text(title)
        if(artist.isNotBlank())frames["TPE1"]=text(artist)
        if(cover!=null) {
            val mime=when { cover.size>=3 && cover[0]==0xff.toByte() && cover[1]==0xd8.toByte()->"image/jpeg";cover.size>=8 && cover.copyOfRange(0,8).contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))->"image/png";else->error("Некорректная обложка") }
            frames["APIC"]=byteArrayOf(0)+mime.toByteArray(Charsets.ISO_8859_1)+byteArrayOf(0,3,0)+cover
        }
        val body=ByteArrayOutputStream()
        DataOutputStream(body).use { out -> for((id,data) in frames) { require(body.size()+data.size+10<=LIMIT);out.write(id.toByteArray(Charsets.US_ASCII));out.writeInt(data.size);out.writeShort(0);out.write(data) } }
        fun check() { if(!active() || Thread.currentThread().isInterrupted)throw CancellationException("Сохранение отменено") }
        try {
            check()
            target.outputStream().use { out ->
                out.write("ID3".toByteArray(Charsets.US_ASCII));out.write(byteArrayOf(3,0,0));out.write(size(body.size()));out.write(body.toByteArray())
                RandomAccessFile(source,"r").use { input -> input.seek(audioAt);val buffer=ByteArray(32768)
                    while(true) { check();val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n) }
                }
            }
            check();return frames.containsKey("APIC")
        } catch(e:Exception) { target.delete();throw e }
    }
}
