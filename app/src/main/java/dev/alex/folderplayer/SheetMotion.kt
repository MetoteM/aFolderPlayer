package dev.alex.folderplayer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Owns the overlay lifecycle, including cancellation and interrupted transitions. */
internal class SheetMotion(private val view:View) {
 var running:ObjectAnimator?=null
  private set
 private var generation=0
 fun show(open:Boolean,height:Int,animate:Boolean,fromBelow:Boolean=true,startAt:Float?=null) {
  cancel()
  val transition=generation
  if(!animate || height<=0 || !android.animation.ValueAnimator.areAnimatorsEnabled()) { settle(open);return }
  if(open) { view.visibility=View.VISIBLE;view.translationY=if(fromBelow)startAt ?: height.toFloat() else 24*view.resources.displayMetrics.density;view.alpha=if(fromBelow)1f else 0f }
  val animation=ObjectAnimator.ofPropertyValuesHolder(view,PropertyValuesHolder.ofFloat(View.TRANSLATION_Y,view.translationY,if(open)0f else height.toFloat()),PropertyValuesHolder.ofFloat(View.ALPHA,view.alpha,1f))
  running=animation;animation.duration=if(open) { if(fromBelow)600 else 300 } else 300;animation.interpolator=DecelerateInterpolator()
  animation.addListener(object:AnimatorListenerAdapter() {
   override fun onAnimationEnd(animator:Animator) {
    if(transition!=generation)return
    running=null;settle(open)
   }
  });animation.start()
 }
 fun preview(height:Int,travel:Float) {
  cancel();view.visibility=View.VISIBLE;view.alpha=1f;view.translationY=(height-travel*1.4f).coerceIn(0f,height.toFloat())
 }
 fun leavePage(after:()->Unit) {
  cancel();val transition=generation
  if(!android.animation.ValueAnimator.areAnimatorsEnabled()) { after();return }
  val target=view.translationY+64*view.resources.displayMetrics.density
  val animation=ObjectAnimator.ofPropertyValuesHolder(view,PropertyValuesHolder.ofFloat(View.TRANSLATION_Y,view.translationY,target),PropertyValuesHolder.ofFloat(View.ALPHA,view.alpha,0f))
  running=animation;animation.duration=180;animation.interpolator=DecelerateInterpolator()
  animation.addListener(object:AnimatorListenerAdapter() {
   override fun onAnimationEnd(animator:Animator) { if(transition==generation) { running=null;after() } }
  });animation.start()
 }
 fun cancel() { ++generation;running?.removeAllListeners();running?.cancel();running=null }
 private fun settle(open:Boolean) { view.visibility=if(open)View.VISIBLE else View.GONE;view.translationY=0f;view.alpha=1f }
}
