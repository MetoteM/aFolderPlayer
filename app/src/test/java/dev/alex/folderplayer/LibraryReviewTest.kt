package dev.alex.folderplayer

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class LibraryReviewTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val prefs get()=app.getSharedPreferences("library",Context.MODE_PRIVATE)
    private val root="content://unavailable.documents/tree/music"
    private val items get()=listOf(Track("content://music/1","One","Artist",30000,"Music",rootUri=root))
    @Before fun prepare(){prefs.edit().clear().putString("tracks",JSONArray(items.map { it.json() }).toString()).commit()}
    @Test fun temporarilyUnreadableRootKeepsCachedMusicAndPlaylist(){
        val library=Library(app);library.setRoots(listOf(root));library.savePlaylist("My music",items.map { it.uri })
        val (found,errors,retained)=library.scan()
        assertTrue(errors>0);assertEquals(1,retained);assertEquals(items,found)
        assertEquals(items,Library(app).tracks());assertEquals(items.map { it.uri }.toSet(),library.playlist("My music"))
    }
    @Test fun explicitlyRemovedRootsAreNotKeptByFailureRecovery(){
        val removed=items.single().copy(uri="content://music/removed",rootUri="content://other.documents/tree/removed")
        prefs.edit().putString("tracks",JSONArray((items+removed).map { it.json() }).toString()).commit()
        val library=Library(app);library.setRoots(listOf(root))
        assertEquals(items,library.scan().tracks)
        library.setRoots(emptyList());assertTrue(library.scan().tracks.isEmpty())
    }
    @Test fun recoveryDoesNotBakeManualAlbumYearIntoStoredTags(){
        val original=items.single().copy(year=1991)
        prefs.edit().putString("tracks",JSONArray(listOf(original.json())).toString()).commit()
        val years=AlbumYears(app);years.save(original.albumIdentity(),AlbumYear(1988,"manual",overridesTags=true))
        val library=Library(app);library.setRoots(listOf(root))
        assertEquals(1988,library.scan().tracks.single().year)
        years.remove(original.albumIdentity())
        assertEquals(1991,library.tracks().single().year)
    }
    @Test fun interruptedScanCannotReplacePersistedMetadata(){
        Library(app).setRoots(listOf(root))
        Thread.currentThread().interrupt()
        try { Library(app).scan();fail("Interrupted scan must cancel") }
        catch(expected:CancellationException) { }
        finally { Thread.interrupted() }
        assertEquals(items,Library(app).tracks())
    }
    @Test fun separateLibraryInstancesCannotOverwriteEachOthersPlaylists(){
        val paused=CountDownLatch(1);val release=CountDownLatch(1);val secondRead=CountDownLatch(1)
        val calls=AtomicInteger();val delegate=prefs
        val intercepted=Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,arrayOf(SharedPreferences::class.java)) { _,method,args ->
            val result=method.invoke(delegate,*(args ?: emptyArray()))
            if(method.name=="getString" && args?.firstOrNull()=="playlists") {
                if(Thread.currentThread().name=="writer-a" && calls.incrementAndGet()==2) {
                    paused.countDown();check(release.await(5,TimeUnit.SECONDS))
                } else if(Thread.currentThread().name=="writer-b") secondRead.countDown()
            }
            result
        } as SharedPreferences
        val context=object:ContextWrapper(app){override fun getSharedPreferences(name:String,mode:Int)=if(name=="library")intercepted else super.getSharedPreferences(name,mode)}
        val pool=Executors.newFixedThreadPool(2)
        try {
            val first=pool.submit { Thread.currentThread().name="writer-a";Library(context).savePlaylist("A",listOf("a")) }
            assertTrue(paused.await(5,TimeUnit.SECONDS))
            val started=CountDownLatch(1)
            val second=pool.submit { Thread.currentThread().name="writer-b";started.countDown();Library(context).savePlaylist("B",listOf("b")) }
            assertTrue(started.await(5,TimeUnit.SECONDS))
            assertFalse("Another instance must wait for the in-flight mutation",secondRead.await(200,TimeUnit.MILLISECONDS))
            release.countDown();first.get(5,TimeUnit.SECONDS);second.get(5,TimeUnit.SECONDS)
            assertEquals(setOf("a"),Library(app).playlist("A"));assertEquals(setOf("b"),Library(app).playlist("B"))
        }finally { release.countDown();pool.shutdownNow() }
    }
}
