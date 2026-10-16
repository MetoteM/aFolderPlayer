package dev.alex.afponline

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.json.JSONArray
import org.json.JSONObject

/** Read-only laboratory: no login impersonation, retries, DRM or preview fallback. */
internal class YandexClient(private val token: String, private val transport: ((String, String?) -> String)? = null) {
    class Problem(val reason: String) : Exception(reason)
    data class Song(val id: String, val title: String, val artist: String, val duration: Long = 0, val cover: String = "", val available: Boolean = true) {
        fun json() = JSONObject().put("id",id).put("title",title).put("artist",artist).put("duration",duration).put("cover",cover).put("available",available)
    }
    data class Page(val tracks:List<Song>,val total:Int,val next:Int?) {
        fun json() = JSONObject().put("tracks",JSONArray(tracks.map { it.json() })).put("total",total).put("next",next ?: JSONObject.NULL)
    }
    private var likedIds:List<String>? = null
    private var likedAt = 0L
    private val playlistRows = mutableMapOf<String, Pair<Long,JSONArray>>()
    @Volatile private var connection: HttpURLConnection? = null
    private var uid: String? = null
    init {
        if (token.length > 8192 || token.any { it.code !in 33..126 }) throw Problem("Некорректный формат токена")
    }
    fun cancel() { connection?.disconnect() }
    private fun fetch(url: String, auth: Boolean): String {
        transport?.let { return it(url, if (auth && token.isNotEmpty()) token else null) }
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        connection = c
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "aFolderPlayer-Yandex-Lab/0.2")
            if (auth && token.isNotEmpty()) c.setRequestProperty("Authorization", "OAuth $token")
            val code = c.responseCode
            if (code != 200) throw Problem(when(code) {
                401 -> "HTTP 401 · авторизация отклонена"
                403, 418 -> "HTTP $code · доступ ограничен сервисом"
                451 -> "HTTP 451 · ограничение сервиса; причина не установлена"
                429 -> "HTTP 429 · слишком много запросов"
                else -> "HTTP $code · запрос не выполнен"
            })
            return c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw Problem("Отменено")
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > 8_000_000) throw Problem("Ответ слишком большой")
                    out.write(buffer, 0, n)
                }
                out.toString("UTF-8")
            }
        } catch(e: Problem) { throw e }
        catch (_: Exception) { throw Problem("Ошибка сети или формата ответа") }
        finally { connection = null; c.disconnect() }
    }
    private fun api(path: String): JSONObject {
        try {
            val obj = JSONObject(fetch("https://api.music.yandex.net$path", true))
            if (obj.has("error") || obj.has("errors") || !obj.has("result")) throw Problem("API отклонил запрос или изменил формат")
            return obj
        } catch(e: Problem) { throw e }
        catch (_: Exception) { throw Problem("Ответ API не распознан; возможна страница защиты") }
    }
    fun checkSession() {
        uid = null
        if (token.isEmpty()) throw Problem("Для проверки аккаунта нужен OAuth-токен")
        val value = api("/account/status").optJSONObject("result")?.optJSONObject("account")?.optString("uid").orEmpty()
        if (!value.matches(Regex("[1-9][0-9]{0,19}"))) throw Problem("HTTP 200, но вход в аккаунт не подтверждён")
        uid = value
    }
    private fun songs(rows: JSONArray): List<Song> = (0 until minOf(rows.length(),50)).mapNotNull { i ->
        val row = rows.optJSONObject(i) ?: return@mapNotNull null
        val id = row.optString("id")
        if (!id.matches(Regex("[0-9]{1,20}"))) return@mapNotNull null
        val artists = row.optJSONArray("artists") ?: JSONArray()
        Song(id, row.optString("title").take(200).ifEmpty { "Трек $id" },
            (0 until minOf(artists.length(),5)).joinToString(", ") { artists.optJSONObject(it)?.optString("name").orEmpty().take(100) },
            row.optLong("durationMs",0).coerceAtLeast(0),
            coverUrl(row.optString("coverUri").ifBlank { row.optJSONArray("albums")?.optJSONObject(0)?.optString("coverUri").orEmpty() }),
            row.optBoolean("available",true))
    }
    fun search(query: String): List<Song> {
        if (query.isBlank() || query.length > 200) throw Problem("Введи запрос до 200 символов")
        val result = api("/search?text=${URLEncoder.encode(query,"UTF-8")}&type=track&page=0").getJSONObject("result")
        return songs(result.optJSONObject("tracks")?.optJSONArray("results") ?: JSONArray())
    }
    fun collection(): List<Song> = collectionPage(0).tracks
    fun collectionPage(offset:Int, refresh:Boolean=false):Page {
        val user=uid ?: throw Problem("Сначала подтверди аккаунт")
        if(offset < 0) throw Problem("Некорректная страница")
        if(refresh || likedIds==null || (offset==0 && System.currentTimeMillis()-likedAt>300_000)) {
            val rows=api("/users/$user/likes/tracks").getJSONObject("result").getJSONObject("library").getJSONArray("tracks")
            likedIds=(0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.optString("id") }
                .filter { it.matches(Regex("[0-9]{1,20}")) }
            likedAt=System.currentTimeMillis()
        }
        val ids=likedIds!!
        val page=ids.drop(offset).take(50)
        if(page.isEmpty()) return Page(emptyList(),ids.size,null)
        val found=songs(api("/tracks?track-ids=${page.joinToString(",")}").getJSONArray("result")).associateBy { it.id }
        return Page(page.mapNotNull { found[it] },ids.size,(offset+page.size).takeIf { it<ids.size })
    }
    fun playlists():JSONArray {
        val user=uid ?: throw Problem("Сначала подтверди аккаунт")
        val rows=api("/users/$user/playlists/list").getJSONArray("result")
        val result=JSONArray()
        for(i in 0 until rows.length()) {
            val row=rows.optJSONObject(i) ?: continue
            val kind=row.optString("kind")
            if(kind.matches(Regex("[0-9]{1,20}"))) result.put(JSONObject().put("id",kind).put("title",row.optString("title").take(200)).put("count",row.optInt("trackCount",0)))
        }
        return result
    }
    fun playlistPage(kind:String,offset:Int,refresh:Boolean=false):Page {
        val user=uid ?: throw Problem("Сначала подтверди аккаунт")
        if(!kind.matches(Regex("[0-9]{1,20}")) || offset<0) throw Problem("Некорректный плейлист")
        val cached=playlistRows[kind]
        val rows=if(!refresh && cached!=null && System.currentTimeMillis()-cached.first<300_000) cached.second else {
            val list=api("/users/$user/playlists/$kind").getJSONObject("result").getJSONArray("tracks")
            if(playlistRows.size>=5) playlistRows.clear()
            playlistRows[kind]=System.currentTimeMillis() to list
            list
        }
        val end=minOf(rows.length(),offset+50)
        val full=JSONArray();val ids=mutableListOf<String>()
        for(i in offset until end) {
            val row=rows.optJSONObject(i) ?: continue
            val track=row.optJSONObject("track")
            if(track!=null) full.put(track) else row.optString("id").takeIf { it.matches(Regex("[0-9]{1,20}")) }?.let { ids+=it }
        }
        val fetched=if(ids.isEmpty()) emptyList() else songs(api("/tracks?track-ids=${ids.joinToString(",")}").getJSONArray("result"))
        val byId=(songs(full)+fetched).associateBy { it.id }
        val ordered=(offset until end).mapNotNull { i ->
            val row=rows.optJSONObject(i);val id=row?.optJSONObject("track")?.optString("id") ?: row?.optString("id")
            byId[id]
        }
        return Page(ordered,rows.length(),end.takeIf { it<rows.length() })
    }
    fun stream(id: String): URI {
        if (uid == null) throw Problem("Сначала подтверди аккаунт; поиск не подтверждает вход")
        if (!id.matches(Regex("[0-9]{1,20}"))) throw Problem("Некорректный трек")
        val rows = api("/tracks/$id/download-info").getJSONArray("result")
        val descriptor = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .filter { it.optString("codec") == "mp3" && it.has("preview") && !it.optBoolean("preview", true) }
            .minByOrNull { it.optInt("bitrateInKbps", 9999) }
            ?: throw Problem("Полный MP3 не предоставлен; preview и зашифрованные форматы не используются")
        val url = safeStream(descriptor.optString("downloadInfoUrl"))
        val xml = fetch(url.toString(), false)
        return resolve(xml)
    }
    companion object {
        fun coverUrl(raw:String):String {
            if(raw.isEmpty()) return ""
            val text=if(raw.startsWith("https://")) raw else "https://$raw"
            return runCatching { safeStream(text.replace("%%","300x300")).toString() }.getOrDefault("")
        }

        fun safeStream(raw: String): URI {
            val uri = try { URI(raw) } catch (_: Exception) { throw Problem("Некорректный адрес аудио") }
            val host = uri.host.orEmpty().lowercase()
            if (raw.length > 8192 || uri.scheme != "https" || uri.userInfo != null || uri.fragment != null || uri.port !in listOf(-1,443) ||
                !listOf("yandex.net","yandex.ru").any { host.endsWith(".$it") }) throw Problem("Аудиоадрес вне разрешённых HTTPS-доменов Яндекса")
            return uri
        }
        fun resolve(xml: String): URI {
            try {
                if (xml.contains("<!DOCTYPE", true) || xml.contains("<!ENTITY",true)) throw Problem("Небезопасный XML")
                if (xml.trimStart().startsWith("<html", true) || xml.contains("<!doctype html",true))
                    throw Problem("Вместо аудиодескриптора получена HTML-страница")
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(xml.reader())
                val fields = mutableMapOf<String, String>()
                val expected = setOf("host", "path", "ts", "s")
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name in expected) {
                        val key = parser.name
                        if (fields.containsKey(key)) throw Problem("Аудиодескриптор содержит повторяющиеся поля")
                        fields[key] = parser.nextText().trim()
                    }
                    event = parser.next()
                }
                fun value(tag: String) = fields[tag]?.takeIf { it.isNotEmpty() }
                    ?: throw Problem("В аудиодескрипторе отсутствует поле $tag")
                val host=value("host"); val path=value("path"); val ts=value("ts"); val salt=value("s")
                if (!path.startsWith("/") || path.contains('?') || path.contains('#') || !ts.matches(Regex("[A-Fa-f0-9]{1,64}"))) throw Problem("Некорректный аудиодескриптор")
                val sign=MessageDigest.getInstance("MD5").digest(("XGRlBW9FXlekgbPrRHuSiA"+path.drop(1)+salt).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
                return safeStream("https://$host/get-mp3/$sign/$ts$path")
            } catch(e: Problem) { throw e }
            catch (_: Exception) { throw Problem("Аудиодескриптор: некорректный XML") }
        }
    }
}
