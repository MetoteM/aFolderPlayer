package dev.alex.folderplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.media3.common.Player

/** Local UI only: playback remains owned by the media service. */
internal class PlayerScreens(private val context:Context, private val actions:Actions) {
 interface Actions {
  fun back(); fun open(page:String); fun toggle(); fun previous(); fun next(); fun skip(direction:Int)
  fun seek(progress:Int); fun favorite(); fun playlists(); fun repeat(); fun sound()
  fun lyricsMenu(); fun select(index:Int); fun move(from:Int,to:Int)
  fun installTranslationModel() {};fun retryTranslation() {};
  fun retryLyrics() {};fun chooseLyrics(index:Int) {}
 }
 data class State(val id:String?,val title:String,val artist:String,val album:String,val source:String,
  val playing:Boolean,val favorite:Boolean,val repeat:Int,val position:Long,val duration:Long,val step:Int)
 data class Entry(val id:String,val title:String,val artist:String,val duration:Long)
 val view=SheetSurface(context) { actions.back() }.apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(bg);isClickable=true }
 private var headerTitle:TextView?=null
 private var retryButton:Button?=null
 private var resultPanel:LinearLayout?=null
 private var translationStatus:TextView?=null;private var translationText:TextView?=null;private var translationButton:Button?=null
 private var translatedId:String?=null;private var translatedValue="";private var translatedMessage="";private var translatedAction=0
 private var lyricError:String?=null
 private var lyricBusy=false
 private var lyricResults=emptyList<LyricsSearch.Result>()
 private var page="player"
 private var state:State?=null
 private var title:TextView?=null;private var artist:TextView?=null;private var album:TextView?=null
 private var source:TextView?=null;private var cover:ImageView?=null;private var heart:Button?=null
 private var play:Button?=null;private var repeat:Button?=null
 private var backward:Button?=null;private var forward:Button?=null;private var seek:SeekBar?=null
 private var times:TextView?=null;private var totalTime:TextView?=null;private var dragging=false
 private var content:LinearLayout?=null;private var scroll:ScrollView?=null
 private var lyricStatus:TextView?=null;private var lyricText:TextView?=null
 private var doc:LyricsDocument?=null;private var lyricId:String?=null;private var active=Int.MIN_VALUE
 private var autoToggle:Switch?=null
 private var offsets=emptyList<Int>();private var auto=true
 private var queue=emptyList<Entry>();private var queueKey="";private var queueRows=mutableListOf<Pair<String,TextView>>()
 private var artwork:Bitmap?=null;private var artworkId:String?=null
 private var moving=false
 fun show(value:String) {
  view.resetGesture();page=value;translationStatus=null;translationText=null;translationButton=null
  view.horizontal=if(page=="lyrics" || page=="translation")({ left:Boolean -> if(left && page=="lyrics")actions.open("translation") else if(!left && page=="translation")actions.open("lyrics") }) else null
  autoToggle=null;view.removeAllViews();headerTitle=null;retryButton=null;resultPanel=null;title=null;artist=null;album=null;source=null;cover=null;heart=null;play=null;repeat=null;backward=null;forward=null;seek=null;times=null;totalTime=null;content=null;scroll=null;lyricStatus=null;lyricText=null;queueRows.clear();queueKey="";active=Int.MIN_VALUE
  val header=row();header.addView(button("‹","Назад") { actions.back() },LinearLayout.LayoutParams(dp(52),dp(52)))
  headerTitle=text(when(page){"lyrics"->"Текст песни";"translation"->"Перевод";"queue"->"Очередь";"sound"->"Звук";else->"Сейчас играет"},18f).apply { gravity=Gravity.CENTER;maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END }
  UiGestures.swipe(headerTitle!!,false) { actions.back() };header.addView(headerTitle,weight())
  header.addView(button("⋮","Меню песни") { actions.lyricsMenu() },LinearLayout.LayoutParams(dp(52),dp(52)));view.addView(header)
  scroll=ScrollView(context).apply { isFillViewport=true }
  content=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(16)) }
  scroll!!.addView(content);view.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
  if(page=="lyrics" || page=="translation") {
   val tabs=row();tabs.addView(button("Оригинал","Открыть оригинальный текст") { actions.open("lyrics") }.apply { setTextColor(if(page=="lyrics")accent else muted) },weight());tabs.addView(button("Перевод","Открыть машинный перевод") { actions.open("translation") }.apply { setTextColor(if(page=="translation")accent else muted) },weight());content!!.addView(tabs)
  }
  when(page) {
   "translation" -> {
    title=text("",18f);content!!.addView(title)
    translationStatus=text("Готовим перевод…",13f).apply { setTextColor(muted) };content!!.addView(translationStatus)
    translationText=text("",22f).apply { setLineSpacing(dp(12).toFloat(),1f);setTextIsSelectable(true) };content!!.addView(translationText)
    translationButton=button("Расширения перевода","Установить офлайн-пакет английский → русский") { if(translatedAction==1)actions.installTranslationModel() else actions.retryTranslation() };content!!.addView(translationButton)
    mini();setTranslation(translatedId,translatedValue,translatedMessage,translatedAction)
   }
   "lyrics" -> {
    title=text("",18f);content!!.addView(title)
    lyricStatus=text("Читаем локальный текст…",13f);content!!.addView(lyricStatus)
    lyricText=text("",22f).apply { setLineSpacing(dp(12).toFloat(),1f);setTextIsSelectable(true);setTextColor(muted) };content!!.addView(lyricText)
    val toggle=Switch(context).apply { text="Автопрокрутка";setTextColor(Color.WHITE);isChecked=auto;setPadding(dp(12),dp(8),dp(12),dp(8));setOnCheckedChangeListener { _,yes -> auto=yes;active=Int.MIN_VALUE } }
    retryButton=button("Найти текст","Найти или повторить поиск текста") { actions.retryLyrics() };content!!.addView(retryButton)
    resultPanel=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL };content!!.addView(resultPanel)
    content!!.setOnClickListener { if(doc==null && !lyricBusy)actions.retryLyrics() }
    autoToggle=toggle;view.addView(toggle);mini();setLyrics(lyricId,doc,lyricError);renderLyricsResults()
   }
   "queue" -> { source=text("",14f);content!!.addView(source);mini() }
   "sound" -> { soundPanel(content!!);mini() }
   else -> {
    cover=SquareArtwork(context).apply { scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="Локальная обложка целиком";background=shape(surface);clipToOutline=true;setPadding(dp(6),dp(6),dp(6),dp(6)) }
    content!!.addView(cover,LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(12),dp(4),dp(12),dp(12)) })
    val names=row();title=text("",23f).apply { setTypeface(null,Typeface.BOLD) };names.addView(title,weight())
    heart=button("♡","Добавить в избранное") { actions.favorite() }.apply { setOnLongClickListener { actions.playlists();true } };names.addView(heart,LinearLayout.LayoutParams(dp(56),dp(56)));content!!.addView(names)
    artist=text("",16f);album=text("",14f);content!!.addView(artist);content!!.addView(album)
    progress(content!!)
    val controls=row();backward=button("−10","Назад на несколько секунд") { actions.skip(-1) };controls.addView(backward,weight())
    controls.addView(button("⏮","Предыдущая песня") { actions.previous() },weight());play=button("▶","Воспроизведение / пауза",true) { actions.toggle() };controls.addView(play,LinearLayout.LayoutParams(dp(76),dp(76)))
    controls.addView(button("⏭","Следующая песня") { actions.next() },weight());forward=button("+10","Вперёд на несколько секунд") { actions.skip(1) };controls.addView(forward,weight());content!!.addView(controls)
    val links=row();links.addView(button("Текст","Открыть текст песни") { actions.open("lyrics") },weight());links.addView(button("Очередь","Открыть очередь") { actions.open("queue") },weight());links.addView(button("Звук","Эквалайзер") { actions.sound() },weight());repeat=button("Повтор: выкл","Режим повтора") { actions.repeat() };links.addView(repeat,weight());content!!.addView(links)

   }
  }
  state?.let { update(it) };setArtwork(artworkId,artwork)
 }
 private fun mini() {
  val panel=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;background=shape(surface);setPadding(dp(8),dp(4),dp(8),dp(4)) }
  val r=row();cover=ImageView(context).apply { scaleType=ImageView.ScaleType.FIT_CENTER;background=shape(surface);clipToOutline=true;contentDescription="Обложка текущей песни" };r.addView(cover,LinearLayout.LayoutParams(dp(44),dp(44)));val caption=text("",15f);artist=caption;r.addView(caption,weight())
  play=button("▶","Воспроизведение / пауза") { actions.toggle() };r.addView(play,LinearLayout.LayoutParams(dp(56),dp(56)));r.addView(button("⏭","Следующая песня") { actions.next() },LinearLayout.LayoutParams(dp(56),dp(56)));panel.addView(r);progress(panel);UiGestures.swipe(panel,true) { actions.open("player") };view.addView(panel)
 }
 private fun progress(parent:LinearLayout) {
  seek=SeekBar(context).apply { max=1000;progressTintList=android.content.res.ColorStateList.valueOf(accent);thumbTintList=android.content.res.ColorStateList.valueOf(accent);contentDescription="Позиция воспроизведения" }
  seek!!.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
   override fun onStartTrackingTouch(bar:SeekBar) { dragging=true }
   override fun onStopTrackingTouch(bar:SeekBar) { actions.seek(bar.progress);dragging=false }
   override fun onProgressChanged(bar:SeekBar,progress:Int,user:Boolean) {}
  });parent.addView(seek);val line=row();times=text("",12f);totalTime=text("",12f).apply { gravity=Gravity.END };line.addView(times,weight());line.addView(totalTime,weight());parent.addView(line)
 }
 fun update(s:State) {
  val changed=state?.id!=s.id;state=s
  title?.text=s.title;artist?.text=if(page=="player")s.artist else "${s.title}\n${s.artist}"
  if(page=="player")headerTitle?.text="Сейчас играет — ${s.source}"
  album?.text=s.album;source?.text=if(page=="queue")"${s.source} · ${queue.size} треков" else "Плейлист · ${s.source}"
  play?.text=if(s.playing)"❚❚" else "▶";heart?.text=if(s.favorite)"♥" else "♡";heart?.setTextColor(if(s.favorite)accent else Color.WHITE)
  backward?.text="−${s.step}";forward?.text="+${s.step}"
  repeat?.text=when(s.repeat){Player.REPEAT_MODE_ONE->"Повтор: 1";Player.REPEAT_MODE_ALL->"Повтор: все";else->"Повтор: выкл"}
  repeat?.setTextColor(if(s.repeat==Player.REPEAT_MODE_OFF)muted else accent)
  times?.text=clock(s.position);totalTime?.text=clock(s.duration)
  if(!dragging)seek?.progress=if(s.duration>0)(s.position*1000/s.duration).toInt().coerceIn(0,1000) else 0
  queueRows.forEach { (id,label) -> label.setTextColor(if(id==s.id)accent else Color.WHITE);label.setTypeface(null,if(id==s.id)Typeface.BOLD else Typeface.NORMAL);(label.parent as View).background=shape(if(id==s.id)Color.rgb(20,52,53) else bg) }
  if(changed && page=="translation") { translationText?.text="";translationStatus?.text="Готовим перевод…";translationButton?.visibility=View.GONE }
  if(changed && artworkId!=s.id)cover?.setImageDrawable(NoteArtwork())
  updateLyrics(s)
 }
 fun setTranslation(id:String?,value:String,message:String,action:Int=0) {
  translatedId=id;translatedValue=value;translatedMessage=message;translatedAction=action
  if(page!="translation")return
  translationText?.text=if(id==state?.id)value else ""
  translationStatus?.text=if(id==state?.id)message else "Готовим перевод…"
  translationButton?.visibility=if(id==state?.id && action!=0)View.VISIBLE else View.GONE
  translationButton?.text=if(action==1)"Расширения перевода" else "Повторить перевод"
 }
 fun setArtwork(id:String?,bitmap:Bitmap?) { artworkId=id;artwork=bitmap;if(id==state?.id) { if(bitmap!=null)cover?.setImageBitmap(bitmap) else cover?.setImageDrawable(NoteArtwork()) } }
 fun setLyrics(id:String?,document:LyricsDocument?,error:String?) {
  if(lyricId!=id || document!=null)lyricResults=emptyList()
  autoToggle?.visibility=if(document!=null && document.cues.isNotEmpty() && id==state?.id)View.VISIBLE else View.GONE
  lyricId=id;doc=document;lyricError=error;active=Int.MIN_VALUE
  offsets=buildList { var count=0;document?.cues?.forEach { add(count);count+=it.text.length+1 } }
  lyricStatus?.text=when { error!=null->error;document==null->"Текста пока нет. Нажми, чтобы найти, или добавь свой через ⋮.";document.cues.isEmpty()->"Сохранён на устройстве · обычный текст";else->"Сохранён на устройстве · LRC" }
  lyricText?.text=document?.plain.orEmpty();retryButton?.visibility=if(document==null)View.VISIBLE else View.GONE;retryButton?.isEnabled=!lyricBusy;renderLyricsResults();state?.let { updateLyrics(it) }
 }
 fun lyricsSearching(id:String,busy:Boolean,message:String) {
  lyricBusy=busy;setLyrics(id,null,message);retryButton?.text=if(busy)"Ищем…" else "Повторить поиск";retryButton?.isEnabled=!busy
 }
 fun setLyricsResults(id:String,results:List<LyricsSearch.Result>) {
  lyricId=id;lyricResults=results;lyricBusy=false;lyricError="Выбери подходящую запись — текст сохранится на устройстве";lyricStatus?.text=lyricError;renderLyricsResults();retryButton?.isEnabled=true
 }
 private fun renderLyricsResults() {
  val panel=resultPanel ?: return;panel.removeAllViews()
  if(lyricId!=state?.id)return
  lyricResults.forEachIndexed { index,item -> panel.addView(button("${item.title} — ${item.artist}\n${item.album} · ${clock((item.duration*1000).toLong())}"+(if(item.timed)" · LRC" else ""),"Сохранить выбранный текст") { actions.chooseLyrics(index) },LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,dp(4),0,dp(4)) }) }
 }
 private fun soundPanel(panel:LinearLayout) {
  val prefs=context.getSharedPreferences("playback",Context.MODE_PRIVATE)
  panel.addView(Switch(context).apply { text="Эквалайзер";setTextColor(Color.WHITE);setPadding(dp(8),dp(8),dp(8),dp(8));isChecked=prefs.getBoolean("eqEnabled",false);setOnCheckedChangeListener { _,yes -> prefs.edit().putBoolean("eqEnabled",yes).apply() } })
  panel.addView(text("Ноль — без изменения звука. При искажениях уменьши предусиление.",14f).apply { setTextColor(muted) })
  val sliders=mutableListOf<SeekBar>()
  fun band(caption:String,key:String,min:Int,max:Int) {
   val label=text("",15f);val bar=SeekBar(context).apply { this.max=max-min;progress=prefs.getInt(key,0).coerceIn(min,max)-min;progressTintList=android.content.res.ColorStateList.valueOf(accent);thumbTintList=android.content.res.ColorStateList.valueOf(accent);contentDescription=caption }
   fun update(value:Int) { label.text=caption+" · "+(if(value>0)"+" else "")+value+" дБ" }
   update(bar.progress+min);panel.addView(label);panel.addView(bar);sliders.add(bar)
   bar.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
    override fun onStartTrackingTouch(bar:SeekBar){};override fun onStopTrackingTouch(bar:SeekBar) { prefs.edit().putInt(key,bar.progress+min).apply() };override fun onProgressChanged(bar:SeekBar,value:Int,user:Boolean){ update(value+min) }
   })
  }
  listOf("31 Гц","62 Гц","125 Гц","250 Гц","500 Гц","1 кГц","2 кГц","4 кГц","8 кГц","16 кГц").forEachIndexed { i,name -> band(name,"eqBand$i",-12,12) };band("Предусиление","eqPreamp",-12,0)
  panel.addView(button("Сбросить в ноль","Сбросить эквалайзер") { val edit=prefs.edit().putInt("eqPreamp",0);for(i in 0 until 10)edit.putInt("eqBand$i",0);edit.apply();sliders.forEach { it.progress=12 } },LinearLayout.LayoutParams(-1,dp(52)))
 }
 internal class SquareArtwork(context:Context):ImageView(context) {
  override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) { val side=View.MeasureSpec.getSize(widthMeasureSpec);super.onMeasure(View.MeasureSpec.makeMeasureSpec(side,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(side,View.MeasureSpec.EXACTLY)) }
 }
 private fun updateLyrics(s:State) {
  autoToggle?.visibility=if(page=="lyrics" && lyricId==s.id && doc?.cues?.isNotEmpty()==true)View.VISIBLE else View.GONE
  if(page!="lyrics" || lyricId!=s.id)return
  val d=doc ?: return;if(d.cues.isEmpty())return
  val index=d.activeIndex(s.position);if(index==active)return;active=index
  val value=android.text.SpannableString(d.plain)
  if(index>=0)for(i in d.groupStart(index)..index)if(d.cues[i].text.isNotEmpty()) {
   value.setSpan(android.text.style.ForegroundColorSpan(accent),offsets[i],offsets[i]+d.cues[i].text.length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
   value.setSpan(android.text.style.StyleSpan(Typeface.BOLD),offsets[i],offsets[i]+d.cues[i].text.length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
  }
  lyricText?.text=value
  if(auto && index>=0)lyricText?.post { if(page=="lyrics" && doc===d && state?.id==s.id && active==index)lyricText?.layout?.let { l -> scroll?.smoothScrollTo(0,(lyricText!!.top+l.getLineTop(l.getLineForOffset(offsets[d.groupStart(index)]))-(scroll?.height ?: 0)/3).coerceAtLeast(0)) } }
 }
 fun setQueue(entries:List<Entry>) {
  queue=entries;if(page!="queue" || moving)return
  val key=entries.joinToString("\n") { "${it.id}|${it.title}|${it.artist}" };if(key==queueKey)return;queueKey=key
  val panel=content ?: return;while(panel.childCount>1)panel.removeViewAt(1);queueRows.clear()
  entries.forEachIndexed { index,item ->
   val r=row();r.addView(text("♫",24f).apply { setTextColor(accent);gravity=Gravity.CENTER },LinearLayout.LayoutParams(dp(44),dp(60)));val label=text("${item.title}\n${item.artist} · ${clock(item.duration)}",16f);r.addView(label,weight());label.setOnClickListener { actions.select(index) };queueRows.add(item.id to label)
   val handle=button("☰","Переставить ${item.title}") { android.app.AlertDialog.Builder(context).setTitle(item.title).setItems(arrayOf("Выше","Ниже")) { _,which -> val target=index+if(which==0)-1 else 1;if(target in entries.indices)actions.move(index,target) }.show() }
   handle.setOnLongClickListener { moving=handle.startDragAndDrop(android.content.ClipData.newPlainText("queue-index",index.toString()),View.DragShadowBuilder(r),index,0);moving }
   r.setOnDragListener { _,event -> when(event.action){ android.view.DragEvent.ACTION_DRAG_STARTED-> event.localState is Int;android.view.DragEvent.ACTION_DROP->{ val from=event.localState as? Int;moving=false;if(from!=null && from!=index)actions.move(from,index);true };android.view.DragEvent.ACTION_DRAG_ENDED->{ moving=false;true };else->true } }
   r.addView(handle,LinearLayout.LayoutParams(dp(52),dp(64)));panel.addView(r)
  }
  if(entries.isEmpty())panel.addView(text("Очередь пуста. Включи песню из библиотеки или плейлиста.",16f))
  state?.let { update(it) }
 }
 private fun dp(n:Int)=(n*context.resources.displayMetrics.density).toInt()
 private fun weight()=LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(3),dp(4),dp(3),dp(4)) }
 private fun row()=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
 private fun text(value:String,size:Float)=TextView(context).apply { text=value;textSize=size;setTextColor(Color.WHITE);setPadding(dp(8),dp(8),dp(8),dp(8)) }
 private fun button(value:String,description:String,primary:Boolean=false,action:()->Unit)=Button(context).apply {
  text=value;textSize=if(primary)24f else 14f;isAllCaps=false;minWidth=0;minimumWidth=0;minHeight=dp(48);minimumHeight=dp(48);setPadding(dp(6),dp(4),dp(6),dp(4));setTextColor(if(primary)bg else Color.WHITE);background=shape(if(primary)accent else surface);backgroundTintList=null;contentDescription=description;setOnClickListener { action() }
 }
 private fun shape(color:Int)=GradientDrawable().apply { setColor(color);cornerRadius=dp(16).toFloat() }
 private fun clock(ms:Long):String { val s=ms.coerceAtLeast(0)/1000;return "%d:%02d".format(s/60,s%60) }
 private class NoteArtwork:android.graphics.drawable.Drawable() {
  private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
  override fun draw(canvas:android.graphics.Canvas) {
   paint.shader=android.graphics.LinearGradient(0f,0f,bounds.width().toFloat(),bounds.height().toFloat(),intArrayOf(Color.rgb(28,57,62),surface),null,android.graphics.Shader.TileMode.CLAMP);canvas.drawRect(bounds,paint);paint.shader=null
   paint.color=accent;paint.textSize=bounds.height()*0.36f;paint.textAlign=android.graphics.Paint.Align.CENTER
   canvas.drawText("♫",bounds.exactCenterX(),bounds.exactCenterY()-(paint.ascent()+paint.descent())/2,paint)
  }
  override fun setAlpha(alpha:Int) { paint.alpha=alpha };override fun setColorFilter(filter:android.graphics.ColorFilter?) { paint.colorFilter=filter }
  @Deprecated("Drawable opacity") override fun getOpacity()=android.graphics.PixelFormat.OPAQUE
 }
 companion object { val bg=Color.rgb(16,25,30);val surface=Color.rgb(27,39,45);val accent=Color.rgb(105,223,194);val muted=Color.rgb(165,177,185) }
}
