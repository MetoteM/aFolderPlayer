package dev.alex.folderplayer

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale

/** Only GitHub asset redirects are accepted; ordinary hosts retain the no-redirect policy. */
object ExtensionHttp {
    private val GITHUB_HOSTS =
        setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

    @JvmStatic
    @Throws(IOException::class)
    internal fun validate(uri: URI) {
        if (
            !"https".equals(uri.scheme, ignoreCase = true) ||
                uri.host == null ||
                uri.userInfo != null ||
                uri.fragment != null ||
                (uri.port != -1 && uri.port != 443)
        )
            throw IOException("Нужен безопасный HTTPS-адрес загрузки")
    }

    @JvmStatic
    @Throws(IOException::class)
    fun redirect(original: URI, current: URI, location: String?): URI {
        if (location.isNullOrBlank()) throw IOException("Сервер не указал адрес загрузки")
        val target =
            try {
                current.resolve(location)
            } catch (e: IllegalArgumentException) {
                throw IOException("Некорректный адрес загрузки", e)
            }
        validate(target)
        val host = original.host
        if (
            host == null ||
                !host.equals("github.com", ignoreCase = true) ||
                !(original.path.matches(Regex("/[^/]+/[^/]+/releases/download/[^/]+/[^/]+")) ||
                    original.path.matches(Regex("/[^/]+/[^/]+/releases/latest/download/[^/]+"))) ||
                target.host?.lowercase(Locale.ROOT) !in GITHUB_HOSTS
        )
            throw IOException("Перенаправление загрузки не разрешено")
        return target
    }

    @JvmStatic
    @Throws(IOException::class)
    fun connect(original: URI): HttpURLConnection {
        validate(original)
        var uri = original
        val visited = HashSet<URI>()
        for (hop in 0..5) {
            if (!visited.add(uri)) throw IOException("Сервер повторяет адрес загрузки")
            val c = uri.toURL().openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "aFolderPlayer")
            try {
                val status = c.responseCode
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308)
                    return c
                if (hop == 5) throw IOException("Слишком много перенаправлений загрузки")
                uri = redirect(original, uri, c.getHeaderField("Location"))
            } catch (e: IOException) {
                c.disconnect()
                throw e
            } catch (e: RuntimeException) {
                c.disconnect()
                throw e
            }
            c.disconnect()
        }
        throw IOException("Не удалось открыть загрузку")
    }
}
