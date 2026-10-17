package dev.alex.folderplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.*

/** Applies to the Yandex catalogue, audio and artwork. Local playback is unaffected. */
internal class OnlineSettings(context:Context) {
    private val context=context.applicationContext
    private val prefs=this.context.getSharedPreferences("online",0)
    private fun wifi():Boolean {
        val cm=context.getSystemService(ConnectivityManager::class.java)
        val caps=cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
    fun requireNetwork() {
        val cm=context.getSystemService(ConnectivityManager::class.java)
        val caps=cm.getNetworkCapabilities(cm.activeNetwork)
        if(caps==null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) throw OnlinePlayback.Problem("Нет подключения к интернету")
        if(prefs.getBoolean("wifiOnly",false) && !wifi()) throw OnlinePlayback.Problem("Яндекс Музыка: разрешён только Wi-Fi. Проверь настройки сети")
    }
    fun quality():String=prefs.getString(if(wifi()) "wifiQuality" else "mobileQuality","economy") ?: "economy"
    fun show(host:Activity) {
        val root=LinearLayout(host).apply { orientation=LinearLayout.VERTICAL;setPadding(24,12,24,12) }
        val only=Switch(host).apply { text="Только Wi-Fi";isChecked=prefs.getBoolean("wifiOnly",false) };root.addView(only)
        fun quality(label:String,key:String):Spinner {
            root.addView(TextView(host).apply { text=label })
            return Spinner(host).apply {
                adapter=ArrayAdapter(host,android.R.layout.simple_spinner_dropdown_item,arrayOf("Экономное · минимальный MP3","Максимальное доступное MP3"))
                setSelection(if(prefs.getString(key,"economy")=="high")1 else 0)
                root.addView(this)
            }
        }
        val wifi=quality("Качество по Wi-Fi","wifiQuality")
        val mobile=quality("Качество по мобильной / другой сети","mobileQuality")
        root.addView(TextView(host).apply { text="Качество применяется при следующем открытии потока. Только Wi-Fi блокирует новые запросы и дальнейшее чтение потока вне Wi-Fi; буфер может доиграть. VPN без подтверждённого Wi-Fi тоже блокируется. Вход в Яндекс и внешние сервисы текста/распознавания имеют отдельный сетевой доступ." })
        AlertDialog.Builder(host).setTitle("Яндекс Музыка · сеть и качество").setView(root)
            .setPositiveButton("Сохранить") { _,_-> prefs.edit().putBoolean("wifiOnly",only.isChecked)
                .putString("wifiQuality",if(wifi.selectedItemPosition==1)"high" else "economy")
                .putString("mobileQuality",if(mobile.selectedItemPosition==1)"high" else "economy").apply() }
            .setNegativeButton("Отмена",null).show()
    }
}
