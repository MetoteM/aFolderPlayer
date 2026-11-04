package dev.alex.folderplayer

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.LinearLayout
import android.view.View
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class LibraryThumbnailsTest {
 private class Queue : AbstractExecutorService() {
  val tasks=mutableListOf<Runnable>();var stopped=false
  override fun execute(r:Runnable){tasks.add(r)}
  override fun shutdown(){stopped=true}
  override fun shutdownNow():MutableList<Runnable>{stopped=true;return tasks.toMutableList().also{tasks.clear()}}
  override fun isShutdown()=stopped
  override fun isTerminated()=stopped
  override fun awaitTermination(t:Long,u:TimeUnit)=stopped
  fun drain(){var count=0;while(tasks.isNotEmpty()){check(++count<20);tasks.removeAt(0).run();shadowOf(Looper.getMainLooper()).idle()}}
 }
 private fun screen(block:(Activity,List<ImageView>,Queue)->Unit) {
  val host=Robolectric.buildActivity(Activity::class.java).setup().visible()
  val activity=host.get();val box=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
  val images=List(2){ImageView(activity).also { box.addView(it,LinearLayout.LayoutParams(100,100)) }}
  activity.setContentView(box)
  val decor=activity.window.decorView
  val spec=View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY)
  decor.measure(spec,spec);decor.layout(0,0,500,500)
  assertTrue(images[0].getGlobalVisibleRect(Rect()))
  val queue=Queue()
  try { block(activity,images,queue) } finally { queue.shutdownNow();host.pause().stop().destroy() }
 }
 @Test fun sharedCoverIsDecodedOnceAndClosingReleasesImages()=screen { activity,images,queue ->
  var reads=0;val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
  val loader=LibraryThumbnails(activity,queue,Handler(Looper.getMainLooper())) { context,_ ->
   assertSame(activity.applicationContext,context);reads++;bitmap
  }
  loader.begin(null);images.forEach { loader.add(it,"file:///cover") };loader.loadVisible();queue.drain()
  assertEquals(1,reads);images.forEach { assertNotNull(it.drawable) }
  loader.close();images.forEach { assertNull(it.drawable) }
 }
 @Test fun rebuildingCancelsQueuedOldReadsAndClearsOldBitmaps()=screen { activity,images,queue ->
  val reads=mutableListOf<String>();val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
  val loader=LibraryThumbnails(activity,queue,Handler(Looper.getMainLooper())) { _,uri -> reads.add(uri.toString());bitmap }
  loader.begin(null);loader.add(images[0],"file:///old");loader.loadVisible()
  images[0].setImageBitmap(bitmap)
  loader.begin(null);assertNull(images[0].drawable)
  loader.add(images[1],"file:///new");loader.loadVisible();queue.drain()
  assertEquals(listOf("file:///new"),reads);assertNull(images[0].drawable);assertNotNull(images[1].drawable)
  loader.close()
 }
 @Test fun closingDuringReadDiscardsLateResultEvenWhenReadIgnoresCancel()=screen { activity,images,queue ->
  val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
  lateinit var loader:LibraryThumbnails
  loader=LibraryThumbnails(activity,queue,Handler(Looper.getMainLooper())) { _,_->loader.close();Thread.interrupted();bitmap }
  loader.begin(null);loader.add(images[0],"file:///late");loader.loadVisible();queue.drain()
  assertNull(images[0].drawable)
 }
 @Test fun unreadableCoverDoesNotBlockOtherRows()=screen { activity,images,queue ->
  val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
  val loader=LibraryThumbnails(activity,queue,Handler(Looper.getMainLooper())) { _,uri ->
   if(uri.lastPathSegment=="bad")throw java.io.IOException("Unavailable provider") else bitmap
  }
  loader.begin(null);loader.add(images[0],"file:///bad");loader.add(images[1],"file:///good")
  loader.loadVisible();queue.drain()
  assertEquals(View.GONE,images[0].visibility);assertNotNull(images[1].drawable);loader.close()
 }
}
