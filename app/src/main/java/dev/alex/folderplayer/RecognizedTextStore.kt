package dev.alex.folderplayer

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Small independent cache, never attached to the currently playing local file. */
internal class RecognizedTextStore(context: Context) {
    private val root = File(context.filesDir, "recognized-texts").apply { mkdirs() }

    private fun file(title: String, artist: String): AtomicFile {
        val key = LyricsSearch.clean(title) + "\n" + LyricsSearch.clean(artist)
        val hash =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        return AtomicFile(File(root, "$hash.json"))
    }

    data class Entry(val title: String, val artist: String, val text: String)

    @Synchronized
    fun readEntry(title: String, artist: String): Entry? =
        runCatching {
                val bytes =
                    file(title, artist).openRead().use { input ->
                        val b = input.readBytes()
                        require(b.size <= 600000)
                        b
                    }
                val value = JSONObject(String(bytes, Charsets.UTF_8))
                val text = value.getString("text")
                require(text.isNotBlank())
                Entry(
                    value.optString("displayTitle", title),
                    value.optString("displayArtist", artist),
                    text,
                )
            }
            .getOrNull()

    data class Saved(
        val cacheTitle: String,
        val cacheArtist: String,
        val title: String,
        val artist: String,
    )

    fun saved(): List<Saved> =
        root
            .listFiles()
            .orEmpty()
            .filter { it.name.endsWith(".json") }
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f ->
                runCatching {
                        require(f.length() <= 600000)
                        val r = JSONObject(f.readText())
                        Saved(
                            r.getString("title"),
                            r.getString("artist"),
                            r.optString("displayTitle", r.getString("title")),
                            r.optString("displayArtist", r.getString("artist")),
                        )
                    }
                    .getOrNull()
            }

    fun read(title: String, artist: String) = readEntry(title, artist)?.text

    @Synchronized
    fun save(
        title: String,
        artist: String,
        text: String,
        displayTitle: String = title,
        displayArtist: String = artist,
    ) {
        require(
            text.isNotBlank() &&
                text.toByteArray(Charsets.UTF_8).size <= LyricsDocument.MAX_BYTES &&
                text.lines().size <= 4000
        )
        val data =
            JSONObject()
                .put("title", title)
                .put("artist", artist)
                .put("text", text)
                .put("displayTitle", displayTitle)
                .put("displayArtist", displayArtist)
                .toString()
                .toByteArray(Charsets.UTF_8)
        val target = file(title, artist)
        var output: java.io.FileOutputStream? = null
        try {
            output = target.startWrite()
            output.write(data)
            target.finishWrite(output)
            root
                .listFiles()
                ?.filter { it.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(50)
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            output?.let { target.failWrite(it) }
            throw e
        }
    }
}
