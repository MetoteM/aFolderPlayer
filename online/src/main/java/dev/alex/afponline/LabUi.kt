package dev.alex.afponline

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Лёгкость" (UI refactor A+B) for the online extension lab screens: one
 * typographic scale (18sp section header, 15sp rows, 13sp body, 12sp muted),
 * an 8dp spacing grid and quiet section grouping. Theme-aware: colours are
 * read from the activity theme, so dark and light days both look right.
 * Presentation only — the order and identity of controls is unchanged, the
 * token field stays the first EditText and lab buttons stay Buttons.
 */
internal object LabUi {
    fun dp(activity: Activity, n: Int) = (n * activity.resources.displayMetrics.density).toInt()

    fun color(activity: Activity, attr: Int): Int {
        val value = TypedValue()
        activity.theme.resolveAttribute(attr, value, true)
        return value.data
    }

    fun primary(activity: Activity) = color(activity, android.R.attr.textColorPrimary)
    fun secondary(activity: Activity) = color(activity, android.R.attr.textColorSecondary)

    fun header(activity: Activity, root: LinearLayout, text: String) {
        root.addView(
            TextView(activity).apply {
                this.text = text
                textSize = 18f
                setTextColor(primary(activity))
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.01f
                setPadding(dp(activity, 0), dp(activity, 8), dp(activity, 0), dp(activity, 4))
            }
        )
    }

    fun section(activity: Activity, root: LinearLayout, text: String) {
        root.addView(
            TextView(activity).apply {
                this.text = text.uppercase()
                textSize = 12f
                setTextColor(secondary(activity))
                letterSpacing = 0.06f
                setPadding(dp(activity, 0), dp(activity, 16), dp(activity, 0), dp(activity, 4))
            }
        )
    }

    fun body(activity: Activity, root: LinearLayout, text: String) {
        root.addView(
            TextView(activity).apply {
                this.text = text
                textSize = 13f
                setTextColor(secondary(activity))
                setLineSpacing(dp(activity, 2).toFloat(), 1f)
                setPadding(dp(activity, 0), dp(activity, 4), dp(activity, 0), dp(activity, 4))
            }
        )
    }

    fun status(activity: Activity, root: LinearLayout): TextView =
        TextView(activity).apply {
            textSize = 13f
            setTextColor(secondary(activity))
            setPadding(dp(activity, 0), dp(activity, 6), dp(activity, 0), dp(activity, 6))
            root.addView(this)
        }

    fun button(
        activity: Activity,
        root: LinearLayout,
        text: String,
        gated: Boolean = false,
        actions: MutableList<Button>? = null,
        action: () -> Unit,
    ): Button =
        Button(activity).apply {
            this.text = text
            textSize = 14f
            isAllCaps = false
            setOnClickListener { action() }
            root.addView(
                this,
                LinearLayout.LayoutParams(-1, dp(activity, 44)).apply {
                    setMargins(0, dp(activity, 2), 0, dp(activity, 2))
                },
            )
            if (gated) actions?.add(this)
        }

    fun tokenField(activity: Activity, root: LinearLayout): EditText =
        EditText(activity).apply {
            hint = "Токен сессии · локально"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            textSize = 14f
            root.addView(
                this,
                LinearLayout.LayoutParams(-1, dp(activity, 44)).apply {
                    setMargins(0, dp(activity, 2), 0, dp(activity, 2))
                },
            )
        }

    fun queryField(activity: Activity, root: LinearLayout): EditText =
        EditText(activity).apply {
            hint = "Песня или исполнитель"
            isSingleLine = true
            textSize = 14f
            root.addView(
                this,
                LinearLayout.LayoutParams(-1, dp(activity, 44)).apply {
                    setMargins(0, dp(activity, 2), 0, dp(activity, 2))
                },
            )
        }

    /** Two-line song row instead of a fat two-line Button: title 15sp, artist 12sp. */
    fun songRow(
        activity: Activity,
        list: LinearLayout,
        title: String,
        artist: String,
        onClick: () -> Unit,
    ) {
        val ripple = GradientDrawable().apply {
            setColor(LabUi.color(activity, android.R.attr.colorBackground))
            cornerRadius = dp(activity, 10).toFloat()
        }
        list.addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = ripple
                isClickable = true
                isFocusable = true
                setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8))
                (layoutParams ?: LinearLayout.LayoutParams(-1, -2)).let {
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                        setMargins(0, dp(activity, 3), 0, dp(activity, 3))
                    }
                }
                addView(
                    TextView(activity).apply {
                        text = title
                        textSize = 15f
                        setTextColor(primary(activity))
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                )
                addView(
                    TextView(activity).apply {
                        text = artist
                        textSize = 12f
                        setTextColor(secondary(activity))
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                )
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { onClick() }
            }
        )
    }
}
