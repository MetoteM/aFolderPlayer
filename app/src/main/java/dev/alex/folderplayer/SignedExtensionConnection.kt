package dev.alex.folderplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** No binding to untrusted publishers, no account access, no automatic startup. */
internal class SignedExtensionConnection(
    context: Context,
    private val pkg: String,
    private val service: String,
) {
    private val context = context.applicationContext

    fun installed(): Boolean =
        runCatching {
                context.packageManager.getApplicationInfo(pkg, 0).enabled &&
                    context.packageManager.checkSignatures(context.packageName, pkg) ==
                        PackageManager.SIGNATURE_MATCH
            }
            .getOrDefault(false)

    fun <T> use(block: (IBinder) -> T): T {
        check(installed()) { "Расширение не установлено или его подпись не подтверждена" }
        val ready = CountDownLatch(1)
        val binder = AtomicReference<IBinder?>()
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, value: IBinder) {
                    binder.set(value)
                    ready.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    binder.set(null)
                    ready.countDown()
                }

                override fun onNullBinding(name: ComponentName) {
                    ready.countDown()
                }

                override fun onBindingDied(name: ComponentName) {
                    binder.set(null)
                    ready.countDown()
                }
            }
        val bound =
            context.bindService(
                Intent().setComponent(ComponentName(pkg, service)),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        try {
            check(bound) { "Не удалось подключиться к расширению" }
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (!ready.await(200, TimeUnit.MILLISECONDS)) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                check(SystemClock.elapsedRealtime() < deadline) { "Расширение не отвечает" }
            }
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            return block(binder.get() ?: error("Расширение отключилось"))
        } finally {
            if (bound) runCatching { context.unbindService(connection) }
        }
    }
}
