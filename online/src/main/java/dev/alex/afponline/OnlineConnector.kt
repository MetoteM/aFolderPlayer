package dev.alex.afponline

/** Connectors own service-specific authentication and transport; the player sees capabilities. */
internal interface OnlineConnector {
    val id: String
    val title: String
    val status: String
    val explanation: String
    val capabilities: Set<String>
}

/** Discovery placeholder, not a working service client. No credentials or network requests. */
internal class ZvukConnector : OnlineConnector {
    override val id = "zvuk"
    override val title = "Звук"
    override val status = "unavailable"
    override val explanation = "Есть отдельный эксперимент: ручное подключение сессии и проверка потока. Автоматический вход пока недоступен."
    override val capabilities = emptySet<String>()
}

internal class ConnectorRegistry(val connectors: List<OnlineConnector> = listOf(YandexConnector())) {
    init {
        require(connectors.map { it.id }.toSet().size == connectors.size) { "Duplicate connector" }
        require(connectors.all { it.id.matches(Regex("[a-z][a-z0-9_-]{0,39}")) })
    }
}

internal class YandexConnector : OnlineConnector {
    override val id = "yandex"
    override val title = "Яндекс Музыка"
    override val status = "experimental"
    override val explanation = "Поиск, коллекция и полный MP3 проверены на телефоне. Вход и воспроизведение через основной плеер — эксперимент."
    override val capabilities = setOf("search", "collection", "stream")
}
