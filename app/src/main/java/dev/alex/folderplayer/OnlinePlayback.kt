package dev.alex.folderplayer

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import dev.alex.afponline.IOnlineService
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal fun onlinePackage(context: Context) = "dev.alex.afponline" + if(context.packageName.endsWith(".yandexlab")) ".yandexlab" else ""

internal interface OnlineCatalogue {
    fun installed():Boolean
    fun request(operation:String,argument:String=""):JSONObject
}

/** IPC carries catalogue metadata and short-lived URLs, never OAuth credentials. */
internal class OnlinePlayback(context: Context):OnlineCatalogue {
    class Problem(message:String):IOException(message)
    private val settings = OnlineSettings(context)
    private val connection = SignedExtensionConnection(context,onlinePackage(context),"dev.alex.afponline.OnlineService")
    override fun installed() = connection.installed()
    override fun request(operation:String, argument:String):JSONObject {
        if(operation!="status") settings.requireNetwork()
        return connection.use { binder ->
        val service=IOnlineService.Stub.asInterface(binder)
        if(service.apiVersion()!=3) throw Problem("Обнови плеер и AFP Online из одного тестового комплекта")
        val raw=service.yandex(operation,argument)
        if(raw.length>150000) throw Problem("Слишком большой ответ расширения")
        val result=JSONObject(raw)
        if(result.has("error")) throw Problem(result.optString("error").take(200))
        result
        }
    }
    fun resolve(uri:Uri):Uri {
        val id=trackId(uri) ?: throw Problem("Некорректный трек Яндекс Музыки")
        val result=request("streamOptions",JSONObject().put("id",id).put("quality",settings.quality()).toString())
        val out=Uri.parse(result.optString("url"));val host=out.host.orEmpty().lowercase()
        if(out.scheme!="https" || out.userInfo!=null || out.fragment!=null || out.port !in listOf(-1,443) ||
            !listOf("yandex.net","yandex.ru").any { host.endsWith(".$it") }) throw Problem("Расширение вернуло недопустимый аудиоадрес")
        return out
    }
    companion object {
        fun trackId(uri:Uri):String? = uri.lastPathSegment?.takeIf {
            uri.scheme=="afp-yandex" && uri.host=="track" && uri.pathSegments.size==1 && uri.query==null && uri.fragment==null && it.matches(Regex("[0-9]{1,20}"))
        }
        fun items(raw:String):List<MediaItem> {
            require(raw.length<=8_000_000)
            val rows=JSONArray(raw);require(rows.length() in 1..20000)
            return (0 until rows.length()).map { i ->
                val row=rows.getJSONObject(i);val id=row.getString("id");require(id.matches(Regex("[0-9]{1,20}")))
                MediaItem.Builder().setMediaId("afp-yandex://track/$id").setUri("afp-yandex://track/$id").setMimeType(MimeTypes.AUDIO_MPEG)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(row.optString("title").take(200)).setArtist(row.optString("artist").take(500)).setArtworkUri(OnlineArtwork.valid(row.optString("cover"))).setExtras(android.os.Bundle().apply { putLong("onlineDuration",row.optLong("duration").coerceAtLeast(0)) }).build()).build()
            }
        }
    }
}
