package dev.alex.folderplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.widget.Toast
import dev.alex.afptorrent.*

/** Optional signed IPC only. No torrent engine, network loop or storage in the player. */
internal class TorrentLink(private val host:Activity,private val changed:(String)->Unit):AutoCloseable {
    private var service:ITorrentService?=null
    private var bound=false
    private var closed=false
    private val main=Handler(Looper.getMainLooper())
    private val callback=object:ITorrentCallback.Stub(){override fun changed(snapshot:String){main.post { if(!closed)changed(snapshot) }}}
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,binder:IBinder){if(closed)return;service=ITorrentService.Stub.asInterface(binder);runCatching { if(service!!.apiVersion()==1)service!!.subscribe(callback) }}
        override fun onServiceDisconnected(name:ComponentName){service=null}
    }
    init { if(trusted(host))bound=runCatching { host.bindService(Intent().setComponent(ComponentName(PACKAGE,"dev.alex.afptorrent.TorrentService")),connection,Context.BIND_AUTO_CREATE) }.getOrDefault(false) }
    override fun close(){closed=true;runCatching { service?.unsubscribe(callback) };if(bound){host.unbindService(connection);bound=false};main.removeCallbacksAndMessages(null)}
    companion object {
        const val PACKAGE="dev.alex.afptorrent.lab"
        const val REQUEST=216
        fun trusted(context:Context)=context.packageManager.checkSignatures(context.packageName,PACKAGE)==PackageManager.SIGNATURE_MATCH
        fun open(host:Activity){
            if(!trusted(host)){
                AlertDialog.Builder(host).setTitle("AFP Torrent не установлен")
                    .setMessage("Установи подписанный AFP Torrent из комплекта прототипа 0.9.0.1. Локальная музыка работает без расширения.")
                    .setPositiveButton("Понятно",null).show();return
            }
            runCatching { host.startActivityForResult(Intent().setComponent(ComponentName(PACKAGE,"dev.alex.afptorrent.TorrentActivity")),REQUEST) }
                .onFailure { Toast.makeText(host,"Не удалось открыть AFP Torrent",Toast.LENGTH_LONG).show() }
        }
    }
}
