package dev.alex.afponline

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ZvukLabActivityTest {
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
