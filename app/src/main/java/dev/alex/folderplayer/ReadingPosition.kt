package dev.alex.folderplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ScrollView

internal object ReadingPosition {
    @JvmStatic
    @JvmOverloads
    fun attach(context: Context, view: ScrollView, key: String, restore: Boolean = true) {
        val prefs = context.applicationContext.getSharedPreferences("reading", 0)
        val name = LyricsDocument.key(key)
        var restored = !restore
        var latest = prefs.getInt(name, 0)
        var dirty = false
        val handler = Handler(Looper.getMainLooper())
        val save = Runnable {
            if (dirty) {
                prefs.edit().putInt(name, latest).apply()
                dirty = false
            }
        }
        if (restore)
            view.post {
                view.scrollTo(0, latest)
                restored = true
            }
        view.setOnScrollChangeListener { _: View, _: Int, y: Int, _: Int, _: Int ->
            if (restored && latest != y) {
                latest = y
                dirty = true
                handler.removeCallbacks(save)
                handler.postDelayed(save, 300)
            }
        }
        view.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}

                override fun onViewDetachedFromWindow(v: View) {
                    handler.removeCallbacks(save)
                    save.run()
                }
            }
        )
    }
}
