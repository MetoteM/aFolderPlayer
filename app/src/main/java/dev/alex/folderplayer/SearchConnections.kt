package dev.alex.folderplayer

import android.content.Context
import android.net.http.HttpEngine
import android.os.Build
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Shared, lazy system Chromium transport. Does not change global TLS or playback networking. */
class SearchConnections(context: Context) : LyricsSearch.Connections {
    private val context: Context = context.applicationContext

    override fun open(url: URL): java.net.URLConnection =
        openHttp(url)

    fun openHttp(url: URL): HttpURLConnection {
        if (
            url.protocol != "https" ||
                (url.host != "musicbrainz.eu" && url.host != "lrclib.net")
        )
            throw IOException("Unexpected search endpoint")
        synchronized(SearchConnections::class.java) {
            if (selected == null) {
                selected =
                    if (Build.VERSION.SDK_INT >= 34) {
                        try {
                            Modern.create(context)
                        } catch (unavailable: UnsupportedOperationException) {
                            DEFAULT
                        } catch (unavailable: IllegalStateException) {
                            DEFAULT
                        } catch (unavailable: LinkageError) {
                            DEFAULT
                        }
                    } else DEFAULT
            }
        }
        return selected!!.open(url) as HttpURLConnection
    }

    @android.annotation.TargetApi(34)
    private object Modern {
        fun create(context: Context): LyricsSearch.Connections {
            val engine =
                HttpEngine.Builder(context)
                    .setEnableHttp2(true)
                    .setEnableQuic(true)
                    .setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISABLED, 0)
                    .build()
            return LyricsSearch.Connections { url ->
                engine.openConnection(url) as HttpURLConnection
            }
        }
    }

    companion object {
        private val DEFAULT =
            LyricsSearch.Connections { url -> url.openConnection() as HttpURLConnection }
        private var selected: LyricsSearch.Connections? = null
    }
}
