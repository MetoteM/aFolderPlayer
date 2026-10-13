package dev.alex.folderplayer

import android.content.Context
import android.widget.ScrollView

internal object ReadingPosition {
 @JvmStatic @JvmOverloads fun attach(context:Context,view:ScrollView,key:String,restore:Boolean=true) {
  val prefs=context.getSharedPreferences("reading",0);val name=LyricsDocument.key(key);var restored=!restore
  if(restore)view.post { view.scrollTo(0,prefs.getInt(name,0));restored=true }
  view.setOnScrollChangeListener { _:android.view.View, _:Int,y:Int, _:Int, _:Int -> if(restored)prefs.edit().putInt(name,y).apply() }
 }
}
