package dev.alex.afponline

import android.app.Service
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/** Optional signed extension. Binding only discovers connectors; it never signs users in. */
class OnlineService : Service() {
    companion object {
        // Independent lanes: slow catalogue requests must never hold the stream lock.
        private val catalogue=YandexClientLane()
        private val playback=YandexClientLane()
    }
    private val registry = ConnectorRegistry()
    private val binder =
        object : IOnlineService.Stub() {
            override fun apiVersion() = 3

            override fun yandex(operation: String, argument: String): String {
                if (argument.length > 1500) return JSONObject().put("error", "Слишком длинный запрос").toString()
                return try {
                    val token=YandexSession(this@OnlineService).read()
                    val sessionKey=token?.let { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
                    if(operation=="status") return JSONObject().put("connected",token!=null).put("sessionKey",sessionKey ?: "").toString()
                    if(token==null) throw YandexClient.Problem("Войди в Яндекс Музыку")
                    val lane=if(operation in listOf("stream","streamOptions"))playback else catalogue
                    lane.use(token) { client ->
                        when(operation) {
                            "search" -> JSONObject().put("tracks",JSONArray(client.search(argument).map { it.json() })).toString()
                            "collection" -> JSONObject().put("tracks",JSONArray(client.collection().map { it.json() })).toString()
                            "collectionPage", "playlistPage" -> {
                                val args=JSONObject(argument);val offset=args.optInt("offset",0)
                                val page=if(operation=="collectionPage") client.collectionPage(offset,args.optBoolean("refresh")) else client.playlistPage(args.optString("id"),offset,args.optBoolean("refresh"))
                                page.json().toString()
                            }
                            "playlists" -> JSONObject().put("playlists",client.playlists()).put("warning",client.playlistWarning).toString()
                            "streamOptions" -> { val options=JSONObject(argument);JSONObject().put("url",client.stream(options.getString("id"),options.optString("quality")).toString()).toString() }
                            "stream" -> JSONObject().put("url",client.stream(argument).toString()).toString()
                            else -> JSONObject().put("error","Неизвестное действие").toString()
                        }
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
