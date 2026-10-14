package dev.alex.folderplayer

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class PowerSchedulingTest {
 @Test fun pausedServiceDoesNotWritePeriodicallyAndSleepTimerStillFires() {
  val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("playback",0);prefs.edit().clear().commit()
  val host=Robolectric.buildService(PlaybackService::class.java).create()
  try {
   shadowOf(Looper.getMainLooper()).idle();val initial=prefs.all.toMap()
   shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60));assertEquals(initial,prefs.all)
   prefs.edit().putLong("sleepDeadline",System.currentTimeMillis()+1000).commit()
   shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1001));assertFalse(prefs.contains("sleepDeadline"))
  }finally{host.destroy()}
 }
}
