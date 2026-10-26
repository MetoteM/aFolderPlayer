package dev.alex.folderplayer

import android.content.ComponentName
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
class LibraryProcessRestoreTest {
    private fun field(host: MainActivity, name: String) =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(host)

    private fun all(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) }
            else emptyList()

    private fun layout(host: MainActivity) {
        val root = field(host, "root") as View
        repeat(3) {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 360, 640)
        }
        root.viewTreeObserver.dispatchOnGlobalLayout()
    }

    @Test
    fun savedPageAndScrollWaitForTheColdLibraryInsteadOfResettingToZero() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app)
            .setComponentNameAndServiceForBindService(
                ComponentName(app, PlaybackService::class.java),
                Binder(),
            )
        shadowOf(app).setUnbindServiceCallsOnServiceDisconnected(false)
        app.getSharedPreferences("playback", 0).edit().clear().commit()
        app.getSharedPreferences("library", 0)
            .edit()
            .clear()
            .putString(
                "tracks",
                JSONArray(
                        (1..1000).map {
                            Track("file:///$it.mp3", "Track $it", "Artist", 30000, "Music").json()
                        }
                    )
                    .toString(),
            )
            .commit()
        val first = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val saved = Bundle()
        try {
            awaitLibrary(first.get())
            repeat(2) {
                all(field(first.get(), "root") as View)
                    .filterIsInstance<Button>()
                    .single { it.text == "Далее →" }
                    .performClick()
            }
            layout(first.get())
            val scroll = (field(first.get(), "list") as View).parent as ScrollView
            scroll.scrollTo(0, 640)
            first.saveInstanceState(saved)
        } finally {
            first.pause().stop().destroy()
        }
        val restored =
            Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume()
        try {
            awaitLibrary(restored.get())
            layout(restored.get())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, field(restored.get(), "libraryPage"))
            assertEquals(
                640,
                ((field(restored.get(), "list") as View).parent as ScrollView).scrollY,
            )
        } finally {
            restored.pause().stop().destroy()
        }
    }
}
