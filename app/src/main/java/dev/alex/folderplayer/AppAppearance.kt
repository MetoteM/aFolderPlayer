package dev.alex.folderplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*

object AppAppearance {
    data class Palette(val id:String, val title:String, val description:String, val light:Boolean,
        val style:Int, val background:Int, val surface:Int, val text:Int, val muted:Int,
        val accent:Int, val selected:Int, val control:Int)
    val choices = listOf(
        Palette("night", "Ночная", "Графит и мята", false, R.style.AppTheme, 0xFF10191E.toInt(), 0xFF1B272D.toInt(), 0xFFFFFFFF.toInt(), 0xFFA5B1B9.toInt(), 0xFF69DFC2.toInt(), 0xFF143435.toInt(), 0xFF2D4649.toInt()),
        Palette("day", "Дневная", "Светлая и прохладная", true, R.style.AppThemeDay, 0xFFF3F6FA.toInt(), 0xFFFFFFFF.toInt(), 0xFF172B3A.toInt(), 0xFF526675.toInt(), 0xFF006B60.toInt(), 0xFFD6ECE7.toInt(), 0xFFB8CBC7.toInt()),
        Palette("sepia", "Сепия", "Тёплая бумага и янтарь", true, R.style.AppThemeSepia, 0xFFF5EDE0.toInt(), 0xFFFFF8EC.toInt(), 0xFF3C3026.toInt(), 0xFF71604D.toInt(), 0xFF86520F.toInt(), 0xFFEAD8B8.toInt(), 0xFFB69B73.toInt()),
        Palette("ocean", "Океан", "Глубокий синий и лаванда", false, R.style.AppThemeOcean, 0xFF10182C.toInt(), 0xFF1B2942.toInt(), 0xFFEDF3FF.toInt(), 0xFFA8B8D4.toInt(), 0xFF9DBBFF.toInt(), 0xFF293F65.toInt(), 0xFF4F658C.toInt())
    )
    @get:JvmStatic var palette:Palette = choices.first()
        private set
    @get:JvmStatic val textColor get() = palette.text
    fun selected(context:Context):Palette {
        val id=context.getSharedPreferences("appearance",Context.MODE_PRIVATE).getString("theme","night")
        return choices.firstOrNull { it.id==id } ?: choices.first()
    }
    fun save(context:Context, choice:Palette) {
        context.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit().putString("theme",choice.id).apply()
    }
    fun apply(activity:Activity):Palette {
        val selected=selected(activity)
        palette=selected
        activity.setTheme(selected.style)
        activity.window.statusBarColor=selected.background
        activity.window.navigationBarColor=selected.background
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = activity.window.decorView.systemUiVisibility.let { flags ->
            val mask=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            if(selected.light) flags or mask else flags and mask.inv()
        }
        if(android.os.Build.VERSION.SDK_INT>=30) {
            val mask=android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            activity.window.insetsController?.setSystemBarsAppearance(if(selected.light) mask else 0,mask)
        }
        return selected
    }
}

open class ThemedActivity:androidx.activity.ComponentActivity() {
    private var appliedTheme=""
    override fun onCreate(state:Bundle?) {
        appliedTheme=AppAppearance.apply(this).id
        super.onCreate(state)
    }
    override fun onResume() {
        super.onResume()
        if(appliedTheme!=AppAppearance.selected(this).id) recreate()
    }
}

internal object ThemePicker {
    fun show(host:Activity) {
        val current=AppAppearance.selected(host).id
        val adapter=object:BaseAdapter() {
            override fun getCount()=AppAppearance.choices.size
            override fun getItem(position:Int)=AppAppearance.choices[position]
            override fun getItemId(position:Int)=position.toLong()
            override fun getView(position:Int,recycled:View?,parent:ViewGroup):View {
                val p=getItem(position)
                fun dp(n:Int)=(n*host.resources.displayMetrics.density).toInt()
                return LinearLayout(host).apply {
                    orientation=LinearLayout.HORIZONTAL
                    gravity=android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(12),dp(12),dp(16),dp(12))
                    setBackgroundColor(p.background)
                    contentDescription=p.title+", "+p.description+if(p.id==current) ", выбрана" else ""
                    addView(RadioButton(host).apply {
                        isChecked=p.id==current;isClickable=false;isFocusable=false
                        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        buttonTintList=android.content.res.ColorStateList.valueOf(p.accent)
                    })
                    addView(LinearLayout(host).apply {
                        orientation=LinearLayout.VERTICAL
                        addView(TextView(host).apply { text=p.title;textSize=17f;setTextColor(p.text) })
                        addView(TextView(host).apply { text=p.description;textSize=12f;setTextColor(p.muted) })
                    },LinearLayout.LayoutParams(0,-2,1f))
                    addView(View(host).apply {
                        background=android.graphics.drawable.GradientDrawable().apply { setColor(p.accent);cornerRadius=dp(12).toFloat() }
                        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },LinearLayout.LayoutParams(dp(24),dp(24)))
                }
            }
        }
        AlertDialog.Builder(host).setTitle("Тема оформления").setAdapter(adapter) { dialog,index ->
            dialog.dismiss()
            val choice=AppAppearance.choices[index]
            if(choice.id!=current) { AppAppearance.save(host,choice);host.recreate() }
        }.setNegativeButton(host.afp(R.string.cancel),null).show()
    }
}
