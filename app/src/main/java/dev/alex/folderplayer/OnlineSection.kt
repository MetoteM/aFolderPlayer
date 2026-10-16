package dev.alex.folderplayer

import android.app.Activity
import android.app.Dialog
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/** One source screen: explicit network actions, paged collection, private metadata cache. */
internal class OnlineSection(private val host:Activity, private val updates:()->Unit,private val play:(String,Int)->Unit) {
    private val extension=OnlinePlayback(host)
    private val store=OnlineLibraryStore(host)
    private val worker=Executors.newSingleThreadExecutor()
    private val covers=Executors.newFixedThreadPool(2)
    private val main=Handler(Looper.getMainLooper())
    private val artwork=OnlineArtwork(host)
    private var dialog:Dialog?=null
    private var generation=0
    private var closed=false
    private var returning=false
    private var connected=false
    private var busy=false
    private var operation="collectionPage"
    private var playlistId=""
    private var next:Int?=null
    private var total=0
    private var rows=JSONArray()
    private var playlistMode=false
    private lateinit var status:TextView
    private lateinit var list:ListView
    private lateinit var more:Button
    private lateinit var login:Button
    private lateinit var progress:ProgressBar
    private val actions=mutableListOf<Button>()
    private val adapter=object:BaseAdapter() {
        override fun getCount()=rows.length()
        override fun getItem(position:Int)=rows.optJSONObject(position)
        override fun getItemId(position:Int)=position.toLong()
        override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {
            val root=convertView as? LinearLayout ?: LinearLayout(host).apply {
                orientation=LinearLayout.HORIZONTAL;setPadding(12,10,12,10)
                addView(ImageView(host),LinearLayout.LayoutParams(112,112))
                addView(TextView(host).apply { setTextColor(Color.WHITE);textSize=16f;setPadding(16,0,8,0) },LinearLayout.LayoutParams(0,-2,1f))
            }
            val song=rows.getJSONObject(position)
            val image=root.getChildAt(0) as ImageView
            val text=root.getChildAt(1) as TextView
            val uri=song.optString("cover")
            image.tag=uri;image.setImageResource(android.R.drawable.ic_menu_gallery)
            text.text=if(playlistMode) song.optString("title")+"\n"+song.optInt("count")+" треков" else song.optString("title")+"\n"+song.optString("artist")+
                if(!song.optBoolean("available",true)) " · недоступен" else ""
            root.alpha=if(!playlistMode && !song.optBoolean("available",true)) .5f else 1f
            if(uri.isNotEmpty() && !closed) covers.execute {
                val bitmap=artwork.load(uri)
                main.post { if(!closed && dialog?.isShowing==true && image.tag==uri && bitmap!=null) image.setImageBitmap(bitmap) }
            }
            return root
        }
    }
    fun show() {
        if(closed || dialog?.isShowing==true)return
        if(!extension.installed()) {
            AlertDialog.Builder(host).setTitle("Яндекс Музыка").setMessage("Установи AFP Online из того же тестового комплекта, что и плеер.").setPositiveButton("Закрыть",null).show();return
        }
        val screen=Dialog(host,android.R.style.Theme_Material_NoActionBar)
        dialog=screen;generation++;actions.clear();rows=JSONArray();next=null;busy=false;playlistMode=false
        operation="collectionPage";playlistId=""
        val root=LinearLayout(host).apply { orientation=LinearLayout.VERTICAL;setPadding(20,12,20,12);setBackgroundColor(Color.rgb(15,21,32)) }
        fun button(title:String,action:()->Unit):Button=Button(host).apply { text=title;setOnClickListener { if(!busy)action() };actions+=this }
        val top=LinearLayout(host)
        top.addView(button("← Библиотека") { screen.dismiss() },LinearLayout.LayoutParams(0,-2,1f))
        login=button("Аккаунт") { openLogin(!connected) }
        top.addView(login,LinearLayout.LayoutParams(0,-2,1f));root.addView(top)
        root.addView(TextView(host).apply { text="Яндекс Музыка";textSize=24f;setTextColor(Color.WHITE) })
        status=TextView(host).apply { setTextColor(Color.LTGRAY);setPadding(0,8,0,8) };root.addView(status)
        progress=ProgressBar(host).apply { visibility=View.GONE };root.addView(progress,LinearLayout.LayoutParams(-1,8))
        val tabs=LinearLayout(host)
        tabs.addView(button("Мне нравится") { choose("collectionPage","") },LinearLayout.LayoutParams(0,-2,1f))
        tabs.addView(button("Плейлисты") { operation="playlists";playlistId="";load(0,false) },LinearLayout.LayoutParams(0,-2,1f))
        tabs.addView(button("↻") { load(0,true) },LinearLayout.LayoutParams(90,-2));root.addView(tabs)
        val search=LinearLayout(host)
        val query=EditText(host).apply { hint="Песня или исполнитель";isSingleLine=true;setTextColor(Color.WHITE);setHintTextColor(Color.GRAY) }
        search.addView(query,LinearLayout.LayoutParams(0,-2,1f))
        search.addView(button("Найти") {
            val q=query.text.toString().trim()
            if(q.isBlank()) { status.text="Введи поисковый запрос" } else { operation="search";playlistId=q;load(0,false) }
        });root.addView(search)
        list=ListView(host).apply { adapter=this@OnlineSection.adapter }
        list.setOnItemClickListener { _,_,position,_->
            if(busy)return@setOnItemClickListener
            val row=rows.optJSONObject(position) ?: return@setOnItemClickListener
            if(playlistMode) choose("playlistPage",row.optString("id"))
            else if(!row.optBoolean("available",true)) status.text="Этот трек недоступен в сервисе"
            else {
                val snapshot=JSONArray(rows.toString());val id=generation
                busy=true;update()
                worker.execute {
                    val result=runCatching { store.saveQueue(snapshot) }
                    main.post {
                        if(closed || id!=generation)return@post
                        busy=false;update()
                        result.onSuccess { play(it,position);screen.dismiss() }.onFailure { status.text="Не удалось подготовить очередь" }
                    }
                }
            }
        }
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        more=button("Показать ещё") { next?.let { load(it,false) } };root.addView(more)
        screen.setContentView(root)
        screen.setOnDismissListener { generation++;dialog=null;busy=false }
        screen.show();screen.window?.setLayout(-1,-1)
        checkStatus()
    }
    private fun update() {
        actions.forEach { it.isEnabled=!busy }
        progress.visibility=if(busy)View.VISIBLE else View.GONE
        more.visibility=if(next!=null && !playlistMode)View.VISIBLE else View.GONE
        login.text=if(connected) "Аккаунт" else "Войти в Яндекс"
    }
    private fun checkStatus() {
        val id=generation;busy=true;update();status.text="Проверяем сохранённый вход…"
        worker.execute {
            val result=runCatching {
                val value=extension.request("status")
                store.setSession(value.optString("sessionKey"))
                value
            }
            main.post {
                if(closed || id!=generation)return@post
                busy=false
                result.onSuccess {
                    connected=it.optBoolean("connected");update()
                    if(connected) choose("collectionPage","") else { rows=JSONArray();adapter.notifyDataSetChanged();status.text="Войди один раз, чтобы открыть свою коллекцию" }
                }.onFailure { update();status.text=it.message?.take(200) ?: "Расширение не отвечает" }
            }
        }
    }
    private fun choose(op:String,arg:String) {
        operation=op;playlistId=arg;next=null
        val cacheKey=if(op=="collectionPage") "likes" else "pl-$arg"
        val id=generation;busy=true;update()
        worker.execute {
            val cached=store.read(cacheKey)
            main.post {
                if(closed || id!=generation)return@post
                busy=false;update()
                if(cached!=null) apply(cached,false,true) else load(0,false)
            }
        }
    }
    private fun load(offset:Int,refresh:Boolean) {
        if(busy)return
        val id=generation;val op=operation;val arg=playlistId;val prior=JSONArray(rows.toString())
        busy=true;update();status.text="Загрузка…"
        worker.execute {
            val result=runCatching {
                val param=when(op) {
                    "search" -> arg
                    "collectionPage", "playlistPage" -> JSONObject().put("offset",offset).put("refresh",refresh).put("id",arg).toString()
                    else -> ""
                }
                val data=extension.request(op,param)
                if(op=="collectionPage" || op=="playlistPage") {
                    val combined=if(offset>0) merge(prior,data.getJSONArray("tracks")) else data.getJSONArray("tracks")
                    val cache=JSONObject(data.toString()).put("tracks",combined)
                    store.write(if(op=="collectionPage")"likes" else "pl-$arg",cache)
                }
                data
            }
            main.post {
                if(closed || id!=generation)return@post
                busy=false;update()
                result.onSuccess { apply(it,offset>0,false) }.onFailure {
                    status.text=(it.message?.take(200) ?: "Не удалось загрузить")+if(rows.length()>0) " · сохранённый список доступен" else ""
                }
            }
        }
    }
    private fun apply(data:JSONObject,append:Boolean,cached:Boolean) {
        playlistMode=data.has("playlists")
        val received=data.optJSONArray(if(playlistMode)"playlists" else "tracks") ?: JSONArray()
        rows=if(append)merge(rows,received) else received
        next=if(data.isNull("next") || !data.has("next"))null else data.optInt("next").takeIf { it>=0 }
        total=data.optInt("total",rows.length())
        adapter.notifyDataSetChanged();update()
        if(!append)list.setSelection(0)
        status.text=if(playlistMode) "${rows.length()} плейлистов · чтение" else "Загружено ${rows.length()} из $total"+
            if(cached) " · сохранено на телефоне" else ""
    }
    private fun openLogin(now:Boolean) {
        returning=true;dialog?.dismiss()
        runCatching { host.startActivity(Intent().setComponent(ComponentName(onlinePackage(host),"dev.alex.afponline.YandexAccountActivity")).putExtra("loginNow",now)) }
            .onFailure { returning=false;Toast.makeText(host,"Не удалось открыть вход",Toast.LENGTH_LONG).show() }
    }
    fun resume() { if(returning && !closed) { returning=false;show() } }
    fun close() { closed=true;dialog?.dismiss();worker.shutdownNow();covers.shutdownNow();main.removeCallbacksAndMessages(null) }
    companion object {
        fun merge(old:JSONArray,more:JSONArray):JSONArray {
            val result=JSONArray()
            for(array in listOf(old,more)) for(i in 0 until array.length()) {
                array.optJSONObject(i)?.let { result.put(it) }
            }
            return result
        }
    }
}
