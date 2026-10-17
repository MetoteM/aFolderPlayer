package dev.alex.folderplayer

import android.os.Build
import android.view.View
import android.view.WindowInsets

/** Keep interactive content outside system bars and display cutouts, without accumulating padding. */
internal object SystemBarInsets {
    fun apply(view:View) {
        val left=view.paddingLeft;val top=view.paddingTop
        val right=view.paddingRight;val bottom=view.paddingBottom
        view.setOnApplyWindowInsetsListener { target,insets ->
            if(Build.VERSION.SDK_INT>=30) {
                val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                target.setPadding(left+bars.left,top+bars.top,right+bars.right,bottom+bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                target.setPadding(left+insets.systemWindowInsetLeft,top+insets.systemWindowInsetTop,
                    right+insets.systemWindowInsetRight,bottom+insets.systemWindowInsetBottom)
            }
            insets
        }
        view.requestApplyInsets()
    }
}
