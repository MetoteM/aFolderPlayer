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
  val reader=ScrollView(this).apply { addView(box) };setContentView(reader);ReadingPosition.attach(this,reader,"original:"+cacheTitle+"\n"+cacheArtist+"\n"+source)
  if(source.isNotBlank() && !intent.hasExtra("localUri"))worker.execute {
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
     val key=intent.getStringExtra("localUri") ?: "recognized:"+cacheTitle+"\n"+cacheArtist
     when(val decision=SongTranslation.decide(source,models.map { it.first }.toSet(),SongTranslationChoice(this).read(key,source))) {
      is SongTranslation.Decision.Ready -> launchTranslation(decision.model to TranslationClient.MODELS.getValue(decision.model))
      is SongTranslation.Decision.Missing -> {
       status.text=TranslationClient.MissingSongModel(decision.model).message
       if(models.isNotEmpty())AlertDialog.Builder(this).setMessage(status.text).setNeutralButton("Выбрать вручную") { _,_ -> chooseInstalledLanguage(models,null) }.setPositiveButton("Закрыть",null).show()
      }
      SongTranslation.Decision.Choose -> if(models.isEmpty())status.text="Нет установленных языковых пакетов. Открой расширения." else chooseInstalledLanguage(models,null)
      SongTranslation.Decision.Russian -> status.text="Оригинал уже на русском"
     }
    }.onFailure { status.text=it.message ?: "Не удалось проверить языковые пакеты" }
   } }
  }
 }
 private fun chooseInstalledLanguage(models:List<Pair<String,String>>,preferred:Pair<String,String>?) {
  val ordered=if(preferred==null)models else listOf(preferred)+models.filterNot { it.first==preferred.first }
  status.text="Выбери язык оригинала только для этого текста"
  AlertDialog.Builder(this).setTitle("Язык оригинала")
   .setItems(ordered.map { it.second+(if(it.first==preferred?.first)" · предложен" else "") }.toTypedArray()) { _,index ->
    val key=intent.getStringExtra("localUri") ?: "recognized:"+cacheTitle+"\n"+cacheArtist
    runCatching { SongTranslationChoice(this).save(key,source,ordered[index].first) }.onSuccess { launchTranslation(ordered[index]) }.onFailure { status.text="Не удалось сохранить выбор языка" }
   }.setNegativeButton("Отмена",null).show()
 }
 private fun launchTranslation(model:Pair<String,String>){
  getSharedPreferences("extensions",0).edit().putBoolean("enabled",true).apply()
  status.text="Текст сохранён для чтения без интернета"
  startActivity(Intent(this,DocumentActivity::class.java).putExtra("source",source).putExtra("translateNow",true).putExtra("translationModel",model.first).putExtra("track",intent.getStringExtra("localUri")))
 }
 override fun onResume(){super.onResume();foreground=true}
 override fun onStop(){foreground=false;super.onStop()}
 override fun onDestroy(){alive=false;worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy()}
 companion object {
  internal fun russian(value:String):Boolean = SongLanguage.detect(value)==SongLanguage.RUSSIAN
  internal fun sourceModel(value:String):String = SongLanguage.detect(value)?.takeUnless { it==SongLanguage.RUSSIAN } ?: "opus-en-ru-v2"
 }
}
