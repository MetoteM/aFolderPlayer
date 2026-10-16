package dev.alex.folderplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Small bounded cover cache. No session headers, cookies, or tracking SDK. */
internal class OnlineArtwork(context:Context) {
    private val dir=File(context.cacheDir,"online-covers").apply { mkdirs() }
    fun load(raw:String):Bitmap?=runCatching {
        val uri=valid(raw) ?: return null
        val name=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val file=File(dir,name)
        var bytes=if(file.isFile && file.length()<=1_000_000) file.readBytes() else null
        if(bytes==null) {
            val c=URL(uri.toString()).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects=false;c.connectTimeout=7000;c.readTimeout=10000
                c.setRequestProperty("User-Agent","aFolderPlayer-Artwork/0.3")
                if(c.responseCode!=200)return null
                bytes=c.inputStream.use { input ->
                    val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192)
                    while(true) { val n=input.read(buf);if(n<0)break;require(out.size()+n<=1_000_000);out.write(buf,0,n) }
                    out.toByteArray()
                }
                file.writeBytes(bytes!!)
                var total=dir.listFiles()?.sumOf { it.length() } ?: 0
                for(old in dir.listFiles().orEmpty().sortedBy { it.lastModified() }) {
                    if(total<=20_000_000)break
                    val size=old.length();if(old.delete())total-=size
                }
            } finally { c.disconnect() }
        }
        val data=bytes!!
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(data,0,data.size,bounds)
        if(bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outWidth>10000 || bounds.outHeight>10000)return null
        var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>600)sample*=2
        BitmapFactory.decodeByteArray(data,0,data.size,BitmapFactory.Options().apply { inSampleSize=sample })
    }.getOrNull()
    companion object {
        fun valid(raw:String):Uri? {
            if(raw.isEmpty() || raw.length>2000)return null
            val uri=Uri.parse(raw);val h=uri.host.orEmpty().lowercase()
            return uri.takeIf { it.scheme=="https" && it.userInfo==null && it.fragment==null && it.port in listOf(-1,443) && listOf("yandex.net","yandex.ru").any { d -> h.endsWith(".$d") } }
        }
    }
}
