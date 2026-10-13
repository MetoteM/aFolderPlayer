package dev.alex.folderplayer

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class HistoryUiTest {
 private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) }else emptyList()
 @Test fun filteringAndUndoOperateOnSelectedIdRatherThanVisibleIndex() {
  val host=Robolectric.buildActivity(Activity::class.java).setup();val entries=listOf(HistoryDialog.Entry("a","First"),HistoryDialog.Entry("b","Second"));val removed=mutableSetOf<String>()
  HistoryDialog.show(host.get(),"History",{entries.filter { it.id !in removed }},{},{removed.add(it)},{removed.remove(it)})
  val history=ShadowAlertDialog.getLatestAlertDialog();val views=views(history.window!!.decorView);views.filterIsInstance<EditText>().single().setText("Second")
  val list=views.filterIsInstance<ListView>().single();assertEquals(1,list.adapter.count);list.performItemClick(list.adapter.getView(0,null,list),0,0)
  shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(1);assertEquals(setOf("b"),removed);assertEquals(0,list.adapter.count)
  ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(removed.isEmpty());assertEquals(1,list.adapter.count)
  host.pause().stop().destroy()
 }
 @Test fun readingPositionRestoresAfterReaderRecreation() {
  val host=Robolectric.buildActivity(Activity::class.java).setup();val activity=host.get();activity.getSharedPreferences("reading",0).edit().clear().commit()
  fun reader():android.widget.ScrollView = android.widget.ScrollView(activity).apply { addView(android.widget.TextView(activity).apply { text="Line\n".repeat(200) }) }
  fun layout(view:View) { view.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY));view.layout(0,0,360,400) }
  val first=reader();activity.setContentView(first);layout(first);ReadingPosition.attach(activity,first,"original");shadowOf(android.os.Looper.getMainLooper()).idle();first.scrollTo(0,200)
  assertEquals(200,activity.getSharedPreferences("reading",0).getInt(LyricsDocument.key("original"),0))
  val second=reader();activity.setContentView(second);layout(second);ReadingPosition.attach(activity,second,"original");shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(200,second.scrollY)
  host.pause().stop().destroy()
 }
}
