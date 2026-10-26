package dev.alex.folderplayer

import android.content.Context
import android.graphics.drawable.*
import android.view.Gravity
import android.widget.*

/** Common themed widgets shared by browser and song dialogs. */
internal class PlayerUi(private val context: Context) {
    fun row() =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun weight() =
        LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }

    fun label(text: String, size: Float = 16f) =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(AppAppearance.textColor)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

    fun button(text: String, action: () -> Unit) =
        Button(context).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f
            minWidth = 0
            minimumWidth = 0
            minimumHeight = dp(48)
            setTextColor(AppAppearance.textColor)
            background =
                android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(AppAppearance.palette.control),
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(PlayerScreens.surface)
                        cornerRadius = dp(16).toFloat()
                    },
                    null,
                )
            setOnClickListener { action() }
        }

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    fun clock(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
