package dev.alex.folderplayer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Public track page only: no stream URL, OAuth token, or tracking parameters. */
internal object YandexTrackShare {
    fun intent(mediaId:String,title:String):Intent? {
        val id=OnlinePlayback.trackId(Uri.parse(mediaId)) ?: return null
        val send=Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT,"https://music.yandex.ru/track/$id")
            .putExtra(Intent.EXTRA_SUBJECT,title.take(200))
        return Intent.createChooser(send,"Поделиться треком")
    }
    fun show(host:Activity,mediaId:String,title:String) {
        val share=intent(mediaId,title) ?: return
        try { host.startActivity(share) }
        catch(_:ActivityNotFoundException) { Toast.makeText(host,"Нет приложения для отправки ссылки",Toast.LENGTH_LONG).show() }
    }
}
