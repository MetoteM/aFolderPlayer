package dev.alex.folderplayer

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class AudiobooksTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val books get()=Audiobooks(app)
    private fun book(id:String)=Audiobook(id,"content://books/$id",id,listOf(BookChapter("file:///$id-1.mp3","01",60000),BookChapter("file:///$id-2.mp3","02",90000)))
    @Before fun clear(){app.getSharedPreferences("audiobooks",0).edit().clear().commit()}
    @Test fun positionsSpeedsAndBookmarksAreIndependentAndSurviveMetadataRefresh(){
        val a=book("a");val b=book("b");books.put(a);books.put(b)
        books.savePosition(a.id,a.chapters[1].uri,41000);books.savePosition(b.id,b.chapters[0].uri,12000)
        books.setSpeed(a.id,1.5f);books.addBookmark(a.id,a.chapters[1].uri,30000,"Start")
        books.put(a.copy(title="New title"))
        val restored=Audiobooks(app)
        assertEquals(BookPosition(a.chapters[1].uri,41000),restored.position(a))
        assertEquals(BookPosition(b.chapters[0].uri,12000),restored.position(b))
        assertEquals(1.5f,restored.speed(a.id));assertEquals(1f,restored.speed(b.id))
        assertEquals(30000L,restored.bookmarks(a.id).single().position)
        assertTrue(restored.bookmarks(b.id).isEmpty())
    }
    @Test fun removedChapterFallsBackSafelyAndPositionIsClamped(){
        val a=book("a");books.put(a);books.savePosition(a.id,a.chapters[1].uri,Long.MAX_VALUE,true)
        assertEquals(90000L,books.position(a).position)
        val updated=a.copy(chapters=listOf(a.chapters.first()));books.put(updated)
        assertEquals(a.chapters.first().uri,books.position(updated).uri)
        assertFalse(books.position(updated).completed)
    }
    @Test fun chapterOrderIsNaturalAndShortChaptersRemainEligible(){
        val chapters=listOf("10.mp3","2.mp3","1.mp3").map { BookChapter(it,it,1000) }
        assertEquals(listOf("1.mp3","2.mp3","10.mp3"),Audiobooks.order(chapters).map { it.title })
        assertEquals(3,Audiobooks.order(chapters).size)
        assertTrue("m4b" in Audiobooks.extensions)
    }
    @Test fun removingOneBookDoesNotRemoveAnotherBookOrItsProgress(){
        val a=book("a");val b=book("b");books.put(a);books.put(b);books.savePosition(b.id,b.chapters[0].uri,23000)
        books.addBookmark(a.id,a.chapters[0].uri,200,"A");val mark=books.bookmarks(a.id).single();books.removeBookmark(a.id,mark.id);assertTrue(books.bookmarks(a.id).isEmpty())
        books.remove(a.id);assertNull(books.book(a.id));assertEquals(23000L,books.position(b).position)
        assertTrue(books.bookmarks(a.id).isEmpty())
    }
    @Test fun invalidSpeedCannotPoisonPlayer(){
        for(speed in listOf(Float.NaN,Float.POSITIVE_INFINITY,0f,3f)){
            try { books.setSpeed("a",speed);fail("Invalid speed") }catch(expected:IllegalArgumentException){}
        }
    }
}
