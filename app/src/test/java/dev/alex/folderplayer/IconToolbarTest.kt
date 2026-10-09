package dev.alex.folderplayer

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class IconToolbarTest {
 private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
 private fun press(b:View,action:Int,x:Float=40f,y:Float=40f) { val e=MotionEvent.obtain(0,20,action,x,y,0);b.dispatchTouchEvent(e);e.recycle();shadowOf(Looper.getMainLooper()).idle() }
 private fun button(action:()->Unit):IconButton {
  val activity=Robolectric.buildActivity(Activity::class.java).setup().visible().get();val b=IconButton(activity,IconButton.Kind.LIBRARY,"Библиотека",action);activity.setContentView(b)
  b.measure(View.MeasureSpec.makeMeasureSpec(96,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(96,View.MeasureSpec.EXACTLY));b.layout(0,0,96,96);return b
 }
 @Test fun normalTapWorksAndLongHoldOnlyShowsHintUntilRelease() {
  var calls=0;val b=button { calls++ };press(b,MotionEvent.ACTION_DOWN);press(b,MotionEvent.ACTION_UP);assertEquals(1,calls)
  press(b,MotionEvent.ACTION_DOWN);shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));assertTrue(b.hintVisible)
  val popup=IconButton::class.java.getDeclaredField("hint").apply { isAccessible=true }.get(b) as PopupWindow
  assertEquals("Библиотека",(popup.contentView as TextView).text.toString())
  press(b,MotionEvent.ACTION_UP);assertFalse(b.hintVisible);assertEquals(1,calls)
 }
 @Test fun LeavingButtonClearsHintAndNeverClicksEvenAfterReentry() {
  var calls=0;val b=button { calls++ };press(b,MotionEvent.ACTION_DOWN);shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));assertTrue(b.hintVisible)
  press(b,MotionEvent.ACTION_MOVE,b.width+20f);assertFalse(b.hintVisible);press(b,MotionEvent.ACTION_MOVE);press(b,MotionEvent.ACTION_UP);assertEquals(0,calls)
  press(b,MotionEvent.ACTION_DOWN);press(b,MotionEvent.ACTION_MOVE,b.width+20f);press(b,MotionEvent.ACTION_UP);assertEquals(0,calls)
 }
 @Test fun focusLossAndCancelClearHintWithoutAction() {
  var calls=0;val b=button { calls++ };press(b,MotionEvent.ACTION_DOWN);shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));assertTrue(b.hintVisible)
  b.onWindowFocusChanged(false);assertFalse(b.hintVisible);press(b,MotionEvent.ACTION_UP);assertEquals(0,calls)
  b.onWindowFocusChanged(true);press(b,MotionEvent.ACTION_DOWN);shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));press(b,MotionEvent.ACTION_CANCEL);assertFalse(b.hintVisible);assertEquals(0,calls)
 }
 @Test fun toolbarIsOneRowWithLabelsForAccessibilityAndAboutIsClean() {
  val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("playback",Context.MODE_PRIVATE).edit().clear().commit()
  val host=Robolectric.buildActivity(MainActivity::class.java).create().start();val a=host.get()
  try {
   val icons=all(a.window.decorView).filterIsInstance<IconButton>();assertEquals(5,icons.size);assertEquals(1,icons.map { it.parent }.distinct().size)
   assertTrue(icons.all { it.text.isEmpty() && it.contentDescription.isNotBlank() });assertFalse(all(a.window.decorView).filterIsInstance<Button>().any { it.text=="⋮" })
   icons.single { it.contentDescription=="Меню" }.performClick();shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(7)
   val text=all(ShadowAlertDialog.getLatestAlertDialog().window!!.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
   assertTrue(text.contains("Разработка при участии ChatGPT"));assertFalse(text.contains("Личный проект"));assertFalse(text.contains("Исходный код"));assertFalse(text.contains("Цель проверки"))
  } finally { host.stop().destroy() }
 }
 @Test fun openingPreviewFollowsFingerAndCompletionKeepsItsPosition() {
  val b=button {};val motion=SheetMotion(b);motion.preview(1000,100f);assertEquals(860f,b.translationY)
  motion.preview(1000,200f);assertEquals(720f,b.translationY)
  motion.show(true,1000,true,true,b.translationY);assertEquals(720f,b.translationY);assertEquals(600L,motion.running!!.duration)
  motion.running!!.end();assertEquals(0f,b.translationY)
  motion.preview(1000,100f);motion.show(false,1000,true);motion.running!!.end();assertEquals(View.GONE,b.visibility)
 }
}
