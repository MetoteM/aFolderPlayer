package dev.alex.folderplayer

/** Module identity and compatibility are separate from the catalogue's latest version. */
internal object ExtensionModules {
    data class Module(
        val kind: String,
        val title: String,
        val pkg: String,
        val prefix: String,
        val minimumCode: Long,
        val minimumVersion: String,
    )

    val translation =
        Module("engine", "AFP Translate", TranslationClient.PACKAGE, "AFP_Translate_", 8, "0.3.5")
    val online = Module("online", "Онлайн", "dev.alex.afponline", "AFP_Online_", 1, "0.1.0")
    val all = listOf(translation, online)

    fun find(kind: String) = all.firstOrNull { it.kind == kind }
}

internal object ExtensionUpdates {
    data class Installed(val version: String, val code: Long, val trusted: Boolean)

    enum class State {
        CURRENT,
        AVAILABLE,
        REQUIRED,
        OPTIONAL,
        UNKNOWN,
        BLOCKED,
    }

    data class Row(
        val title: String,
        val detail: String,
        val state: State,
        val entry: ExtensionCatalog.Entry? = null,
    )

    fun targetVersion(entry: ExtensionCatalog.Entry): String? {
        val module = ExtensionModules.find(entry.kind) ?: return null
        val value =
            entry.versionName.ifBlank {
                entry.file.removePrefix(module.prefix).removeSuffix(".apk")
            }
        return value.takeIf { AppUpdates.version(it) != null }
    }

    private fun newer(a: String, b: String): Boolean? {
        val av = AppUpdates.version(a) ?: return null
        val bv = AppUpdates.version(b) ?: return null
        return AppUpdates.compare(av, bv) > 0
    }

    fun plan(
        entries: List<ExtensionCatalog.Entry>,
        installed: Map<String, Installed>,
        models: Set<String>?,
        playerCode: Long,
        translationEnabled: Boolean,
    ): List<Row> {
        val rows = mutableListOf<Row>()
        for (module in ExtensionModules.all) {
            val old = installed[module.kind]
            val entry = entries.firstOrNull { it.kind == module.kind }
            if (old == null && entry == null) continue
            val target = entry?.let(::targetVersion)
            if (old != null && !old.trusted) {
                rows +=
                    Row(
                        module.title,
                        "Подпись установленного расширения не подтверждена",
                        State.BLOCKED,
                    )
                continue
            }
            val required =
                old != null && old.code < module.minimumCode ||
                    old == null && module.kind == "engine" && translationEnabled
            if (entry != null && entry.minimumPlayer > playerCode) {
                rows +=
                    Row(
                        module.title,
                        "Сначала обнови плеер; расширению нужна более новая версия приложения",
                        State.BLOCKED,
                    )
            } else if (old == null) {
                rows +=
                    Row(
                        module.title,
                        if (required) "Для перевода требуется установка"
                        else "Не установлен · по желанию",
                        if (required) State.REQUIRED else State.OPTIONAL,
                        entry?.takeIf {
                            target != null &&
                                (!required || newer(module.minimumVersion, target) == false)
                        },
                    )
            } else if (required) {
                val suitable = target != null && newer(module.minimumVersion, target) == false
                rows +=
                    Row(
                        module.title,
                        "Установлена ${old.version}. Нужна ${module.minimumVersion} или новее" +
                            (if (module.kind == "engine")
                                " для восстановления незавершённого перевода"
                            else "") +
                            (if (!suitable) ". В источнике нет подходящей версии" else ""),
                        State.REQUIRED,
                        entry?.takeIf { suitable },
                    )
            } else if (target == null) {
                rows +=
                    Row(
                        module.title,
                        "Установлена ${old.version}; в каталоге нет данных для сравнения",
                        State.UNKNOWN,
                    )
            } else
                when (newer(target, old.version)) {
                    true ->
                        rows +=
                            Row(module.title, "${old.version} → $target", State.AVAILABLE, entry)
                    false ->
                        rows +=
                            Row(
                                module.title,
                                "Установлена ${old.version} · обновление не требуется",
                                State.CURRENT,
                            )
                    null -> rows += Row(module.title, "Не удалось сравнить версии", State.UNKNOWN)
                }
        }
        if (installed["engine"]?.trusted == true) {
            if (models == null)
                rows +=
                    Row(
                        "Языковые пакеты",
                        "Не удалось проверить установленные пакеты",
                        State.UNKNOWN,
                    )
            else {
                val available = entries.filter { it.kind == "model" }
                for (id in models) {
                    val entry = available.firstOrNull { it.model == id }
                    if (entry != null)
                        rows +=
                            Row(
                                entry.title,
                                "Установлен · повторная загрузка не нужна",
                                State.CURRENT,
                            )
                    else
                        rows +=
                            Row(
                                TranslationClient.MODELS[id] ?: id,
                                "Установлен; в текущем каталоге не представлен",
                                State.UNKNOWN,
                            )
                }
                for (entry in available.filter { it.model !in models }) {
                    val match = Regex("(.+)-v([0-9]+)").matchEntire(entry.model) ?: continue
                    val replacement =
                        models.any { old ->
                            val previous = Regex("(.+)-v([0-9]+)").matchEntire(old)
                            previous != null &&
                                previous.groupValues[1] == match.groupValues[1] &&
                                previous.groupValues[2].toLongOrNull()?.let { n ->
                                    match.groupValues[2].toLongOrNull()?.let { it > n }
                                } == true
                        }
                    if (replacement) {
                        val engineReady = installed["engine"]!!.code >= entry.minimumEngine
                        val playerReady = entry.minimumPlayer <= playerCode
                        rows +=
                            Row(
                                entry.title,
                                if (!playerReady) "Новая редакция; сначала обнови плеер"
                                else if (engineReady) "Доступна новая редакция · по желанию"
                                else "Новая редакция; сначала обнови AFP Translate",
                                if (engineReady && playerReady) State.AVAILABLE else State.BLOCKED,
                                entry.takeIf { engineReady && it.minimumPlayer <= playerCode },
                            )
                    }
                }
                if (models.isEmpty())
                    rows +=
                        Row(
                            "Языковые пакеты",
                            "Не установлены. Добавить язык можно в расширениях",
                            State.OPTIONAL,
                        )
            }
        }
        return rows
    }
}
