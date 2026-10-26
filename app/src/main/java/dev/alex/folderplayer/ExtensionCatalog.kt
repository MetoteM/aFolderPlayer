package dev.alex.folderplayer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.Base64
import java.util.Collections
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject

/** Signed data only. Executable code is installed by Android as a separately signed APK. */
class ExtensionCatalog(payload: JSONObject) {
    class Entry(item: JSONObject) {
        val id: String
        val kind: String
        val title: String
        val file: String
        val hash: String
        val model: String
        val url: String
        val versionName: String
        val size: Long
        val minimumEngine: Long
        val minimumPlayer: Long

        init {
            id = item.getString("id")
            kind = item.getString("kind")
            title = item.getString("title")
            file = item.getString("file")
            hash = item.getString("sha256")
            size = item.getLong("size")
            model = item.optString("model", "")
            url = item.optString("url", "")
            if (url.isNotEmpty()) validateModelUrl(url, kind, file)
            minimumEngine = item.optLong("minimumEngine", 0)
            minimumPlayer = item.optLong("minimumPlayer", 0)
            versionName = item.optString("versionName", "")
            if (
                minimumPlayer < 0 ||
                minimumPlayer > Int.MAX_VALUE ||
                versionName.length > 40 ||
                minimumEngine < 0 ||
                minimumEngine > 100000 ||
                kind !in setOf("engine", "model", "online") ||
                !file.matches(Regex("[A-Za-z0-9_.-]{1,100}")) ||
                !hash.matches(Regex("[a-f0-9]{64}")) ||
                size <= 0 ||
                size > 240_000_000 ||
                title.length > 150
            )
                throw IOException("Некорректный каталог расширений")
            if (
                (kind == "online" && id != "online") ||
                (kind == "engine" && id != "engine") ||
                (kind == "model" && !model.matches(Regex("[a-z0-9][a-z0-9_-]{1,63}")))
            )
                throw IOException("Пакет пока не поддерживается этой версией")
        }

        @Throws(Exception::class)
        fun address(base: URI): URI =
            if (url.isEmpty()) base.resolve(file) else validateModelUrl(url, kind, file)

        companion object {
            @JvmStatic
            @Throws(Exception::class)
            internal fun validateModelUrl(value: String, kind: String, file: String): URI {
                val uri = URI(value)
                if (
                    kind != "model" ||
                    value.length > 500 ||
                    "https" != uri.scheme ||
                    "github.com" != uri.host ||
                    uri.port != -1 ||
                    uri.userInfo != null ||
                    uri.query != null ||
                    uri.fragment != null ||
                    !uri.rawPath.matches(
                        Regex(
                            "/MetoteM/aFolderPlayer/releases/download/v[0-9]+(?:\\.[0-9]+){1,2}/" +
                                Pattern.quote(file)
                        )
                    )
                )
                    throw IOException("Некорректная ссылка на языковой пакет")
                return uri
            }
        }
    }

    val version: Int
    val entries: List<Entry>

    init {
        val schema = payload.getInt("schema")
        if (schema != 1 && schema != 2) throw IOException("Нужна новая версия плеера")
        version = payload.getInt("version")
        if (version < 1) throw IOException("Некорректная версия каталога")
        val items: JSONArray = payload.getJSONArray("entries")
        if (items.length() > 20) throw IOException("Каталог слишком большой")
        val values = ArrayList<Entry>()
        val ids = HashSet<String>()
        for (i in 0 until items.length()) {
            val e = Entry(items.getJSONObject(i))
            if (schema == 1 && e.kind == "online")
                throw IOException("Онлайн требует каталога второй версии")
            if (!ids.add(e.id)) throw IOException("Повторяющиеся пакеты")
            values.add(e)
        }
        entries = Collections.unmodifiableList(values)
    }

    companion object {
        @JvmStatic
        @Throws(Exception::class)
        fun verify(envelope: ByteArray, certificate: ByteArray, minimumVersion: Int): ExtensionCatalog {
            if (envelope.size > 200_000) throw IOException("Каталог слишком большой")
            val object0 = JSONObject(String(envelope, Charsets.UTF_8))
            val payload = Base64.getDecoder().decode(object0.getString("payload"))
            val signature = Base64.getDecoder().decode(object0.getString("signature"))
            val cert =
                CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(certificate))
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(cert)
            verifier.update(payload)
            if (!verifier.verify(signature)) throw IOException("Подпись каталога не подтверждена")
            val catalog = ExtensionCatalog(JSONObject(String(payload, Charsets.UTF_8)))
            if (catalog.version < minimumVersion)
                throw IOException("Сервер предлагает устаревший каталог")
            return catalog
        }

        @JvmStatic
        @Throws(Exception::class)
        fun base(address: String): URI {
            val uri = URI(address.trim())
            if (
                !"https".equals(uri.scheme, ignoreCase = true) ||
                uri.host == null ||
                uri.userInfo != null ||
                uri.query != null ||
                uri.fragment != null
            )
                throw IOException("Нужен HTTPS-адрес каталога без параметров")
            return URI(if (uri.toString().endsWith("/")) uri.toString() else "$uri/")
        }

        @JvmStatic
        @Throws(IOException::class)
        fun read(input: InputStream, limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val b = ByteArray(8192)
            while (true) {
                val n = input.read(b)
                if (n == -1) break
                if (out.size() + n > limit) throw IOException("Файл больше допустимого размера")
                out.write(b, 0, n)
            }
            return out.toByteArray()
        }

        @JvmStatic
        @Throws(IOException::class)
        fun connect(uri: URI): HttpURLConnection = ExtensionHttp.connect(uri)
    }
}
