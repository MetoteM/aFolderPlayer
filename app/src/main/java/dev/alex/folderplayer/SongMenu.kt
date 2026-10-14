package dev.alex.folderplayer

import android.app.AlertDialog
import android.content.Context

/** Typed actions: adding a menu entry cannot silently renumber another command. */
internal object SongMenu {
    enum class Action(val title: String) {
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

    fun show(context: Context, title: String, chosen: (Action) -> Unit) {
        val actions = Action.values()
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(actions.map { it.title }.toTypedArray()) { _, index ->
                chosen(actions[index])
            }
            .show()
    }
}
