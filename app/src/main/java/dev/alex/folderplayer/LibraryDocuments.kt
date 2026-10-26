package dev.alex.folderplayer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException

internal data class LibraryDocument(val uri:Uri, val name:String?, val isDirectory:Boolean, val size:Long)

/** Read child metadata in one query. A failed query is not an empty directory. */
internal object LibraryDocuments {
    fun children(context:Context, parent:Uri):List<LibraryDocument> {
        val columns=arrayOf(Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_SIZE)
        val uri=DocumentsContract.buildChildDocumentsUriUsingTree(parent,DocumentsContract.getDocumentId(parent))
        val cursor=context.contentResolver.query(uri,columns,null,null,null)
            ?: throw IOException("Не удалось прочитать содержимое папки")
        return cursor.use {
            val idColumn=it.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
            val nameColumn=it.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
            val mimeColumn=it.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
            val sizeColumn=it.getColumnIndexOrThrow(Document.COLUMN_SIZE)
            val rows=mutableListOf<LibraryDocument>()
            while(it.moveToNext()) {
                val id=it.getString(idColumn)
                val mime=it.getString(mimeColumn)
                if(id.isNullOrBlank() || mime.isNullOrBlank()) throw IOException("Неполные сведения о файле")
                rows.add(LibraryDocument(DocumentsContract.buildDocumentUriUsingTree(parent,id),
                    it.getString(nameColumn),mime==Document.MIME_TYPE_DIR,if(it.isNull(sizeColumn))0 else it.getLong(sizeColumn)))
            }
            rows
        }
    }
}
