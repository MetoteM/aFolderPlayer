package dev.alex.afponline

import android.net.Uri

internal object YandexAuth {
    // Existing OAuth client used by the successful local experiment, not an AFP registration.
    private const val CLIENT = "23cabbbdc6cd418abb4b39c32c41195d"
    fun authorize(state: String): String = "https://oauth.yandex.ru/authorize?response_type=token&client_id=$CLIENT&state=${Uri.encode(state)}"
    fun navigationAllowed(raw: String): Boolean {
        val u = Uri.parse(raw); val h = u.host.orEmpty().lowercase()
        return u.scheme == "https" && u.userInfo == null && (u.port == -1 || u.port == 443) &&
            listOf("yandex.ru", "yandex.com", "yandex.kz", "yandex.by", "yandex.com.tr", "yandex.uz").any { h == it || h.endsWith(".$it") }
    }
    fun callback(raw: String, state: String): String? {
        val u = Uri.parse(raw)
        if (u.scheme != "https" || u.host != "music.yandex.ru" || u.userInfo != null || u.port !in listOf(-1,443)) return null
        val fragment = u.fragment ?: return null
        val args = Uri.parse("https://local.invalid/?$fragment")
        val token = args.getQueryParameter("access_token") ?: return null
        require(args.getQueryParameters("access_token").size == 1 && args.getQueryParameters("state").size == 1 && args.getQueryParameter("state") == state) { "Состояние входа не совпало. Повтори вход" }
        require(token.isNotEmpty() && token.length <= 8192 && token.all { it.code in 33..126 }) { "Некорректный токен" }
        return token
    }
}
