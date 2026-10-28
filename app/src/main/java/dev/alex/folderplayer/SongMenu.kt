package dev.alex.folderplayer

import android.app.AlertDialog
import android.content.Context

/** Typed actions: adding a menu entry cannot silently renumber another command. */
internal object SongMenu {
    enum class Action(val title: String) {
        SHARE("Поделиться треком"),
        SAVE_MP3("Сохранить MP3…"),
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
        val actions = Action.values().filter { if(online)it !in listOf(Action.YEAR,Action.PLAYLIST) else it !in listOf(Action.SHARE,Action.SAVE_MP3) }
        // UI refactor step C: bottom sheet instead of centred AlertDialog.
        if (context is android.app.Activity)
            BottomSheet.show(context, title = title, items = actions.map { it.title }) { index ->
                if (index >= 0) chosen(actions[index])
            }
        else
            AlertDialog.Builder(context)
                .setTitle(title)
                .setItems(actions.map { it.title }.toTypedArray()) { _, index ->
                    chosen(actions[index])
                }
                .show()
    }
}
