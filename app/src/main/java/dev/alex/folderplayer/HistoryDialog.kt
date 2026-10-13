package dev.alex.folderplayer

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.widget.*

/** Stable IDs keep filtered lists and undo attached to the selected record. */
internal object HistoryDialog {
 data class Entry(val id:String,val label:String,val search:String=label)
 fun show(host:Activity,title:String,entries:()->List<Entry>,open:(String)->Unit,remove:(String)->Boolean,restore:(String)->Boolean) {
  val panel=LinearLayout(host).apply { orientation=LinearLayout.VERTICAL;setPadding(20,8,20,8) }
  val query=EditText(host).apply { hint="Поиск в истории";isSingleLine=true };val list=ListView(host);panel.addView(query);panel.addView(list)
  var shown=emptyList<Entry>()
  fun render() { val q=query.text.toString().trim();shown=entries().filter { it.search.contains(q,true) };list.adapter=ArrayAdapter(host,android.R.layout.simple_list_item_1,shown.map { it.label }); }
  query.addTextChangedListener(object:TextWatcher { override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){render()};override fun afterTextChanged(s:Editable?){} })
  val dialog=AlertDialog.Builder(host).setTitle(title).setView(panel).setNegativeButton("Закрыть",null).create()
  list.setOnItemClickListener { _,_,i,_ -> val entry=shown[i]
   AlertDialog.Builder(host).setTitle(entry.label).setItems(arrayOf("Открыть","Удалить из истории")) { _,action ->
    if(action==0) { dialog.dismiss();open(entry.id) } else if(remove(entry.id)) {
     render();AlertDialog.Builder(host).setMessage("Запись удалена из истории.").setPositiveButton("Вернуть") { _,_ -> if(restore(entry.id))render() else Toast.makeText(host,"Не удалось вернуть запись",Toast.LENGTH_SHORT).show() }.setNegativeButton("Закрыть",null).show()
    }else Toast.makeText(host,"Не удалось удалить запись",Toast.LENGTH_SHORT).show()
   }.setNegativeButton("Отмена",null).show()
  }
  render();dialog.show()
 }
}
