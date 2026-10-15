package dev.alex.afponline

import org.junit.Assert.*
import org.junit.Test

class ConnectorRegistryTest {
    @Test
    fun firstConnectorIsZvukAndDoesNotPretendToPlay() {
        val connector = ConnectorRegistry().connectors.single()
        assertEquals("zvuk", connector.id)
        assertEquals("unavailable", connector.status)
        assertTrue(connector.capabilities.isEmpty())
    }

    @Test
    fun duplicateProviderCannotOverrideRegisteredConnector() {
        try {
            ConnectorRegistry(listOf(ZvukConnector(), ZvukConnector()))
            fail()
        } catch (_: IllegalArgumentException) {}
    }
}
