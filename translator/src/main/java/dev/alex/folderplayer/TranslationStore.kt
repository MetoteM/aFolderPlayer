package dev.alex.folderplayer

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Complete, pinned packages only; interrupted installs preserve existing models. */
internal class TranslationStore(context: Context) {
    private val root = File(context.filesDir, "translation").apply { mkdirs() }
    val model = File(root, OfflineTranslator.MODEL_ID)

    companion object {
        const val MODEL_V2 = "opus-en-ru-v2"

        fun supported(id: String) = ModelPackages.find(id) != null
    }

    fun model(id: String): File {
        require(supported(id))
        return File(root, id)
    }

    fun ready(id: String = OfflineTranslator.MODEL_ID) =
        supported(id) &&
            File(model(id), "verified").isFile &&
            ModelPackages.find(id)!!.files.keys.all { File(model(id), it).isFile }

    fun install(input: InputStream): String {
        val stage = File(root, "staging")
        stage.deleteRecursively()
        check(stage.mkdirs())
        try {
            val found = mutableSetOf<String>()
            val actual = mutableMapOf<String, String>()
            var total = 0L
            val allowed = ModelPackages.packages.flatMap { it.files.keys }.toSet()
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(
                        !entry.isDirectory &&
                            entry.name in
                                allowed + setOf("NOTICE.txt", "LICENSE.txt", "manifest.json") &&
                            found.add(entry.name)
                    ) {
                        "Не тот языковой пакет"
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    val destination = File(stage, entry.name)
                    destination.parentFile!!.mkdirs()
                    destination.outputStream().use { out ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            size += n
                            total += n
                            require(
                                total <= 240_000_000 &&
                                    size <=
                                        if (entry.name.endsWith(".onnx")) 65_000_000
                                        else if (entry.name.endsWith("tokenizer.json")) 8_000_000
                                        else 65536
                            ) {
                                "Языковой пакет слишком большой"
                            }
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                        }
                    }
                    if (entry.name in allowed)
                        actual[entry.name] = digest.digest().joinToString("") { "%02x".format(it) }
                }
            }
            val pkg =
                ModelPackages.packages.singleOrNull { it.files == actual }
                    ?: throw IllegalArgumentException(
                        "Языковой пакет повреждён или не поддерживается"
                    )
            val target = model(pkg.id)
            File(stage, "verified").writeText(pkg.id)
            val old = File(root, "previous")
            old.deleteRecursively()
            if (target.exists()) check(target.renameTo(old))
            if (!stage.renameTo(target)) {
                old.renameTo(target)
                error("Не удалось установить пакет")
            }
            old.deleteRecursively()
            return pkg.id
        } finally {
            stage.deleteRecursively()
        }
    }

    fun removeModel(id: String = OfflineTranslator.MODEL_ID) {
        model(id).deleteRecursively()
    }

    fun bytes(id: String = OfflineTranslator.MODEL_ID) =
        model(id).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
