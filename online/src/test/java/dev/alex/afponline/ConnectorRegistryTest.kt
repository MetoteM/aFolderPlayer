package dev.alex.afponline

import org.junit.Assert.*
import org.junit.Test

class ConnectorRegistryTest {
    @Test
    fun onlyActiveConnectorIsYandexExperiment() {
        val connector = ConnectorRegistry().connectors.single()
        assertEquals("yandex", connector.id)
        assertEquals("experimental", connector.status)
        assertEquals(setOf("search", "collection", "stream"),connector.capabilities)
    }

    @Test
    fun duplicateProviderCannotOverrideRegisteredConnector() {
        try {
            ConnectorRegistry(listOf(ZvukConnector(), ZvukConnector()))
            fail()
        } catch (_: IllegalArgumentException) {}
    }
}
