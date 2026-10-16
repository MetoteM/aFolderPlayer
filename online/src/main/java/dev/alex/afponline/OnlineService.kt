package dev.alex.afponline

import android.app.Service
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/** Optional signed extension. Binding only discovers connectors; it never signs users in. */
class OnlineService : Service() {
    private val registry = ConnectorRegistry()
    private val binder =
        object : IOnlineService.Stub() {
            override fun apiVersion() = 2

            @Synchronized override fun yandex(operation: String, argument: String): String {
                if (argument.length > 500) return JSONObject().put("error", "Слишком длинный запрос").toString()
                return try {
                    val token = YandexSession(this@OnlineService).read()
                    if (operation == "status") return JSONObject().put("connected", token != null).toString()
                    val client = YandexClient(token ?: throw YandexClient.Problem("Войди в Яндекс Музыку"))
                    client.checkSession()
                    when (operation) {
                        "search", "collection" -> {
                            val songs = if (operation == "search") client.search(argument) else client.collection()
                            JSONObject().put("tracks", JSONArray(songs.map { song ->
                                JSONObject().put("id", song.id).put("title", song.title).put("artist", song.artist)
                            })).toString()
                        }
                        "stream" -> JSONObject().put("url", client.stream(argument).toString()).toString()
                        else -> JSONObject().put("error", "Неизвестное действие").toString()
                    }
                } catch (e: Exception) {
                    JSONObject().put("error", (e as? YandexClient.Problem)?.reason ?: "Расширение не выполнило запрос").toString()
                }
            }

            override fun connectorCatalogue(): String =
                JSONObject()
                    .put("schema", 1)
                    .put(
                        "connectors",
                        JSONArray(
                            registry.connectors.map { connector ->
                                JSONObject()
                                    .put("id", connector.id)
                                    .put("title", connector.title)
                                    .put("status", connector.status)
                                    .put("explanation", connector.explanation)
                                    .put("capabilities", JSONArray(connector.capabilities.toList()))
                            }
                        ),
                    )
                    .toString()
        }

    override fun onBind(intent: Intent) = binder
}
