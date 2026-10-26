package dev.alex.folderplayer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MainDocumentsTest {
    private class Registry : ActivityResultRegistry() {
        var lastCode = 0

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            lastCode = requestCode
        }
    }

    @Test
    fun eachNamedRequestReceivesItsOwnResultIncludingCancellation() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val registry = Registry()
        val received = mutableListOf<Pair<DocumentRequest, Int>>()
        val requests =
            MainDocuments(
                host.get(),
                { request, result -> received += request to result.resultCode },
                registry,
            )
        host.start().resume()
        try {
            for (request in DocumentRequest.entries) {
                requests.launch(Intent("test"), request)
                registry.dispatchResult(registry.lastCode, Activity.RESULT_CANCELED, null)
                assertEquals(request to Activity.RESULT_CANCELED, received.last())
            }
            assertEquals(DocumentRequest.entries.size, received.size)
        } finally {
            host.pause().stop().destroy()
        }
    }

    @Test
    fun pendingDocumentResultSurvivesRegistryAndScreenRecreation() {
        val first = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val registry = Registry()
        val requests =
            MainDocuments(
                first.get(),
                { _, _ -> fail("Old screen must not receive the restored result") },
                registry,
            )
        first.start().resume()
        requests.launch(Intent("pick"), DocumentRequest.IMPORT_LYRICS)
        val code = registry.lastCode
        val saved = Bundle()
        registry.onSaveInstanceState(saved)
        first.pause().stop().destroy()
        val second = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val restored = Registry()
        restored.onRestoreInstanceState(saved)
        var received: DocumentRequest? = null
        MainDocuments(
            second.get(),
            { request, result ->
                assertEquals(Activity.RESULT_OK, result.resultCode)
                received = request
            },
            restored,
        )
        second.start().resume()
        try {
            restored.dispatchResult(code, Activity.RESULT_OK, Intent())
            assertEquals(DocumentRequest.IMPORT_LYRICS, received)
        } finally {
            second.pause().stop().destroy()
        }
    }
}
