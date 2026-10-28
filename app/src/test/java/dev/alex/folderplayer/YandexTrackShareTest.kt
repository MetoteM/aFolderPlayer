package dev.alex.folderplayer

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class YandexTrackShareTest {
    private fun texts(view: View): List<String> =
        (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            else emptyList()

    private fun sheet(host: ActivityController<Activity>): Dialog =
        ShadowDialog.getLatestDialog()

    private fun tap(dialog: Dialog, label: String) {
        val queue = ArrayDeque<View>().apply { add(dialog.window!!.decorView) }
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (view is TextView && view.text == label) { view.performClick(); return }
            if (view is ViewGroup) for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
        }
        error("Пункт '$label' не найден в нижнем листе")
    }

    @Test fun playerMenuOpensSystemChooserWithPublicTrackLinkOnly() {
        val host=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            SongMenu.show(host.get(),"Song",true) { if(it==SongMenu.Action.SHARE)YandexTrackShare.show(host.get(),"afp-yandex://track/178529","Song") }
            val shown=texts(sheet(host).window!!.decorView)
            assertTrue(shown.contains("Поделиться треком"))
            tap(sheet(host),"Поделиться треком")
            val chooser=shadowOf(host.get()).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER,chooser.action)
            @Suppress("DEPRECATION") val send=chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND,send.action);assertEquals("text/plain",send.type)
            assertEquals("https://music.yandex.ru/track/178529",send.getStringExtra(Intent.EXTRA_TEXT))
            assertNull(send.data);assertNull(send.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM))
            SongMenu.show(host.get(),"Local",false) {}
            val local=texts(sheet(host).window!!.decorView)
            assertFalse(local.contains("Поделиться треком"))
        } finally { host.pause().stop().destroy() }
    }
    @Test fun nonYandexAndMalformedIdentifiersCannotBecomeSharedLinks() {
        for(value in listOf("content://music/1","https://example.com/token","afp-yandex://track/1?token=secret","afp-yandex://track/../1"))assertNull(YandexTrackShare.intent(value,"Song"))
    }
}
