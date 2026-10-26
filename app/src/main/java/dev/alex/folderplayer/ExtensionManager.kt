package dev.alex.folderplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

internal class ExtensionManager(
    private val host: Activity,
    private val engine: TranslationClient,
    private val message: (String) -> Unit,
    private val importModel: () -> Unit,
    private val changed: () -> Unit = {},
    private val online: () -> Unit = {},
    private val updates: () -> Unit = {},
) {
    companion object {
        const val DEFAULT_CATALOG =
            "https://github.com/MetoteM/aFolderPlayer/releases/latest/download/"
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs =
        host.getSharedPreferences("extensions", 0).also { preferences ->
            if (
                preferences.getString("server", null) ==
                    "https://github.com/MetoteM/aFolderPlayer/releases/download/v0.8.0/"
            ) {
                preferences.edit().putString("server", DEFAULT_CATALOG).apply()
            }
        }
    private val folder = File(host.filesDir, "extensions").apply { mkdirs() }
    @Volatile private var closed = false
    private var busy: AlertDialog? = null
    private var progress: ProgressBar? = null
    private var progressText: TextView? = null
    @Volatile private var cancelled = false
    @Volatile private var connection: java.net.HttpURLConnection? = null

    fun show() {
        val installed = engine.installed()
        val choices = mutableListOf<String>()
        choices.add("Перевод: " + if (prefs.getBoolean("enabled", false)) "включён" else "выключен")
        choices.add("Скачать расширения")
        choices.add("Проверить обновления")
        choices.add("Источник загрузки")
        choices.add("Онлайн")
        choices.add("Установить переводчик из файла")
        if (installed) {
            choices.add("Пакет для отдельных документов")
            choices.add("Добавить язык из файла")
            choices.add("Установленный язык")
            choices.add("Удалить движок AFP Translate")
            if (File(host.filesDir, "translation/opus-en-ru-v1/verified").isFile)
                choices.add("Перенести модель из 0.4.0")
        }
        if (File(host.filesDir, "translation/opus-en-ru-v1").exists())
            choices.add("Удалить оставшуюся модель из 0.4.0")
        optionMenu(host, "Расширения", choices) { index ->
            when (choices[index]) {
                choices[0] -> {
                    prefs.edit().putBoolean("enabled", !prefs.getBoolean("enabled", false)).apply()
                    changed()
                    choices[0] =
                        "Перевод: " +
                            if (prefs.getBoolean("enabled", false)) "включён" else "выключен"
                }
                "Скачать расширения" -> fetchCatalog()
                "Проверить обновления" -> updates()
                "Источник загрузки" -> address()
                "Онлайн" -> online()
                "Установить переводчик из файла" ->
                    host.startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .setType("application/vnd.android.package-archive")
                            .addCategory(Intent.CATEGORY_OPENABLE),
                        104,
                    )
                "Пакет для отдельных документов" ->
                    run("Проверяем установленные языки…") {
                        val models = engine.availableModels().toList()
                        main.post {
                            if (!closed && !cancelled) {
                                if (models.isEmpty()) message("Сначала установи языковой пакет")
                                else
                                    AlertDialog.Builder(host)
                                        .setTitle("Пакет для отдельных документов")
                                        .setMessage(
                                            "Для песен язык определяется автоматически. Этот выбор используется только для документов без выбранного языка."
                                        )
                                        .setSingleChoiceItems(
                                            models.map { it.second }.toTypedArray(),
                                            models.indexOfFirst { it.first == engine.modelId },
                                        ) { dialog, index ->
                                            engine.selectModel(
                                                models[index].first,
                                                models[index].second,
                                            )
                                            changed()
                                            dialog.dismiss()
                                        }
                                        .setNegativeButton("Отмена", null)
                                        .show()
                            }
                        }
                    }
                "Добавить язык из файла" -> importModel()
                "Установленный язык" ->
                    run("Проверяем пакет…") {
                        val bytes = engine.modelBytes()
                        main.post {
                            if (!closed)
                                AlertDialog.Builder(host)
                                    .setTitle(engine.modelTitle)
                                    .setMessage(
                                        if (bytes < 0) "Пакет не установлен"
                                        else "На устройстве: ${bytes/1_000_000} МБ"
                                    )
                                    .setPositiveButton("Закрыть", null)
                                    .apply {
                                        if (bytes >= 0)
                                            setNegativeButton("Удалить пакет") { _, _ ->
                                                run("Удаляем пакет…") {
                                                    engine.removeModel()
                                                    main.post {
                                                        message(
                                                            "Пакет удалён. Сохранённые переводы остались"
                                                        )
                                                    }
                                                }
                                            }
                                    }
                                    .show()
                        }
                    }
                "Перенести модель из 0.4.0" ->
                    run("Переносим модель в AFP Translate…") {
                        val old = File(host.filesDir, "translation/opus-en-ru-v1")
                        val zip = File(folder, "migration.zip")
                        try {
                            java.util.zip.ZipOutputStream(zip.outputStream()).use { out ->
                                out.setLevel(0)
                                for (name in
                                    listOf("encoder.onnx", "decoder.onnx", "tokenizer.json")) {
                                    check(!cancelled) { "Отменено" }
                                    out.putNextEntry(java.util.zip.ZipEntry(name))
                                    File(old, name).inputStream().use { it.copyTo(out) }
                                    out.closeEntry()
                                }
                            }
                            ParcelFileDescriptor.open(zip, ParcelFileDescriptor.MODE_READ_ONLY)
                                .use { engine.install(it) }
                            enable()
                            main.post {
                                if (!closed) {
                                    changed()
                                    message(
                                        "Модель перенесена. Старую копию можно удалить в расширениях"
                                    )
                                }
                            }
                        } finally {
                            zip.delete()
                        }
                    }
                "Удалить движок AFP Translate" ->
                    host.startActivity(
                        Intent(
                            Intent.ACTION_DELETE,
                            Uri.parse("package:${TranslationClient.PACKAGE}"),
                        )
                    )
                else ->
                    AlertDialog.Builder(host)
                        .setTitle("Модель из 0.4.0")
                        .setMessage(
                            "Удалить старую модель из плеера? Сохранённые переводы останутся. Для отдельного движка пакет устанавливается заново."
                        )
                        .setPositiveButton("Удалить") { _, _ ->
                            run("Удаляем старую модель…") {
                                File(host.filesDir, "translation/opus-en-ru-v1").deleteRecursively()
                                main.post { message("Старая модель удалена") }
                            }
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
            }
        }
    }

    fun enabled() = prefs.getBoolean("enabled", false)

    fun enable() {
        prefs.edit().putBoolean("enabled", true).apply()
    }

    private fun address() {
        val field =
            EditText(host).apply {
                setText(prefs.getString("server", DEFAULT_CATALOG))
                hint = "https://…/afp/"
                inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_URI
            }
        val dialog =
            AlertDialog.Builder(host)
                .setTitle("Источник расширений")
                .setMessage(
                    "Собственный HTTPS-сервер или зеркало. Адрес сохраняется без подключения; запрос — только по кнопке загрузки."
                )
                .setView(field)
                .setPositiveButton("Сохранить", null)
                .setNegativeButton("Отмена", null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                runCatching {
                        val value = field.text.toString().trim()
                        if (value.isNotEmpty()) ExtensionCatalog.base(value)
                        prefs.edit().putString("server", value).apply()
                        dialog.dismiss()
                    }
                    .onFailure { field.error = it.message }
            }
        }
        dialog.show()
    }

    private fun run(title: String, total: Long = 0, action: () -> Unit) {
        if (busy != null) return
        cancelled = false
        val content =
            LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 16, 32, 16)
            }
        progressText = TextView(host).apply { text = title }
        content.addView(progressText)
        progress =
            ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                isIndeterminate = total <= 0
            }
        content.addView(progress)
        busy =
            AlertDialog.Builder(host)
                .setView(content)
                .setNegativeButton("Отмена") { _, _ ->
                    cancelled = true
                    connection?.disconnect()
                }
                .create()
                .apply {
                    setOnCancelListener {
                        cancelled = true
                        connection?.disconnect()
                    }
                    show()
                }
        worker.execute {
            val result = runCatching(action)
            main.post {
                busy?.dismiss()
                busy = null
                progress = null
                progressText = null
                if (!closed && !cancelled)
                    result.onFailure { message(it.message ?: "Не удалось выполнить действие") }
            }
        }
    }

    private fun downloadProgress(done: Long, total: Long, title: String) {
        main.post {
            if (!closed && !cancelled && busy != null) {
                progress?.isIndeterminate = false
                progress?.progress = ExtensionProgress.scaled(done, total)
                progressText?.text = ExtensionProgress.label(title, done, total)
            }
        }
    }

    private fun phase(title: String) {
        main.post {
            if (!closed && !cancelled) {
                progress?.isIndeterminate = true
                progressText?.text = title
            }
        }
    }

    private fun fetchCatalog() {
        run("Загружаем каталог…") {
            val result =
                ExtensionCatalogueSource(host)
                    .read({ check(!closed && !cancelled) { "Отменено" } }, { connection = it })
            main.post {
                if (!closed && !cancelled)
                    AlertDialog.Builder(host)
                        .setTitle("Выбери расширение")
                        .setItems(
                            result.catalog.entries
                                .map { "${it.title} · ${it.size / 1_000_000} МБ" }
                                .toTypedArray()
                        ) { _, index ->
                            confirm(result.base, result.catalog.entries[index])
                        }
                        .setNegativeButton("Закрыть", null)
                        .show()
            }
        }
    }

    fun offerUpdate(base: java.net.URI, entry: ExtensionCatalog.Entry) {
        confirm(base, entry)
    }

    private fun confirm(base: java.net.URI, entry: ExtensionCatalog.Entry) {
        if(!ExtensionUpdates.compatible(entry)) {
            message("В источнике нет совместимой версии расширения. Проверь обновления или выбери другой источник")
            return
        }
        @Suppress("DEPRECATION")
        val installedCode =
            runCatching {
                    host.packageManager
                        .getPackageInfo(TranslationClient.PACKAGE, 0)
                        .versionCode
                        .toLong()
                }
                .getOrDefault(0)
        @Suppress("DEPRECATION")
        val playerCode =
            host.packageManager.getPackageInfo(host.packageName, 0).versionCode.toLong()
        if (entry.minimumPlayer > playerCode) {
            message("Сначала обнови aFolderPlayer")
            return
        }
        if (entry.kind == "model" && installedCode < entry.minimumEngine) {
            AlertDialog.Builder(host)
                .setTitle("Обнови переводчик")
                .setMessage(
                    "Для этого языка нужен новый переводчик. В списке расширений сначала выбери «Переводчик», установи обновление, затем добавь язык."
                )
                .setPositiveButton("К списку") { _, _ -> fetchCatalog() }
                .setNegativeButton("Закрыть", null)
                .show()
            return
        }
        if (entry.kind == "model" && !engine.installed()) {
            message("Сначала установи AFP Translate")
            return
        }
        AlertDialog.Builder(host)
            .setTitle(entry.title)
            .setMessage(
                "Скачать ${entry.size/1_000_000} МБ с ${base.host}?" +
                    (if (entry.kind != "model") " Затем Android попросит подтвердить установку."
                    else " Пакет будет установлен в AFP Translate.")
            )
            .setPositiveButton("Скачать") { _, _ -> download(base, entry) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun download(base: java.net.URI, entry: ExtensionCatalog.Entry) =
        run("Подключаемся для загрузки ${entry.title}…", entry.size) {
            val destination =
                File(
                    folder,
                    when (entry.kind) {
                        "engine" -> "engine.apk"
                        "online" -> "online.apk"
                        else -> "model.zip"
                    },
                )
            val part = File(folder, destination.name + ".part")
            val c = ExtensionCatalog.connect(entry.address(base))
            connection = c
            try {
                check(c.responseCode == 200) { "Сервер загрузки недоступен (${c.responseCode})" }
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                var lastProgress = 0L
                downloadProgress(0, entry.size, entry.title)
                c.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            check(!cancelled && !closed) { "Загрузка отменена" }
                            val n = input.read(buffer)
                            if (n < 0) break
                            size += n
                            check(size <= entry.size) { "Размер файла не совпадает" }
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastProgress >= 200 || size == entry.size) {
                                downloadProgress(size, entry.size, entry.title)
                                lastProgress = now
                            }
                        }
                    }
                }
                phase("Проверяем скачанный файл…")
                check(
                    size == entry.size &&
                        digest.digest().joinToString("") { "%02x".format(it) } == entry.hash
                ) {
                    "Файл повреждён: проверка не пройдена"
                }
                check(!cancelled && !closed) { "Загрузка отменена" }
                check(part.renameTo(destination)) { "Не удалось сохранить файл" }
                if (entry.kind != "model") {
                    validateComponent(
                        destination,
                        entry.kind,
                        ExtensionUpdates.targetVersion(entry),
                    )
                    main.post { if (!closed && !cancelled) installComponent(entry.kind) }
                } else {
                    phase("Устанавливаем языковой пакет…")
                    ParcelFileDescriptor.open(destination, ParcelFileDescriptor.MODE_READ_ONLY)
                        .use { engine.install(it) }
                    engine.selectModel(entry.model, entry.title)
                    enable()
                    destination.delete()
                    main.post {
                        if (!closed) {
                            changed()
                            message("Языковой пакет установлен")
                        }
                    }
                }
            } finally {
                connection = null
                c.disconnect()
                part.delete()
            }
        }

    fun importEngine(uri: Uri) =
        run("Проверяем переводчик…") {
            val file = File(folder, "engine.apk")
            val part = File(folder, "engine.apk.part")
            try {
                host.contentResolver.openInputStream(uri)?.use { input ->
                    part.outputStream().use { out ->
                        var count = 0L
                        val buffer = ByteArray(65536)
                        while (true) {
                            check(!cancelled) { "Отменено" }
                            val n = input.read(buffer)
                            if (n < 0) break
                            count += n
                            require(count <= 60_000_000) { "APK слишком большой" }
                            out.write(buffer, 0, n)
                        }
                    }
                } ?: error("Не удалось открыть APK")
                validateEngine(part)
                check(part.renameTo(file))
                main.post { if (!closed && !cancelled) installEngine() }
            } finally {
                part.delete()
            }
        }

    @Suppress("DEPRECATION")
    private fun validateEngine(file: File) = validateComponent(file, "engine")

    @Suppress("DEPRECATION")
    private fun validateComponent(file: File, kind: String, expectedVersion: String? = null) {
        val module = ExtensionModules.find(kind) ?: error("Неизвестное расширение")
        val pm = host.packageManager
        val info =
            pm.getPackageArchiveInfo(file.absolutePath, ApkIdentity.flags)
                ?: error("Не удалось прочитать APK")
        require(expectedVersion == null || info.versionName == expectedVersion) {
            "Версия APK не соответствует каталогу"
        }
        require(info.versionCode.toLong()>=module.minimumCode) { "Версия расширения несовместима с этим плеером" }
        require(info.packageName == module.pkg) { "Это не ${module.title}" }
        val own = pm.getPackageInfo(host.packageName, ApkIdentity.flags)
        require(
            ApkIdentity.matches(info, own)
        ) {
            "Подпись расширения не совпадает с плеером"
        }
        val installed = runCatching { pm.getPackageInfo(module.pkg, 0).versionCode }.getOrNull()
        require(installed == null || info.versionCode >= installed) {
            "Расширение старее установленного"
        }
    }

    private fun installEngine() = installComponent("engine")

    private fun installComponent(kind: String) {
        val module = ExtensionModules.find(kind) ?: error("Неизвестное расширение")
        val file = File(folder, "$kind.apk")
        validateComponent(file, kind)
        if (!host.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(host)
                .setTitle("Установка расширения")
                .setMessage(
                    "Разреши aFolderPlayer устанавливать выбранный APK. После возврата откроется установка; файл уже скачан."
                )
                .setPositiveButton("Разрешить") { _, _ ->
                    prefs
                        .edit()
                        .putBoolean("pendingInstall", true)
                        .putString("pendingKind", kind)
                        .apply()
                    host.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${host.packageName}"),
                        )
                    )
                }
                .setNegativeButton("Позже", null)
                .show()
            return
        }
        @Suppress("DEPRECATION")
        val previousCode =
            runCatching { host.packageManager.getPackageInfo(module.pkg, 0).versionCode }
                .getOrDefault(0)
        host
            .getSharedPreferences("updates", 0)
            .edit()
            .putString("reviewComponentKind", kind)
            .putInt("reviewComponentCode", previousCode)
            .apply()
        host.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(
                    Uri.parse("content://dev.alex.folderplayer.extensions/$kind.apk"),
                    "application/vnd.android.package-archive",
                )
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    fun resume() {
        if (
            prefs.getBoolean("pendingInstall", false) &&
                host.packageManager.canRequestPackageInstalls()
        ) {
            prefs.edit().putBoolean("pendingInstall", false).apply()
            val kind = prefs.getString("pendingKind", "engine") ?: "engine"
            if (ExtensionModules.find(kind) != null && File(folder, "$kind.apk").isFile) {
                runCatching { installComponent(kind) }
                    .onFailure {
                        File(folder, "$kind.apk").delete()
                        message(it.message ?: "Не удалось продолжить установку")
                    }
            }
        }
    }

    fun close() {
        closed = true
        cancelled = true
        connection?.disconnect()
        busy?.dismiss()
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }
}
