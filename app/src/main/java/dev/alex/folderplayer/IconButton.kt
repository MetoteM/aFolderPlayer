package dev.alex.folderplayer

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.PopupWindow
import android.widget.TextView

/** Compact command with a held hint; leaving its bounds cancels the whole press. */
internal class IconButton(context:Context,kind:Kind,label:String,action:()->Unit):Button(context) {
 enum class Kind { LIBRARY, PLAYLIST, SORT, SELECT, DONE, MENU }
 var icon=kind
  set(value) { field=value;setCompoundDrawables(null,Symbol(value),null,null) }
 private var hint:PopupWindow?=null
 private var cancelled=false
 internal val hintVisible get()=hint?.isShowing==true
 init {
  text="";textSize=0f;includeFontPadding=false;gravity=android.view.Gravity.CENTER;contentDescription=label;isAllCaps=false;minWidth=0;minimumWidth=0;minHeight=dp(48);minimumHeight=dp(48);setPadding(dp(10),dp(10),dp(10),dp(10));compoundDrawablePadding=0
  background=RippleDrawable(ColorStateList.valueOf(Color.rgb(45,70,73)),GradientDrawable().apply { setColor(PlayerScreens.surface);cornerRadius=dp(14).toFloat() },null)
  backgroundTintList=null;icon=kind;setOnClickListener { action() };setOnLongClickListener { showHint();true }
 }
 private fun showHint() {
  dismissHint()
  val label=TextView(context).apply { text=this@IconButton.contentDescription;textSize=14f;setTextColor(Color.WHITE);setPadding(dp(12),dp(8),dp(12),dp(8));background=GradientDrawable().apply { setColor(Color.rgb(45,61,68));cornerRadius=dp(8).toFloat() } }
  hint=PopupWindow(label,-2,-2,false).apply { isTouchable=false;isOutsideTouchable=false;isClippingEnabled=true;showAsDropDown(this@IconButton,0,dp(4)) }
 }
 private fun dismissHint() { hint?.dismiss();hint=null }
 private fun cancelPress(event:MotionEvent?=null) {
  cancelled=true;dismissHint();cancelLongPress();isPressed=false
  val cancel=if(event!=null)MotionEvent.obtain(event) else MotionEvent.obtain(0,0,MotionEvent.ACTION_CANCEL,0f,0f,0)
  cancel.action=MotionEvent.ACTION_CANCEL;super.onTouchEvent(cancel);cancel.recycle()
 }
 override fun onTouchEvent(event:MotionEvent):Boolean {
  if(event.actionMasked==MotionEvent.ACTION_DOWN) { cancelled=false;dismissHint() }
  if(event.actionMasked==MotionEvent.ACTION_MOVE && (event.x<0 || event.y<0 || event.x>=width || event.y>=height)) { cancelPress(event);return true }
  if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL)dismissHint()
  if(cancelled)return true
  return super.onTouchEvent(event)
 }
 override fun onFocusChanged(gainFocus:Boolean,direction:Int,previouslyFocusedRect:Rect?) { super.onFocusChanged(gainFocus,direction,previouslyFocusedRect);if(!gainFocus)cancelPress() }
 override fun onWindowFocusChanged(hasWindowFocus:Boolean) { super.onWindowFocusChanged(hasWindowFocus);if(!hasWindowFocus)cancelPress() }
 override fun onDetachedFromWindow() { dismissHint();cancelLongPress();super.onDetachedFromWindow() }
 private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
 private inner class Symbol(private val kind:Kind):Drawable() {
  private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=PlayerScreens.accent;style=Paint.Style.STROKE;strokeWidth=1.8f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND }
  init { setBounds(0,0,dp(26),dp(26)) }
  override fun draw(canvas:Canvas) {
   canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width()/24f,bounds.height()/24f)
   fun line(vararg points:Float) { val path=Path();path.moveTo(points[0],points[1]);for(i in 2 until points.size step 2)path.lineTo(points[i],points[i+1]);canvas.drawPath(path,paint) }
   when(kind) {
    Kind.LIBRARY -> { canvas.drawRoundRect(3f,4f,8f,20f,1f,1f,paint);canvas.drawRoundRect(10f,4f,15f,20f,1f,1f,paint);line(17f,5f,21f,19f);line(4f,8f,7f,8f,7f,8f) }
    Kind.PLAYLIST -> { line(3f,5f,16f,5f);line(3f,10f,12f,10f);line(3f,15f,10f,15f);line(17f,18f,17f,9f,21f,8f);canvas.drawOval(12f,17f,17f,21f,paint) }
    Kind.SORT -> { line(4f,6f,16f,6f);line(4f,12f,12f,12f);line(4f,18f,8f,18f);line(19f,5f,19f,19f);line(16f,16f,19f,19f,22f,16f) }
    Kind.SELECT -> { canvas.drawRoundRect(4f,4f,20f,20f,2f,2f,paint);line(8f,12f,11f,15f,16f,9f) }
    Kind.DONE -> line(4f,12f,10f,18f,20f,6f)
    Kind.MENU -> { paint.style=Paint.Style.FILL;for(x in listOf(5f,12f,19f))canvas.drawCircle(x,12f,1.6f,paint);paint.style=Paint.Style.STROKE }
   }
   canvas.restore()
  }
  override fun setAlpha(alpha:Int){paint.alpha=alpha};override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter}
  @Deprecated("Drawable opacity") override fun getOpacity()=PixelFormat.TRANSLUCENT
 }
}
