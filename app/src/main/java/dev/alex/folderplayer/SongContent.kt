package dev.alex.folderplayer

import android.content.Context
import android.net.Uri

/** Shared local lyric loading for retained work. */
internal class SongContent(context: Context) {
    private val context = context.applicationContext
    private val lyricStore = LyricsStore(this.context)
    private val prefs = this.context.getSharedPreferences("playback", 0)

    fun load(track: Track): String? {
        lyricStore.read(track.uri)?.let {
            LyricsDocument(it)
            return it
        }
        if(track.uri.startsWith("afp-yandex://") && prefs.getString("lyrics:${track.uri}",null)==null) return null
        val attached = prefs.getString("lyrics:${track.uri}", null)
        val source =
            attached?.let { Uri.parse(it) }
                ?: run {
                    val uri = Uri.parse(track.uri)
                    val doc =
                        androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
                    val name =
                        track.filename.ifBlank { doc?.name.orEmpty() }.substringBeforeLast('.')
                    val id = android.provider.DocumentsContract.getDocumentId(uri)
                    val parent = id.substringBeforeLast('/', "")
                    if (parent.isBlank()) null
                    else {
                        val children =
                            android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                                uri,
                                parent,
                            )
                        context.contentResolver
                            .query(
                                children,
                                arrayOf(
                                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                ),
                                null,
                                null,
                                null,
                            )
                            ?.use { cursor ->
                                var found: Uri? = null
                                while (cursor.moveToNext()) {
                                    val file = cursor.getString(1)
                                    if (
                                        file.equals("$name.lrc", true) ||
                                            file.equals("$name.txt", true)
                                    ) {
                                        found =
                                            android.provider.DocumentsContract
                                                .buildDocumentUriUsingTree(uri, cursor.getString(0))
                                        if (file.endsWith(".lrc", true)) break
                                    }
                                }
                                found
                            }
                    }
                }
        return source?.let {
            lyricStore.importText(it).also { value -> lyricStore.save(track.uri, value) }
        }
    }
}
