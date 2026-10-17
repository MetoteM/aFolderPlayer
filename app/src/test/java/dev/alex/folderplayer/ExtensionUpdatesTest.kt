package dev.alex.folderplayer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExtensionUpdatesTest {
    private fun engine(version: String = "0.3.5") =
        ExtensionCatalog.Entry(
            JSONObject()
                .put("id", "engine")
                .put("kind", "engine")
                .put("title", "Переводчик")
                .put("file", "AFP_Translate_$version.apk")
                .put("sha256", "a".repeat(64))
                .put("size", 100)
        )

    private fun model(id: String, minimumEngine: Int = 0) =
        ExtensionCatalog.Entry(
            JSONObject()
                .put("id", id)
                .put("kind", "model")
                .put("title", id)
                .put("model", id)
                .put("file", "$id.zip")
                .put("sha256", "a".repeat(64))
                .put("size", 100)
                .put("minimumEngine", minimumEngine)
        )

    private fun installed(version: String = "0.3.5", code: Long = 8, trusted: Boolean = true) =
        mapOf("engine" to ExtensionUpdates.Installed(version, code, trusted))

    @Test
    fun olderEngineIsRequiredEvenWhenPlayerItselfHasNoUpdate() {
        val row =
            ExtensionUpdates.plan(listOf(engine()), installed("0.3.4", 7), emptySet(), 39, true)
                .first()
        assertEquals(ExtensionUpdates.State.REQUIRED, row.state)
        assertNotNull(row.entry)
        assertTrue(row.detail.contains("восстановления"))
    }

    @Test
    fun optionalExtensionsDoNotBecomeMandatoryForOfflinePlayer() {
        val row =
            ExtensionUpdates.plan(listOf(engine()), emptyMap(), emptySet(), 39, false).single()
        assertEquals(ExtensionUpdates.State.OPTIONAL, row.state)
        assertTrue(row.detail.contains("по желанию"))
    }

    @Test
    fun unchangedInstalledModelsNeverOfferRedownload() {
        val rows =
            ExtensionUpdates.plan(
                listOf(engine(), model("opus-en-ru-v2")),
                installed(),
                setOf("opus-en-ru-v2"),
                39,
                true,
            )
        val row = rows.single { it.title == "opus-en-ru-v2" }
        assertEquals(ExtensionUpdates.State.CURRENT, row.state)
        assertNull(row.entry)
    }

    @Test
    fun newModelEditionIsOptionalAndWaitsForEngineDependency() {
        val entry = model("opus-en-ru-v2", 9)
        var row =
            ExtensionUpdates.plan(
                    listOf(engine(), entry),
                    installed(),
                    setOf("opus-en-ru-v1"),
                    39,
                    true,
                )
                .last()
        assertEquals(ExtensionUpdates.State.BLOCKED, row.state)
        assertNull(row.entry)
        row =
            ExtensionUpdates.plan(
                    listOf(engine("0.3.6"), entry),
                    installed("0.3.6", 9),
                    setOf("opus-en-ru-v1"),
                    39,
                    true,
                )
                .last()
        assertEquals(ExtensionUpdates.State.AVAILABLE, row.state)
        assertNotNull(row.entry)
    }

    @Test
    fun unavailableModelInspectionIsNotReportedAsUpToDate() {
        val row = ExtensionUpdates.plan(listOf(engine()), installed(), null, 39, true).last()
        assertEquals(ExtensionUpdates.State.UNKNOWN, row.state)
        assertTrue(row.detail.contains("Не удалось"))
    }

    @Test
    fun untrustedInstallationDoesNotReceiveAutomaticReplacement() {
        val row =
            ExtensionUpdates.plan(
                    listOf(engine("0.3.6")),
                    installed(trusted = false),
                    emptySet(),
                    39,
                    true,
                )
                .single()
        assertEquals(ExtensionUpdates.State.BLOCKED, row.state)
        assertNull(row.entry)
    }

    @Test
    fun onlineUsesIndependentVersionAndPlayerRequirement() {
        val obj =
            JSONObject()
                .put("id", "online")
                .put("kind", "online")
                .put("title", "Онлайн")
                .put("file", "AFP_Online_0.2.2.apk")
                .put("sha256", "a".repeat(64))
                .put("size", 100)
        val installed = mapOf("online" to ExtensionUpdates.Installed("0.2.1", 12, true))
        assertEquals(
            ExtensionUpdates.State.AVAILABLE,
            ExtensionUpdates.plan(
                    listOf(ExtensionCatalog.Entry(obj)),
                    installed,
                    emptySet(),
                    39,
                    false,
                )
                .single()
                .state,
        )
        obj.put("minimumPlayer", 40)
        val row =
            ExtensionUpdates.plan(
                    listOf(ExtensionCatalog.Entry(obj)),
                    installed,
                    emptySet(),
                    39,
                    false,
                )
                .single()
        assertEquals(ExtensionUpdates.State.BLOCKED, row.state)
        assertNull(row.entry)
    }

    @Test
    fun legacyCatalogueFilenameStillSuppliesEngineVersion() {
        assertEquals("0.3.5", ExtensionUpdates.targetVersion(engine()))
    }
    @Test fun legacyOnlineRequiresCompatibleProtocolAndRcCanMoveToStable() {
        fun entry(version:String)=ExtensionCatalog.Entry(JSONObject().put("id","online").put("kind","online").put("title","Online")
            .put("file","AFP_Online_$version.apk").put("sha256","a".repeat(64)).put("size",100))
        val fresh=ExtensionUpdates.plan(listOf(entry("0.1.1")),emptyMap(),emptySet(),51,false).single()
        assertEquals(ExtensionUpdates.State.OPTIONAL,fresh.state);assertNull(fresh.entry)
        assertFalse(ExtensionUpdates.compatible(entry("0.1.1")));assertTrue(ExtensionUpdates.compatible(entry("0.2.1")))
        val legacy=mapOf("online" to ExtensionUpdates.Installed("0.1.0",1,true))
        val incompatible=ExtensionUpdates.plan(listOf(entry("0.1.1")),legacy,emptySet(),51,false).single()
        assertEquals(ExtensionUpdates.State.REQUIRED,incompatible.state);assertNull(incompatible.entry)
        val compatible=ExtensionUpdates.plan(listOf(entry("0.2.1")),legacy,emptySet(),51,false).single()
        assertEquals(ExtensionUpdates.State.REQUIRED,compatible.state);assertNotNull(compatible.entry)
        val rc=mapOf("online" to ExtensionUpdates.Installed("0.2.1-rc.1",12,true))
        assertEquals(ExtensionUpdates.State.AVAILABLE,ExtensionUpdates.plan(listOf(entry("0.2.1")),rc,emptySet(),51,false).single().state)
    }

}
