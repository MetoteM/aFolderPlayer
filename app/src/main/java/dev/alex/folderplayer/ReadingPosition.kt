package dev.alex.folderplayer

import android.content.Context
import android.widget.ScrollView

internal object ReadingPosition {
 @JvmStatic fun attach(context:Context,view:ScrollView,key:String) {
  val prefs=context.getSharedPreferences("reading",0);val name=LyricsDocument.key(key);var restored=false
  view.post { view.scrollTo(0,prefs.getInt(name,0));restored=true }
  view.setOnScrollChangeListener { _:android.view.View, _:Int,y:Int, _:Int, _:Int -> if(restored)prefs.edit().putInt(name,y).apply() }
 }
}
