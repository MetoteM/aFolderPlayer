package dev.alex.folderplayer

import android.app.Activity
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class YandexSaveActivityTest {
    @Test fun destinationIsAlwaysPickedWithAudioTypeAndSafeNameBeforeAnyDownload() {
        val intent=Intent(RuntimeEnvironment.getApplication(),YandexSaveActivity::class.java)
            .putExtra("mediaId","afp-yandex://track/178529").putExtra("title","Song").putExtra("filename","Artist / Song: live")
        val activity=Robolectric.buildActivity(YandexSaveActivity::class.java,intent).setup()
        try {
            val request=shadowOf(activity.get()).nextStartedActivityForResult
            assertEquals(Intent.ACTION_CREATE_DOCUMENT,request.intent.action)
            assertEquals("audio/mpeg",request.intent.type)
            assertTrue(request.intent.categories.contains(Intent.CATEGORY_OPENABLE))
            assertEquals("Artist _ Song_ live.mp3",request.intent.getStringExtra(Intent.EXTRA_TITLE))
            shadowOf(activity.get()).receiveResult(request.intent,Activity.RESULT_CANCELED,null)
            assertTrue(activity.get().isFinishing)
        } finally { activity.pause().stop().destroy() }
    }
}
