package dev.alex.folderplayer

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast

internal object ExternalExtensions {
    const val ACTION = "dev.alex.folderplayer.action.EXTENSION"
    const val API = "dev.alex.folderplayer.extension.API"
    const val REQUEST = 216
    data class Entry(val component: ComponentName, val title: String)

    fun installed(context: Context): List<Entry> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(ACTION), PackageManager.GET_META_DATA)
            .mapNotNull { resolved ->
                val info = resolved.activityInfo ?: return@mapNotNull null
                if (!info.exported || !info.enabled || !info.applicationInfo.enabled ||
                    info.metaData?.getInt(API) != 1 ||
                    pm.checkSignatures(context.packageName, info.packageName) != PackageManager.SIGNATURE_MATCH
                ) return@mapNotNull null
                Entry(ComponentName(info.packageName, info.name), resolved.loadLabel(pm).toString())
            }.distinctBy { it.component }.sortedBy { it.title }
    }

    fun open(host: Activity, entry: Entry) {
        if (installed(host).none { it.component == entry.component }) return
        runCatching {
            host.startActivityForResult(Intent(ACTION).setComponent(entry.component), REQUEST)
        }.onFailure { Toast.makeText(host, "Не удалось открыть расширение", Toast.LENGTH_LONG).show() }
    }
}
