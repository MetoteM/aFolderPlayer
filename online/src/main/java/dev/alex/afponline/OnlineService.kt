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
            override fun apiVersion() = 1

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
