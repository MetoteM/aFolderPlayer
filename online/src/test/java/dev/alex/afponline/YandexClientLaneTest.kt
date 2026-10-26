package dev.alex.afponline

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class YandexClientLaneTest {
    @Test fun blockedCatalogueDoesNotBlockPlaybackLaneAndEachCachesItsSession() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val checks=java.util.concurrent.atomic.AtomicInteger()
        fun client(token:String)=YandexClient(token) { url,_ ->
            if(url.endsWith("/account/status")) { checks.incrementAndGet();"""{"result":{"account":{"uid":"42"}}}""" }
            else { entered.countDown();check(release.await(3,TimeUnit.SECONDS));"""{"result":{"tracks":{"results":[]}}}""" }
        }
        val catalogue=YandexClientLane(::client);val playback=YandexClientLane(::client)
        val pool=Executors.newFixedThreadPool(2)
        try {
            val slow=pool.submit { catalogue.use("token") { it.search("slow") } }
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            val quick=pool.submit<Int> { playback.use("token") { 7 } }
            assertEquals(7,quick.get(1,TimeUnit.SECONDS).toInt())
            assertFalse(slow.isDone)
            playback.use("token") { };assertEquals(2,checks.get())
            playback.use("new-token") { };assertEquals(3,checks.get())
            release.countDown();slow.get(2,TimeUnit.SECONDS)
        } finally { release.countDown();pool.shutdownNow() }
    }
}
