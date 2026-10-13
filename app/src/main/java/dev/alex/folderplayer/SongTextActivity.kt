package dev.alex.folderplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import java.util.concurrent.Executors

class SongTextActivity:Activity(){
 private var title="";private var artist="";private var source="";private var cacheTitle="";private var cacheArtist=""
 private val worker=Executors.newSingleThreadExecutor()
 private val main=Handler(Looper.getMainLooper())
 private var alive=true
 private var foreground=false
 private lateinit var status:TextView
 private lateinit var translate:Button
 override fun onCreate(state:Bundle?){
  super.onCreate(state)
  title=intent.getStringExtra("songTitle").orEmpty();artist=intent.getStringExtra("artist").orEmpty()
  cacheTitle=intent.getStringExtra("cacheTitle") ?: title;cacheArtist=intent.getStringExtra("cacheArtist") ?: artist
  val cached=RecognizedTextStore(this).readEntry(cacheTitle,cacheArtist)
  source=intent.getStringExtra("source") ?: cached?.text.orEmpty()
  if(!intent.hasExtra("source") && cached!=null){title=cached.title;artist=cached.artist}
  val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24) }
  if(android.os.Build.VERSION.SDK_INT>=30)box.setOnApplyWindowInsetsListener { view,insets ->
   val bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());view.setPadding(24,bars.top+16,24,bars.bottom+16);insets
  }
  box.addView(TextView(this).apply { text=title.ifBlank { "Текст песни" };textSize=24f })
  if(artist.isNotBlank())box.addView(TextView(this).apply { text=artist;textSize=18f })
  status=TextView(this);box.addView(status)
  val actions=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };box.addView(actions)
  translate=Button(this).apply { text="Перевести";setOnClickListener { chooseTranslation() };visibility=if(russian(source))android.view.View.GONE else android.view.View.VISIBLE };actions.addView(translate)
  actions.addView(Button(this).apply { text="Копировать текст";setOnClickListener { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Слова песни",source));Toast.makeText(this@SongTextActivity,"Текст скопирован",Toast.LENGTH_SHORT).show() } })
  if(russian(source))actions.addView(Button(this).apply { text="Дополнительно";setOnClickListener {
   AlertDialog.Builder(this@SongTextActivity).setItems(arrayOf("Перевод")){_,_->AlertDialog.Builder(this@SongTextActivity).setMessage("Текст уже на русском. Доступные языковые пакеты переводят на русский; обратный перевод пока не поддерживается.").setPositiveButton("Закрыть",null).show()}.show()
  } })
  box.addView(TextView(this).apply { text=source.ifBlank { "Сохранённый текст не найден" };textSize=19f;setTextIsSelectable(true);setPadding(0,20,0,20) })
  box.addView(Button(this).apply { text="Назад";setOnClickListener { finish() } })
  setContentView(ScrollView(this).apply { addView(box) })
  if(source.isNotBlank())worker.execute {
   val result=runCatching { RecognizedTextStore(this).save(cacheTitle,cacheArtist,source,title,artist) }
   main.post { if(alive)status.text=if(result.isSuccess)"Сохранено для чтения без интернета" else "Текст открыт. Сохранить для офлайн-чтения не удалось." }
  }
 }
 private fun chooseTranslation(){
  translate.isEnabled=false;status.text="Проверяем установленные языки…"
  worker.execute {
   val outcome=runCatching { TranslationClient(this).availableModels().toList() }
   main.post { if(alive){translate.isEnabled=true
    if(!foreground){status.text="Перевод не запущен. Можно повторить.";return@post}
    outcome.onSuccess { models ->
     val expected=sourceModel(source)
     val preferred=models.firstOrNull { it.first==expected }
      ?: if(expected=="opus-en-ru-v2")models.firstOrNull { it.first==TranslationClient.MODEL } else null
     if(preferred==null)missingLanguage()
     if(models.isNotEmpty()) {
      if(preferred!=null && models.size==1)launchTranslation(preferred)
      else chooseInstalledLanguage(models,preferred)
     }
    }.onFailure { missingLanguage() }
   } }
  }
 }
 private fun chooseInstalledLanguage(models:List<Pair<String,String>>,preferred:Pair<String,String>?) {
  val ordered=if(preferred==null)models else listOf(preferred)+models.filterNot { it.first==preferred.first }
  status.text=if(preferred==null)"Для текста нет подходящего языкового пакета. Можно вручную выбрать установленный язык." else "Проверь определённый язык оригинала"
  AlertDialog.Builder(this).setTitle("Язык оригинала")
   .setItems(ordered.map { it.second+(if(it.first==preferred?.first)" · предложен" else "") }.toTypedArray()) { _,index ->
    launchTranslation(ordered[index])
   }.setNegativeButton("Отмена",null).show()
 }
 private fun missingLanguage(){status.text="Для перевода нужен пакет «${TranslationClient.MODELS[sourceModel(source)]}». Установи его и переводчик в разделе «Расширения». Текст можно читать без них."}
 private fun launchTranslation(model:Pair<String,String>){
  getSharedPreferences("extensions",0).edit().putBoolean("enabled",true).apply()
  status.text="Текст сохранён для чтения без интернета"
  startActivity(Intent(this,DocumentActivity::class.java).putExtra("source",source).putExtra("translateNow",true).putExtra("translationModel",model.first))
 }
 override fun onResume(){super.onResume();foreground=true}
 override fun onStop(){foreground=false;super.onStop()}
 override fun onDestroy(){alive=false;worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy()}
 companion object {
  internal fun russian(value:String):Boolean { val letters=value.count { it.isLetter() };return letters>0 && value.count { it in 'А'..'я' || it=='ё' || it=='Ё' }>.6*letters }
  internal fun sourceModel(value:String):String {
   if(value.any { it in '\u4e00'..'\u9fff' })return "opus-zh-ru-v1"
   val words=value.lowercase().split(Regex("[^\\p{L}]+"));val german=setOf("ich","du","nicht","und","mein","dein","mich","dich","wir","der","die","das","ist","ein","eine","mit","auf","für","hast","bist","habe","wenn","liebe","alle","zwei","drei","vier","fünf")
   val unique=words.toSet()
   val strong=setOf("sonne","mutter","herz","augen","träume","mädchen","schwarz","feuer","himmel","liebst","liebt","liebe")
   return if(value.any { it in "äöüÄÖÜß" } || unique.count { it in german }>=3 ||
    ("du" in unique && "hast" in unique) || unique.any { it in strong })"opus-de-ru-v1" else "opus-en-ru-v2"
  }
 }
}
