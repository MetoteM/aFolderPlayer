package dev.alex.folderplayer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** One gesture surface for the title, time and intervening space; seek stays outside. */
internal class NowPlayingStrip(context:Context,pull:((Float)->Unit)?=null,cancel:(()->Unit)?=null,open:()->Unit):LinearLayout(context) {
 val title=TextView(context).apply { text="Выбери песню";textSize=18f;setTextColor(Color.WHITE);setPadding(dp(8),dp(6),dp(8),dp(2)) }
 val time=TextView(context).apply { text="0:00 / 0:00";textSize=12f;setTextColor(Color.LTGRAY);setPadding(dp(8),dp(2),dp(8),dp(6)) }
 init {
  orientation=VERTICAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(76);setPadding(0,dp(4),0,dp(2))
  val grip=View(context).apply { background=GradientDrawable().apply { setColor(Color.rgb(91,116,119));cornerRadius=dp(2).toFloat() };importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
  addView(grip,LayoutParams(dp(28),dp(3)).apply { gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=dp(2) })
  addView(title,LayoutParams(-1,-2));addView(time,LayoutParams(-1,-2))
  // The whole strip is announced once. Its two text children do not handle touches.
  title.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO;time.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
  importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
  UiGestures.swipe(this,true,pull,cancel,open)
 }
 override fun onInitializeAccessibilityNodeInfo(info:android.view.accessibility.AccessibilityNodeInfo) {
  super.onInitializeAccessibilityNodeInfo(info);info.contentDescription="${title.text}. ${time.text}. Свайп вверх открывает экран песни"
 }
 private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
}
