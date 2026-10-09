package dev.alex.folderplayer
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
internal object UiGestures {
 @SuppressLint("ClickableViewAccessibility")
 fun swipe(view:View,up:Boolean,pull:((Float)->Unit)?=null,cancel:(()->Unit)?=null,open:()->Unit) {
  var x=0f;var y=0f;var preview=false
  val distance=36*view.resources.displayMetrics.density
  view.setOnTouchListener { target,event -> when(event.actionMasked) {
   MotionEvent.ACTION_DOWN -> { x=event.rawX;y=event.rawY;preview=false;true }
   MotionEvent.ACTION_MOVE -> {
    val dy=event.rawY-y;val dx=event.rawX-x
    if(kotlin.math.abs(dy)>distance/3)target.parent?.requestDisallowInterceptTouchEvent(true)
    if(up && -dy>distance/3 && -dy>kotlin.math.abs(dx)*1.3f && pull!=null) { preview=true;pull(-dy) }
    true
   }
   MotionEvent.ACTION_UP -> { val dy=event.rawY-y;val dx=event.rawX-x;if((if(up)-dy else dy)>distance && kotlin.math.abs(dy)>kotlin.math.abs(dx)*1.3f)open() else if(preview)cancel?.invoke();preview=false;target.parent?.requestDisallowInterceptTouchEvent(false);target.performClick();true }
   MotionEvent.ACTION_CANCEL -> { if(preview)cancel?.invoke();preview=false;target.parent?.requestDisallowInterceptTouchEvent(false);true }

   else -> true
  } }
  view.accessibilityDelegate=object:View.AccessibilityDelegate() {
   override fun onInitializeAccessibilityNodeInfo(host:View,info:android.view.accessibility.AccessibilityNodeInfo) { super.onInitializeAccessibilityNodeInfo(host,info);info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_EXPAND,if(up)"Открыть экран песни" else "Закрыть экран песни")) }
   override fun performAccessibilityAction(host:View,action:Int,args:android.os.Bundle?):Boolean { if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_EXPAND) { open();return true };return super.performAccessibilityAction(host,action,args) }
  }
 }
}
