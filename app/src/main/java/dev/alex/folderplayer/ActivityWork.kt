package dev.alex.folderplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * UI callbacks belong to the screen. Running work receives only the application context. Closing
 * drops callbacks immediately, even when a provider ignores interruption.
 */
internal class ActivityWork(context: Context) : AutoCloseable {
    private val application = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val callbacks = mutableMapOf<Int, (Result<Any?>) -> Unit>()
    private val futures = mutableMapOf<Int, Future<*>>()
    private var sequence = 0
    private var closed = false

    fun <T> submit(work: (Context) -> T, complete: (Result<T>) -> Unit) {
        if (closed) return
        val id = ++sequence
        @Suppress("UNCHECKED_CAST")
        callbacks[id] = { result ->
            complete(result as Result<T>)
        }
        val weak = WeakReference(this)
        val context = application
        futures[id] =
            worker.submit {
                val result = runCatching { work(context) }
                main.post { weak.get()?.deliver(id, result) }
            }
    }

    private fun deliver(id: Int, result: Result<Any?>) {
        futures.remove(id)
        val callback = callbacks.remove(id)
        if (!closed) callback?.invoke(result)
    }

    fun cancelAll() {
        callbacks.clear()
        futures.values.forEach { it.cancel(true) }
        futures.clear()
    }

    override fun close() {
        closed = true
        cancelAll()
        worker.shutdownNow()
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
    }
}
