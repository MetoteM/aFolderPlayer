package dev.alex.folderplayer

import android.content.Context
import java.net.HttpURLConnection
import java.net.URI

/** One signed catalogue policy. Legacy clients retain catalog.json; new modules use v2. */
internal class ExtensionCatalogueSource(
    context: Context,
    private val open: (URI) -> HttpURLConnection = { ExtensionHttp.connect(it) },
) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("extensions", 0)

    data class Result(val base: URI, val catalog: ExtensionCatalog)

    fun read(active: () -> Unit, connection: (HttpURLConnection?) -> Unit): Result {
        val address =
            prefs.getString("server", ExtensionManager.DEFAULT_CATALOG).orEmpty().ifBlank {
                ExtensionManager.DEFAULT_CATALOG
            }
        val base = ExtensionCatalog.base(address)
        for (name in listOf("catalog-v2.json", "catalog.json")) {
            active()
            val request = open(base.resolve(name))
            connection(request)
            try {
                active()
                if (request.responseCode == 404 && name == "catalog-v2.json") continue
                check(request.responseCode == 200) {
                    "Каталог расширений недоступен (${request.responseCode})"
                }
                val bytes = request.inputStream.use { ExtensionCatalog.read(it, 200_000) }
                active()
                val certificate =
                    context.assets.open("catalog_certificate.der").use { it.readBytes() }
                val catalog =
                    synchronized(lock) {
                        ExtensionCatalog.verify(
                                bytes,
                                certificate,
                                prefs.getInt("catalogVersion", 1),
                            )
                            .also { prefs.edit().putInt("catalogVersion", it.version).apply() }
                    }
                return Result(base, catalog)
            } finally {
                connection(null)
                request.disconnect()
            }
        }
        error("Каталог расширений не найден")
    }

    companion object {
        private val lock = Any()
    }
}
