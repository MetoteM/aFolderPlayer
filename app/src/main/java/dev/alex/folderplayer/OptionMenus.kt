package dev.alex.folderplayer

import android.app.Activity
import android.app.AlertDialog
import android.widget.ArrayAdapter

/** Selection opens a child without discarding its parent menu. */
internal fun optionMenu(host: Activity, title: String, choices: List<String>, action: (Int) -> Unit): AlertDialog {
 val dialog=AlertDialog.Builder(host).setTitle(title).setItems(choices.toTypedArray(),null)
  .setNegativeButton("Закрыть",null).create()
 dialog.show()
 dialog.listView.setOnItemClickListener { _,_,index,_ ->
  action(index)
  dialog.listView.adapter=ArrayAdapter(host,android.R.layout.simple_list_item_1,choices.toTypedArray())
 }
 return dialog
}
