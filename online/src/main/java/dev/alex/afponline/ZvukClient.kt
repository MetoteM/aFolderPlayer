package dev.alex.afponline

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/** Experimental read-only web API. No account mutations or automatic fallback login. */
internal class ZvukClient(
    private val token: String,
    private val transport: ((String, String?, String) -> String)? = null,
) {
    class Problem(val reason: String) : Exception(reason)

    data class Song(val id: String, val title: String, val artist: String)

    @Volatile private var connection: HttpURLConnection? = null

    init {
        if (token.isEmpty() || token.length > 8192 || token.any { it.code !in 33..126 })
            throw Problem("Некорректный формат токена")
    }

    fun cancel() {
        connection?.disconnect()
    }

    private fun request(path: String, body: JSONObject? = null): JSONObject {
        val text = transport?.invoke(path, body?.toString(), token) ?: fetch(path, body?.toString())
        return try {
            val data = JSONObject(text)
            if (data.has("errors") || data.optBoolean("error", false))
                throw Problem("Сервис отклонил запрос. Проверь доступ и сессию")
            data
        } catch (e: Problem) {
            throw e
        } catch (_: Exception) {
            throw Problem("Ответ не распознан: возможна защита сервиса или изменение API")
        }
    }

    private fun fetch(path: String, body: String?): String {
        val c = URI("https://zvuk.com$path").toURL().openConnection() as HttpURLConnection
        connection = c
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "aFolderPlayer-Online-Lab/0.1")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("X-Auth-Token", token)
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            when (val status = c.responseCode) {
                200 -> Unit
                401 -> throw Problem("Сессия отклонена. Получи новый токен после входа в Звук")
                403,
                418 -> throw Problem("Доступ ограничен или сработала защита сервиса (HTTP $status)")
                429 -> throw Problem("Слишком много запросов. Попробуй позже")
                else -> throw Problem("Сервис недоступен (HTTP $status)")
            }
            return c.inputStream.use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw Problem("Отменено")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > 2_000_000)
                        throw Problem("Ответ сервиса слишком большой")
                    bytes.write(buffer, 0, count)
                }
                bytes.toString("UTF-8")
            }
        } catch (e: Problem) {
            throw e
        } catch (_: Exception) {
            throw Problem("Не удалось подключиться к Звуку. Проверь сеть")
        } finally {
            connection = null
            c.disconnect()
        }
    }

    private fun gql(name: String, query: String, variables: JSONObject = JSONObject()): JSONObject {
        val response =
            request(
                "/api/v1/graphql",
                JSONObject()
                    .put("operationName", name)
                    .put("query", query)
                    .put("variables", variables),
            )
        return response.optJSONObject("data") ?: throw Problem("Изменился ответ музыкального API")
    }

    fun checkSession() {
        val profile = request("/api/tiny/profile").optJSONObject("result")
        if (
            profile == null ||
                !profile.has("is_anonymous") ||
                profile.optBoolean("is_anonymous", true)
        )
            throw Problem("Пользовательская сессия не подтверждена")
    }

    /** This only proves a readable music API response, not account identity or subscription. */
    fun checkMusicApi() {
        val tracks =
            gql("userCollection", "query userCollection { collection { tracks { id } } }")
                .optJSONObject("collection")
                ?.optJSONArray("tracks")
        if (tracks == null) throw Problem("Музыкальный API не подтвердил чтение коллекции")
    }

    private fun songs(items: JSONArray): List<Song> =
        (0 until minOf(items.length(), 30)).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            val title = item.optString("title").take(200)
            if (!id.matches(Regex("[0-9]{1,20}")) || title.isBlank()) return@mapNotNull null
            val artists = item.optJSONArray("artists") ?: JSONArray()
            val artist =
                (0 until minOf(artists.length(), 5))
                    .mapNotNull { artists.optJSONObject(it)?.optString("title")?.take(100) }
                    .joinToString(", ")
            Song(id, title, artist)
        }

    fun search(query: String): List<Song> {
        val q = query.trim().take(200)
        if (q.isEmpty()) throw Problem("Введи название песни или исполнителя")
        val data =
            gql(
                "quickSearch",
                "query quickSearch(\$query: String, \$limit: Int) { quickSearch(query: \$query, limit: \$limit) { content { __typename ... on Track { id title artists { title } } } } }",
                JSONObject().put("query", q).put("limit", 20),
            )
        val content =
            data.optJSONObject("quickSearch")?.optJSONArray("content")
                ?: throw Problem("Изменился ответ поиска")
        val tracks = JSONArray()
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            if (item.optString("__typename") == "Track") tracks.put(item)
        }
        return songs(tracks)
    }

    fun collection(): List<Song> {
        val collection =
            gql("userCollection", "query userCollection { collection { tracks { id } } }")
                .optJSONObject("collection")
                ?.optJSONArray("tracks") ?: throw Problem("Коллекция не прочитана")
        if (collection.length() == 0) return emptyList()
        val ids = JSONArray()
        for (i in 0 until minOf(collection.length(), 20)) {
            val id = collection.optJSONObject(i)?.optString("id").orEmpty()
            if (id.matches(Regex("[0-9]{1,20}"))) ids.put(id)
        }
        if (ids.length() == 0) throw Problem("Изменился формат коллекции")
        val tracks =
            gql(
                    "getTracks",
                    "query getTracks(\$ids: [ID!]!) { getTracks(ids: \$ids) { id title artists { title } } }",
                    JSONObject().put("ids", ids),
                )
                .optJSONArray("getTracks") ?: throw Problem("Треки не прочитаны")
        return songs(tracks)
    }

    fun stream(id: String): URI {
        if (!id.matches(Regex("[0-9]{1,20}"))) throw Problem("Некорректный трек")
        val data =
            request("/api/tiny/track/stream?id=${URLEncoder.encode(id, "UTF-8")}&quality=mid")
        val raw = data.optJSONObject("result")?.optString("stream").orEmpty()
        return safeStream(raw)
    }

    companion object {
        fun safeStream(raw: String): URI {
            val uri =
                try {
                    URI(raw)
                } catch (_: Exception) {
                    throw Problem("Некорректный адрес потока")
                }
            val host = uri.host.orEmpty()
            if (
                raw.length > 8192 ||
                    uri.scheme != "https" ||
                    uri.userInfo != null ||
                    uri.fragment != null ||
                    uri.port !in listOf(-1, 443) ||
                    !host.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]*\\.[A-Za-z]{2,}"))
            )
                throw Problem("Поток не содержит допустимый HTTPS-адрес")
            return uri
        }
    }
}
