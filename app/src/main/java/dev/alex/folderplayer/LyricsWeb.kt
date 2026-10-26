package dev.alex.folderplayer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast

internal object LyricsWeb {
    fun open(host: Activity, title: String, artist: String) {
        if (title.isBlank()) {
            Toast.makeText(host, "Введи название песни", Toast.LENGTH_SHORT).show()
            return
        }
        val query = "$artist $title текст песни lyrics"
        runCatching {
                host.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://yandex.ru/search/?text=" + Uri.encode(query)),
                    )
                )
            }
            .onFailure {
                Toast.makeText(host, "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
            }
    }
}
