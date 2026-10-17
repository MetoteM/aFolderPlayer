package dev.alex.afptorrent

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.*
import org.json.*

class TorrentActivity:Activity() {
    private var remote:ITorrentService?=null
    private var bound=false
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var rows:LinearLayout
    private lateinit var source:EditText
    private lateinit var destination:TextView
    private var snapshot=JSONObject()
    private var pendingSource:String?=null
    private var displayed=""
    private val labels=mapOf("metadata" to "Описание раздачи","ready" to "Выбор файлов","queued" to "В очереди","checking" to "Проверка файлов","downloading" to "Загрузка","exporting" to "Сохранение в папку","complete" to "Готово","paused" to "Пауза","cancelled" to "Отменено","error" to "Ошибка","network" to "Ожидание сети")
    private val callback=object:ITorrentCallback.Stub(){override fun changed(state:String){handler.post { if(!isFinishing){snapshot=JSONObject(state);renderTasks()} }}}
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,binder:IBinder){remote=ITorrentService.Stub.asInterface(binder);runCatching { remote!!.subscribe(callback) };pendingSource?.let { source.setText(it);pendingSource=null }}
        override fun onServiceDisconnected(name:ComponentName){remote=null;message("Расширение остановлено; открой экран заново")}
    }
    override fun onCreate(saved:Bundle?) {
        super.onCreate(saved)
        val scroll=ScrollView(this);val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,dp(12),0) }
        scroll.addView(root);setContentView(scroll)
        root.setOnApplyWindowInsetsListener { v,insets ->
            if(Build.VERSION.SDK_INT>=30){val i=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout());v.setPadding(dp(12)+i.left,dp(8)+i.top,dp(12)+i.right,dp(12)+i.bottom)}
            else v.setPadding(dp(12),insets.systemWindowInsetTop+dp(8),dp(12),insets.systemWindowInsetBottom+dp(12));insets
        };root.requestApplyInsets()
        root.addView(TextView(this).apply { text="AFP Torrent · прототип";textSize=24f })
        root.addView(TextView(this).apply { text="Скачивание музыки в выбранную папку. Отдача файлов запрещена, сидирования нет. Служебный обмен и видимость IP участникам BitTorrent сохраняются.";textSize=15f })
        source=EditText(this).apply { hint="Magnet-ссылка";inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI;setText(saved?.getString("source").orEmpty()) };root.addView(source)
        button(root,"Получить список файлов") { prepare(source.text.toString().trim()) }
        button(root,"Открыть .torrent") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),10) }
        destination=TextView(this).apply { text="Папка ещё не выбрана" };root.addView(destination)
        button(root,"Выбрать папку сохранения") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),11) }
        button(root,"Сеть, скорость и место") { settings() }
        button(root,"Добавить готовую папку в AFP") { importFolder() }
        button(root,"Лицензии") { AlertDialog.Builder(this).setTitle("libtorrent4j / libtorrent").setMessage(assets.open("LIBTORRENT_LICENSES.txt").bufferedReader().use { it.readText() }).setPositiveButton("Закрыть",null).show() }
        rows=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };root.addView(rows)
        pendingSource=externalSource(intent)
        bound=bindService(Intent(this,TorrentService::class.java),connection,BIND_AUTO_CREATE)
    }
    private fun externalSource(intent:Intent):String? = intent.data?.takeIf { it.scheme in setOf("magnet","content") }?.toString()
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);externalSource(intent)?.let { source.setText(it) }}
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun button(parent:LinearLayout,title:String,action:()->Unit){parent.addView(Button(this).apply { text=title;setOnClickListener { runCatching(action).onFailure { message(it.message ?: "Не удалось выполнить действие") } } })}
    private fun message(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    private fun foreground(op:String,args:JSONObject) {
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),12)
        startForegroundService(Intent(this,TorrentService::class.java).putExtra("operation",op).putExtra("arguments",args.toString()))
    }
    private fun send(op:String,args:JSONObject=JSONObject(),start:Boolean=false) {
        val service=remote ?: return message("Подключение к расширению ещё не готово")
        if(start){foreground(op,args);return}
        val response=JSONObject(service.command(op,args.toString()));response.optString("error").takeIf { it.isNotBlank() }?.let(::message)
    }
    private fun prepare(input:String){if(input.isBlank())return message("Вставь magnet-ссылку или выбери .torrent");send("prepare",JSONObject().put("source",input),true)}
    private fun renderTasks() {
        val settings=snapshot.optJSONObject("settings")?:JSONObject()
        destination.text="Папка: "+settings.optString("destination").ifBlank { "не выбрана" }
        val a=snapshot.optJSONArray("tasks")?:JSONArray()
        // Only rebuild task rows. Input, keyboard, settings and scroll position stay intact.
        val signature=a.toString();if(signature==displayed)return;displayed=signature;rows.removeAllViews()
        if(a.length()==0)rows.addView(TextView(this).apply { text="Задач пока нет" })
        for(i in 0 until a.length()) {
            val t=a.getJSONObject(i);val id=t.getString("id")
            val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(0,dp(18),0,dp(8)) };rows.addView(card)
            card.addView(TextView(this).apply { text="${t.optString("title")}\n${labels[t.optString("state")] ?: t.optString("state")} · ${size(t.optLong("done"))} / ${size(t.optLong("total"))}\n↓ ${size(t.optLong("down"))}/с · участников ${t.optInt("peers")}\n${t.optString("detail")}";textSize=17f })
            val progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100;progress=if(t.optLong("total")>0)(100*t.optLong("done")/t.optLong("total")).coerceIn(0,100).toInt() else 0 };card.addView(progress)
            val controls=LinearLayout(this);card.addView(controls)
            if(!t.optBoolean("wanted") && t.optInt("exportedCount")==0)button(controls,"Файлы") { selectFiles(id) }
            if(t.optString("state") !in setOf("complete","cancelled"))button(controls,if(t.optBoolean("wanted"))"Пауза" else "Загрузить") { send(if(t.optBoolean("wanted"))"pause" else "run",JSONObject().put("id",id),!t.optBoolean("wanted")) }
            button(controls,"⋯") { AlertDialog.Builder(this).setItems(arrayOf("Отменить загрузку","Убрать задачу и временные файлы")) { _,which->
                if(which==0)send("cancel",JSONObject().put("id",id))
                else AlertDialog.Builder(this).setMessage("Готовые файлы в выбранной папке останутся. Удалить задачу и временные данные?").setPositiveButton("Удалить") { _,_->send("remove",JSONObject().put("id",id)) }.setNegativeButton("Назад",null).show()
            }.show() }
        }
    }
    private fun selectFiles(id:String) {
        val task=JSONObject(remote?.detail(id) ?: "{}");val all=task.optJSONArray("files")?:return message("Сначала получи описание раздачи")
        val files=(0 until all.length()).map { all.getJSONObject(it) }.filter { it.optBoolean("audio") }
        if(files.isEmpty())return message("Поддерживаемых аудиофайлов пока нет")
        val old=task.optJSONArray("selected")?:JSONArray();val selected=(0 until old.length()).map { old.getInt(it) }.toSet()
        val flags=BooleanArray(files.size) { if(selected.isEmpty())true else files[it].getInt("index") in selected }
        AlertDialog.Builder(this).setTitle("Какие файлы скачать?")
            .setMultiChoiceItems(files.map { it.getString("path")+" · "+size(it.getLong("size")) }.toTypedArray(),flags) { _,index,checked->flags[index]=checked }
            .setPositiveButton("Выбрать") { _,_->send("select",JSONObject().put("id",id).put("files",JSONArray(files.filterIndexed { index,_->flags[index] }.map { it.getInt("index") }))) }.setNegativeButton("Отмена",null).show()
    }
    private fun settings() {
        val s=snapshot.optJSONObject("settings")?:JSONObject();val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),0) }
        val wifi=CheckBox(this).apply { text="Только Wi-Fi без оплаты за трафик";isChecked=s.optBoolean("wifiOnly",true) };box.addView(wifi)
        val roaming=CheckBox(this).apply { text="Разрешить роуминг";isChecked=s.optBoolean("roaming",false) };box.addView(roaming)
        fun number(label:String,value:Int)=EditText(this).apply { hint=label;inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText(value.toString());box.addView(TextView(this@TorrentActivity).apply { text=label });box.addView(this) }
        val down=number("Скачивание, КиБ/с (0 — без лимита)",s.optInt("downKB",0));val max=number("Максимум выбранных файлов на задачу, МиБ",s.optInt("maxMB",2048))
        box.addView(TextView(this).apply { text="Отдача аудиоданных отключена постоянно. Для загрузки нужно место и под временные данные, и под готовую копию. После проверки готовые файлы остаются в выбранной папке; временные удаляются через меню задачи." })
        AlertDialog.Builder(this).setTitle("Сеть и ограничения").setView(box).setPositiveButton("Сохранить") { _,_->send("settings",JSONObject().put("wifiOnly",wifi.isChecked).put("roaming",roaming.isChecked).put("downKB",down.text.toString().toIntOrNull()?:0).put("maxMB",max.text.toString().toIntOrNull()?:2048)) }.setNegativeButton("Отмена",null).show()
    }
    private fun importFolder() {
        val all=snapshot.optJSONArray("tasks")?:JSONArray();val trees=(0 until all.length()).map { all.getJSONObject(it) }.filter { it.optInt("exportedCount")>0 }.map { it.getString("tree") }.distinct()
        if(trees.isEmpty())return message("Пока нет сохранённых файлов")
        fun done(tree:String){
            val uri=Uri.parse(tree);val result=Intent().setData(uri).setClipData(ClipData.newRawUri("Папка музыки",uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if(callingActivity!=null){setResult(RESULT_OK,result);finish()}
            else { val pkg="dev.alex.folderplayer.torrentlab";if(packageManager.checkSignatures(packageName,pkg)!=PackageManager.SIGNATURE_MATCH)return message("Установи экспериментальный AFP из комплекта")
                startActivity(result.setComponent(ComponentName(pkg,"dev.alex.folderplayer.MainActivity")).setAction("dev.alex.afp.IMPORT_TORRENT_FOLDER")) }
        }
        if(trees.size==1)done(trees[0]) else AlertDialog.Builder(this).setTitle("Какую папку добавить?").setItems(trees.toTypedArray()) { _,i->done(trees[i]) }.show()
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(resultCode!=RESULT_OK)return
        data?.data?.let { uri->runCatching {
            if(requestCode==10)prepare(uri.toString())
            if(requestCode==11){contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);send("destination",JSONObject().put("tree",uri.toString()))}
        }.onFailure { message("Не удалось сохранить доступ к выбранной папке") } }
    }
    override fun onSaveInstanceState(out:Bundle){out.putString("source",source.text.toString());super.onSaveInstanceState(out)}
    override fun onDestroy(){runCatching { remote?.unsubscribe(callback) };if(bound)unbindService(connection);handler.removeCallbacksAndMessages(null);super.onDestroy()}
    private fun size(bytes:Long)=if(bytes<1024*1024)"${bytes/1024} КиБ" else "${bytes/(1024*1024)} МиБ"
}
