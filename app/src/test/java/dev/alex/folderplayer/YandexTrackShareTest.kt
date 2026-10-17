package dev.alex.folderplayer

import android.app.Activity
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class YandexTrackShareTest {
    @Test fun playerMenuOpensSystemChooserWithPublicTrackLinkOnly() {
        val host=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            SongMenu.show(host.get(),"Song",true) { if(it==SongMenu.Action.SHARE)YandexTrackShare.show(host.get(),"afp-yandex://track/178529","Song") }
            val list=ShadowAlertDialog.getLatestAlertDialog().listView
            val index=(0 until list.count).single { list.adapter.getItem(it)=="Поделиться треком" }
            list.performItemClick(list.adapter.getView(index,null,list),index,index.toLong())
            val chooser=shadowOf(host.get()).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER,chooser.action)
            @Suppress("DEPRECATION") val send=chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND,send.action);assertEquals("text/plain",send.type)
            assertEquals("https://music.yandex.ru/track/178529",send.getStringExtra(Intent.EXTRA_TEXT))
            assertNull(send.data);assertNull(send.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM))
            SongMenu.show(host.get(),"Local",false) {}
            val local=ShadowAlertDialog.getLatestAlertDialog().listView
            assertFalse((0 until local.count).any { local.adapter.getItem(it)=="Поделиться треком" })
        } finally { host.pause().stop().destroy() }
    }
    @Test fun nonYandexAndMalformedIdentifiersCannotBecomeSharedLinks() {
        for(value in listOf("content://music/1","https://example.com/token","afp-yandex://track/1?token=secret","afp-yandex://track/../1"))assertNull(YandexTrackShare.intent(value,"Song"))
    }
}
