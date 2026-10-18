package dev.alex.folderplayer

import android.content.ComponentName
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SettingsMenuTest {
    @Test fun optionalEntriesDoNotChangeActionIdentityAndAboutRemainsLast(){
        val extra=ExternalExtensions.Entry(ComponentName("example.addon","example.addon.Main"),"Extra")
        for(extensions in listOf(emptyList(),listOf(extra))){
            val entries=SettingsMenu.entries(extensions)
            assertEquals(SettingsMenu.Action.ABOUT,entries.last().action)
            assertEquals(SettingsMenu.Action.BOOKS,entries.single { it.title=="Аудиокниги" }.action)
            assertEquals(SettingsMenu.Action.THEME,entries.single { it.title=="Тема оформления" }.action)
            assertEquals(extensions,entries.mapNotNull { it.extension })
        }
    }
    @Test fun booksEntryOpensSeparateScreen(){
        RuntimeEnvironment.getApplication().getSharedPreferences("playback",Context.MODE_PRIVATE).edit().clear().commit()
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start()
        try {
            MainActivity::class.java.getDeclaredMethod("settings").apply { isAccessible=true }.invoke(c.get())
            val menu=ShadowAlertDialog.getLatestAlertDialog();val list=menu.listView
            val index=(0 until list.count).first { list.adapter.getItem(it)=="Аудиокниги" }
            list.performItemClick(null,index,index.toLong())
            assertEquals(BooksActivity::class.java.name,org.robolectric.Shadows.shadowOf(c.get()).nextStartedActivity.component!!.className)
            assertFalse(SettingsMenu.entries(emptyList()).any { it.title.contains("История") })
        }finally { c.stop().destroy() }
    }
}
