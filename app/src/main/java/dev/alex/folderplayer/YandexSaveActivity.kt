package dev.alex.folderplayer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException

/** User-selected destination; no broad storage permission, automatic export, or credentials in output. */
class YandexSaveActivity:Activity() {
    private val worker=Executors.newSingleThreadExecutor()
    @Volatile private var cancelled=false
    @Volatile private var closed=false
    @Volatile private var connection:HttpURLConnection?=null
    private var destination:Uri?=null
    @Volatile private var completed=false
    private var missingCover=false
    private var coverEmbedded=false
    private lateinit var status:TextView
    private lateinit var progress:ProgressBar
    private lateinit var cancel:Button
    private val mediaId get()=intent.getStringExtra("mediaId").orEmpty()
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24);setBackgroundColor(PlayerScreens.bg) }
        root.addView(TextView(this).apply { text="Сохранить MP3 · эксперимент";textSize=22f;setTextColor(android.graphics.Color.WHITE) })
        status=TextView(this).apply { text=intent.getStringExtra("title");setTextColor(android.graphics.Color.WHITE) };root.addView(status)
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100;isIndeterminate=true };root.addView(progress)
        cancel=Button(this).apply { text="Отменить";setOnClickListener { stop();status.text="Отменяем сохранение…";isEnabled=false } };root.addView(cancel)
        SystemBarInsets.apply(root);setContentView(root)
        destination=state?.getString("destination")?.let(Uri::parse);completed=state?.getBoolean("completed") ?: false
        if(destination!=null) { if(!completed)deleteDestination();finish();return }
        if(OnlinePlayback.trackId(Uri.parse(mediaId))==null) { finish();return }
        if(state?.getBoolean("picker")==true)return
        val filename=intent.getStringExtra("filename").orEmpty().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"),"_").trim().trimEnd('.').take(150).ifBlank { "Яндекс Музыка" }+".mp3"
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/mpeg").putExtra(Intent.EXTRA_TITLE,filename),1)
        } catch(_:Exception) { fail("Не удалось открыть выбор места сохранения") }
    }
    override fun onSaveInstanceState(out:Bundle) {
        out.putBoolean("picker",true);out.putString("destination",destination?.toString());out.putBoolean("completed",completed);super.onSaveInstanceState(out)
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=1)return
        val uri=data?.data
        if(resultCode!=RESULT_OK || uri==null) { finish();return }
        destination=uri;window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text="Получаем полный MP3…"
        worker.execute {
            var temp:File?=null
            val result=runCatching {
                val settings=OnlineSettings(this);settings.requireNetwork();checkActive()
                val audio=OnlinePlayback(this).resolve(Uri.parse(mediaId));checkActive()
                val c=URL(audio.toString()).openConnection() as HttpURLConnection;connection=c
                try {
                    c.instanceFollowRedirects=false;c.connectTimeout=10000;c.readTimeout=15000
                    c.setRequestProperty("Accept-Encoding","identity");c.setRequestProperty("User-Agent","aFolderPlayer-MP3-Export/0.1")
                    require(c.responseCode==200) { "Сервис не предоставил файл (HTTP ${c.responseCode})" }
                    temp=File.createTempFile("ym-export-",".part",cacheDir)
                    var last=0L
                    c.inputStream.use { input ->
                        YandexMp3Transfer.copy(input,temp!!,c.contentLengthLong,{ !cancelled && !closed },{ settings.requireNetwork() }) { n,total ->
                            val now=android.os.SystemClock.elapsedRealtime()
                            if(now-last>250) { last=now;runOnUiThread { if(!closed) {
                                progress.isIndeterminate=total<=0;if(total>0)progress.progress=(n*100/total).toInt()
                                status.text="Загружено %.1f МБ%s".format(n/1_000_000.0,if(total>0)" из %.1f".format(total/1_000_000.0) else "")
                            } } }
                        }
                    }
                } finally { c.disconnect();connection=null }
                checkActive()
                runOnUiThread { if(!closed) { status.text="Добавляем обложку и теги…";progress.isIndeterminate=true } }
                val coverUrl=intent.getStringExtra("cover").orEmpty()
                val bitmap=if(coverUrl.isBlank())null else OnlineArtwork(this).load(coverUrl)
                val cover=bitmap?.let { image -> java.io.ByteArrayOutputStream().use { bytes -> image.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,bytes);bytes.toByteArray() } }
                bitmap?.recycle();checkActive()
                val tagged=File.createTempFile("ym-tags-",".part",cacheDir)
                try {
                    val hasCover=Mp3Tags.write(temp!!,tagged,intent.getStringExtra("title").orEmpty(),intent.getStringExtra("artist").orEmpty(),cover) { !cancelled && !closed }
                    coverEmbedded=hasCover;missingCover=coverUrl.isNotBlank() && !hasCover
                    temp!!.delete();temp=tagged
                } catch(e:Exception) { tagged.delete();throw e }
                runOnUiThread { if(!closed)status.text="Записываем файл в выбранное место…" }
                contentResolver.openOutputStream(uri,"wt")?.use { output ->
                    temp!!.inputStream().use { input ->
                        val bytes=ByteArray(32768)
                        while(true) { checkActive();val n=input.read(bytes);if(n<0)break;output.write(bytes,0,n) }
                    }
                } ?: error("Не удалось записать файл в выбранное место")
                checkActive()
            }
            temp?.delete()
            if(result.isFailure)deleteDestination()
            else completed=true
            runOnUiThread {
                if(!closed) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    if(result.isSuccess) { Toast.makeText(this,if(missingCover)"MP3 сохранён с тегами, но обложку загрузить не удалось" else if(coverEmbedded)"MP3 сохранён с тегами и обложкой" else "MP3 сохранён с тегами",Toast.LENGTH_LONG).show();finish() }
                    else if(cancelled) { Toast.makeText(this,"Сохранение отменено",Toast.LENGTH_SHORT).show();finish() }
                    else {
                        val e=result.exceptionOrNull()
                        fail(when(e) {
                            is OnlinePlayback.Problem,is IllegalArgumentException -> e.message?.take(180) ?: "Не удалось сохранить MP3"
                            else -> "Ошибка сети или записи файла. Попробуй сохранить трек заново"
                        })
                    }
                }
            }
        }
    }
    private fun checkActive() { if(cancelled || closed || Thread.currentThread().isInterrupted)throw CancellationException("Сохранение отменено") }
    private fun deleteDestination() { destination?.let { runCatching { DocumentsContract.deleteDocument(contentResolver,it) } } }
    private fun fail(message:String) { status.text=message;progress.visibility=android.view.View.GONE;cancel.isEnabled=true;cancel.text="Закрыть";cancel.setOnClickListener { finish() } }
    private fun stop() { cancelled=true;connection?.disconnect() }
    override fun onDestroy() { closed=true;stop();worker.shutdown();super.onDestroy() }
    companion object {
        fun open(host:Activity,mediaId:String,title:String,artist:String="",cover:String="") {
            if(OnlinePlayback.trackId(Uri.parse(mediaId))==null)return
            val id=OnlinePlayback.trackId(Uri.parse(mediaId))!!
            val rows=if(cover.isBlank())OnlineLibraryStore(host).read("playing")?.optJSONArray("tracks") else null
            val cached=(0 until (rows?.length() ?: 0)).firstNotNullOfOrNull { i -> rows?.optJSONObject(i)?.takeIf { it.optString("id")==id } }
            val artwork=cover.ifBlank { cached?.optString("cover").orEmpty() }
            host.startActivity(Intent(host,YandexSaveActivity::class.java).putExtra("mediaId",mediaId).putExtra("title",title).putExtra("artist",artist).putExtra("cover",artwork)
                .putExtra("filename",listOf(artist,title).filter { it.isNotBlank() }.joinToString(" — ")))
        }
    }
}
