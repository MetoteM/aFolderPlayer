package dev.alex.folderplayer

import android.app.Activity
import android.app.Dialog
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/** A source browser with explicit selection and a complete, transactional playback snapshot. */
internal class OnlineSection(
    private val host:Activity,
    private val play:(String,Int,String,(String?)->Unit)->Unit,
    private val extension:OnlineCatalogue=OnlinePlayback(host),
    private val worker:ExecutorService=Executors.newSingleThreadExecutor(),
    private val openPlayer:()->Unit={},
) {
    private val store=OnlineLibraryStore(host)
    private val decisions=OnlineLibraryActions(host)
    private val loader=OnlineCatalogueLoader(extension)
    private val covers=java.util.concurrent.ThreadPoolExecutor(2,2,0L,java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(24),java.util.concurrent.ThreadPoolExecutor.DiscardPolicy())
    private val main=Handler(Looper.getMainLooper())
    private val artwork=OnlineArtwork(host)
    private var dialog:Dialog?=null
    @Volatile private var generation=0
    @Volatile private var closed=false
    @Volatile private var suspended=false
    private var returning=false
    private var busy=false
    private var pending:java.util.concurrent.Future<*>?=null
    private var handingOff=false
    private var renderedBusy=false
    private var now:NowPlayingStrip?=null
    private var operation="collectionPage"
    private var sourceId=""
    private var sourceTitle="Мне нравится в Яндексе"
    private var data=JSONObject().put("tracks",JSONArray())
    private var rows=JSONArray()
    private var shown=emptyList<OnlineRows.Row>()
    private var playlistMode=false
    private var query=""
    private var sort=0
    private var selecting=false
    private val selected=mutableSetOf<Int>()
    private lateinit var title:TextView
    private lateinit var status:TextView
    private lateinit var selection:TextView
    private lateinit var list:ListView
    private lateinit var more:Button
    private lateinit var retry:Button
    private lateinit var cancel:Button
    private lateinit var playAll:Button
    private lateinit var sortView:Spinner
    private lateinit var search:EditText
    private val buttons=mutableListOf<Button>()
    private val adapter=object:BaseAdapter() {
        override fun getCount()=shown.size
        override fun getItem(position:Int)=shown[position].song
        override fun getItemId(position:Int)=shown[position].sourceIndex.toLong()
        override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {
            val entry=shown[position];val song=entry.song
            val row=LinearLayout(host).apply { orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(8,8,8,8) }
            if(!playlistMode) row.addView(CheckBox(host).apply {
                isChecked=entry.sourceIndex in selected;isEnabled=!busy;contentDescription="Выбрать ${song.optString("title")}"
                setOnCheckedChangeListener { _,checked -> if(busy)return@setOnCheckedChangeListener;selecting=true;if(checked)selected+=entry.sourceIndex else selected-=entry.sourceIndex;updateSelection() }
            })
            val image=ImageView(host).apply { setImageResource(android.R.drawable.ic_menu_gallery) }
            row.addView(image,LinearLayout.LayoutParams(dp(40),dp(40)))
            val id=song.optString("id")
            row.addView(TextView(host).apply {
                setTextColor(Color.WHITE);textSize=16f;setPadding(12,0,8,0)
                text=if(playlistMode) song.optString("title")+"\n"+song.optInt("count")+" треков"
                else (if(decisions.contains("favorites",id))"♥ " else "")+song.optString("title")+"\n"+song.optString("artist")+" · "+formatDuration(song.optLong("duration"))+
                    (if(!song.optBoolean("available",true))" · недоступен" else "")
            },LinearLayout.LayoutParams(0,-2,1f))
            if(playlistMode) row.addView(Button(host).apply {
                text="Открыть";isFocusable=false;isEnabled=!busy
                setOnClickListener { if(!busy)openPlaylist(song) }
            })
            row.setOnClickListener { if(!busy) { if(playlistMode)openPlaylist(song) else if(selecting || operation=="hidden") { selecting=true;toggle(entry.sourceIndex) } else prepare("play",entry.sourceIndex) } }
            if(!playlistMode)row.setOnLongClickListener { if(!busy) { selecting=true;toggle(entry.sourceIndex) };true }
            row.setBackgroundColor(PlayerScreens.surface)
            row.alpha=if(song.optBoolean("available",true))1f else .5f
            val cover=song.optString("cover");image.tag=cover
            val coverGeneration=generation
            if(cover.isNotEmpty() && !closed)covers.execute {
                if(closed || coverGeneration!=generation || (suspended || dialog?.isShowing!=true))return@execute
                val bitmap=artwork.load(cover)
                main.post { if(!closed && coverGeneration==generation && !suspended && dialog?.isShowing==true && image.tag==cover && bitmap!=null)image.setImageBitmap(bitmap) }
            }
            return row
        }
    }
    fun show() {
        if(closed)return
        dialog?.let { if(suspended) { suspended=false;it.show();adapter.notifyDataSetChanged() };return }
        if(!extension.installed()) { AlertDialog.Builder(host).setTitle("Яндекс Музыка").setMessage("Установи AFP Online из того же тестового комплекта.").setPositiveButton("Закрыть",null).show();return }
        val screen=Dialog(host,android.R.style.Theme_Material_NoActionBar)
        dialog=screen;generation++;busy=false;buttons.clear();selected.clear();selecting=false;query="";sort=0;rows=JSONArray();shown=emptyList()
        val root=LinearLayout(host).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(4),dp(8),dp(4));setBackgroundColor(PlayerScreens.bg) }
        fun button(label:String,action:()->Unit)=Button(host).apply { text=label;setOnClickListener { if(!busy)action() };buttons+=this }
        fun pair(a:View,b:View) { root.addView(LinearLayout(host).apply { addView(a,LinearLayout.LayoutParams(0,-2,1f));addView(b,LinearLayout.LayoutParams(0,-2,1f)) }) }
        pair(Button(host).apply { text="← Назад";setOnClickListener { if(busy)cancelWork() else if(operation=="playlistPage")choose("playlists","","Плейлисты") else screen.dismiss() } },
            Button(host).apply { text="Вход / аккаунт";setOnClickListener { if(!busy)openLogin() } })
        title=TextView(host).apply { textSize=22f;setTextColor(Color.WHITE) };root.addView(title)
        status=TextView(host).apply { textSize=13f;setTextColor(Color.LTGRAY) };root.addView(status)
        val tabs=LinearLayout(host)
        fun tool(kind:IconButton.Kind,label:String,action:()->Unit) {
            val view=IconButton(host,kind,label) { if(!busy)action() };buttons+=view
            tabs.addView(view,LinearLayout.LayoutParams(0,dp(48),1f))
        }
        tool(IconButton.Kind.LIBRARY,"Коллекция") { choose("collectionPage","","Мне нравится в Яндексе") }
        tool(IconButton.Kind.PLAYLIST,"Плейлисты") { choose("playlists","","Плейлисты") }
        tool(IconButton.Kind.SORT,"Сортировка") {
            val token=generation
            AlertDialog.Builder(host).setTitle("Сортировка").setSingleChoiceItems(arrayOf("Исходный порядок","По названию","По исполнителю","По длительности"),sort) { menu,index ->
                if(!busy && token==generation) { sort=index;sortView.setSelection(index);render() };menu.dismiss()
            }.show()
        }
        tool(IconButton.Kind.SELECT,"Выбрать треки") { selecting=!selecting;selected.clear();render() }
        tool(IconButton.Kind.MENU,"⋮") { sourceMenu() }
        root.addView(tabs)
        search=EditText(host).apply { hint="Фильтр списка / поиск";isSingleLine=true;setTextColor(Color.WHITE);setHintTextColor(Color.GRAY) }
        search.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { query=s?.toString().orEmpty();selected.clear();selecting=false;render() }
            override fun afterTextChanged(s:Editable?){}
        })
        val searchRow=LinearLayout(host);searchRow.addView(search,LinearLayout.LayoutParams(0,-2,1f))
        searchRow.addView(button("В Яндексе") { val q=query.trim();if(q.isNotEmpty())choose("search",q,"Поиск: $q") });root.addView(searchRow)
        sortView=Spinner(host).apply { adapter=ArrayAdapter(host,android.R.layout.simple_spinner_dropdown_item,arrayOf("Исходный порядок","По названию","По исполнителю","По длительности")) }
        sortView.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:AdapterView<*>?){}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) { sort=position;render() }
        }
        sortView.visibility=View.GONE;root.addView(sortView)
        root.addView(button("Действия") { actionsMenu() })
        selection=TextView(host).apply { setTextColor(Color.CYAN);setOnClickListener { if(!busy) { selected.clear();selecting=false;render() } } };root.addView(selection)
        playAll=button("▶ Играть список целиком") { prepare("play",null) };root.addView(playAll)
        list=ListView(host).apply { adapter=this@OnlineSection.adapter;descendantFocusability=ViewGroup.FOCUS_BLOCK_DESCENDANTS }
        list.setOnItemClickListener { _,_,position,_ -> shown.getOrNull(position)?.let { if(!busy) { if(playlistMode)openPlaylist(it.song) else if(selecting || operation=="hidden") { selecting=true;toggle(it.sourceIndex) } else prepare("play",it.sourceIndex) } } }
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        more=button("Показать ещё") { next()?.let { fetch(it,false) } };root.addView(more)
        retry=button("Повторить загрузку") { fetch(0,true) };root.addView(retry)
        cancel=Button(host).apply { text="Отменить загрузку";setOnClickListener { cancelWork() } };root.addView(cancel)
        now=NowPlayingStrip(host) { if(!busy) { suspended=true;screen.hide();openPlayer() } }.also { root.addView(it,LinearLayout.LayoutParams(-1,-2)) }
        SystemBarInsets.apply(root);screen.setContentView(root)
        screen.setOnDismissListener { generation++;pending?.cancel(true);pending=null;dialog=null;suspended=false;busy=false;handingOff=false }
        screen.show();screen.window?.setLayout(-1,-1)
        update();checkStatus()
    }
    private fun dp(value:Int)=(value*host.resources.displayMetrics.density).toInt()
    private fun next():Int?=if(data.has("next") && !data.isNull("next"))data.optInt("next").takeIf { it>=0 } else null
    private fun cacheKey()=if(operation=="collectionPage")"likes" else OnlineLibraryStore.playlistKey(sourceId)
    private fun openPlaylist(song:JSONObject) { choose("playlistPage",song.optString("id"),song.optString("title")) }
    private fun choose(op:String,id:String,label:String) {
        if(busy)return
        pending?.cancel(true)
        generation++;operation=op;sourceId=id;sourceTitle=label;selected.clear();selecting=false
        rows=JSONArray();data=JSONObject().put("tracks",rows);playlistMode=op=="playlists";search.setText("")
        render()
        if(op in listOf("favorites","hidden")) { apply(JSONObject().put("tracks",decisions.rows(op)));return }
        val token=generation;val key=cacheKey();busy=true;status.text="Загрузка…";retry.visibility=View.GONE;update()
        pending=worker.submit {
            if(closed || token!=generation || Thread.currentThread().isInterrupted)return@submit
            val cached=if(op in listOf("collectionPage","playlistPage"))store.read(key) else null
            main.post { if(!closed && token==generation) { busy=false;if(cached!=null)apply(cached) else fetch(0,false) } }
        }
    }
    private fun checkStatus() {
        val token=generation;busy=true;status.text="Проверяем сохранённый вход…";update()
        pending=worker.submit {
            if(closed || token!=generation || Thread.currentThread().isInterrupted)return@submit
            val result=runCatching { extension.request("status").also { store.setSession(it.optString("sessionKey")) } }
            main.post { if(!closed && token==generation) {
                busy=false
                result.onSuccess { if(it.optBoolean("connected"))choose("collectionPage","","Мне нравится в Яндексе") else { status.text="Войди, чтобы открыть коллекцию";update() } }
                    .onFailure { fail(it) }
            } }
        }
    }
    private fun fetch(offset:Int,refresh:Boolean) {
        if(busy)return
        val token=generation;val op=operation;val id=sourceId;val key=cacheKey();val prior=JSONArray(rows.toString())
        busy=true;retry.visibility=View.GONE;status.text="Загрузка…";update()
        pending=worker.submit {
            if(closed || token!=generation || Thread.currentThread().isInterrupted)return@submit
            val result=runCatching {
                val value=if(op in listOf("collectionPage","playlistPage"))loader.page(op,id,offset,refresh) else extension.request(op,if(op=="search")id else "")
                if(offset>0)value.put("tracks",merge(prior,value.getJSONArray("tracks")))
                check(!closed && token==generation && !Thread.currentThread().isInterrupted) { "Загрузка отменена" }
                if(op in listOf("collectionPage","playlistPage"))store.write(key,value)
                value
            }
            main.post { if(!closed && token==generation) { busy=false;result.onSuccess { apply(it,offset>0) }.onFailure { fail(it) } } }
        }
    }
    private fun apply(value:JSONObject,append:Boolean=false) {
        data=value;playlistMode=value.has("playlists");rows=value.optJSONArray(if(playlistMode)"playlists" else "tracks") ?: JSONArray()
        if(!append) { selected.clear();selecting=false };retry.visibility=View.GONE;render();if(!append)list.setSelection(0)
    }
    private fun render() {
        if(!::title.isInitialized || !::list.isInitialized)return
        title.text=if(operation=="playlistPage")sourceTitle else "Яндекс Музыка · $sourceTitle"
        shown=OnlineRows.display(rows,query,sort,if(playlistMode)emptySet() else decisions.ids("hidden"),operation=="hidden")
        adapter.notifyDataSetChanged()
        if(!busy)status.text=(if(playlistMode)"Плейлистов: ${shown.size} · нажми «Открыть»" else "Показано ${shown.size} · загружено ${rows.length()} из ${data.optInt("total",rows.length())}")+
            data.optString("warning").takeIf { it.isNotBlank() }?.let { " · Добавленные плейлисты не загружены: $it" }.orEmpty()
        update()
    }
    private fun updateSelection() { playAll.text=if(selected.isNotEmpty())"▶ Играть выбранные · ${selected.size}" else "▶ Играть список целиком";selection.text=if(selecting)"Выбрано: ${selected.size} · нажми здесь, чтобы снять выбор" else "Долгое нажатие или флажок — выбрать песни" }
    private fun update() {
        if(!::more.isInitialized)return
        if(renderedBusy!=busy) { renderedBusy=busy;adapter.notifyDataSetChanged() }
        list.isEnabled=!busy;selection.isEnabled=!busy
        buttons.forEach { it.isEnabled=!busy };sortView.isEnabled=!busy;search.isEnabled=!busy
        more.visibility=if(!playlistMode && next()!=null)View.VISIBLE else View.GONE
        playAll.visibility=if(!playlistMode && operation!="hidden" && shown.isNotEmpty())View.VISIBLE else View.GONE
        dialog?.setCancelable(!handingOff)
        cancel.visibility=if(busy && !handingOff)View.VISIBLE else View.GONE
        updateSelection()
    }
    private fun fail(error:Throwable) {
        status.text="Не удалось загрузить: "+(error.message?.take(180) ?: "неизвестная ошибка")
        retry.visibility=View.VISIBLE;update()
        // Keep one inline error and retry; repeated taps must not stack modal windows.
    }
    private fun toggle(index:Int) { if(!selected.add(index))selected.remove(index);render() }
    private fun sourceMenu() {
        val menuGeneration=generation
        AlertDialog.Builder(host).setTitle("Онлайн-библиотека").setItems(arrayOf("Избранное в плеере","Скрытые в плеере","Обновить список","Сеть и качество")) { _,i ->
            if(busy || menuGeneration!=generation || (suspended || dialog?.isShowing!=true))return@setItems
            when(i) { 0->choose("favorites","","Избранное в плеере");1->choose("hidden","","Скрытые в плеере");2->if(operation in listOf("favorites","hidden"))choose(operation,"",sourceTitle) else fetch(0,true);3->OnlineSettings(host).show(host) }
        }.show()
    }
    private fun selectedRows():JSONArray=JSONArray(shown.filter { it.sourceIndex in selected }.map { it.song })
    private fun actionsMenu() {
        val menuGeneration=generation
        if(playlistMode) { status.text="Сначала открой плейлист";return }
        val has=selected.isNotEmpty()
        val labels=mutableListOf("Выбрать все показанные","Снять выбор")
        if(has)labels+=listOf("Воспроизвести выбранные","Играть выбранные далее","Выбранные в конец очереди","В избранное плеера","Убрать из избранного",if(operation=="hidden")"Вернуть в библиотеку" else "Скрыть в плеере")
        else labels+=listOf("Воспроизвести список целиком","Весь список — играть далее","Весь список — в конец очереди")
        if(selected.size==1)labels+=listOf("Поделиться треком","Сохранить MP3…")
        AlertDialog.Builder(host).setTitle(if(has)"Выбрано: ${selected.size}" else "Действия со списком").setItems(labels.toTypedArray()) { _,i ->
            if(busy || menuGeneration!=generation || (suspended || dialog?.isShowing!=true))return@setItems
            when(i) {
                0->{ selecting=true;selected.clear();selected+=shown.map { it.sourceIndex };render() }
                1->{ selecting=false;selected.clear();render() }
                2->prepare("play",null)
                3->prepare("next",null)
                4->prepare("append",null)
                5->changeDecision("favorites",true)
                6->changeDecision("favorites",false)
                7->changeDecision("hidden",operation!="hidden")
                9->selectedRows().optJSONObject(0)?.let { YandexSaveActivity.open(host,"afp-yandex://track/${it.optString("id")}",it.optString("title"),it.optString("artist")) }
                8->selectedRows().optJSONObject(0)?.let { YandexTrackShare.show(host,"afp-yandex://track/${it.optString("id")}",it.optString("title")) }
            }
        }.setNegativeButton("Закрыть",null).show()
    }
    private fun changeDecision(kind:String,enabled:Boolean) {
        if(busy)return
        val snapshot=selectedRows();if(snapshot.length()==0)return
        val token=generation;busy=true;update()
        pending=worker.submit {
            if(closed || token!=generation || Thread.currentThread().isInterrupted)return@submit
            val result=runCatching { decisions.set(kind,snapshot,enabled);if(kind=="hidden" && enabled)store.saveQueue(snapshot) else null }
            main.post { if(!closed && token==generation) {
                busy=false
                result.onSuccess { handle ->
                    if(handle!=null)play(handle,0,"remove") { error -> if(!closed && token==generation && error!=null)status.text=error }
                    selected.clear();selecting=false
                    if(operation in listOf("favorites","hidden"))apply(JSONObject().put("tracks",decisions.rows(operation))) else render()
                }.onFailure { fail(it) }
            } }
        }
    }
    private fun prepare(mode:String,start:Int?) {
        if(busy || playlistMode || (suspended || dialog?.isShowing!=true))return
        val token=++generation;val op=operation;val id=sourceId;val key=cacheKey()
        val initial=JSONObject(data.toString());val selectionSnapshot=selected.toSet();val q=query;val sorting=sort;val label=sourceTitle
        busy=true;status.text="Подготовка очереди…";update()
        pending=worker.submit {
            if(closed || token!=generation || Thread.currentThread().isInterrupted)return@submit
            val result=runCatching {
                val full=if(selectionSnapshot.isEmpty() && op in listOf("collectionPage","playlistPage")) {
                    loader.complete(op,id,initial,{ !closed && token==generation }) { n,total -> main.post { if(!closed && token==generation)status.text="Подготовка очереди: $n из $total" } }.also { store.write(key,it) }
                } else initial
                val all=full.optJSONArray("tracks") ?: JSONArray()
                val visible=OnlineRows.display(all,q,sorting,decisions.ids("hidden"))
                    .filter { it.song.optBoolean("available",true) && (selectionSnapshot.isEmpty() || it.sourceIndex in selectionSnapshot) }
                check(visible.isNotEmpty()) { "Нет доступных нескрытых песен для воспроизведения" }
                val index=if(start==null)0 else visible.indexOfFirst { it.sourceIndex==start }.also { check(it>=0) { "Этот трек недоступен или скрыт" } }
                check(!closed && token==generation) { "Подготовка отменена" }
                Triple(store.saveQueue(JSONArray(visible.map { it.song }),label),index,full)
            }
            main.post { if(!closed && token==generation) {
                busy=false
                result.onSuccess { (handle,index,full) ->
                    data=full;rows=full.optJSONArray("tracks") ?: rows;busy=true;handingOff=true;status.text="Передаём очередь плееру…";render();cancel.visibility=View.GONE
                    var acknowledged=false
                    val finish:(String?)->Unit={ error ->
                        if(!acknowledged && !closed && token==generation) {
                            acknowledged=true;busy=false;handingOff=false
                            if(error!=null)fail(IllegalStateException(error))
                            else { render();if(mode=="play") { suspended=true;dialog?.hide() } else status.text="Добавлено в очередь" }
                        }
                    }
                    try { play(handle,index,mode,finish) }
                    catch(_:Exception) { finish("Не удалось передать очередь плееру. Повтори действие") }

                }.onFailure { fail(it) }
            } }
        }
    }
    private fun cancelWork() {
        if(handingOff)return
        generation++;pending?.cancel(true);pending=null;busy=false
        status.text="Загрузка отменена";retry.visibility=View.VISIBLE;update()
    }
    fun updateNowPlaying(label:String,time:String) { now?.title?.text=label;now?.time?.text=time }
    private fun openLogin() {
        returning=true;dialog?.dismiss()
        runCatching { host.startActivity(Intent().setComponent(ComponentName(onlinePackage(host),"dev.alex.afponline.YandexAccountActivity"))) }
            .onFailure { returning=false;Toast.makeText(host,"Не удалось открыть вход",Toast.LENGTH_LONG).show() }
    }
    fun resume() { if(returning && !closed) { returning=false;show() } }
    fun close() { closed=true;generation++;dialog?.dismiss();worker.shutdownNow();covers.shutdownNow();main.removeCallbacksAndMessages(null) }
    companion object {
        private fun formatDuration(ms:Long):String=if(ms<=0)"—" else "${ms/60000}:${(ms/1000%60).toString().padStart(2,'0')}"
        fun merge(old:JSONArray,more:JSONArray):JSONArray {
            val result=JSONArray();for(array in listOf(old,more))for(i in 0 until array.length())array.optJSONObject(i)?.let { result.put(it) };return result
        }
    }
}
