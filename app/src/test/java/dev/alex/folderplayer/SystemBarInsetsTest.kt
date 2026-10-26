package dev.alex.folderplayer

import android.app.Activity
import android.graphics.Insets
import android.view.View
import android.view.WindowInsets
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class SystemBarInsetsTest {
    @Test fun systemBarsAddToBasePaddingWithoutAccumulating() {
        val host=Robolectric.buildActivity(Activity::class.java).setup().get()
        val view=View(host).apply { setPadding(20,12,20,12) }
        SystemBarInsets.apply(view)
        val portrait=WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(),Insets.of(0,96,0,72)).build()
        repeat(2) { view.dispatchApplyWindowInsets(portrait) }
        assertEquals(108,view.paddingTop);assertEquals(84,view.paddingBottom)
        val landscape=WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(),Insets.of(64,32,48,0)).build()
        view.dispatchApplyWindowInsets(landscape)
        assertEquals(84,view.paddingLeft);assertEquals(44,view.paddingTop)
        assertEquals(68,view.paddingRight);assertEquals(12,view.paddingBottom)
    }
}
