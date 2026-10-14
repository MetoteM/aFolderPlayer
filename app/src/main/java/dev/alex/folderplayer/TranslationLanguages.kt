package dev.alex.folderplayer

internal object TranslationLanguages {
    fun title(model: String): String =
        when {
            model.startsWith("opus-en-") -> "Английский"
            model.startsWith("opus-de-") -> "Немецкий"
            model.startsWith("opus-fr-") -> "Французский"
            model.startsWith("opus-es-") -> "Испанский"
            model.startsWith("opus-zh-") -> "Китайский"
            else -> TranslationClient.MODELS[model] ?: model
        }

    fun choices(models: List<Pair<String, String>>): List<Pair<String, String>> =
        models
            .filterNot {
                it.first == TranslationClient.MODEL &&
                    models.any { model -> model.first == "opus-en-ru-v2" }
            }
            .map { it.first to title(it.first) }
}
