package dev.alex.folderplayer

import android.animation.ObjectAnimator
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.*
import kotlin.math.abs

/** A downward sheet gesture, shared by the whole surface, with controls and scrolling protected. */
internal class SheetSurface(context:Context,private val close:()->Unit):LinearLayout(context) {
 var horizontal:((Boolean)->Unit)?=null
 private var sideways=false
 private var horizontalEligible=false
 private var x=0f;private var y=0f;private var started=0L
 private var eligible=false;private var pulling=false;private var cancelledStream=false
 private var spring:ObjectAnimator?=null
 private var path=emptyList<View>()
 private val slop=ViewConfiguration.get(context).scaledTouchSlop
 private val distance=72*resources.displayMetrics.density
 override fun dispatchTouchEvent(event:MotionEvent):Boolean {
  if(cancelledStream && event.actionMasked!=MotionEvent.ACTION_DOWN) {
   if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL)cancelledStream=false
   return true
  }
  when(event.actionMasked) {
   MotionEvent.ACTION_DOWN -> {
    if(spring!=null) { spring?.cancel();spring=null;translationY=0f };cancelledStream=false;pulling=false;sideways=false;x=event.rawX;y=event.rawY;started=SystemClock.uptimeMillis()
    path=hitPath(this,event.x,event.y)
    eligible=path.none { it is Button || it is SeekBar || it is EditText || (it is TextView && it.hasSelection()) || (it is ScrollView && it.canScrollVertically(-1)) }
    horizontalEligible=horizontal!=null && path.none { it is Button || it is SeekBar || it is EditText || (it is TextView && it.hasSelection()) }
   }
   MotionEvent.ACTION_POINTER_DOWN -> {
    eligible=false;horizontalEligible=false
    if(sideways) { sideways=false;cancelledStream=true;return true }
    if(pulling) { pulling=false;cancelledStream=true;recover();return true }
   }
   MotionEvent.ACTION_MOVE -> {
    val dx=event.rawX-x;val dy=event.rawY-y
    if(horizontalEligible && !sideways && !pulling) {
     if(abs(dy)>slop && abs(dy)>abs(dx) || SystemClock.uptimeMillis()-started>=ViewConfiguration.getLongPressTimeout())horizontalEligible=false
     else if(abs(dx)>slop && abs(dx)>abs(dy)*1.5f) {
      val cancel=MotionEvent.obtain(event);cancel.action=MotionEvent.ACTION_CANCEL;super.dispatchTouchEvent(cancel);cancel.recycle();sideways=true;eligible=false
     }
    }
    if(sideways)return true
    if(eligible && !pulling && (dy < -slop || abs(dx)>slop && abs(dx)>abs(dy)))eligible=false
    // Let long-press selection and its handles keep ownership of text.
    if(eligible && !pulling && SystemClock.uptimeMillis()-started>=ViewConfiguration.getLongPressTimeout())eligible=false
    if(eligible && !pulling && dy>slop && dy>abs(dx)*1.3f) {
     val cancel=MotionEvent.obtain(event);cancel.action=MotionEvent.ACTION_CANCEL
     super.dispatchTouchEvent(cancel);cancel.recycle();pulling=true
    }
    if(pulling) { translationY=dy.coerceAtLeast(0f)*0.8f;return true }
   }
   MotionEvent.ACTION_UP -> if(sideways) {
    sideways=false;horizontalEligible=false;eligible=false;path=emptyList()
    val dx=event.rawX-x;if(abs(dx)>=distance && abs(dx)>abs(event.rawY-y)*1.5f)horizontal?.invoke(dx<0)
    return true
   } else if(pulling) {
    pulling=false;eligible=false
    val dy=event.rawY-y
    if(dy>=distance && dy>abs(event.rawX-x)*1.3f)close() else recover()
    path=emptyList();return true
   }
   MotionEvent.ACTION_CANCEL -> if(sideways) { sideways=false;horizontalEligible=false;eligible=false;path=emptyList();return true } else if(pulling) { pulling=false;eligible=false;path=emptyList();recover();return true }
  }
  val result=super.dispatchTouchEvent(event)
  if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) { eligible=false;path=emptyList() }
  return result
 }
 private fun recover() {
  spring=ObjectAnimator.ofFloat(this,View.TRANSLATION_Y,translationY,0f).apply { duration=180;interpolator=DecelerateInterpolator();start() }
 }
 fun resetGesture() { spring?.cancel();spring=null;eligible=false;pulling=false;sideways=false;horizontalEligible=false;cancelledStream=false;path=emptyList();translationY=0f }
 override fun onDetachedFromWindow() { resetGesture();super.onDetachedFromWindow() }
 private fun hitPath(view:View,x:Float,y:Float):List<View> {
  if(view is ViewGroup)for(i in view.childCount-1 downTo 0) {
   val child=view.getChildAt(i);if(child.visibility!=View.VISIBLE)continue
   val cx=x+view.scrollX-child.left-child.translationX;val cy=y+view.scrollY-child.top-child.translationY
   if(cx>=0 && cx<child.width && cy>=0 && cy<child.height)return listOf(view)+hitPath(child,cx,cy)
  }
  return listOf(view)
 }
}
