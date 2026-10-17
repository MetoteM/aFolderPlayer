package dev.alex.folderplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/** Local code locations only, no exception messages, account data or automatic upload. */
internal object CrashNotes {
    private val installed=AtomicBoolean()
    fun install(context:Context) {
        if(!installed.compareAndSet(false,true))return
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        if(previous?.javaClass?.name==LocalHandler::class.java.name)return
        Thread.setDefaultUncaughtExceptionHandler(LocalHandler(context.applicationContext,previous))
    }
    private class LocalHandler(private val app:Context,private val previous:Thread.UncaughtExceptionHandler?):Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread:Thread,error:Throwable) {
            record(app,error,true);previous?.uncaughtException(thread,error)
        }
    }

    fun record(context:Context,error:Throwable,fatal:Boolean=false) {
        runCatching {
            val text=buildString {
                append("aFolderPlayer · ").append(System.currentTimeMillis()).append('\n')
                var cause:Throwable?=error
                repeat(4) {
                    val value=cause ?: return@repeat
                    append(value.javaClass.name).append('\n')
                    value.stackTrace.take(24).forEach { frame -> append(frame.className).append('.').append(frame.methodName).append(':').append(frame.lineNumber).append('\n') }
                    cause=value.cause?.takeIf { it!==value }
                }
            }.take(12000)
            context.getSharedPreferences("local_crash",0).edit().putString(if(fatal)"fatal" else "trace",text).commit()
        }
    }
    fun show(host:Activity) {
        val prefs=host.getSharedPreferences("local_crash",0)
        val text=prefs.getString("fatal",null) ?: prefs.getString("trace",null) ?: "В этой версии сбоев пока не зафиксировано"
        AlertDialog.Builder(host).setTitle("Последний сбой · локально").setMessage(text)
            .setPositiveButton("Закрыть",null).setNeutralButton("Копировать") { _,_->
                host.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Сбой плеера",text))
            }.show()
    }
}
