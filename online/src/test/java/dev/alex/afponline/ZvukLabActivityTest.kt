package dev.alex.afponline

import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ZvukLabActivityTest {
    private fun views(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
            else emptyList()

    @Test
    fun independentMusicButtonRemainsUsableAfterProfileFailure() {
        val activity = Robolectric.buildActivity(ZvukLabActivity::class.java).setup()
        try {
            val host = activity.get()
            val client =
                ZvukClient("private-local-session") { path, _, _ ->
                    assertEquals("/api/v1/graphql", path)
                    "{\"data\":{\"collection\":{\"tracks\":[]}}}"
                }
            host.javaClass
                .getDeclaredField("client")
                .apply { isAccessible = true }
                .set(host, client)
            @Suppress("UNCHECKED_CAST")
            val evidence =
                host.javaClass.getDeclaredField("evidence").apply { isAccessible = true }.get(host)
                    as MutableMap<String, String>
            evidence["session"] = "failed"
            views(host.window.decorView)
                .filterIsInstance<Button>()
                .single { it.text.toString() == "Проверить музыкальный API отдельно" }
                .performClick()
            val deadline = System.currentTimeMillis() + 3000
            while (evidence["music_api"] != "passed" && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertEquals("passed", evidence["music_api"])
            assertEquals("failed", evidence["session"])
            val text =
                views(host.window.decorView).filterIsInstance<TextView>().joinToString("\n") {
                    it.text
                }
            assertTrue(text.contains("не подтверждает профиль"))
            assertFalse(text.contains("private-local-session"))
        } finally {
            activity.pause().stop().destroy()
        }
    }

    private fun fields(view: View): List<EditText> =
        (if (view is EditText) listOf(view) else emptyList()) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { fields(view.getChildAt(it)) }
            else emptyList()

    @Test
    fun screenOpensAndSessionIsNotRestoredFromActivityState() {
        val first = Robolectric.buildActivity(ZvukLabActivity::class.java).setup()
        val saved = Bundle()
        try {
            val activity = first.get()
            assertTrue(
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            )
            val token = fields(activity.window.decorView).first()
            assertFalse(token.isSaveEnabled)
            token.setText("local-private-session")
            first.saveInstanceState(saved).pause().stop().destroy()
            val second =
                Robolectric.buildActivity(ZvukLabActivity::class.java)
                    .create(saved)
                    .start()
                    .resume()
                    .visible()
            try {
                assertEquals("", fields(second.get().window.decorView).first().text.toString())
            } finally {
                second.pause().stop().destroy()
            }
        } finally {
            // The first activity was explicitly destroyed before recreation.
        }
    }
}
