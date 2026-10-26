package dev.alex.folderplayer

import android.content.ComponentName
import android.os.Binder
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ListView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class BooksActivityTest {
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun run(check:(BooksActivity,List<View>)->Unit){
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("audiobooks",0).edit().clear().commit()
        app.getSharedPreferences("playback",0).edit().clear().commit()
        Audiobooks(app).put(Audiobook("book","content://books/book","Test book",listOf(BookChapter("file:///1.mp3","01",60000),BookChapter("file:///2.mp3","02",90000))))
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app,PlaybackService::class.java),Binder())
        shadowOf(app).setUnbindServiceCallsOnServiceDisconnected(false)
        val screen=Robolectric.buildActivity(BooksActivity::class.java).create().start().resume()
        try {check(screen.get(),views(screen.get().window.decorView))}finally {screen.pause().stop().destroy()}
    }
    @Test fun chaptersAndSpeedAreAvailableWithoutChangingMusicSettings()=run { activity,all ->
        assertEquals(1,all.filterIsInstance<ListView>().single().count)
        all.filterIsInstance<Button>().single { it.text=="Главы" }.performClick()
        assertEquals(2,ShadowAlertDialog.getLatestAlertDialog().listView.count)
        ShadowAlertDialog.getLatestAlertDialog().dismiss()
        all.filterIsInstance<Button>().single { it.text=="Скорость" }.performClick()
        val choice=ShadowAlertDialog.getLatestAlertDialog().listView
        choice.performItemClick(null,4,4)
        assertEquals(1.5f,Audiobooks(activity).speed("book"))
        assertFalse(activity.getSharedPreferences("playback",0).contains("requestedRepeat"))
        assertFalse(all.filterIsInstance<Button>().any { it.text.contains("История") })
    }
    @Test @Config(qualifiers="land") fun controlsRemainPresentInLandscape()=run { activity,all ->
        val root=activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY));root.layout(0,0,1000,500)
        assertTrue(all.filterIsInstance<ListView>().single().width>0)
        assertEquals(1,all.filterIsInstance<Button>().count { it.text=="Закладки" && it.width>0 })
        all.filterIsInstance<Button>().single { it.text=="Таймер сна" }.performClick()
        val choice=ShadowAlertDialog.getLatestAlertDialog().listView;choice.performItemClick(null,0,0)
        assertTrue(activity.getSharedPreferences("playback",0).getLong("sleepDeadline",0)>System.currentTimeMillis())
    }
}
