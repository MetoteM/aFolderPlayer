package dev.alex.folderplayer

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AppAppearanceTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun clearPreference(){app.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit().clear().commit()}
    private fun luminance(c:Int):Double {
        fun part(v:Int):Double { val s=v/255.0;return if(s<=0.04045)s/12.92 else Math.pow((s+0.055)/1.055,2.4) }
        return .2126*part(Color.red(c))+.7152*part(Color.green(c))+.0722*part(Color.blue(c))
    }
    private fun contrast(a:Int,b:Int):Double { val x=luminance(a);val y=luminance(b);return (maxOf(x,y)+.05)/(minOf(x,y)+.05) }
    @Test fun defaultsToExistingNightAndRejectsUnknownSavedTheme(){
        assertEquals("night",AppAppearance.selected(app).id)
        app.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit().putString("theme","missing").commit()
        assertEquals("night",AppAppearance.selected(app).id)
    }
    @Test fun allThemesKeepTextAndAccentReadable(){
        assertEquals(4,AppAppearance.choices.size)
        AppAppearance.choices.forEach { p ->
            listOf(p.background,p.surface).forEach { bg ->
                assertTrue("${p.id}: primary text",contrast(p.text,bg)>=4.5)
                assertTrue("${p.id}: secondary text",contrast(p.muted,bg)>=4.5)
                assertTrue("${p.id}: accent",contrast(p.accent,bg)>=4.5)
            }
            assertTrue("${p.id}: selected text",contrast(p.accent,p.selected)>=4.5)
        }
    }
    @Test fun everyActivityThemeUsesItsPaletteAndSystemBarAppearance(){
        for(p in AppAppearance.choices){
            AppAppearance.save(app,p)
            val c=Robolectric.buildActivity(ThemedActivity::class.java).setup()
            try {
                val a=c.get();val value=TypedValue()
                assertEquals(p.id,AppAppearance.palette.id)
                a.theme.resolveAttribute(android.R.attr.textColorPrimary,value,true)
                assertEquals(p.text,if(value.resourceId!=0) a.getColor(value.resourceId) else value.data)
                a.theme.resolveAttribute(android.R.attr.colorBackground,value,true)
                assertEquals(p.background,value.data)
                assertEquals(p.background,a.window.navigationBarColor)
                assertEquals(p.light,a.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR != 0)
            }finally { c.pause().stop().destroy() }
        }
    }
    @Test fun pickerSavesChoiceAndCancelLeavesChoiceUntouched(){
        val c=Robolectric.buildActivity(ThemedActivity::class.java).setup()
        try {
            ThemePicker.show(c.get());val d=ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(4,d.listView.count)
            d.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            assertEquals("night",AppAppearance.selected(app).id)
            ThemePicker.show(c.get());val picker=ShadowAlertDialog.getLatestAlertDialog()
            picker.listView.performItemClick(null,1,1)
            assertEquals("day",AppAppearance.selected(app).id)
        }finally { c.pause().stop().destroy() }
    }
    @Test fun changingThemeRetainsLibraryQueryAndAboutStaysLast(){
        val first=Robolectric.buildActivity(MainActivity::class.java).create().start()
        val saved=Bundle()
        try {
            MainActivity::class.java.getDeclaredField("query").apply { isAccessible=true }.set(first.get(),"my album")
            AppAppearance.save(app,AppAppearance.choices[2]);first.saveInstanceState(saved)
        }finally { first.stop().destroy() }
        val second=Robolectric.buildActivity(MainActivity::class.java).create(saved).start()
        try {
            val a=second.get()
            assertEquals("sepia",AppAppearance.palette.id)
            assertEquals("my album",MainActivity::class.java.getDeclaredField("query").apply { isAccessible=true }.get(a))
            MainActivity::class.java.getDeclaredMethod("settings").apply { isAccessible=true }.invoke(a)
            val menu=ShadowAlertDialog.getLatestAlertDialog().listView
            assertEquals("О приложении",menu.adapter.getItem(menu.count-1))
            assertTrue((0 until menu.count).any { menu.adapter.getItem(it)=="Тема оформления" })
        }finally { second.stop().destroy() }
    }
}
