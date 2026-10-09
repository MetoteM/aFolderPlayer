package dev.alex.folderplayer

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.widget.*
import java.util.concurrent.Executors

/** All requests originate from a visible search button; saved years are independent of audio tags. */
internal class AlbumYearDialogs(private val activity:Activity,private val changed:()->Unit,private val client:MusicBrainzSearch=MusicBrainzSearch(SearchConnections(activity))) {
 private val store=AlbumYears(activity)
 private val prefs=activity.getSharedPreferences("album-network",Activity.MODE_PRIVATE)
 private val executor=Executors.newSingleThreadExecutor()
 private val handler=Handler(Looper.getMainLooper())
 private var alive=true
 private var cancel:LyricsSearch.Cancel?=null
 private var task:java.util.concurrent.Future<*>?=null
 private var active:AlertDialog?=null
 init { client.restoreCooldown(prefs.getLong("retryUntil",0)) }
 private fun text(value:String)=TextView(activity).apply { text=value;textSize=16f;setPadding(16,10,16,10) }
 private fun panel()=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(16,8,16,8) }
 private fun fresh(group:AlbumGroup)=albumGroups(Library(activity).tracks()).find { it.key==group.key } ?: group
 fun show(tracks:List<Track>,missingOnly:Boolean=false) {
  val groups=albumGroups(tracks).filter { !missingOnly || it.year==null }
  if(groups.isEmpty()) { Toast.makeText(activity,if(missingOnly) "У всех альбомов уже есть год" else "В списке нет альбомов",Toast.LENGTH_LONG).show();return }
  val duplicate=groups.groupingBy { it.title to it.artist }.eachCount()
  val labels=groups.map { "${it.title} — ${it.artist.ifBlank { "исполнитель неизвестен" }}\n${it.year ?: "год неизвестен"} · ${it.tracks.size} песен"+if((duplicate[it.title to it.artist] ?: 0)>1) "\n${it.track.folder} · источник ${groups.map { g -> g.track.rootUri }.distinct().indexOf(it.track.rootUri)+1}" else "" }
  AlertDialog.Builder(activity).setTitle(if(missingOnly) "Найти недостающие годы" else "Годы альбомов")
   .setItems(labels.toTypedArray()) { _,i -> details(groups[i]) }.setNegativeButton("Закрыть",null).show()
 }
 private fun details(original:AlbumGroup) {
  val group=fresh(original);val saved=store.get(group.key)
  val description=buildString {
   append("${group.title}\n${group.artist}\nГод для сортировки: ${group.year ?: "неизвестен"}\n${group.tracks.size} песен · ${group.track.folder}")
   if(saved!=null) {
    append("\n\nСохранено: ${saved.year}\nИсточник: ${saved.source}")
    if(saved.mbid.isNotEmpty())append("\n${saved.title} — ${saved.artist}\nДата первого выпуска: ${saved.date}\nhttps://musicbrainz.eu/release-group/${saved.mbid}")
    if(!saved.overridesTags)append("\nИспользуется при отсутствии года в тегах и папке.")
   } else append("\n\nГод из тегов или явно указанной даты папки. Сохранённого дополнения нет.")
  }
  val p=panel();p.addView(text(description).apply { setTextIsSelectable(true) })
  p.addView(Button(activity).apply { text="Исправить год вручную";isAllCaps=false;setOnClickListener { manual(group) } })
  if(saved!=null)p.addView(Button(activity).apply { text="Сбросить сохранённый год";isAllCaps=false;setOnClickListener {
   AlertDialog.Builder(activity).setTitle("Сбросить год?").setMessage("Вернётся год из тегов или папки. Файлы не изменятся.")
    .setPositiveButton("Сбросить") { _,_ -> runCatching { store.remove(group.key) }.onSuccess { changed();active?.dismiss();details(group) }.onFailure { toast(it) } }.setNegativeButton("Отмена",null).show()
  } })
  val dialog=AlertDialog.Builder(activity).setTitle("Год альбома").setView(ScrollView(activity).apply { addView(p) })
   .setPositiveButton("Найти в интернете") { _,_ -> search(group) }.setNegativeButton("Закрыть",null).create();active=dialog;dialog.show()
 }
 private fun manual(group:AlbumGroup) {
  val input=EditText(activity).apply { hint="Год — четыре цифры";inputType=InputType.TYPE_CLASS_NUMBER;filters=arrayOf(InputFilter.LengthFilter(4));setText(fresh(group).year?.toString().orEmpty()) }
  val dialog=AlertDialog.Builder(activity).setTitle("Год для «${group.title}»").setView(input).setMessage("Применится ко всему альбому внутри плеера, включая треки с годом в тегах. Музыкальные файлы не меняются.")
   .setPositiveButton("Сохранить",null).setNegativeButton("Отмена",null).create()
  dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
   val year=input.text.toString().toIntOrNull();if(year==null || year !in 1000..9999)input.error="Введи год от 1000 до 9999" else {
    runCatching { store.save(group.key,AlbumYear(year,"Вручную",overridesTags=true)) }.onSuccess { changed();dialog.dismiss();active?.dismiss();details(group) }.onFailure { input.error=it.message }
   }
  } };dialog.show()
 }
 private fun search(group:AlbumGroup) {
  val p=panel()
  val album=EditText(activity).apply { hint="Альбом";setText(SearchSupport.albumTitle(group.title));isSingleLine=true;filters=arrayOf(InputFilter.LengthFilter(200)) };p.addView(album)
  val artist=EditText(activity).apply { hint="Исполнитель альбома";setText(group.artist);isSingleLine=true;filters=arrayOf(InputFilter.LengthFilter(200)) };p.addView(artist)
  p.addView(text("MusicBrainz · поиск по названию и исполнителю"))
  val status=text("");p.addView(status);var busy=false
  val dialog=AlertDialog.Builder(activity).setTitle("Найти год альбома").setView(ScrollView(activity).apply { addView(p) })
   .setPositiveButton("Поиск",null).setNegativeButton("Закрыть",null).create();active=dialog
  dialog.setOnDismissListener { cancel?.cancel();task?.cancel(true);cancel=null;task=null }
  dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
   if(busy)return@setOnClickListener
   val name=album.text.toString().trim();val performer=artist.text.toString().trim()
   if(name.isBlank()) { album.error="Введи название альбома";return@setOnClickListener }
   val token=LyricsSearch.Cancel();cancel=token;busy=true;status.text="Ищем альбом…";dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
   task=executor.submit {
    val result=runCatching { client.search(name,performer,token) };prefs.edit().putLong("retryUntil",client.cooldown()).apply()
    handler.post { if(alive && dialog.isShowing) {
     busy=false;dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true
     result.onFailure { status.text=SearchSupport.message(it,"MusicBrainz") }.onSuccess { found ->
      if(found.isEmpty())status.text="Альбом не найден. Поправь название, убери LP/Remaster или уточни исполнителя. Год можно задать вручную."
      else {
       dialog.dismiss()
       results(group,found)
      }
     }
    } }
   }
  } };dialog.show()
 }
 private fun results(group:AlbumGroup,found:List<MusicBrainzSearch.Result>) {
AlertDialog.Builder(activity).setTitle("Выбери альбом")
        .setItems(found.map { "${it.title} — ${it.artist}\n${it.date.ifBlank { "дата неизвестна" }} · ${it.type}\n${it.comment}" }.toTypedArray()) { _,i -> preview(group,found[i]) }
        .setNeutralButton("Другой запрос") { _,_ -> search(group) }.setNegativeButton("Закрыть",null).show()
 }
 private fun preview(group:AlbumGroup,result:MusicBrainzSearch.Result) {
  val p=panel();p.addView(text("${result.title} — ${result.artist}\n${result.type}\n${result.comment}\n\nДата первого выпуска: ${result.date.ifBlank { "неизвестна" }}\nИсточник: MusicBrainz\n${result.sourceUrl()}\n\nСохранить для «${group.title}», ${group.tracks.size} песен. Год будет дополнением при отсутствии даты в тегах или папке. Файлы не изменятся.").apply { setTextIsSelectable(true) })
  val dialog=AlertDialog.Builder(activity).setTitle("Проверить год альбома").setView(ScrollView(activity).apply { addView(p) }).setPositiveButton("Сохранить",null).setNegativeButton("Отмена",null).create();active=dialog
  dialog.setOnShowListener {
   dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=result.year!=null
   dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
    val year=result.year ?: return@setOnClickListener
    runCatching { store.save(group.key,AlbumYear(year,"MusicBrainz",result.id,result.title,result.artist,result.date)) }.onSuccess { changed();dialog.dismiss();Toast.makeText(activity,"Год $year сохранён для альбома",Toast.LENGTH_LONG).show() }.onFailure { toast(it) }
   }
  };dialog.show()
 }
 private fun toast(error:Throwable) { Toast.makeText(activity,error.message ?: "Не удалось сохранить год",Toast.LENGTH_LONG).show() }
 fun close() { alive=false;cancel?.cancel();task?.cancel(true);active?.dismiss();executor.shutdownNow();handler.removeCallbacksAndMessages(null) }
}
