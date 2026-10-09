package dev.alex.folderplayer
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/** Only the already verified candidate APK is shared, read-only, with Android's installer. */
class ExtensionFileProvider:ContentProvider() {
 override fun onCreate()=true
 override fun getType(uri:Uri)="application/vnd.android.package-archive"
 private fun file(uri:Uri):File { require(uri.path=="/engine.apk");return File(context!!.filesDir,"extensions/engine.apk") }
 override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor { require(mode=="r");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY) }
 override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor=MatrixCursor(arrayOf("_display_name","_size")).apply { addRow(arrayOf("AFP_Translate.apk",file(uri).length())) }
 override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException()
 override fun delete(uri:Uri,selection:String?,args:Array<out String>?):Int=throw UnsupportedOperationException()
 override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?):Int=throw UnsupportedOperationException()
}
