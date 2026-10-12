package dev.alex.folderplayer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/** Explicit foreground-only recognition. Recording never touches disk. */
class RecognitionActivity : Activity() {
 private val main=Handler(Looper.getMainLooper())
 private val worker=Executors.newSingleThreadExecutor()
 private lateinit var status:TextView
 private lateinit var start:Button
 private lateinit var stop:Button
 private lateinit var results:LinearLayout
 @Volatile private var cancelled=true
 @Volatile private var recorder:AudioRecord?=null
 @Volatile private var connection:HttpURLConnection?=null
 private var task:java.util.concurrent.Future<*>?=null
 private var lyricsCancel:LyricsSearch.Cancel?=null
 private var generation=0
 private var alive=true
 private val history by lazy { getSharedPreferences("recognition",0) }
 override fun onCreate(state:Bundle?) {
  super.onCreate(state)
  val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24) }
  panel.addView(TextView(this).apply { text="Определить песню";textSize=24f })
  panel.addView(TextView(this).apply { text="Поднеси телефон к музыке. Запись — до 30 секунд. Для определения нужен интернет." })
  status=TextView(this).apply { text="Готово к прослушиванию";textSize=18f };panel.addView(status)
  start=Button(this).apply { text="Слушать";setOnClickListener { begin() } };panel.addView(start)
  stop=Button(this).apply { text="Отмена";isEnabled=false;setOnClickListener { cancel();status.text="Прослушивание отменено" } };panel.addView(stop)
  results=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };panel.addView(results)
  panel.addView(Button(this).apply { text="История";setOnClickListener { showHistory() } })
  panel.addView(Button(this).apply { text="О распознавании";setOnClickListener {
   AlertDialog.Builder(this@RecognitionActivity).setTitle("Распознавание музыки")
    .setMessage("После нажатия «Слушать» микрофон записывает до 30 секунд. На сервер Shazam отправляется звуковой отпечаток; сама запись и геолокация не отправляются. Аудио не сохраняется. Используется неофициальный интерфейс сервиса: его доступность может меняться.")
    .setPositiveButton("Закрыть",null).show()
  } })
  setContentView(ScrollView(this).apply { addView(panel) })
 }
 private fun begin() {
  if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),1);return}
  cancel();val token=generation;cancelled=false;start.isEnabled=false;stop.isEnabled=true;results.removeAllViews()
  task=worker.submit {
   val outcome=runCatching {
    val minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
    check(minimum>0)
    val audio=AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(minimum,8192));recorder=audio
    val samples=ShortArray(30*16000)
    try {
     check(audio.state==AudioRecord.STATE_INITIALIZED);checkActive(token);audio.startRecording()
     check(audio.recordingState==AudioRecord.RECORDSTATE_RECORDING)
     var count=0;var last=-1
     while(count<samples.size){
      checkActive(token)
      val n=audio.read(samples,count,minOf(4096,samples.size-count),AudioRecord.READ_BLOCKING);check(n>0)
      count+=n;val seconds=count/16000
      if(seconds!=last){last=seconds;ui(token){status.text="Слушаем… $seconds / 30 с"}}
     }
    } finally { runCatching { audio.stop() };audio.release();if(recorder===audio)recorder=null }
    ui(token){status.text="Определяем песню…"}
    var found:JSONObject?=null
    for(offset in intArrayOf(0,12*16000,24*16000)){
     checkActive(token)
     val segment=samples.copyOfRange(offset,minOf(offset+12*16000,samples.size))
     val signature=MusicFingerprint.encode(segment)
     val data=identify(signature,segment.size,token)
     val track=data.optJSONObject("track")
     if(track!=null && track.optString("title").isNotBlank() && (data.optJSONArray("matches")?.length() ?: 0) > 0){found=track;break}
    }
    samples.fill(0);found
   }
   ui(token){
    cancelled=true;start.isEnabled=true;stop.isEnabled=false
    outcome.onSuccess { track ->
     if(track==null)status.text="Песню не удалось определить. Попробуй другой фрагмент."
     else { val title=track.optString("title").take(300);val artist=track.optString("subtitle").take(300);status.text="Песня найдена";save(title,artist);showResult(title,artist) }
    }.onFailure { status.text="Не удалось определить песню. Проверь интернет и попробуй ещё раз." }
   }
  }
 }
 private fun checkActive(token:Int){if(cancelled || token!=generation || Thread.currentThread().isInterrupted())throw java.util.concurrent.CancellationException()}
 private fun ui(token:Int,action:()->Unit){main.post { if(alive && token==generation)action() }}
 private fun identify(signature:String,samples:Int,token:Int):JSONObject {
  checkActive(token)
  val c=URL("https://amp.shazam.com/discovery/v5/en/US/android/-/tag/${UUID.randomUUID()}/${UUID.randomUUID()}").openConnection() as HttpURLConnection
  connection=c
  try {
   c.connectTimeout=10000;c.readTimeout=20000;c.instanceFollowRedirects=false;c.requestMethod="POST";c.doOutput=true
   c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("User-Agent","Shazam/14.0.0 (Android)");c.setRequestProperty("Content-Language","en_US")
   val now=System.currentTimeMillis()
   val body=JSONObject().put("signature",JSONObject().put("samplems",samples*1000/16000).put("timestamp",now).put("uri",signature)).put("timestamp",now).put("timezone","UTC").toString().toByteArray(Charsets.UTF_8)
   c.setFixedLengthStreamingMode(body.size);checkActive(token);c.outputStream.use { it.write(body) }
   check(c.responseCode==200)
   val bytes=java.io.ByteArrayOutputStream();c.inputStream.use { input -> val buffer=ByteArray(8192);while(true){checkActive(token);val n=input.read(buffer);if(n<0)break;check(bytes.size()+n<=2*1024*1024);bytes.write(buffer,0,n)} }
   checkActive(token);return JSONObject(bytes.toString("UTF-8"))
  } finally { c.disconnect();if(connection===c)connection=null }
 }
 private fun showResult(title:String,artist:String){
  results.removeAllViews();results.addView(TextView(this).apply { text="$title\n$artist";textSize=22f })
  results.addView(Button(this).apply { text="Найти текст и перевести";setOnClickListener { findLyrics(title,artist) } })
 }
 private fun findLyrics(title:String,artist:String){
  cancel();val token=generation;cancelled=false;start.isEnabled=false;stop.isEnabled=true;status.text="Ищем текст…"
  val signal=LyricsSearch.Cancel();lyricsCancel=signal
  task=worker.submit {
   val outcome=runCatching { LyricsSearch().search(title,artist,0L,signal) }
   ui(token){cancelled=true;start.isEnabled=true;stop.isEnabled=false
    outcome.onSuccess { matches ->
     val found=matches.filter { it.text.isNotBlank() }
     if(found.isEmpty())status.text="Текст не найден"
     else {status.text="Выбери текст";AlertDialog.Builder(this).setTitle("Найденные тексты").setItems(found.map { "${it.title} — ${it.artist}" }.toTypedArray()) { _,index ->
      startActivity(Intent(this,DocumentActivity::class.java).putExtra("source",LyricsDocument(found[index].text).plain))
     }.setNegativeButton("Закрыть",null).show()}
    }.onFailure { status.text="Не удалось найти текст. Попробуй позже." }
   }
  }
 }
 private fun save(title:String,artist:String){
  val old=runCatching { JSONArray(history.getString("items","[]")) }.getOrDefault(JSONArray());val next=JSONArray().put(JSONObject().put("title",title).put("artist",artist))
  for(i in 0 until old.length()){val item=old.optJSONObject(i)?:continue;if(item.optString("title")==title && item.optString("artist")==artist)continue;if(next.length()<50)next.put(item)}
  history.edit().putString("items",next.toString()).apply()
 }
 private fun showHistory(){
  val items=runCatching { JSONArray(history.getString("items","[]")) }.getOrDefault(JSONArray())
  if(items.length()==0){Toast.makeText(this,"История пока пуста",Toast.LENGTH_SHORT).show();return}
  AlertDialog.Builder(this).setTitle("Определённые песни").setItems(Array(items.length()){ i -> val item=items.getJSONObject(i);"${item.optString("title")} — ${item.optString("artist")}" }) { _,i ->
   cancel();val item=items.getJSONObject(i);status.text="Из истории";showResult(item.optString("title"),item.optString("artist"))
  }.setNegativeButton("Закрыть",null).show()
 }
 private fun cancel(){generation++;cancelled=true;lyricsCancel?.cancel();lyricsCancel=null;connection?.disconnect();runCatching { recorder?.stop() };task?.cancel(true);task=null;if(::start.isInitialized){start.isEnabled=true;stop.isEnabled=false}}
 override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,grants:IntArray){super.onRequestPermissionsResult(code,permissions,grants);if(code==1){if(grants.firstOrNull()==PackageManager.PERMISSION_GRANTED)begin() else status.text="Для прослушивания разреши доступ к микрофону"}}
 override fun onStop(){cancel();if(::status.isInitialized && status.text.startsWith("Слушаем"))status.text="Прослушивание остановлено";super.onStop()}
 override fun onDestroy(){alive=false;cancel();worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy()}
}
