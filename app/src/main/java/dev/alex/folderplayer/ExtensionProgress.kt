package dev.alex.folderplayer

import java.util.Locale

/** Pure download progress calculation, independent from Android views. */
internal object ExtensionProgress {
 fun percent(received:Long,total:Long):Int =
  if(total<=0) 0 else ((received.coerceIn(0L,total)*100L)/total).toInt()

 fun scaled(received:Long,total:Long):Int =
  if(total<=0) 0 else ((received.coerceIn(0L,total)*1000L)/total).toInt()

 fun label(title:String,received:Long,total:Long):String =
  "Скачиваем $title\n${percent(received,total)}% · " +
  String.format(Locale.ROOT,"%.1f",received.coerceAtLeast(0L)/1_000_000.0) +
  " / " + String.format(Locale.ROOT,"%.1f",total.coerceAtLeast(0L)/1_000_000.0) + " МБ"
}
