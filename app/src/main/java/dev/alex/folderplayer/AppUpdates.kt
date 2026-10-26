package dev.alex.folderplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors
import org.json.JSONArray

/** Explicit checks only. GitHub integrity plus the installed APK signing identity. */
internal class AppUpdates(
    private val host: Activity,
    private val extensions: ExtensionManager,
    private val open: (URI) -> java.net.HttpURLConnection = { ExtensionHttp.connect(it) },
) {
    companion object {
        const val RELEASES =
            "https://api.github.com/repos/MetoteM/aFolderPlayer/releases?per_page=100"
        private const val ROOT = "https://github.com/MetoteM/aFolderPlayer/releases/download/"
        private const val MAX_APK = 60_000_000L

        data class Release(
            val version: String,
            val url: URI,
            val size: Long,
            val hash: String,
            val notes: String,
        )

        fun version(value: String): List<Int>? {
            if (!value.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) return null
            return value.split('.').map { it.toIntOrNull() ?: return null }
        }

        fun releaseCandidateBase(value:String):String? = Regex("""([0-9]+\.[0-9]+\.[0-9]+)-rc\.[1-9][0-9]*""").matchEntire(value)?.groupValues?.get(1)
        fun installedVersionParts(value:String):List<Int>? = version(value) ?: releaseCandidateBase(value)?.let(::version)

        fun compare(a: List<Int>, b: List<Int>): Int {
            for (i in 0..2) {
                val c = a[i].compareTo(b[i])
                if (c != 0) return c
            }
            return 0
        }

        internal fun verifyDownload(file: File, size: Long, hash: String) {
            require(size in 1..MAX_APK && file.length() == size) { "Размер APK не совпадает" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                var total = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= size) { "Размер APK не совпадает" }
                    digest.update(buffer, 0, count)
                }
                require(total == size && digest.digest().joinToString("") { "%02x".format(it) } == hash) {
                    "APK повреждён. Скачай обновление повторно."
                }
            }
        }

        @Suppress("DEPRECATION")
        internal fun validateInfo(
            apk: PackageInfo,
            own: PackageInfo,
            expectedPackage: String,
            expectedVersion: String,
        ) {
            require(apk.packageName == expectedPackage && apk.versionName == expectedVersion) {
                "APK не соответствует обновлению плеера"
            }
            require(
                ApkIdentity.matches(apk, own)
            ) {
                "Подпись APK не совпадает. Установка отменена."
            }
            val newer =
                if (android.os.Build.VERSION.SDK_INT >= 28)
                    apk.longVersionCode > own.longVersionCode
                else apk.versionCode > own.versionCode
            require(newer) { "APK не новее установленного приложения" }
        }

        fun candidate(bytes: ByteArray, current: String): Release? {
            val installed = installedVersionParts(current) ?: error("Не удалось определить установленную версию")
            val releases = JSONArray(String(bytes, Charsets.UTF_8))
            var best: Release? = null
            for (i in 0 until releases.length()) {
                val r = releases.getJSONObject(i)
                if (
                    r.optBoolean("draft") ||
                        r.optBoolean("prerelease") ||
                        (r.isNull("published_at") || r.optString("published_at").isBlank())
                )
                    continue
                val tag = r.optString("tag_name")
                if (!tag.startsWith("v")) continue
                val name = tag.removePrefix("v")
                val v = version(name) ?: continue
                if (
                    (compare(v, installed) < 0 || (compare(v, installed)==0 && releaseCandidateBase(current)==null)) ||
                        best?.let { compare(v, version(it.version)!!) <= 0 } == true
                )
                    continue
                val assets = r.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    val filename = "aFolderPlayer_${name}.apk"
                    val url = ROOT + tag + "/" + filename
                    val hash = a.optString("digest").removePrefix("sha256:")
                    if (
                        a.optString("name") != filename ||
                            a.optString("browser_download_url") != url ||
                            a.optString("state") != "uploaded"
                    )
                        continue
                    val size = a.optLong("size")
                    if (
                        size !in 1..MAX_APK ||
                            !a.optString("digest").startsWith("sha256:") ||
                            !hash.matches(Regex("[0-9a-f]{64}"))
                    )
                        continue
                    best = Release(name, URI(url), size, hash, r.optString("body").take(3000))
                    break
                }
            }
            return best
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs = host.getSharedPreferences("updates", 0)
    private val folder = File(host.filesDir, "extensions").apply { mkdirs() }
    @Volatile private var closed = false
    @Volatile private var cancelled = false
    @Volatile private var connection: java.net.HttpURLConnection? = null
    private var busy: AlertDialog? = null

    @Suppress("DEPRECATION")
    fun installedVersion() =
        host.packageManager.getPackageInfo(host.packageName, 0).versionName.orEmpty()

    private fun run(title: String, action: () -> Unit) {
        if (closed || busy != null) return
        cancelled = false
        busy =
            AlertDialog.Builder(host)
                .setMessage(title)
                .setNegativeButton("Отмена") { _, _ -> cancel() }
                .create()
                .apply {
                    setOnCancelListener { cancel() }
                    show()
                }
        worker.execute {
            val result = runCatching(action)
            main.post {
                busy?.dismiss()
                busy = null
                if (!closed && !cancelled) result.onFailure { showError(it) }
            }
        }
    }

    private fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    private fun active() {
        check(!closed && !cancelled) { "Отменено" }
    }

    private fun showError(error: Throwable) {
        AlertDialog.Builder(host)
            .setTitle("Обновление")
            .setMessage(
                if (error is java.io.IOException)
                    "Не удалось проверить источник обновлений. Проверь подключение или попробуй позже."
                else error.message ?: "Не удалось проверить обновление. Попробуй позже."
            )
            .setPositiveButton("Закрыть", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun installedExtensions(): Map<String, ExtensionUpdates.Installed> =
        ExtensionModules.all
            .mapNotNull { module ->
                runCatching {
                        val info = host.packageManager.getPackageInfo(module.pkg, 0)
                        module.kind to
                            ExtensionUpdates.Installed(
                                info.versionName.orEmpty(),
                                info.versionCode.toLong(),
                                host.packageManager.checkSignatures(host.packageName, module.pkg) ==
                                    PackageManager.SIGNATURE_MATCH,
                            )
                    }
                    .getOrNull()
            }
            .toMap()

    fun check() =
        run("Проверяем плеер и расширения…") {
            val player = runCatching {
                val request = open(URI(RELEASES))
                connection = request
                try {
                    active()
                    check(request.responseCode == 200) {
                        "GitHub недоступен (${request.responseCode})"
                    }
                    request.inputStream.use {
                        candidate(ExtensionCatalog.read(it, 2_000_000), installedVersion())
                    }
                } finally {
                    connection = null
                    request.disconnect()
                }
            }
            active()
            val catalogue = runCatching {
                ExtensionCatalogueSource(host, open).read(::active) { connection = it }
            }
            active()
            val installed = installedExtensions()
            val models =
                if (installed["engine"]?.trusted == true)
                    runCatching {
                            TranslationClient(host.applicationContext).availableModels().keys
                        }
                        .getOrNull()
                else emptySet()
            active()
            @Suppress("DEPRECATION")
            val code = host.packageManager.getPackageInfo(host.packageName, 0).versionCode.toLong()
            val extensionRows =
                catalogue
                    .getOrNull()
                    ?.let { result ->
                        ExtensionUpdates.plan(
                            result.catalog.entries,
                            installed,
                            models,
                            code,
                            host.getSharedPreferences("extensions", 0).getBoolean("enabled", false),
                        )
                    }
                    .orEmpty()
            main.post { if (!closed && !cancelled) showUpdates(player, catalogue, extensionRows) }
        }

    private fun showUpdates(
        player: Result<Release?>,
        catalogue: Result<ExtensionCatalogueSource.Result>,
        rows: List<ExtensionUpdates.Row>,
    ) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<(() -> Unit)?>()
        val release = player.getOrNull()
        labels +=
            when {
                player.isFailure -> "aFolderPlayer · не удалось проверить"
                release != null -> "aFolderPlayer · ${installedVersion()} → ${release.version}"
                else -> "aFolderPlayer · ${installedVersion()} · актуально"
            }
        actions +=
            if (release != null) ({ offerPlayer(release) })
            else if (player.isFailure) ({ showError(player.exceptionOrNull()!!) }) else null
        if (catalogue.isFailure) {
            labels += "Расширения · не удалось проверить"
            actions += { showError(catalogue.exceptionOrNull()!!) }
        } else {
            for (row in rows) {
                labels += row.title + "\n" + row.detail
                actions += {
                    val entry = row.entry
                    if (entry != null) extensions.offerUpdate(catalogue.getOrThrow().base, entry)
                    else
                        AlertDialog.Builder(host)
                            .setTitle(row.title)
                            .setMessage(row.detail)
                            .setPositiveButton("Закрыть", null)
                            .show()
                }
            }
            if (rows.isEmpty()) {
                labels += "Расширения не установлены"
                actions += null
            }
        }
        AlertDialog.Builder(host)
            .setTitle("Обновления плеера и расширений")
            .setItems(labels.toTypedArray()) { _, index -> actions[index]?.invoke() }
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("Расширения") { _, _ -> extensions.show() }
            .show()
    }

    private fun offerPlayer(release: Release) {
        AlertDialog.Builder(host)
            .setTitle("aFolderPlayer ${release.version}")
            .setMessage(
                "Установлена ${installedVersion()}\nРазмер · ${"%.1f".format(release.size / 1_000_000.0)} МБ" +
                    if (release.notes.isBlank()) "" else "\n\n${release.notes}"
            )
            .setPositiveButton("Скачать") { _, _ -> download(release) }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun download(release: Release) =
        run("Скачиваем ${release.version}…") {
            val part = File(folder, "player-update.apk.part")
            val file = File(folder, "player-update.apk")
            val c = ExtensionHttp.connect(release.url)
            connection = c
            try {
                require(c.responseCode == 200) {
                    "Не удалось скачать обновление (${c.responseCode})"
                }
                var size = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                c.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            active()
                            val n = input.read(buffer)
                            if (n < 0) break
                            size += n
                            require(size <= release.size) { "Размер APK не совпадает" }
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                        }
                    }
                }
                require(
                    size == release.size &&
                        digest.digest().joinToString("") { "%02x".format(it) } == release.hash
                ) {
                    "APK повреждён. Установка отменена."
                }
                validate(part, release.version)
                active()
                check(part.renameTo(file)) { "Не удалось сохранить APK" }
                prefs.edit().putString("downloadVersion", release.version)
                    .putString("downloadHash", release.hash).putLong("downloadSize", release.size).apply()
                main.post { if (!closed && !cancelled) install() }
            } finally {
                connection = null
                c.disconnect()
                part.delete()
            }
        }

    @Suppress("DEPRECATION")
    internal fun validate(file: File, expectedVersion: String) {
        val pm = host.packageManager
        val apk =
            pm.getPackageArchiveInfo(file.absolutePath, ApkIdentity.flags)
                ?: error("Не удалось прочитать APK обновления")
        val own = pm.getPackageInfo(host.packageName, ApkIdentity.flags)
        validateInfo(apk, own, host.packageName, expectedVersion)
    }

    private fun install() {
        if (!host.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(host)
                .setTitle("Установка обновления")
                .setMessage(
                    "Разреши aFolderPlayer устанавливать обновления. После возврата откроется установка скачанного обновления."
                )
                .setPositiveButton("Разрешить") { _, _ ->
                    prefs.edit().putBoolean("pendingInstall", true).apply()
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
        prefs.edit().putString("reviewAfterVersion", installedVersion()).apply()
        host.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(
                    Uri.parse("content://dev.alex.folderplayer.extensions/player-update.apk"),
                    "application/vnd.android.package-archive",
                )
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    fun resume() {
        val component = ExtensionModules.find(prefs.getString("reviewComponentKind", null) ?: "")
        if (component != null) {
            val installed = installedExtensions()[component.kind]
            if (
                installed != null &&
                    installed.trusted &&
                    installed.code > prefs.getInt("reviewComponentCode", 0)
            ) {
                prefs.edit().remove("reviewComponentKind").remove("reviewComponentCode").apply()
                if (component.kind == "engine") extensions.enable()
                check()
                return
            }
        }
        val previous = prefs.getString("reviewAfterVersion", null)
        if (previous != null && previous != installedVersion()) {
            prefs.edit().remove("reviewAfterVersion").remove("pendingInstall").apply()
            check()
            return
        }
        if (!prefs.getBoolean("pendingInstall", false)) return
        prefs.edit().remove("pendingInstall").apply()
        val file = File(folder, "player-update.apk")
        if (file.isFile && host.packageManager.canRequestPackageInstalls()) {
            run("Проверяем скачанное обновление…") {
                try {
                    val expectedVersion = prefs.getString("downloadVersion", null)
                        ?: error("Скачай обновление повторно")
                    val expectedHash = prefs.getString("downloadHash", null)
                        ?: error("Скачай обновление повторно")
                    verifyDownload(file, prefs.getLong("downloadSize", -1), expectedHash)
                    validate(file, expectedVersion)
                    active()
                    main.post { if (!closed && !cancelled) install() }
                } catch (error: Exception) {
                    file.delete()
                    throw error
                }
            }
        }
    }

    fun close() {
        closed = true
        cancel()
        busy?.dismiss()
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }
}
