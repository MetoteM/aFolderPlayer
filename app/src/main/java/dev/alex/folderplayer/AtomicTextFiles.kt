package dev.alex.folderplayer

import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/** Shared atomic UTF-8 I/O. A failed replacement preserves the last committed data. */
object AtomicTextFiles {
    @JvmStatic
    @Throws(IOException::class)
    fun read(file: AtomicFile): String? =
        try {
            file.openRead().use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    out.write(buffer, 0, count)
                }
                out.toString("UTF-8")
            }
        } catch (_: java.io.FileNotFoundException) {
            null
        }

    @JvmStatic
    @Throws(IOException::class)
    fun write(file: AtomicFile, value: String) {
        var out: FileOutputStream? = null
        try {
            out = file.startWrite()
            out.write(value.toByteArray(StandardCharsets.UTF_8))
            file.finishWrite(out)
        } catch (failure: IOException) {
            out?.let { file.failWrite(it) }
            throw failure
        } catch (failure: RuntimeException) {
            out?.let { file.failWrite(it) }
            throw failure
        }
    }
}
