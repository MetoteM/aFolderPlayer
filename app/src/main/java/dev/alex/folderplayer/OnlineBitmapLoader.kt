package dev.alex.folderplayer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.datasource.DataSourceBitmapLoader
import com.google.common.util.concurrent.*

/** Notifications use the same bounded cache and network policy as the player cover. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OnlineBitmapLoader(private val context:Context):BitmapLoader {
    private val local=DataSourceBitmapLoader(context)
    private val artwork=OnlineArtwork(context)
    override fun supportsMimeType(mimeType:String)=local.supportsMimeType(mimeType)
    override fun decodeBitmap(data:ByteArray):ListenableFuture<Bitmap> = local.decodeBitmap(data)
    override fun loadBitmap(uri:Uri):ListenableFuture<Bitmap> {
        if(uri.scheme in listOf("content","file"))return worker.submit<Bitmap> { LocalArtwork.readImage(context,uri) ?: throw java.io.IOException("Обложка недоступна") }
        if(uri.scheme !in listOf("https","http"))return local.loadBitmap(uri)
        return worker.submit<Bitmap> { artwork.load(uri.toString()) ?: throw java.io.IOException("Обложка недоступна") }
    }
    companion object { private val worker=MoreExecutors.listeningDecorator(java.util.concurrent.Executors.newSingleThreadExecutor()) }
}
