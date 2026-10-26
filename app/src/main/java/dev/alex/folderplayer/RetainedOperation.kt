package dev.alex.folderplayer

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** Work owns no Activity. Attach/detach screens without restarting requests. */
class RetainedOperation<T> : AutoCloseable {
    fun interface Progress {
        fun update(message: String)
    }

    fun interface Work<T> {
        @Throws(Exception::class)
        fun run(cancel: AtomicBoolean, progress: Progress): T
    }

    fun interface Observer<T> {
        fun changed(state: Snapshot<T>)
    }

    class Snapshot<T>(
        val key: String?,
        val message: String,
        val busy: Boolean,
        val value: T?,
        val failure: Exception?,
    )

    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            val t = Thread(r, "AFP-operation")
            t.priority = Thread.MIN_PRIORITY
            t
        }
    private val main = Handler(Looper.getMainLooper())
    private var observer: Observer<T>? = null
    @Volatile
    private var state: Snapshot<T> = Snapshot(null, "", false, null, null)
    private var cancel = AtomicBoolean()
    private var future: Future<*>? = null
    private var cancellation: Runnable? = null

    fun state(): Snapshot<T> = state

    fun onCancel(action: Runnable?) {
        cancellation = action
    }

    fun attach(value: Observer<T>) {
        observer = value
        value.changed(state)
    }

    fun detach() {
        observer = null
    }

    fun start(key: String, work: Work<T>) {
        cancel()
        val token = AtomicBoolean()
        cancel = token
        state = Snapshot(key, "Подготовка…", true, null, null)
        observer?.changed(state)
        future =
            worker.submit {
                try {
                    if (token.get()) return@submit
                    val value =
                        work.run(
                            token,
                            object : Progress {
                                override fun update(message: String) {
                                    publish(token, Snapshot(key, message, true, null, null))
                                }
                            },
                        )
                    if (!token.get()) publish(token, Snapshot(key, "", false, value, null))
                } catch (e: Exception) {
                    if (!token.get()) publish(token, Snapshot(key, "", false, null, e))
                }
            }
    }

    private fun publish(token: AtomicBoolean, next: Snapshot<T>) {
        main.post {
            if (token !== cancel || token.get()) return@post
            state = next
            observer?.changed(next)
        }
    }

    fun cancel() {
        cancel.set(true)
        cancellation?.run()
        cancellation = null
        future?.cancel(true)
        future = null
        state = Snapshot(null, "", false, null, null)
    }

    override fun close() {
        detach()
        cancel()
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }
}
