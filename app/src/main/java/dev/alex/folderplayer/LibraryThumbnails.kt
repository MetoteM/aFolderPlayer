package dev.alex.folderplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.view.View
import android.widget.ImageView
import android.widget.ScrollView
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** Visible covers only. Workers never own rows, views or their screen. */
internal class LibraryThumbnails(
    context: Context,
    private val worker: ExecutorService,
    private val main: Handler,
    private val read: (Context, Uri) -> Bitmap? = LocalArtwork::readImage,
) {
    private val application = context.applicationContext
    private class Row(val view: ImageView, val uri: String, var loaded: Boolean = false)
    private class Request {
        val cancelled = AtomicBoolean()
        var future: Future<*>? = null
        fun cancel() { cancelled.set(true); future?.cancel(true) }
    }
    private val rows = mutableListOf<Row>()
    private val pending = mutableMapOf<String, Request>()
    private var scroll: ScrollView? = null
    private var closed = false

    private fun clear() {
        scroll?.setOnScrollChangeListener(null)
        scroll = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        rows.forEach { it.view.setImageDrawable(null) }
        rows.clear()
    }

    fun close() { closed = true; clear() }
    fun begin(scroll: ScrollView?) {
        clear()
        if (closed) return
        this.scroll = scroll
        scroll?.setOnScrollChangeListener { _, _, _, _, _ -> loadVisible() }
    }
    fun add(view: ImageView, uri: String) { if (!closed) rows.add(Row(view, uri)) }
    fun loadVisible() {
        if (closed) return
        val rect = Rect()
        val visible = rows.filter { it.view.getGlobalVisibleRect(rect) }.toSet()
        rows.filter { it !in visible && it.view.drawable != null }.forEach {
            it.view.setImageDrawable(null); it.loaded = false
        }
        for (row in visible) {
            if (pending.size >= 2) return
            if (row.loaded || row.uri in pending) continue
            val uri = row.uri
            val request = Request()
            pending[uri] = request
            val owner = WeakReference(this)
            val context = application
            val reader = read
            val handler = main
            request.future = worker.submit {
                if (request.cancelled.get()) return@submit
                val bitmap = try { reader(context, Uri.parse(uri)) } catch (_: Exception) { null }
                if (!request.cancelled.get()) handler.post { owner.get()?.deliver(uri, request, bitmap) }
            }
        }
    }

    private fun deliver(uri: String, request: Request, bitmap: Bitmap?) {
        if (closed || pending[uri] !== request || request.cancelled.get()) return
        pending.remove(uri)
        rows.filter { it.uri == uri && it.view.getGlobalVisibleRect(Rect()) }.forEach {
            it.loaded = true
            it.view.setImageBitmap(bitmap)
            if (bitmap == null) it.view.visibility = View.GONE
        }
        loadVisible()
    }
}
