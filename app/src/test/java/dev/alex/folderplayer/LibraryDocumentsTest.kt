package dev.alex.folderplayer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract.Document
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class LibraryDocumentsTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val uri=Uri.parse("content://review.documents/tree/music/document/music")
    private val columns=arrayOf(Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_SIZE)
    private fun provider(query:()->Cursor?){
        val p=object:ContentProvider(){
            override fun onCreate()=true
            override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?)=query()
            override fun getType(uri:Uri):String?=null
            override fun insert(uri:Uri,values:ContentValues?):Uri?=null
            override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
            override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
        }
        p.attachInfo(app,ProviderInfo().apply { authority="review.documents" })
        ShadowContentResolver.registerProviderInternal("review.documents",p)
    }
    @Test fun readsChildMetadataAndClosesCursor(){
        val c=MatrixCursor(columns).apply {
            addRow(arrayOf<Any>("music/album","Album",Document.MIME_TYPE_DIR,0L))
            addRow(arrayOf<Any>("music/one","01.flac","audio/flac",120L))
        }
        provider { c }
        val rows=LibraryDocuments.children(app,uri)
        assertTrue(c.isClosed);assertEquals(2,rows.size);assertTrue(rows[0].isDirectory)
        assertEquals("01.flac",rows[1].name);assertEquals(120L,rows[1].size);assertFalse(rows[1].isDirectory)
    }
    @Test fun emptyDirectoryRemainsDistinctFromFailedQuery(){
        provider { MatrixCursor(columns) };assertTrue(LibraryDocuments.children(app,uri).isEmpty())
        provider { null }
        try { LibraryDocuments.children(app,uri);fail("Null query must not clear cached music") }catch(expected:IOException){}
    }
    @Test(expected=SecurityException::class) fun deniedQueryIsNotConvertedToEmptyDirectory(){
        provider { throw SecurityException("Permission revoked") };LibraryDocuments.children(app,uri)
    }
}
