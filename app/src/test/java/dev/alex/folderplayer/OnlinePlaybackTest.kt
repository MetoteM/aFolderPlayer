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
    @Test fun rejectsInjectedQueueAndForeignSource() {
        for(raw in listOf("[]", "[{\"id\":\"https://evil.example\"}]")) {
            try { OnlinePlayback.items(raw);fail() } catch(_:IllegalArgumentException) { }
        }
        for(raw in listOf("https://track/123", "afp-yandex://other/123", "afp-yandex://track/123?token=bad")) assertNull(OnlinePlayback.trackId(Uri.parse(raw)))
    }
}
