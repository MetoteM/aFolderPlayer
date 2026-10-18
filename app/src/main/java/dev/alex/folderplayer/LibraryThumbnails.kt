package dev.alex.folderplayer

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.view.View
import android.widget.ImageView
import android.widget.ScrollView
import java.util.concurrent.ExecutorService

/** Only visible covers are decoded; CUE tracks share one cached album bitmap. */
internal class LibraryThumbnails(private val context:Context,private val worker:ExecutorService,private val main:Handler) {
    private class Row(val view:ImageView,val uri:String,var loaded:Boolean=false)
    private val rows=mutableListOf<Row>()
    private val pending=hashSetOf<String>()
    @Volatile private var generation=0
    private var scroll:ScrollView?=null
    private var closed=false
    fun close(){closed=true;generation++;scroll?.setOnScrollChangeListener(null);rows.clear();pending.clear()}
    fun begin(scroll:ScrollView?) {
        this.scroll=scroll
        generation++;rows.clear();pending.clear()
        scroll?.setOnScrollChangeListener { _,_,_,_,_->loadVisible() }
    }
    fun add(view:ImageView,uri:String){rows.add(Row(view,uri))}
    fun loadVisible() {
        if(closed)return
        val rect=Rect()
        val visible=rows.filter { it.view.getGlobalVisibleRect(rect) }.toSet()
        rows.filter { it !in visible && it.view.drawable!=null }.forEach { it.view.setImageDrawable(null);it.loaded=false }
        for(row in visible) {
            if(pending.size>=2)return
            if(row.loaded || row.uri in pending || !row.view.getGlobalVisibleRect(rect))continue
            val token=generation;val uri=row.uri;pending.add(uri)
            worker.execute {
                if(token!=generation)return@execute
                val bitmap=LocalArtwork.readImage(context,android.net.Uri.parse(uri))
                main.post {
                    if(token==generation) {
                        pending.remove(uri)
                        rows.filter { it.uri==uri && it.view.getGlobalVisibleRect(Rect()) }.forEach { it.loaded=true;it.view.setImageBitmap(bitmap);if(bitmap==null)it.view.visibility=View.GONE }
                        loadVisible()
                    }
                }
            }
        }
    }
}
