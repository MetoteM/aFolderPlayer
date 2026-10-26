package dev.alex.folderplayer

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class RetainedOperationTest {
 @Test fun detachedScreenCannotReceiveResultAndNewScreenDoesNotRestartWork() {
  val work=RetainedOperation<String>();val entered=CountDownLatch(1);val finish=CountDownLatch(1);val done=CountDownLatch(1)
  val runs=AtomicInteger();var oldResult:String?=null;var newResult:String?=null
  try {
   work.attach { oldResult=it.value }
   work.start("song") { _,_ -> runs.incrementAndGet();entered.countDown();finish.await();done.countDown();"translation" }
   assertTrue(entered.await(2,TimeUnit.SECONDS));shadowOf(Looper.getMainLooper()).idle();work.detach()
   work.attach { newResult=it.value };finish.countDown();assertTrue(done.await(2,TimeUnit.SECONDS))
   val limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
   while(newResult==null && System.nanoTime()<limit){Thread.yield();shadowOf(Looper.getMainLooper()).idle()}
   assertEquals("translation",newResult);assertNull(oldResult);assertEquals(1,runs.get())
  }finally{finish.countDown();work.close()}
 }
 @Test fun replacedRequestNeverPublishesStaleResult() {
  val work=RetainedOperation<String>();val entered=CountDownLatch(1);val unblock=CountDownLatch(1);val done=CountDownLatch(1);val values=mutableListOf<String>()
  try {
   work.attach { it.value?.let(values::add) }
   work.start("old") { _,_ -> entered.countDown();try{unblock.await()}catch(_:InterruptedException){};"stale" }
   assertTrue(entered.await(2,TimeUnit.SECONDS));work.start("new") { _,_ -> done.countDown();"current" };unblock.countDown()
   assertTrue(done.await(2,TimeUnit.SECONDS));val limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
   while(values.isEmpty() && System.nanoTime()<limit){Thread.yield();shadowOf(Looper.getMainLooper()).idle()}
   assertEquals(listOf("current"),values)
  }finally{unblock.countDown();work.close()}
 }
 @Test fun closingOperationInterruptsWorkAndNeverDeliversCompletion() {
  val work=RetainedOperation<String>();val started=CountDownLatch(1);val stopped=CountDownLatch(1)
  var delivered=false
  work.attach { if(it.value!=null)delivered=true }
  work.start("scan") { _,_ ->
   started.countDown()
   try { CountDownLatch(1).await();"unexpected" }
   finally { stopped.countDown() }
  }
  assertTrue(started.await(2,TimeUnit.SECONDS))
  work.close()
  assertTrue(stopped.await(2,TimeUnit.SECONDS))
  shadowOf(Looper.getMainLooper()).idle()
  assertFalse(delivered)
 }

}
