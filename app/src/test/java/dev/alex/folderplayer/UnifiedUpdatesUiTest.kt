package dev.alex.folderplayer

import android.app.Activity
import android.os.Looper
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UnifiedUpdatesUiTest {
    private class Reply(uri: URI, private val status: Int, private val body: ByteArray) :
        HttpURLConnection(uri.toURL()) {
        override fun getResponseCode() = status

        override fun getInputStream() = ByteArrayInputStream(body)

        override fun connect() {}

        override fun disconnect() {}

        override fun usingProxy() = false
    }

    @Test
    fun oneButtonChecksPlayerAndExtensionsEvenWithoutPlayerRelease() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val host = activity.get()
        val manager = ExtensionManager(host, TranslationClient(host), {}, {})
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val updates =
            AppUpdates(host, manager) { uri ->
                requests += uri.toString()
                when {
                    uri.host == "api.github.com" -> Reply(uri, 200, "[]".toByteArray())
                    uri.path.endsWith("catalog-v2.json") -> Reply(uri, 404, byteArrayOf())
                    else -> Reply(uri, 200, File("../qa/catalog-test-signed.json").readBytes())
                }
            }
        try {
            updates.check()
            val end = System.currentTimeMillis() + 5000
            var labels = emptyList<String>()
            while (System.currentTimeMillis() < end) {
                shadowOf(Looper.getMainLooper()).idle()
                val list = ShadowAlertDialog.getLatestAlertDialog()?.listView
                if (list != null && list.adapter != null) {
                    labels =
                        (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
                    if (labels.any { it.contains("AFP Translate") }) break
                }
                Thread.sleep(10)
            }
            assertTrue(labels.any { it.contains("aFolderPlayer") })
            assertTrue(labels.any { it.contains("AFP Translate") })
            assertEquals(3, requests.size)
            assertTrue(requests[1].endsWith("catalog-v2.json"))
            assertTrue(requests[2].endsWith("catalog.json"))
        } finally {
            updates.close()
            manager.close()
            activity.pause().stop().destroy()
        }
    }
}
