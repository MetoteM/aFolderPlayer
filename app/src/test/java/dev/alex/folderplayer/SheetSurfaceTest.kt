package dev.alex.folderplayer

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w411dp-h891dp-xhdpi")
class SheetSurfaceTest {
 private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
 private class Actions:PlayerScreens.Actions {
  var backs=0;var toggles=0;var chosen=0;var opened=""
  override fun back(){backs++};override fun open(page:String){opened=page};override fun toggle(){toggles++}
  override fun previous(){};override fun next(){};override fun skip(direction:Int){};override fun seek(progress:Int){}
  override fun favorite(){};override fun playlists(){};override fun repeat(){};override fun sound(){};override fun lyricsMenu(){}
  override fun select(index:Int){chosen++};override fun move(from:Int,to:Int){}
 }
 private fun setup(page:String):Triple<Activity,PlayerScreens,Actions> {
  val activity=Robolectric.buildActivity(Activity::class.java).setup().get();val actions=Actions();val ui=PlayerScreens(activity,actions)
  ui.show(page);ui.update(PlayerScreens.State("a","Song","Artist","Album","List",true,false,0,0,300000,10));activity.setContentView(ui.view);layout(ui.view)
  return Triple(activity,ui,actions)
 }
 private fun layout(v:View) { v.measure(View.MeasureSpec.makeMeasureSpec(822,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1782,View.MeasureSpec.EXACTLY));v.layout(0,0,822,1782) }
 private fun gesture(root:View,target:View,dx:Float=0f,dy:Float=180f,cancel:Boolean=false) {
  val r=IntArray(2);val t=IntArray(2);root.getLocationOnScreen(r);target.getLocationOnScreen(t)
  val x=t[0]-r[0]+target.width/2f;val y=t[1]-r[1]+target.height/2f
  for((action,p) in listOf(MotionEvent.ACTION_DOWN to (x to y),MotionEvent.ACTION_MOVE to (x+dx to y+dy), (if(cancel)MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP) to (x+dx to y+dy))) {
   val e=MotionEvent.obtain(0,20,action,p.first,p.second,0);root.dispatchTouchEvent(e);e.recycle()
  }
  shadowOf(android.os.Looper.getMainLooper()).idle()
 }
 @Test fun artworkAndMetadataCloseWithoutHeaderAndCanRepeat() {
  val (_,ui,a)=setup("player")
  repeat(3) { gesture(ui.view,all(ui.view).filterIsInstance<ImageView>().single());assertEquals(it+1,a.backs);ui.show("player");layout(ui.view) }
  gesture(ui.view,all(ui.view).filterIsInstance<TextView>().first { it.text=="Artist" });assertEquals(4,a.backs)
 }
 @Test fun lyricsTextClosesAtTopWithoutClickingSearchOrMiniPlayer() {
  val (_,ui,a)=setup("lyrics");ui.setLyrics("a",LyricsDocument("Plain lyrics"),null);layout(ui.view)
  gesture(ui.view,all(ui.view).filterIsInstance<TextView>().first { it.text.toString()=="Plain lyrics" });assertEquals(1,a.backs);assertEquals(0,a.toggles)
 }
 @Test fun queueRowsCloseWithoutSelectingSong() {
  val (_,ui,a)=setup("queue");ui.setQueue(listOf(PlayerScreens.Entry("a","First","Artist",300000)));layout(ui.view)
  gesture(ui.view,all(ui.view).filterIsInstance<TextView>().first { it.text.toString().startsWith("First") });assertEquals(1,a.backs);assertEquals(0,a.chosen)
 }
 @Test fun downwardScrollingFromMiddleDoesNotClose() {
  val (_,ui,a)=setup("lyrics");ui.setLyrics("a",LyricsDocument((1..100).joinToString("\n") { "Line $it" }),null);layout(ui.view)
  val scroll=all(ui.view).filterIsInstance<ScrollView>().single();scroll.scrollTo(0,800);assertTrue(scroll.canScrollVertically(-1))
  // Start at a visible location within the scroller, not the text's offscreen centre.
  gesture(ui.view,scroll);assertEquals(0,a.backs)
  scroll.scrollTo(0,0);gesture(ui.view,scroll);assertEquals(1,a.backs)
 }
 @Test fun switchesButtonsAndSlidersKeepOwnership() {
  val (_,ui,a)=setup("sound")
  gesture(ui.view,all(ui.view).filterIsInstance<Switch>().single());assertEquals(0,a.backs)
  gesture(ui.view,all(ui.view).filterIsInstance<SeekBar>().first());assertEquals(0,a.backs)
  gesture(ui.view,all(ui.view).filterIsInstance<Button>().first { it.contentDescription=="Воспроизведение / пауза" });assertEquals(0,a.backs)
  gesture(ui.view,all(ui.view).filterIsInstance<Button>().first { it.contentDescription=="Воспроизведение / пауза" },dy=0f);assertEquals(1,a.toggles)
 }
 @Test fun horizontalShortAndCancelledGesturesDoNotClose() {
  val (_,ui,a)=setup("player");val art=all(ui.view).filterIsInstance<ImageView>().single()
  gesture(ui.view,art,dx=200f,dy=20f);gesture(ui.view,art,dy=40f);gesture(ui.view,art,cancel=true);assertEquals(0,a.backs)
  ui.view.resetGesture();gesture(ui.view,art);assertEquals(1,a.backs)
 }
 @Test fun animationsSettleAndInterruptedTransitionsCannotHideCurrentPage() {
  val (activity,ui,_)=setup("player");val motion=SheetMotion(ui.view)
  motion.show(true,1782,true);assertEquals(1782f,ui.view.translationY);assertEquals(View.VISIBLE,ui.view.visibility);assertNotNull(motion.running)
  motion.running!!.end();assertEquals(0f,ui.view.translationY)
  motion.show(true,1782,true,false);assertEquals(0f,ui.view.alpha);motion.running!!.end();assertEquals(1f,ui.view.alpha)
  motion.show(false,1782,true);val old=motion.running!!;motion.show(true,1782,true);old.end();motion.running!!.end();assertEquals(View.VISIBLE,ui.view.visibility)
  motion.show(false,1782,true);motion.running!!.end();assertEquals(View.GONE,ui.view.visibility);assertEquals(0f,ui.view.translationY);assertEquals(1f,ui.view.alpha)
 }
 @Test fun outgoingPageAnimationCanBeInterruptedWithoutOldNavigation() {
  val (_,ui,_)=setup("lyrics");val motion=SheetMotion(ui.view);var navigated=0
  motion.leavePage { navigated++ };val old=motion.running!!;motion.show(true,1782,true,false);old.end();motion.running!!.end();assertEquals(0,navigated)
  motion.leavePage { navigated++;motion.show(true,1782,false,false) };motion.running!!.end();assertEquals(1,navigated);assertEquals(1f,ui.view.alpha);assertEquals(0f,ui.view.translationY)
 }

