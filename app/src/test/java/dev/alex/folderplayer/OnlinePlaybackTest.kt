package dev.alex.folderplayer

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class OnlinePlaybackTest {
    @Test fun queueStoresStableIdsAndMetadataInsteadOfExpiringLinks() {
        val items=OnlinePlayback.items("[{\"id\":\"123\",\"title\":\"Song\",\"artist\":\"Artist\"}]")
        assertEquals("afp-yandex://track/123",items.single().localConfiguration!!.uri.toString())
        assertEquals("Song",items.single().mediaMetadata.title.toString())
        assertEquals("123",OnlinePlayback.trackId(items.single().localConfiguration!!.uri))
    }
    @Test fun durationAndCoverBelongToTheirOwnQueueItem() {
        val items=OnlinePlayback.items("""[{"id":"1","duration":123000,"cover":"https://avatars.yandex.net/first"},{"id":"2","duration":242000,"cover":"https://avatars.yandex.net/second"}]""")
        assertEquals(123000L,items[0].mediaMetadata.extras!!.getLong("onlineDuration"))
        assertEquals(242000L,items[1].mediaMetadata.extras!!.getLong("onlineDuration"))
        assertEquals("https://avatars.yandex.net/second",items[1].mediaMetadata.artworkUri.toString())
    }
    @Test fun onlineLyricsWithoutAttachmentDoNotTouchDocumentProvider() {
        val context=org.robolectric.RuntimeEnvironment.getApplication()
        assertNull(SongContent(context).load(Track("afp-yandex://track/123","Song","Artist",1000,"Яндекс Музыка")))
    }
    @Test fun rejectsInjectedQueueAndForeignSource() {
        for(raw in listOf("[]", "[{\"id\":\"https://evil.example\"}]")) {
            try { OnlinePlayback.items(raw);fail() } catch(_:IllegalArgumentException) { }
        }
        for(raw in listOf("https://track/123", "afp-yandex://other/123", "afp-yandex://track/123?token=bad")) assertNull(OnlinePlayback.trackId(Uri.parse(raw)))
    }
}
