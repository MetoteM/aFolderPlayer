package dev.alex.folderplayer

import android.app.Activity

/** Menu positions never identify actions; optional entries cannot shift their handlers. */
internal object SettingsMenu {
    enum class Action { ADD_FOLDER, FOLDERS, SCAN, SEEK, SLEEP, EXTENSIONS, HISTORY, CRASH, THEME, ABOUT }
    data class Entry(val title:String, val action:Action? = null, val extension:ExternalExtensions.Entry? = null)

    fun entries(extensions:List<ExternalExtensions.Entry>):List<Entry> = listOf(
        Entry("Добавить папку",Action.ADD_FOLDER),
        Entry("Выбранные папки",Action.FOLDERS),
        Entry("Обновить библиотеку",Action.SCAN),
        Entry("Шаг перемотки",Action.SEEK),
        Entry("Таймер сна",Action.SLEEP),
        Entry("Расширения",Action.EXTENSIONS),
        Entry("История текстов",Action.HISTORY),
        Entry("Диагностика последнего сбоя",Action.CRASH),
        Entry("Тема оформления",Action.THEME),
    ) + extensions.map { Entry(it.title,extension=it) } + Entry("О приложении",Action.ABOUT)

    fun show(host:Activity, run:(Action)->Unit) {
        val entries=entries(ExternalExtensions.installed(host))
        optionMenu(host,"Настройки",entries.map { it.title }) { index ->
            val entry=entries[index]
            entry.extension?.let { ExternalExtensions.open(host,it) }
                ?: entry.action?.let(run)
        }
    }
}
