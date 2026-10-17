package dev.alex.afptorrent

import android.app.*
import android.content.*
import android.net.*
import android.os.*
import android.util.AtomicFile
import org.json.*
import org.libtorrent4j.*
import org.libtorrent4j.alerts.*
import java.io.File
import java.util.UUID
import java.util.concurrent.*

class TorrentService:Service() {
    private val worker=Executors.newSingleThreadScheduledExecutor()
    private val metadataWorker=Executors.newSingleThreadExecutor()
    private val exporter=Executors.newSingleThreadExecutor()
    private val callbacks=RemoteCallbackList<ITorrentCallback>()
    private val tasks=linkedMapOf<String,TorrentTask>()
    private val details=ConcurrentHashMap<String,String>()
    @Volatile private var destroyed=false
    private val infos=hashMapOf<String,TorrentInfo>()
    private val cancelled=ConcurrentHashMap.newKeySet<String>()
    private val flushed=ConcurrentHashMap.newKeySet<String>()
    private val flushStarted=hashMapOf<String,Long>()
    private var engine:TorrentEngine?=null
    private var metadataFuture:Future<*>?=null
    private var metadataId:String?=null
    private var metadataToken=""
    private var metadataRun=""
    private var boundNetwork:Network?=null
    @Volatile private var metadataSession:SessionManager?=null
    @Volatile private var view="{\"tasks\":[],\"settings\":{}}"
    @Volatile private var foreground=false
    private var lastError=""
    private var lastSaved=""
    private val pendingStarts=java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var activeView=false
    private val main=Handler(Looper.getMainLooper())
    private var exporting:String?=null
    private var ticks=0
    private lateinit var storage:File
    private lateinit var state:AtomicFile
    private lateinit var prefs:android.content.SharedPreferences
    private var wake:PowerManager.WakeLock?=null
    private val binder=object:ITorrentService.Stub() {
        override fun apiVersion()=1
        override fun capabilities()="{\"magnet\":true,\"torrent\":true,\"selection\":true,\"completedOnly\":true,\"search\":false,\"upload\":false}"
        override fun snapshot()=view
        override fun detail(id:String)=details[id] ?: "{}"
        override fun command(operation:String,arguments:String):String {
            if(arguments.length>40000)return "{\"error\":\"Слишком большой запрос\"}"
            val input=runCatching { JSONObject(arguments) }.getOrElse { return "{\"error\":\"Некорректный запрос\"}" }
            val id=input.optString("id")
            if(operation in setOf("pause","cancel"))cancelled.add(id)
            worker.execute { lastError="";runCatching { execute(operation,input) }.onFailure { e ->
                if(tasks[id]==null)lastError=e.message?.takeIf { !it.contains("://") }?.take(180) ?: "Не удалось выполнить действие"
                tasks[id]?.apply { wanted=false;state="error";detail=e.message?.takeIf { !it.contains("://") }?.take(180) ?: "Не удалось выполнить действие" }
            };publish(true) }
            return "{\"accepted\":true}"
        }
        override fun subscribe(callback:ITorrentCallback){callbacks.register(callback);runCatching { callback.changed(view) }}
        override fun unsubscribe(callback:ITorrentCallback){callbacks.unregister(callback)}
    }
    override fun onBind(intent:Intent)=binder
    override fun onCreate() {
        super.onCreate();prefs=getSharedPreferences("torrent",0)
        storage=File(getExternalFilesDir(null) ?: filesDir,"staging").apply { mkdirs() }
        state=AtomicFile(File(filesDir,"tasks.json"))
        runCatching { val a=JSONArray(state.readFully().toString(Charsets.UTF_8));for(i in 0 until a.length()){val t=TorrentTask.read(a.getJSONObject(i));tasks[t.id]=t} }
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("torrent","Загрузки музыки",NotificationManager.IMPORTANCE_LOW))
        wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"AFP:Torrent").apply { setReferenceCounted(false) }
        worker.scheduleWithFixedDelay({runCatching { tick() }.onFailure { tasks.values.filter { it.wanted }.forEach { t->t.wanted=false;t.state="error";t.detail="Загрузка остановлена из-за ошибки движка" };runCatching { stopEngine() };publish(true) }},0,1,TimeUnit.SECONDS)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="PAUSE_ALL"){
            worker.execute { pauseAll("Приостановлено пользователем");publish(true) };return START_NOT_STICKY
        }
        pendingStarts.incrementAndGet();foreground=true;startForeground(701,notification("Подготовка загрузки"))
        intent?.getStringExtra("operation")?.let { op->val arguments=intent.getStringExtra("arguments").orEmpty();if(arguments.length<=40000)binder.command(op,arguments) }
        worker.execute { pendingStarts.decrementAndGet() }
        return START_NOT_STICKY
    }
    private fun notification(text:String):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,TorrentActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause=PendingIntent.getService(this,1,Intent(this,TorrentService::class.java).setAction("PAUSE_ALL"),PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,"torrent").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("AFP Torrent")
            .setContentText(text).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"Пауза",pause).build()).build()
    }
    private fun allowed():Boolean {
        val cm=getSystemService(ConnectivityManager::class.java);val cap=cm.getNetworkCapabilities(cm.activeNetwork)
        val connected=cap?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
        val wifi=cap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true || cap?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)==true
        val roaming=if(Build.VERSION.SDK_INT>=28)cap!=null && !cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) else cm.activeNetworkInfo?.isRoaming==true
        return TorrentRules.allowed(connected,wifi,cm.isActiveNetworkMetered,roaming,prefs.getBoolean("wifiOnly",true),prefs.getBoolean("roaming",false))
    }
    private fun settings()=JSONObject().put("wifiOnly",prefs.getBoolean("wifiOnly",true)).put("roaming",prefs.getBoolean("roaming",false))
        .put("downKB",prefs.getInt("downKB",0)).put("maxMB",prefs.getInt("maxMB",2048))
        .put("destination",prefs.getString("destination",""))
    private fun execute(op:String,a:JSONObject) {
        val id=a.optString("id");val task=tasks[id]
        when(op){
            "settings"->{
                prefs.edit().putBoolean("wifiOnly",a.optBoolean("wifiOnly",true)).putBoolean("roaming",a.optBoolean("roaming",false))
                    .putInt("downKB",a.optInt("downKB",0).coerceIn(0,100000))
                    .putInt("maxMB",a.optInt("maxMB",2048).coerceIn(64,32768))
                    .apply()
            }
            "destination"->{val uri=Uri.parse(a.getString("tree"));require(uri.scheme=="content");require(contentResolver.persistedUriPermissions.any { it.uri==uri && it.isWritePermission });prefs.edit().putString("destination",uri.toString()).apply()}
            "prepare"->{
                require(tasks.size<20) { "Не больше 20 задач; удали завершённые из списка" }
                require(foreground) { "Начни загрузку из окна AFP Torrent" }
                val input=a.getString("source");require(input.length<=8000)
                val t=TorrentTask(UUID.randomUUID().toString());tasks[t.id]=t;jobDir(t.id).mkdirs()
                try {
                    if(input.startsWith("magnet:?",true)){
                        require(Uri.parse(input).getQueryParameters("xt").any { it.startsWith("urn:btih:",true) || it.startsWith("urn:btmh:",true) }) { "Некорректная magnet-ссылка" }
                        File(jobDir(t.id),"magnet").writeText(input);t.wanted=true;t.detail="Ожидание метаданных и доступных участников"
                    } else {
                        val uri=Uri.parse(input);require(uri.scheme=="content") { "Выбери .torrent через системное окно" }
                        val bytes=contentResolver.openInputStream(uri)!!.use { input ->
                            val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(16384)
                            while(true){val n=input.read(buffer);if(n<0)break;require(bytes.size()+n<=TorrentRules.MAX_METADATA) { "Слишком большой .torrent" };bytes.write(buffer,0,n)}
                            bytes.toByteArray() };require(bytes.size<=TorrentRules.MAX_METADATA) { "Слишком большой .torrent" }
                        acceptMetadata(t,bytes)
                    }
                } catch(e:Exception){t.wanted=false;t.state="error";t.detail=e.message?.takeIf { !it.contains("://") }?.take(180) ?: "Не удалось прочитать раздачу"}
            }
            "select"->{
                require(task!=null && !task.wanted && exporting!=id) { "Сначала поставь загрузку на паузу" }
                val array=a.getJSONArray("files");val selected=(0 until array.length()).map { array.getInt(it) }.toSet()
                task.total=TorrentRules.selectedSize(task.files,selected,prefs.getInt("maxMB",2048)*1024L*1024)
                require(task.exported.isEmpty()) { "После сохранения файлов выбор не меняется" };task.selected=selected;task.state="ready";task.detail="Выбери папку и нажми «Загрузить»"
            }
            "run"->{
                require(task!=null && task.state!="cancelled" && exporting!=id);require(foreground) { "Начни из окна расширения" }
                if(File(jobDir(id),"info.torrent").exists()){
                    task.total=TorrentRules.selectedSize(task.files,task.selected,prefs.getInt("maxMB",2048)*1024L*1024)
                    if(task.exported.keys.containsAll(task.selected)){task.wanted=false;task.state="complete";task.detail="Файлы уже сохранены";return}
                    if(task.exported.isEmpty())task.tree=prefs.getString("destination",task.tree).orEmpty()
                    require(task.tree.isNotBlank()) { "Выбери папку назначения" }
                }
                flushStarted.remove(id);flushed.remove(task.hash);cancelled.remove(id);task.wanted=true;task.detail="";task.state="queued"
            }
            "pause"->{require(task!=null);task.wanted=false;task.state="paused";task.detail="Пауза";pauseMetadata(id)}
            "cancel"->{require(task!=null);task.wanted=false;task.state="cancelled";task.detail="Готовые файлы сохранены; временные можно удалить";pauseMetadata(id)}
            "remove"->{
                require(task!=null && !task.wanted && exporting!=id && metadataId!=id) { "Дождись остановки задачи" }
                stopEngine();jobDir(id).deleteRecursively();tasks.remove(id);infos.remove(id);cancelled.remove(id)
            }
            else->error("Неизвестное действие")
        }
    }
    private fun jobDir(id:String)=File(storage,id)
    private fun payloadDir(id:String)=File(jobDir(id),"data")
    private fun acceptMetadata(t:TorrentTask,data:ByteArray) {
        require(data.size<=TorrentRules.MAX_METADATA)
        val info=TorrentMetadata.load(data).torrentInfo ?: error("В раздаче нет описания файлов");val files=TorrentEngine().describe(info)
        require(files.any { it.audio }) { "В раздаче нет поддерживаемых аудиофайлов" }
        require(tasks.values.none { it.id!=t.id && it.hash==info.infoHash().toString() }) { "Эта раздача уже добавлена" }
        File(jobDir(t.id),"info.torrent").writeBytes(data);infos[t.id]=info;t.hash=info.infoHash().toString();t.files=files;t.title=info.name().take(150)
        t.selected=emptySet();t.wanted=false;t.state="ready";t.detail="Открой выбор файлов"
    }
    private fun pauseMetadata(id:String) { if(metadataId==id){metadataToken="";metadataFuture?.cancel(true)} }
    private fun beginMetadata(t:TorrentTask) {
        if(metadataId!=null)return
        val token=UUID.randomUUID().toString();metadataToken=token;metadataRun=token;metadataId=t.id;t.state="metadata"
        metadataFuture=metadataWorker.submit {
            val session=SessionManager(false);metadataSession=session
            val result=runCatching {
                val sp=SettingsPack().connectionsLimit(30);sp.setMaxMetadataSize(TorrentRules.MAX_METADATA);sp.setEnableLsd(false)
                sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_upnp.swigValue(),false)
                sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_natpmp.swigValue(),false)
                TorrentEngine.noUploadSettings(sp)
                session.start(SessionParams(sp));TorrentEngine.noUploadPeers(session)
                TorrentMetadata.fetch(session,File(jobDir(t.id),"magnet").readText(),File(jobDir(t.id),"metadata"),90)
            }
            runCatching { session.stop() };metadataSession=null
            runCatching { worker.execute {
                if(metadataRun==token){metadataId=null;metadataFuture=null;metadataRun=""}
                if(metadataToken==token && t.wanted)result.onSuccess { runCatching { acceptMetadata(t,it) }.onFailure { t.state="error";t.detail=it.message.orEmpty().take(180);t.wanted=false } }
                    .onFailure { t.state="error";t.detail="Метаданные не получены. Проверь сеть и доступность участников; затем повтори";t.wanted=false }
                publish(true)
            } }
        }
    }
    private fun startEngine():TorrentEngine {
        engine?.let { return it }
        return TorrentEngine().also { e->
            e.start();e.session.addListener(object:AlertListener {
                override fun types()=intArrayOf(AlertType.CACHE_FLUSHED.swig())
                override fun alert(alert:Alert<*>) { if(alert is CacheFlushedAlert)flushed.add(alert.handle().infoHash().toString()) }
            });engine=e
        }
    }
    private fun stopEngine(){engine?.close();engine=null;flushStarted.clear();flushed.clear()}
    private fun tick() {
        if(metadataFuture?.isCancelled==true && metadataSession==null){metadataId=null;metadataFuture=null;metadataRun=""}
        val net=allowed();val active=tasks.values.any { it.wanted } || exporting!=null
        if(net && active){
            val cm=getSystemService(ConnectivityManager::class.java);val current=cm.activeNetwork
            if(current!=boundNetwork){check(cm.bindProcessToNetwork(current)) { "Не удалось закрепить разрешённую сеть" };boundNetwork=current}
        }
        if(!net){
            if(engine!=null)stopEngine()
            metadataId?.let { pauseMetadata(it) }
            tasks.values.filter { it.wanted }.forEach { it.state="network";it.detail="Ожидание разрешённой сети";it.down=0;it.up=0 }
        } else if(foreground) {
            val payloadTask=if(exporting==null)tasks.values.firstOrNull { it.wanted && File(jobDir(it.id),"info.torrent").exists() && it.selected.isNotEmpty() }?.id else null
            for(t in tasks.values.filter { it.wanted && it.id!=exporting }) {
                if(!File(jobDir(t.id),"info.torrent").exists()){beginMetadata(t);continue}
                if(t.selected.isEmpty()){t.wanted=false;t.state="ready";continue}
                if(t.id!=payloadTask){t.state="queued";t.down=0;infos[t.id]?.let { engine?.handle(it)?.pause() };continue}
                if(!infos.containsKey(t.id))infos[t.id]=TorrentMetadata.load(File(jobDir(t.id),"info.torrent").readBytes()).torrentInfo
                val info=infos.getValue(t.id)
                val e=startEngine();e.session.downloadRateLimit(prefs.getInt("downKB",0)*1024)
                var h=e.handle(info)
                if(h==null){
                    val remaining=(t.total-t.done).coerceAtLeast(0)
                    val staged=storage.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    check(staged+remaining+16*1024*1024 <= prefs.getInt("maxMB",2048)*1024L*1024) { "Превышен лимит временного места. Удали старые временные данные или увеличь лимит" }
                    check(storage.usableSpace>remaining+64*1024*1024) { "Недостаточно места для временных файлов" }
                    e.add(info,payloadDir(t.id),t.selected,File(jobDir(t.id),"info.torrent").readBytes(),File(jobDir(t.id),"magnet").takeIf { it.exists() }?.readText());t.state="checking";continue
                }
                h.unsetFlags(TorrentFlags.AUTO_MANAGED);h.resume();e.session.resume()
                val status=h.status(true);check(status.errorCode().value==0) { "Ошибка чтения или записи торрент-файлов" }
                t.done=status.totalWantedDone();t.down=status.downloadRate();t.up=status.uploadRate();t.peers=status.numPeers();t.uploaded=status.totalPayloadUpload();check(t.uploaded==0L) { "Движок нарушил запрет отдачи; передача остановлена" }
                if(status.isFinished){
                    if(t.exported.keys.containsAll(t.selected)){
                        t.wanted=false;t.state="complete";h.pause()
                    } else if(exporting==null) {
                        val hash=info.infoHash().toString();h.pause()
                        if(!flushStarted.containsKey(t.id)){flushed.remove(hash);flushStarted[t.id]=System.currentTimeMillis();h.flushCache()}
                        t.state="checking";t.detail="Запись проверенных файлов на диск"
                        if(hash in flushed){stopEngine();export(t)}
                        else if(System.currentTimeMillis()-flushStarted.getValue(t.id)>30000){t.wanted=false;t.state="error";t.detail="Не удалось завершить запись; нажми продолжить"}
                    }
                } else { t.state="downloading";t.detail="" }
            }
            engine?.let { e->tasks.values.filter { !it.wanted || it.id==exporting }.forEach { t->infos[t.id]?.let { e.handle(it)?.pause() } } }
        }
        if(!active && metadataId==null){if(engine!=null)stopEngine();if(boundNetwork!=null){getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null);boundNetwork=null};if(foreground)main.post { if(!activeView && pendingStarts.get()==0 && foreground){foreground=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()} }}
        if(active && (net || exporting!=null) && foreground && wake?.isHeld==false)wake?.acquire(10*60*1000L)
        if((!active || !net && exporting==null) && wake?.isHeld==true)wake?.release()
        if(foreground){val t=tasks.values.firstOrNull { it.wanted || it.id==exporting };if(Build.VERSION.SDK_INT<33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED)getSystemService(NotificationManager::class.java).notify(701,notification(t?.let { "${it.title}: ${if(it.total>0)it.done*100/it.total else 0}% · ${it.state}" } ?: "Ожидание"))}
        publish(++ticks%5==0)
    }
    private fun export(t:TorrentTask) {
        exporting=t.id;t.state="exporting";t.detail="Сохранение в выбранную папку";publish(true)
        val files=t.files.filter { it.index in t.selected };val existing=t.exported.toMap()
        exporter.execute {
            val result=runCatching { TorrentExport.copy(this,payloadDir(t.id),t.tree,t.id,files,existing,{ destroyed || t.id in cancelled || Thread.currentThread().isInterrupted }) { index,uri->worker.execute { t.exported[index]=uri;publish(true) } } }
            runCatching { worker.execute {
                exporting=null
                if(t.id in cancelled){t.wanted=false;if(t.state!="cancelled")t.state="paused"}
                else result.onSuccess { t.state="complete";t.detail="Сохранено ${t.exported.size} файлов";t.wanted=false;payloadDir(t.id).deleteRecursively() }
                    .onFailure {t.wanted=false;t.state="error";t.detail="Не удалось сохранить в папку. Проверь доступ и место, затем продолжи"}
                publish(true)
            } }
        }
    }
    private fun pauseAll(reason:String){tasks.values.forEach { cancelled.add(it.id);it.wanted=false;if(it.state !in setOf("complete","cancelled")){it.state="paused";it.detail=reason} };metadataId?.let { pauseMetadata(it) };stopEngine()}
    private fun publish(save:Boolean) {
        val previous=view
        activeView=tasks.values.any { it.wanted } || exporting!=null
        tasks.values.forEach { details[it.id]=it.json(true).toString() };details.keys.retainAll(tasks.keys)
        view=JSONObject().put("tasks",JSONArray(tasks.values.map { it.json(false) })).put("settings",settings()).put("networkAllowed",allowed()).put("error",lastError).toString()
        if(save){val saved=JSONArray(tasks.values.map { it.json(true) }).toString();if(saved!=lastSaved){val stream=state.startWrite();try { stream.write(saved.toByteArray());state.finishWrite(stream);lastSaved=saved }catch(e:Exception){state.failWrite(stream)}}}
        if(view==previous)return
        val n=callbacks.beginBroadcast();try { for(i in 0 until n)runCatching { callbacks.getBroadcastItem(i).changed(view) } }finally { callbacks.finishBroadcast() }
    }
    override fun onTimeout(startId:Int,fgsType:Int) {
        worker.execute { pauseAll("Android ограничил время фоновой передачи. Продолжи из окна расширения");publish(true) }
        foreground=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    override fun onDestroy(){
        destroyed=true;foreground=false;metadataFuture?.cancel(true);metadataWorker.shutdownNow();exporter.shutdownNow()
        worker.execute { runCatching { stopEngine() };callbacks.kill() };worker.shutdown()
        if(wake?.isHeld==true)wake?.release();getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null);super.onDestroy()
    }
}
