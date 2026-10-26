package dev.alex.folderplayer

import android.net.Uri
import androidx.media3.common.MediaItem
import org.json.JSONArray
import org.json.JSONObject

internal object OnlineQueueMetadata {
    fun row(item:MediaItem):JSONObject? {
        val id=OnlinePlayback.trackId(Uri.parse(item.mediaId)) ?: return null
        val m=item.mediaMetadata
        return JSONObject().put("id",id).put("title",m.title?.toString().orEmpty()).put("artist",m.artist?.toString().orEmpty())
            .put("duration",m.extras?.getLong("onlineDuration") ?: 0L).put("cover",m.artworkUri?.toString().orEmpty())
    }
    fun rows(items:List<MediaItem>):JSONArray=JSONArray(items.mapNotNull { row(it) })
}
