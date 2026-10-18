package dev.alex.folderplayer

import android.graphics.Color
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class AlbumArtworkTest {
    private fun image(path:String)=AlbumImage(path,"content://art/$path")
    @Test fun knownFrontCoversWinAndWrongAlbumOrBackScanIsNotPicked() {
        val index=AlbumArtwork(listOf(image("Music/A/Back.jpg"),image("Music/B/Cover.jpg"),image("Music/A/Front.jpeg"),image("Music/A/COVER.PNG")))
        assertEquals(listOf("Music/A/COVER.PNG","Music/A/Front.jpeg"),index.candidates("Music/A/01.flac").map { it.path })
        assertTrue(index.candidates("Music/C/01.flac").isEmpty())
        assertTrue(AlbumArtwork(listOf(image("A/Back.jpg"),image("A/CD.jpg"))).candidates("A/album.flac").isEmpty())
    }
    @Test fun artworkSubfoldersAndDiscParentsAreScopedToTheirOwnAlbum() {
        val index=AlbumArtwork(listOf(image("Music/A/Scans/01 - Front.JPG"),image("Music/B/Cover.png"),image("Music/A/Scans/02 - Back.jpg")))
        assertEquals("Music/A/Scans/01 - Front.JPG",index.candidates("Music/A/CD 1/01.flac").single().path)
        assertEquals("Music/A/Scans/01 - Front.JPG",index.candidates("Music/A/album.flac").single().path)
        assertTrue(index.candidates("Music/A/Other/01.flac").isEmpty())
    }
    @Test fun albumNamedImagesAndLoneUnknownImageAreRecognizedWithoutGuessingAmongScans() {
        assertEquals("A/Album Name.jpg",AlbumArtwork(listOf(image("A/Album Name.jpg"),image("A/back.png"))).candidates("A/Album Name.flac").single().path)
        assertEquals("A/Artist - Record.webp",AlbumArtwork(listOf(image("A/Artist - Record.webp"))).candidates("A/01.flac").single().path)
        assertTrue(AlbumArtwork(listOf(image("A/scan1.jpg"),image("A/scan2.jpg"))).candidates("A/01.flac").isEmpty())
        assertTrue(AlbumArtwork.image("COVER.JPEG"));assertTrue(AlbumArtwork.image("Front.WebP"));assertFalse(AlbumArtwork.image("cover.exe"))
    }
    @Test fun fileImagesDecodeWithinBoundsAndSeparateAlbumsNeverShareCacheEntries() {
        val context=RuntimeEnvironment.getApplication();val dir=java.io.File(context.cacheDir,"covers-${System.nanoTime()}").apply { mkdirs() }
        fun png(name:String,width:Int,height:Int,color:Int):java.io.File {
            val bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            return java.io.File(dir,name).also { file ->
                file.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
                bitmap.recycle()
            }
        }
        try {
            LocalArtwork.clear()
            val red=png("red.png",32,32,Color.RED);val blue=png("blue.png",4000,1000,Color.BLUE)
            val a=LocalArtwork.readImage(context,Uri.fromFile(red))!!;val b=LocalArtwork.readImage(context,Uri.fromFile(blue))!!
            assertEquals(Color.RED,a.getPixel(0,0));assertEquals(Color.BLUE,b.getPixel(0,0));assertTrue(b.width<=1024 && b.height<=1024)
            assertSame(a,LocalArtwork.read(context,"content://audio/one",Uri.fromFile(red).toString()))
            val broken=java.io.File(dir,"bad.jpg").apply { writeText("not an image") };assertNull(LocalArtwork.readImage(context,Uri.fromFile(broken)))
            val large=java.io.File(dir,"large.jpg").apply { outputStream().use { out->repeat(13){out.write(ByteArray(1024*1024))} } };assertNull(LocalArtwork.readImage(context,Uri.fromFile(large)))
            assertNull(LocalArtwork.readImage(context,Uri.parse("https://example.test/cover.jpg")))
        } finally { LocalArtwork.clear();dir.deleteRecursively() }
    }
    @Test fun cueTracksRetainAlbumArtworkInSavedLibraryAndPlayerMetadata() {
        val context=RuntimeEnvironment.getApplication();val file=Track("content://music/image","image","artist",120000,"A","album.flac",artworkUri="content://covers/front")
        val cue=CueSheet.parse("FILE \"album.flac\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nINDEX 01 01:00:00")
        val tracks=CueTracks.expand(cue,"cue","A","",listOf(file))
        context.getSharedPreferences("library",0).edit().clear().putString("tracks",org.json.JSONArray(tracks.map { it.json() }).toString()).commit()
        assertEquals(tracks,Library(context).tracks())
        assertTrue(tracks.all { it.item().mediaMetadata.artworkUri.toString()==file.artworkUri })
    }
}
