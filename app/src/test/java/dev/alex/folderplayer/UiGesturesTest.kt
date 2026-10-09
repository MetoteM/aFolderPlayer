package dev.alex.folderplayer
import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class UiGesturesTest {
 @Test fun captionOpensOnlyOnUpwardSwipeNotTapOrHorizontalMovement() {
  val activity=Robolectric.buildActivity(Activity::class.java).setup().get();val view=TextView(activity);var opens=0;UiGestures.swipe(view,true){opens++}
  fun gesture(x:Float,y:Float) { listOf(MotionEvent.ACTION_DOWN to (100f to 200f),MotionEvent.ACTION_UP to (x to y)).forEach { (action,point)->MotionEvent.obtain(0,20,action,point.first,point.second,0).let { view.dispatchTouchEvent(it);it.recycle() } } }
  gesture(100f,200f);gesture(300f,190f);assertEquals(0,opens);gesture(100f,0f);assertEquals(1,opens)
 }
 @Test fun artworkMeasuresAsSquareAndKeepsWholeImage() {
  val activity=Robolectric.buildActivity(Activity::class.java).setup().get();val image=PlayerScreens.SquareArtwork(activity);image.scaleType=android.widget.ImageView.ScaleType.FIT_CENTER
  image.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(999,View.MeasureSpec.AT_MOST))
  assertEquals(360,image.measuredWidth);assertEquals(360,image.measuredHeight);assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER,image.scaleType)
 }
}
