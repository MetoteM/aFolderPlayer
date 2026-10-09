package dev.alex.folderplayer

import android.app.Activity
import android.graphics.Color
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.SeekBar
import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PlayerScreensTest {
 private class Actions:PlayerScreens.Actions {
  var opened="";var toggles=0;var chosen=-1;var moved=Pair(-1,-1);var favorites=0
  override fun back(){};override fun open(page:String){opened=page};override fun toggle(){toggles++}
  override fun previous(){};override fun next(){};override fun skip(direction:Int){};override fun seek(progress:Int){}
  override fun favorite(){favorites++};override fun playlists(){};override fun repeat(){};override fun sound(){}
  override fun lyricsMenu(){};override fun select(index:Int){chosen=index};override fun move(from:Int,to:Int){moved=from to to}
 }
 private fun all(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList())
 private fun state(id:String="a",position:Long=1500)=PlayerScreens.State(id,"Песня","Автор","Альбом · 2026","Плейлист",true,true,Player.REPEAT_MODE_ALL,position,10000,20)
 @Test fun controlsAndMiniPlayerKeepPlaybackStateAcrossScreens() {
  val activity=Robolectric.buildActivity(Activity::class.java).setup().get();val actions=Actions();val ui=PlayerScreens(activity,actions)
  activity.setContentView(ui.view);ui.show("player");ui.update(state())
  assertTrue(all(ui.view).filterIsInstance<TextView>().any { it.text.toString()=="−20" })
  all(ui.view).filterIsInstance<Button>().first { it.text=="Текст" }.performClick();assertEquals("lyrics",actions.opened)
  ui.show("lyrics");ui.update(state(position=6000));all(ui.view).filterIsInstance<Button>().first { it.contentDescription=="Воспроизведение / пауза" }.performClick()
  assertEquals(1,actions.toggles);assertEquals(600,all(ui.view).filterIsInstance<SeekBar>().single().progress)
 }
 @Test fun lyricsHighlightTimestampGroupAndPlainTextStaysPlain() {
  val activity=Robolectric.buildActivity(Activity::class.java).setup().get();val ui=PlayerScreens(activity,Actions());ui.show("lyrics");ui.update(state())
  ui.setLyrics("a",LyricsDocument("[00:01.000]Первая\n[00:01.000]Вторая\n[00:03.000]Третья"),null)
  val value=all(ui.view).filterIsInstance<TextView>().first { it.text.toString().startsWith("Первая") }.text as Spanned
  assertEquals(2,value.getSpans(0,value.length,ForegroundColorSpan::class.java).size)
  ui.update(state(position=4000));val next=all(ui.view).filterIsInstance<TextView>().first { it.text.toString().startsWith("Первая") }.text as Spanned
  assertEquals(1,next.getSpans(0,next.length,ForegroundColorSpan::class.java).size)
  ui.setLyrics("a",LyricsDocument("Просто текст"),null)
  assertTrue(all(ui.view).filterIsInstance<TextView>().any { it.text.toString()=="Просто текст" })
 }
 @Test fun queueSelectUsesRealIndexAndCurrentTrackHighlighted() {
  val activity=Robolectric.buildActivity(Activity::class.java).setup().get();val actions=Actions();val ui=PlayerScreens(activity,actions);ui.show("queue");ui.update(state())
  ui.setQueue(listOf(PlayerScreens.Entry("a","Первая","Автор",10000),PlayerScreens.Entry("b","Вторая","Автор",10000)))
  val labels=all(ui.view).filterIsInstance<TextView>();val current=labels.first { it.text.toString().startsWith("Первая") };val second=labels.first { it.text.toString().startsWith("Вторая") }
  assertEquals(PlayerScreens.accent,current.currentTextColor);second.performClick();assertEquals(1,actions.chosen)
  ui.update(state("b"));assertEquals(Color.WHITE,current.currentTextColor);assertEquals(PlayerScreens.accent,second.currentTextColor)
 }
 @Test fun queueMoveMenuUsesDisplayedOrder() {
  val activity=Robolectric.buildActivity(Activity::class.java).setup().get();val actions=Actions();val ui=PlayerScreens(activity,actions);ui.show("queue");ui.update(state())
  ui.setQueue(listOf(PlayerScreens.Entry("a","Первая","Автор",10000),PlayerScreens.Entry("b","Вторая","Автор",10000)))
  all(ui.view).filterIsInstance<Button>().first { it.contentDescription=="Переставить Первая" }.performClick()
  val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
  org.robolectric.Shadows.shadowOf(dialog).clickOnItem(1)
  assertEquals(0 to 1,actions.moved)
 }

 @Test fun compactPlayerHasRepeatInActionRowAndSourceInHeader() {
  val activity=Robolectric.buildActivity(Activity::class.java).setup().get();val ui=PlayerScreens(activity,Actions());ui.show("player");ui.update(state())
  val buttons=all(ui.view).filterIsInstance<Button>();val text=buttons.first { it.text=="Текст" };val repeat=buttons.first { it.contentDescription=="Режим повтора" }
  assertSame(text.parent,repeat.parent);assertFalse(buttons.any { it.text=="Перемешать" })
  assertTrue(all(ui.view).filterIsInstance<TextView>().any { it.text=="Сейчас играет — Плейлист" });assertFalse(all(ui.view).filterIsInstance<TextView>().any { it.text.toString().startsWith("Плейлист ·") })
 }

 @Test fun miniPlayerTimeOpensSongWhileSeekAndButtonsKeepTheirActions() {
  val activity=Robolectric.buildActivity(Activity::class.java).setup().get();val actions=Actions();val ui=PlayerScreens(activity,actions)
  ui.show("lyrics");ui.update(state());activity.setContentView(ui.view)
  ui.view.measure(View.MeasureSpec.makeMeasureSpec(822,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1782,View.MeasureSpec.EXACTLY));ui.view.layout(0,0,822,1782)
  fun gesture(target:View,dx:Float,dy:Float) {
   val r=IntArray(2);val t=IntArray(2);ui.view.getLocationOnScreen(r);target.getLocationOnScreen(t)
   val x=t[0]-r[0]+target.width/2f;val y=t[1]-r[1]+target.height/2f
   for((action,p) in listOf(android.view.MotionEvent.ACTION_DOWN to (x to y),android.view.MotionEvent.ACTION_MOVE to (x+dx to y+dy),android.view.MotionEvent.ACTION_UP to (x+dx to y+dy))) {
    val event=android.view.MotionEvent.obtain(0,20,action,p.first,p.second,0);ui.view.dispatchTouchEvent(event);event.recycle()
   }
  }
  gesture(all(ui.view).filterIsInstance<TextView>().first { it.text=="0:01" },0f,-160*activity.resources.displayMetrics.density)
  assertEquals("player",actions.opened);actions.opened=""
  gesture(all(ui.view).filterIsInstance<SeekBar>().single(),180f,0f);assertEquals("",actions.opened)
  gesture(all(ui.view).filterIsInstance<Button>().first { it.contentDescription=="Воспроизведение / пауза" },0f,0f)
  org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
  assertEquals(1,actions.toggles);assertEquals("",actions.opened)
 }

 @Test fun autoScrollOnlyAppearsForCurrentSynchronizedLyrics() {
  val activity=Robolectric.buildActivity(Activity::class.java).setup().get();val ui=PlayerScreens(activity,Actions());ui.show("lyrics");ui.update(state())
  val toggle=all(ui.view).filterIsInstance<android.widget.Switch>().single()
  ui.setLyrics("a",LyricsDocument("Plain lyrics"),null);assertEquals(View.GONE,toggle.visibility)
  ui.setLyrics("a",LyricsDocument("[00:01]Timed lyrics"),null);assertEquals(View.VISIBLE,toggle.visibility)
  toggle.isChecked=false;ui.show("lyrics");assertFalse(all(ui.view).filterIsInstance<android.widget.Switch>().single().isChecked)
  ui.update(state("b"));assertEquals(View.GONE,all(ui.view).filterIsInstance<android.widget.Switch>().single().visibility)
 }

}