 @Test fun lyricsSwipeLeftAndTranslationSwipeRightOpenMatchingPages() {
  val (_,ui,a)=setup("lyrics");ui.setLyrics("a",LyricsDocument("English words"),null);layout(ui.view)
  gesture(ui.view,all(ui.view).filterIsInstance<TextView>().first { it.text.toString()=="English words" },dx=-200f,dy=5f);assertEquals("translation",a.opened);assertEquals(0,a.backs)
  ui.show("translation");ui.setTranslation("a","Русский текст","Машинный перевод");layout(ui.view)
  gesture(ui.view,all(ui.view).filterIsInstance<TextView>().first { it.text.toString()=="Русский текст" },dx=200f,dy=5f);assertEquals("lyrics",a.opened)
 }
 @Test fun translationSwipeOnControlOrCancelledGestureDoesNotNavigate() {
  val (_,ui,a)=setup("translation");ui.setTranslation("a","Русский текст","Машинный перевод");layout(ui.view)
  gesture(ui.view,all(ui.view).filterIsInstance<Button>().first { it.contentDescription=="Воспроизведение / пауза" },dx=200f,dy=0f);assertEquals("",a.opened);assertEquals(0,a.toggles)
  gesture(ui.view,all(ui.view).filterIsInstance<TextView>().first { it.text.toString()=="Русский текст" },dx=200f,dy=5f,cancel=true);assertEquals("",a.opened)
 }

}
