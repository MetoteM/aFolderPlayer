package dev.alex.folderplayer

import android.view.Gravity
import android.widget.*

/** Shared view factories: extracted from MainActivity (roadmap 4.2). */
internal fun MainActivity.row() =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

internal fun MainActivity.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

internal fun MainActivity.weight() =
        LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }

internal fun MainActivity.label(text: String, size: Float = 16f) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(AppAppearance.textColor)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

internal fun MainActivity.button(text: String, action: () -> Unit) =
        Button(this).apply {
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

internal fun MainActivity.toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

internal fun MainActivity.clock(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

