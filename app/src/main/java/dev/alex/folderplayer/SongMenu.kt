package dev.alex.folderplayer

import android.app.AlertDialog
import android.content.Context

/** Typed actions: adding a menu entry cannot silently renumber another command. */
internal object SongMenu {
    enum class Action(val title: String) {
        SHARE("Поделиться треком"),
        SEARCH("Найти текст в интернете"),
        EDIT("Свой текст"),
        IMPORT("Импорт TXT / LRC"),
        TIMING("Разметить строки"),
        EXPORT("Экспорт текста"),
        PLAYLIST("Добавить в плейлист"),
        YEAR("Год альбома"),
        SEARCH_NAME("Исправить поисковое название"),
        LANGUAGE("Язык оригинала для перевода"),
        RETRANSLATE("Перевести заново"),
    }

    fun show(context: Context, title: String, online:Boolean=false, chosen: (Action) -> Unit) {
        val actions = Action.values().filter { if(online)it !in listOf(Action.YEAR,Action.PLAYLIST) else it!=Action.SHARE }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(actions.map { it.title }.toTypedArray()) { _, index ->
                chosen(actions[index])
            }
            .show()
    }
}
