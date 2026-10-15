package dev.alex.folderplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dev.alex.afponline.IOnlineService
import java.util.concurrent.Executors
import org.json.JSONObject

/** A small entry point for the optional Online extension; the player screen is unchanged. */
internal class OnlineSection(private val host: Activity, private val updates: () -> Unit) {
    private val connection =
        SignedExtensionConnection(
            host,
            ExtensionModules.online.pkg,
            "dev.alex.afponline.OnlineService",
        )
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false

    fun show() {
        if (!connection.installed()) {
            AlertDialog.Builder(host)
                .setTitle("Онлайн")
                .setMessage(
                    "Это отдельное расширение AFP Online. Первый коннектор — Звук. Подключение сервиса пока готовится."
                )
                .setPositiveButton("Обновления") { _, _ -> updates() }
                .setNegativeButton("Закрыть", null)
                .show()
            return
        }
        worker.execute {
            val result = runCatching {
                connection.use { binder ->
                    val service = IOnlineService.Stub.asInterface(binder)
                    check(service.apiVersion() == 1) { "Обнови расширение Онлайн" }
                    val raw = service.connectorCatalogue()
                    check(raw.length <= 20000) { "Некорректный ответ расширения" }
                    val data = JSONObject(raw)
                    check(data.getInt("schema") == 1)
                    val list = data.getJSONArray("connectors")
                    check(list.length() <= 20)
                    (0 until list.length())
                        .map { index ->
                            val item = list.getJSONObject(index)
                            item.getString("title").take(100) +
                                "\n" +
                                item.getString("explanation").take(500)
                        }
                        .joinToString("\n\n")
                }
            }
            main.post {
                if (!closed)
                    AlertDialog.Builder(host)
                        .setTitle("Онлайн")
                        .setMessage(result.getOrElse { it.message ?: "Расширение не отвечает" })
                        .setPositiveButton("Закрыть", null)
                        .setNeutralButton("Прототип Звука") { _, _ ->
                            runCatching {
                                    host.startActivity(
                                        Intent()
                                            .setComponent(
                                                ComponentName(
                                                    ExtensionModules.online.pkg,
                                                    "dev.alex.afponline.ZvukLabActivity",
                                                )
                                            )
                                    )
                                }
                                .onFailure {
                                    AlertDialog.Builder(host)
                                        .setMessage(
                                            "Установи AFP Online 0.1.1 из предварительного выпуска"
                                        )
                                        .setPositiveButton("Закрыть", null)
                                        .show()
                                }
                        }
                        .show()
            }
        }
    }

    fun close() {
        closed = true
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }
}
