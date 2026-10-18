package dev.alex.folderplayer

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File

internal class LyricsStore(private val context: Context) {
    companion object {
        private val lock = Any()
    }

    private fun file(uri: String) =
        AtomicFile(
            File(
                File(context.filesDir, "lyrics").apply { mkdirs() },
                LyricsDocument.key(uri) + ".txt",
            )
        )

    fun exists(uri: String) = file(uri).baseFile.exists()

    fun read(uri: String): String? =
        synchronized(lock) {
            try {
                AtomicTextFiles.read(file(uri))
            } catch (_: java.io.FileNotFoundException) {
                null
            }
        }

    fun save(uri: String, text: String) =
        synchronized(lock) {
            LyricsDocument(text) // Validate before changing the last saved version.
            val f = file(uri)
            AtomicTextFiles.write(f, text)
        }

    fun importText(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (output.size() <= LyricsDocument.MAX_BYTES) {
                val n = stream.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
            }
            val bytes = output.toByteArray()
            require(bytes.size <= LyricsDocument.MAX_BYTES) { "Текст больше 512 КиБ" }
            val decoder =
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            val text =
                decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            LyricsDocument(text)
            text
        } ?: throw java.io.IOException("Не удалось открыть текст")
}
