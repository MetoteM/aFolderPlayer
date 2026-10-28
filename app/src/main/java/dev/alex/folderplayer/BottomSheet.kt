package dev.alex.folderplayer

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * UI refactor step C — progressive disclosure: track and song menus slide up
 * from the bottom edge (a bottom sheet) instead of a centred AlertDialog.
 * Presentation only; actions and their order are unchanged, so menu semantics
 * and tests stay compatible. No Material Components dependency: a plain
 * Dialog with gravity BOTTOM, rounded surface and a slide-up animation
 * (R.style.BottomSheetAnimation).
 */
internal object BottomSheet {
    fun show(
        context: Context,
        title: String,
        subtitle: String? = null,
        items: List<String>,
        chosen: (Int) -> Unit,
    ): Dialog {
        val p = AppAppearance.palette
        fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
        var dismiss: (() -> Unit)? = null
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(0), dp(10), dp(0), dp(12))
            background = GradientDrawable().apply {
                setColor(p.surface)
                cornerRadii =
                    floatArrayOf(
                        dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(),
                        0f, 0f, 0f, 0f,
                    )
            }
        }
        fun add(view: View, params: LinearLayout.LayoutParams) { box.addView(view, params) }
        // Drag handle — the visual affordance of a sheet.
        add(
            View(context).apply { background = GradientDrawable().apply { setColor(p.muted); cornerRadius = dp(2).toFloat() } },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL },
        )
        add(
            TextView(context).apply {
                text = title
                textSize = 16f
                setTextColor(p.text)
                setTypeface(typeface, Typeface.BOLD)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(20), dp(12), dp(16), if (subtitle == null) dp(6) else dp(2))
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        if (subtitle != null)
            add(
                TextView(context).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(p.muted)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(20), dp(0), dp(16), dp(6))
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var picked = false
        items.forEachIndexed { index, label ->
            rows.addView(
                TextView(context).apply {
                    text = label
                    textSize = 15f
                    setTextColor(p.text)
                    setPadding(dp(20), dp(0), dp(16), dp(0))
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(-1, dp(52))
                    setOnClickListener {
                        picked = true
                        dismiss?.invoke()
                        chosen(index)
                    }
                }
            )
        }
        val list = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(rows)
        }
        add(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(box)
            window?.apply {
                setGravity(Gravity.BOTTOM)
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setWindowAnimations(R.style.BottomSheetAnimation)
                setLayout(-1, -2)
            }
            setCancelable(true)
            // Dismiss without a choice (back/outside tap) reads as "cancelled".
            setOnDismissListener { if (!picked) chosen(-1) }
        }
        dismiss = { dialog.dismiss() }
        dialog.show()
        return dialog
    }
}
