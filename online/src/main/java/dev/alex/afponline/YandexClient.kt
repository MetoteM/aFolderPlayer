package dev.alex.afponline

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONArray
import org.json.JSONObject

/** Read-only laboratory: no login impersonation, retries, DRM or preview fallback. */
internal class YandexClient(private val token: String, private val transport: ((String, String?) -> String)? = null) {
    class Problem(val reason: String) : Exception(reason)
    data class Song(val id: String, val title: String, val artist: String)
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
                    if (out.size() + n > 2_000_000) throw Problem("Ответ слишком большой")
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
    private fun songs(rows: JSONArray): List<Song> = (0 until minOf(rows.length(),20)).mapNotNull { i ->
        val row = rows.optJSONObject(i) ?: return@mapNotNull null
        val id = row.optString("id")
        if (!id.matches(Regex("[0-9]{1,20}"))) return@mapNotNull null
        val artists = row.optJSONArray("artists") ?: JSONArray()
        Song(id, row.optString("title").take(200).ifEmpty { "Трек $id" },
            (0 until minOf(artists.length(),5)).joinToString(", ") { artists.optJSONObject(it)?.optString("name").orEmpty().take(100) })
    }
    fun search(query: String): List<Song> {
        if (query.isBlank() || query.length > 200) throw Problem("Введи запрос до 200 символов")
        val result = api("/search?text=${URLEncoder.encode(query,"UTF-8")}&type=track&page=0").getJSONObject("result")
        return songs(result.optJSONObject("tracks")?.optJSONArray("results") ?: JSONArray())
    }
    fun collection(): List<Song> {
        val user = uid ?: throw Problem("Сначала подтверди аккаунт")
        val rows = api("/users/$user/likes/tracks").getJSONObject("result").getJSONObject("library").getJSONArray("tracks")
        val ids = (0 until minOf(rows.length(),20)).mapNotNull { rows.optJSONObject(it)?.optString("id") }
            .filter { it.matches(Regex("[0-9]{1,20}")) }
        if (ids.isEmpty()) return emptyList()
        val result = api("/tracks?track-ids=${ids.joinToString(",")}").getJSONArray("result")
        return songs(result)
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
                val f = DocumentBuilderFactory.newInstance()
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                f.isExpandEntityReferences = false
                val doc = f.newDocumentBuilder().parse(xml.byteInputStream())
                fun value(tag: String) = doc.getElementsByTagName(tag).item(0)?.textContent ?: throw Problem("Неполный аудиодескриптор")
                val host=value("host"); val path=value("path"); val ts=value("ts"); val salt=value("s")
                if (!path.startsWith("/") || path.contains('?') || path.contains('#') || !ts.matches(Regex("[0-9]+"))) throw Problem("Некорректный аудиодескриптор")
                val sign=MessageDigest.getInstance("MD5").digest(("XGRlBW9FXlekgbPrRHuSiA"+path.drop(1)+salt).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
                return safeStream("https://$host/get-mp3/$sign/$ts$path")
            } catch(e: Problem) { throw e }
            catch (_: Exception) { throw Problem("Аудиодескриптор не распознан") }
        }
    }
}
