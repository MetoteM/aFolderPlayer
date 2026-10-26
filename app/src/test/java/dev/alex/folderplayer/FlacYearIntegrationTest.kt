package dev.alex.folderplayer

import android.media.MediaMetadataRetriever
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class FlacYearIntegrationTest {
 @Test fun scannerYearGetterFallsBackToFlacDateWhenAndroidYearIsMissing() {
  val app=RuntimeEnvironment.getApplication();val file=File(app.cacheDir,"year-fixture.flac")
  javaClass.getResourceAsStream("/synthetic-year-comments.flac")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
  val native=object:MediaMetadataRetriever() { override fun extractMetadata(key:Int):String?=null }
  try { assertEquals(1988,Library(app).readAlbumYear(native,"Fixture.FLAC",Uri.fromFile(file))) } finally { native.release();file.delete() }
 }
 @Test fun nativeYearIsPreservedAndMissingNonFlacYearDoesNotUseFileModificationDate() {
  val library=Library(RuntimeEnvironment.getApplication());val missing=Uri.parse("content://missing/file")
  val native=object:MediaMetadataRetriever() { override fun extractMetadata(key:Int):String?=if(key==METADATA_KEY_YEAR) "1991" else "2026" }
  try { assertEquals(1991,library.readAlbumYear(native,"Song.flac",missing)) } finally { native.release() }
  val empty=object:MediaMetadataRetriever() { override fun extractMetadata(key:Int):String?=if(key==METADATA_KEY_DATE) "2026-10-08" else null }
  try { assertNull(library.readAlbumYear(empty,"Song.mp3",missing)) } finally { empty.release() }
 }
}
