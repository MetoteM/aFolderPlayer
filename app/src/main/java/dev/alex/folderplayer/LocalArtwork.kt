package dev.alex.folderplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract

internal object LocalArtwork {
 private const val MAX_BYTES=12*1024*1024
 fun read(context:Context,id:String):Bitmap? {
  val uri=Uri.parse(id)
  val embedded=runCatching { val r=MediaMetadataRetriever();try { r.setDataSource(context,uri);r.embeddedPicture } finally { r.release() } }.getOrNull()
  if(embedded!=null && embedded.size<=MAX_BYTES)decode(embedded)?.let { return it }
  return runCatching {
   val documentId=DocumentsContract.getDocumentId(uri);val parent=documentId.substringBeforeLast('/',"");if(parent.isBlank())return@runCatching null
   val children=DocumentsContract.buildChildDocumentsUriUsingTree(uri,parent)
   val candidates=mutableListOf<Pair<Int,Uri>>()
   context.contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use { cursor ->
    while(cursor.moveToNext()) {
     val name=cursor.getString(1).lowercase();val rank=listOf("cover.jpg","cover.png","folder.jpg","folder.png","front.jpg","front.png","album.jpg","album.png").indexOf(name)
     if(rank>=0)candidates.add(rank to DocumentsContract.buildDocumentUriUsingTree(uri,cursor.getString(0)))
    }
   }
   for((_,candidate) in candidates.sortedBy { it.first }) {
    val bytes=context.contentResolver.openInputStream(candidate)?.use { stream ->
     val out=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192);while(true) { val n=stream.read(chunk);if(n<0)break;if(out.size()+n>MAX_BYTES)return@use null;out.write(chunk,0,n) };out.toByteArray()
    }
    if(bytes!=null)decode(bytes)?.let { return@runCatching it }
   };null
  }.getOrNull()
 }
 private fun decode(bytes:ByteArray):Bitmap? {
  val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
  if(bounds.outWidth<=0 || bounds.outHeight<=0)return null
  val options=BitmapFactory.Options().apply { inSampleSize=1;while(bounds.outWidth/inSampleSize>1024 || bounds.outHeight/inSampleSize>1024)inSampleSize*=2 }
  return BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
 }
}
