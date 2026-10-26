package dev.alex.folderplayer

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

class BooksActivity:ThemedActivity() {
    private val books by lazy { Audiobooks(this) }
    private var selected:String?=null
    private var controller:MediaController?=null
    private var connection:ListenableFuture<MediaController>?=null
    private val handler=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var importTask:java.util.concurrent.Future<*>?=null
    private var alive=true
    private var resumed=false
    private var dragging=false
    private var importing=false
    private lateinit var status:TextView
    private lateinit var details:TextView
    private lateinit var play:Button
    private lateinit var seek:SeekBar
    private lateinit var list:ListView
    private lateinit var folderButton:Button
    private lateinit var fileButton:Button
    private var rows=emptyList<Audiobook>()
    private val update=object:Runnable { override fun run(){ if(!resumed)return;updatePlayback();if(controller?.isPlaying==true)handler.postDelayed(this,1000) } }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun button(title:String,action:()->Unit)=Button(this).apply { text=title;isAllCaps=false;setOnClickListener { action() } }
    private fun text(size:Float)=TextView(this).apply { textSize=size;setTextColor(AppAppearance.textColor);setPadding(dp(8),dp(6),dp(8),dp(6)) }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        selected=state?.getString("book") ?: getSharedPreferences("playback",0).getString("activeBook",null)
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(8));setBackgroundColor(AppAppearance.palette.background) }
        SystemBarInsets.apply(box)
        box.addView(text(23f).apply { text="Аудиокниги" })
        val wide=resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val body=LinearLayout(this).apply { orientation=if(wide)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        box.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        val shelf=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val controls=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val reader=ScrollView(this).apply { addView(controls) }
        body.addView(shelf,if(wide)LinearLayout.LayoutParams(0,-1,1f) else LinearLayout.LayoutParams(-1,0,1f))
        body.addView(reader,if(wide)LinearLayout.LayoutParams(0,-1,1f) else LinearLayout.LayoutParams(-1,-2))
        val add=LinearLayout(this)
        folderButton=button("Добавить папку") { pick(true) };fileButton=button("Добавить файл") { pick(false) }
        add.addView(folderButton,LinearLayout.LayoutParams(0,-2,1f));add.addView(fileButton,LinearLayout.LayoutParams(0,-2,1f));shelf.addView(add)
        status=text(14f);shelf.addView(status)
        list=ListView(this).apply { dividerHeight=dp(1);setOnItemClickListener { _,_,index,_ -> selected=rows[index].id;refresh();updatePlayback() };setOnItemLongClickListener { _,_,index,_ -> bookOptions(rows[index]);true } }
        shelf.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        details=text(16f);controls.addView(details)
        seek=SeekBar(this).apply { max=1000;contentDescription="Позиция в главе";setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar:SeekBar){dragging=true}
            override fun onStopTrackingTouch(bar:SeekBar){controller?.takeIf { active() }?.let { c -> duration(c).takeIf { it>0 }?.let { c.seekTo(it*bar.progress/1000) } };dragging=false}
            override fun onProgressChanged(bar:SeekBar,p:Int,user:Boolean){}
        }) };controls.addView(seek)
        val transport=LinearLayout(this)
        transport.addView(button("−15 с") { skip(-15000) },LinearLayout.LayoutParams(0,-2,1f))
        play=button("Продолжить") {
            val b=selected?.let { books.book(it) } ?: return@button toast("Добавь или выбери книгу")
            val c=controller ?: return@button toast("Плеер ещё подключается")
            if(active() && c.playbackState!=Player.STATE_ENDED && !books.position(b).completed){ if(c.isPlaying)c.pause() else { if(c.playbackState==Player.STATE_IDLE)c.prepare();c.play() } }
            else start(b)
        };transport.addView(play,LinearLayout.LayoutParams(0,-2,1f))
        transport.addView(button("+15 с") { skip(15000) },LinearLayout.LayoutParams(0,-2,1f));controls.addView(transport)
        val options=LinearLayout(this)
        options.addView(button("Главы") { chapters() },LinearLayout.LayoutParams(0,-2,1f))
        options.addView(button("Скорость") { speed() },LinearLayout.LayoutParams(0,-2,1f))
        options.addView(button("Закладки") { bookmarks() },LinearLayout.LayoutParams(0,-2,1f));controls.addView(options)
        val footer=LinearLayout(this)
        footer.addView(button("Таймер сна") { sleepTimer() },LinearLayout.LayoutParams(0,-2,1f))
        footer.addView(button("Назад") { finish() },LinearLayout.LayoutParams(0,-2,1f));controls.addView(footer);setContentView(box)
        val f=MediaController.Builder(this,SessionToken(this,ComponentName(this,PlaybackService::class.java))).buildAsync();connection=f
        f.addListener({ if(alive)runCatching { f.get() }.onSuccess { c -> controller=c
            c.addListener(object:Player.Listener {
                override fun onEvents(player:Player,events:Player.Events){updatePlayback();handler.removeCallbacks(update);if(resumed)handler.post(update)}
                override fun onPlayerError(error:androidx.media3.common.PlaybackException){toast("Не удалось прочитать главу. Проверь доступ к файлам книги.")}
            })
            if(selected==null)selected=c.currentMediaItem?.mediaMetadata?.extras?.getString("bookId")
            refresh();updatePlayback()
        }.onFailure { toast("Не удалось подключить плеер") } },java.util.concurrent.Executor { handler.post(it) })
        refresh();updatePlayback()
    }
    private fun refresh(){
        rows=books.books().sortedWith { a,b -> naturalCompare(a.title,b.title) }
        if(rows.none { it.id==selected })selected=rows.firstOrNull()?.id
        val labels=rows.map { b -> val p=books.position(b);(if(b.id==selected)"▶ " else "")+b.title+"\n"+(if(p.completed)"Прослушано" else "${b.chapters.indexOfFirst { it.uri==p.uri }+1}/${b.chapters.size} · ${clock(p.position)}") }
        list.adapter=ArrayAdapter(this,android.R.layout.simple_list_item_1,labels)
        if(!importing)status.text=if(rows.isEmpty())"Папка — одна книга. Файлы — главы по порядку имён." else "Удерживай книгу для обновления или удаления из списка."
    }
    private fun active()=selected!=null && controller?.currentMediaItem?.mediaMetadata?.extras?.getString("bookId")==selected
    private fun duration(c:MediaController)=c.duration.takeIf { it>0 && it!=C.TIME_UNSET } ?: c.currentMediaItem?.mediaMetadata?.extras?.getLong("onlineDuration") ?: 0L
    private fun updatePlayback(){
        if(!::details.isInitialized)return
        val b=selected?.let { books.book(it) };val c=controller
        val p=b?.let { books.position(it) }
        val chapter=if(active())c?.currentMediaItem?.mediaMetadata?.title?.toString() else b?.chapters?.find { it.uri==p?.uri }?.title
        val pos=if(active())c?.currentPosition ?: 0L else p?.position ?: 0L
        val dur=if(active() && c!=null)duration(c) else b?.chapters?.find { it.uri==p?.uri }?.duration ?: 0L
        details.text=if(b==null)"Книга не выбрана" else "${b.title}\n${chapter.orEmpty()}\n${clock(pos)} / ${clock(dur)} · ${books.speed(b.id)}×"
        play.text=if(active() && c?.isPlaying==true)"Пауза" else if(p?.completed==true || (active() && c?.playbackState==Player.STATE_ENDED))"Сначала" else "Продолжить"
        play.isEnabled=b!=null && c!=null
        seek.isEnabled=active() && dur>0
        if(!dragging)seek.progress=if(dur>0)(pos*1000/dur).toInt().coerceIn(0,1000) else 0
    }
    private fun start(b:Audiobook,uri:String?=null,position:Long=0){
        command(PlaybackCommands.START_BOOK,Bundle().apply { putString("bookId",b.id);if(uri!=null){putString("uri",uri);putLong("position",position)} })
    }
    private fun command(action:String,args:Bundle){
        val c=controller ?: return toast("Плеер ещё подключается")
        val f=c.sendCustomCommand(SessionCommand(action,Bundle.EMPTY),args)
        f.addListener({if(alive)runCatching { f.get() }.onSuccess { if(it.resultCode!=SessionResult.RESULT_SUCCESS)toast("Не удалось применить действие") }.onFailure { toast("Не удалось применить действие") }},java.util.concurrent.Executor { handler.post(it) })
    }
    private fun skip(delta:Long){controller?.takeIf { active() }?.let { c -> val target=(c.currentPosition+delta).coerceAtLeast(0);val d=duration(c);c.seekTo(if(d>0)target.coerceAtMost(d) else target) }}
    private fun chapters(){
        val b=selected?.let { books.book(it) } ?: return
        AlertDialog.Builder(this).setTitle("Главы · ${b.title}").setItems(b.chapters.mapIndexed { i,c -> "${i+1}. ${c.title} · ${clock(c.duration)}" }.toTypedArray()) { _,i -> start(b,b.chapters[i].uri) }.setNegativeButton("Закрыть",null).show()
    }
    private fun speed(){
        val b=selected?.let { books.book(it) } ?: return
        val values=floatArrayOf(.5f,.75f,1f,1.25f,1.5f,1.75f,2f,2.5f)
        AlertDialog.Builder(this).setTitle("Скорость книги").setSingleChoiceItems(values.map { "$it×" }.toTypedArray(),values.indexOfFirst { it==books.speed(b.id) }) { d,i ->
            books.setSpeed(b.id,values[i]);if(active())command(PlaybackCommands.BOOK_SPEED,Bundle().apply { putFloat("speed",values[i]) });updatePlayback();d.dismiss()
        }.setNegativeButton("Отмена",null).show()
    }
    private fun bookmarks(){
        val b=selected?.let { books.book(it) } ?: return
        val marks=books.bookmarks(b.id)
        val dialog=AlertDialog.Builder(this).setTitle("Закладки · ${b.title}").setItems(marks.map { "${it.title} · ${clock(it.position)}" }.toTypedArray()) { _,i ->
            val m=marks[i];AlertDialog.Builder(this).setTitle(m.title).setMessage(clock(m.position))
                .setPositiveButton("Слушать") { _,_ -> start(b,m.uri,m.position) }.setNeutralButton("Удалить") { _,_ -> books.removeBookmark(b.id,m.id);bookmarks() }.setNegativeButton("Отмена",null).show()
        }.setPositiveButton("Добавить текущую") { _,_ ->
            val c=controller
            if(!active() || c?.currentMediaItem==null)toast("Сначала запусти выбранную книгу")
            else runCatching { books.addBookmark(b.id,c.currentMediaItem!!.mediaId,c.currentPosition,c.currentMediaItem!!.mediaMetadata.title?.toString().orEmpty()) }.onSuccess { toast("Закладка добавлена") }.onFailure { toast(it.message ?: "Не удалось добавить закладку") }
        }.setNegativeButton("Закрыть",null).create()
        dialog.show()
    }
    private fun sleepTimer(){
        val minutes=intArrayOf(15,30,60,0)
        AlertDialog.Builder(this).setTitle("Остановить воспроизведение через…").setItems(arrayOf("15 минут","30 минут","1 час","Выключить таймер")) { _,i ->
            val edit=getSharedPreferences("playback",0).edit()
            if(minutes[i]==0)edit.remove("sleepDeadline") else edit.putLong("sleepDeadline",System.currentTimeMillis()+minutes[i]*60000L)
            edit.apply();toast(if(minutes[i]==0)"Таймер выключен" else "Таймер установлен")
        }.setNegativeButton("Отмена",null).show()
    }
    private fun bookOptions(b:Audiobook){
        AlertDialog.Builder(this).setTitle(b.title).setItems(arrayOf("Обновить файлы книги","Удалить из списка")) { _,i ->
            if(i==0){
                if(controller?.currentMediaItem?.mediaMetadata?.extras?.getString("bookId")==b.id)toast("Перед обновлением книги включи музыку или другую книгу")
                else importBook(Uri.parse(b.root),android.provider.DocumentsContract.isTreeUri(Uri.parse(b.root)))
            } else AlertDialog.Builder(this).setTitle("Удалить книгу из списка?").setMessage("Аудиофайлы останутся. Позиция и закладки этой книги будут удалены.")
                .setPositiveButton("Удалить") { _,_ ->
                    if(controller?.currentMediaItem?.mediaMetadata?.extras?.getString("bookId")==b.id){controller?.pause();controller?.clearMediaItems()}
                    books.remove(b.id);refresh();updatePlayback()
                }.setNegativeButton("Отмена",null).show()
        }.setNegativeButton("Отмена",null).show()
    }
    private fun pick(folder:Boolean){
        if(importing)return
        val intent=Intent(if(folder)Intent.ACTION_OPEN_DOCUMENT_TREE else Intent.ACTION_OPEN_DOCUMENT).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        if(!folder){intent.type="audio/*";intent.addCategory(Intent.CATEGORY_OPENABLE)}
        startActivityForResult(intent,if(folder)201 else 202)
    }
    @Deprecated("Activity result API") override fun onActivityResult(request:Int,result:Int,data:Intent?){
        super.onActivityResult(request,result,data)
        if(request !in 201..202 || result!=RESULT_OK)return
        val uri=data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }.onFailure { toast("Не удалось сохранить доступ к книге");return }
        importBook(uri,request==201)
    }
    private fun importBook(uri:Uri,folder:Boolean){
        if(importing)return
        importing=true;folderButton.isEnabled=false;fileButton.isEnabled=false;status.text="Читаем файлы книги…"
        importTask=worker.submit {
            val result=runCatching { Audiobooks.scan(applicationContext,uri,folder) }
            handler.post { if(alive){importing=false;folderButton.isEnabled=true;fileButton.isEnabled=true
                result.onSuccess {
                    if(books.book(it.id)!=null && controller?.currentMediaItem?.mediaMetadata?.extras?.getString("bookId")==it.id) {
                        status.text="Книга не изменена: перед обновлением переключись на другую книгу или музыку"
                    } else { books.put(it);selected=it.id;refresh();updatePlayback() }
                }.onFailure { status.text="Книга не изменена: ${it.message ?: "не удалось прочитать файлы"}" }
            } }
        }
    }
    private fun toast(s:String){Toast.makeText(this,s,Toast.LENGTH_LONG).show()}
    private fun clock(ms:Long):String {val s=ms.coerceAtLeast(0)/1000;return if(s>=3600)"%d:%02d:%02d".format(s/3600,s/60%60,s%60) else "%d:%02d".format(s/60,s%60)}
    override fun onResume(){super.onResume();resumed=true;if(::list.isInitialized)refresh();handler.post(update)}
    override fun onPause(){resumed=false;handler.removeCallbacks(update);super.onPause()}
    override fun onSaveInstanceState(out:Bundle){out.putString("book",selected);super.onSaveInstanceState(out)}
    override fun onDestroy(){alive=false;importTask?.cancel(true);worker.shutdownNow();handler.removeCallbacksAndMessages(null);connection?.let { MediaController.releaseFuture(it) };super.onDestroy()}
}
