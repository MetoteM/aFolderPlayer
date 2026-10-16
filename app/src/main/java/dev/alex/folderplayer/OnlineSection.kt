package dev.alex.folderplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.*
import java.util.concurrent.Executors
import org.json.JSONObject

/** Catalogue UI only. Queue and playback belong to the player's existing MediaSession. */
internal class OnlineSection(private val host: Activity, private val updates: () -> Unit, private val play:(String,Int)->Unit) {
    private val extension=OnlinePlayback(host)
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var closed=false
    fun show() {
        if(!extension.installed()) {
            AlertDialog.Builder(host).setTitle("Яндекс Музыка").setMessage("Установи AFP Online из того же тестового комплекта, что и плеер.").setPositiveButton("Закрыть",null).show()
            return
        }
        val root=LinearLayout(host).apply { orientation=LinearLayout.VERTICAL;setPadding(20,12,20,12) }
        val status=TextView(host).apply { text="Яндекс Музыка · эксперимент" };root.addView(status)
        val query=EditText(host).apply { hint="Песня или исполнитель";isSingleLine=true };root.addView(query)
        val results=LinearLayout(host).apply { orientation=LinearLayout.VERTICAL }
        val scroll=ScrollView(host).apply { addView(results) }
        var busy=false;var dismissed=false
        val actions=mutableListOf<Button>()
        fun update() { actions.forEach { it.isEnabled=!busy } }
        fun action(title:String,click:()->Unit) { root.addView(Button(host).apply { text=title;setOnClickListener { if(!busy) click() };actions+=this }) }
        lateinit var dialog:AlertDialog
        fun load(operation:String,argument:String="") {
            if(busy)return
            busy=true;update();status.text="Загрузка…"
            worker.execute {
                val result=runCatching { extension.request(operation,argument) }
                main.post {
                    if(closed || dismissed)return@post
                    busy=false;update()
                    result.onSuccess { data ->
                        if(operation=="status") { status.text=if(data.optBoolean("connected")) "Подключено · Яндекс Музыка" else "Нажми «Войти / аккаунт»";return@onSuccess }
                        val rows=data.optJSONArray("tracks")
                        results.removeAllViews()
                        if(rows==null || rows.length()==0) { status.text="Треки не найдены";return@onSuccess }
                        val raw=rows.toString()
                        status.text="${rows.length()} треков · выбранный трек и следующие попадут в очередь"
                        for(i in 0 until rows.length()) {
                            val song=rows.optJSONObject(i) ?: continue
                            results.addView(Button(host).apply {
                                text="${song.optString("title")}\n${song.optString("artist")}"
                                setOnClickListener { play(raw,i);dialog.dismiss() }
                            })
                        }
                    }.onFailure { status.text=it.message?.take(200) ?: "Расширение не отвечает" }
                }
            }
        }
        action("Войти / аккаунт") {
            runCatching { host.startActivity(Intent().setComponent(ComponentName(onlinePackage(host),"dev.alex.afponline.YandexAccountActivity")));dialog.dismiss() }
                .onFailure { status.text="Не удалось открыть вход. Обнови расширение" }
        }
        action("Поиск") { load("search",query.text.toString().trim()) }
        action("Моя коллекция · первые 20") { load("collection") }
        root.addView(scroll,LinearLayout.LayoutParams(-1,host.resources.displayMetrics.heightPixels/3))
        dialog=AlertDialog.Builder(host).setTitle("Яндекс Музыка").setView(root).setNegativeButton("Закрыть",null).create()
        dialog.setOnDismissListener { dismissed=true };dialog.show();load("status")
    }
    fun close() { closed=true;worker.shutdownNow();main.removeCallbacksAndMessages(null) }
}
