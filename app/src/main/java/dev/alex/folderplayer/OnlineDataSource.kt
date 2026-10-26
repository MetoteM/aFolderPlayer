package dev.alex.folderplayer

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.*

/** Rechecks policy during reads as well as opens, including already opened audio streams. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OnlineDataSource(context:Context):DataSource {
    private val delegate=DefaultDataSource.Factory(context).createDataSource()
    private val policy=OnlineSettings(context)
    private var remote=false
    override fun addTransferListener(listener:TransferListener)=delegate.addTransferListener(listener)
    override fun open(spec:DataSpec):Long {
        remote=spec.uri.scheme in listOf("https","http")
        if(remote)policy.requireNetwork()
        return delegate.open(spec)
    }
    override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
        if(remote)policy.requireNetwork()
        return delegate.read(buffer,offset,length)
    }
    override fun getUri():Uri?=delegate.uri
    override fun getResponseHeaders():Map<String,List<String>> = delegate.responseHeaders
    override fun close()=delegate.close()
    class Factory(private val context:Context):DataSource.Factory {
        override fun createDataSource():DataSource=OnlineDataSource(context)
    }
}
