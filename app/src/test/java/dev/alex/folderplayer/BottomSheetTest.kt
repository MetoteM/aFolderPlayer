package dev.alex.folderplayer

import android.app.Activity
import android.app.Dialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BottomSheetTest {
    private fun texts(view: View): List<String> =
        (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            else emptyList()

    private fun allTextViews(view: View): List<TextView> =
        (if (view is TextView) listOf(view) else emptyList()) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { allTextViews(view.getChildAt(it)) }
            else emptyList()

    @Test
    fun sheetShowsTitleAndItemsAndDispatchesChoice() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            var picked = -2
            val dialog: Dialog =
                BottomSheet.show(
                    activity,
                    title = "Track title",
                    subtitle = "Artist",
                    items = listOf("Первое", "Второе"),
                ) { picked = it }
            val shown = texts(dialog.window!!.decorView)
            assertTrue(shown.contains("Track title"))
            assertTrue(shown.contains("Artist"))
            assertTrue(shown.contains("Первое"))
            assertTrue(shown.contains("Второе"))
            shadowOf(Looper.getMainLooper()).idle()
            val rows = texts(dialog.window!!.decorView as ViewGroup).filter { it == "Второе" }
            val target =
                (dialog.window!!.decorView as ViewGroup).let { root ->
                    allTextViews(root).first { it.text == "Второе" }
                }
            target.performClick()
            assertEquals(1, picked)
            assertTrue(!dialog.isShowing)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
