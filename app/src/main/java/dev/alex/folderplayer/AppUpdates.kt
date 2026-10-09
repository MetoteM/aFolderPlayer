package dev.alex.folderplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONArray
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Explicit checks only. GitHub integrity plus the installed APK signing identity. */
internal class AppUpdates(private val host:Activity) {
 companion object {
  const val RELEASES="https://api.github.com/repos/MetoteM/aFolderPlayer/releases?per_page=100"
  private const val ROOT="https://github.com/MetoteM/aFolderPlayer/releases/download/"
  private const val MAX_APK=60_000_000L
  data class Release(val version:String,val url:URI,val size:Long,val hash:String,val notes:String)
  fun version(value:String):List<Int>? {
   if(!value.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))return null
   return value.split('.').map { it.toIntOrNull() ?: return null }
  }
  fun compare(a:List<Int>,b:List<Int>):Int { for(i in 0..2) { val c=a[i].compareTo(b[i]);if(c!=0)return c };return 0 }
  @Suppress("DEPRECATION") internal fun validateInfo(apk:PackageInfo,own:PackageInfo,expectedPackage:String,expectedVersion:String) {
  require(apk.packageName==expectedPackage && apk.versionName==expectedVersion) { "APK не соответствует обновлению плеера" }
  require(!apk.signatures.isNullOrEmpty() && apk.signatures?.toSet()==own.signatures?.toSet()) { "Подпись APK не совпадает. Установка отменена." }
  val newer=if(android.os.Build.VERSION.SDK_INT>=28)apk.longVersionCode>own.longVersionCode else apk.versionCode>own.versionCode
  require(newer) { "APK не новее установленного приложения" }
  }
  fun candidate(bytes:ByteArray,current:String):Release? {
   val installed=version(current) ?: error("Не удалось определить установленную версию")
   val releases=JSONArray(String(bytes,Charsets.UTF_8));var best:Release?=null
   for(i in 0 until releases.length()) {
    val r=releases.getJSONObject(i)
    if(r.optBoolean("draft") || r.optBoolean("prerelease") || (r.isNull("published_at") || r.optString("published_at").isBlank()))continue
    val tag=r.optString("tag_name");if(!tag.startsWith("v"))continue
    val name=tag.removePrefix("v");val v=version(name) ?: continue
    if(compare(v,installed)<=0 || best?.let { compare(v,version(it.version)!!)<=0 }==true)continue
    val assets=r.optJSONArray("assets") ?: continue
    for(j in 0 until assets.length()) {
     val a=assets.getJSONObject(j);val filename="aFolderPlayer_${name}.apk"
     val url=ROOT+tag+"/"+filename;val hash=a.optString("digest").removePrefix("sha256:")
     if(a.optString("name")!=filename || a.optString("browser_download_url")!=url || a.optString("state")!="uploaded")continue
     val size=a.optLong("size");if(size !in 1..MAX_APK || !a.optString("digest").startsWith("sha256:") || !hash.matches(Regex("[0-9a-f]{64}")))continue
     best=Release(name,URI(url),size,hash,r.optString("body").take(3000));break
    }
   }
   return best
  }
 }
 private val main=Handler(Looper.getMainLooper())
 private val worker=Executors.newSingleThreadExecutor()
 private val prefs=host.getSharedPreferences("updates",0)
 private val folder=File(host.filesDir,"extensions").apply { mkdirs() }
 @Volatile private var closed=false
 @Volatile private var cancelled=false
 @Volatile private var connection:java.net.HttpURLConnection?=null
 private var busy:AlertDialog?=null
 @Suppress("DEPRECATION") fun installedVersion()=host.packageManager.getPackageInfo(host.packageName,0).versionName.orEmpty()
 private fun run(title:String,action:()->Unit) {
  if(closed || busy!=null)return
  cancelled=false
  busy=AlertDialog.Builder(host).setMessage(title).setNegativeButton("Отмена") { _,_ -> cancel() }.create().apply { setOnCancelListener { cancel() };show() }
  worker.execute {
   val result=runCatching(action)
   main.post { busy?.dismiss();busy=null;if(!closed && !cancelled)result.onFailure { showError(it) } }
  }
 }
 private fun cancel() { cancelled=true;connection?.disconnect() }
 private fun active() { check(!closed && !cancelled) { "Отменено" } }
 private fun showError(error:Throwable) {
  AlertDialog.Builder(host).setTitle("Обновление").setMessage(if(error is java.io.IOException) "Не удалось соединиться с GitHub. Проверь подключение или попробуй позже." else error.message ?: "Не удалось проверить обновление. Попробуй позже.").setPositiveButton("Закрыть",null).show()
 }
 fun check() = run("Проверяем обновления…") {
  val c=ExtensionHttp.connect(URI(RELEASES));connection=c
  try {
   active();require(c.responseCode==200) { if(c.responseCode==403 || c.responseCode==429)"GitHub ограничил запросы. Попробуй позже." else "GitHub недоступен (${c.responseCode}). Попробуй позже." }
   val release=c.inputStream.use { candidate(ExtensionCatalog.read(it,2_000_000),installedVersion()) }
   main.post { if(!closed && !cancelled) {
    if(release==null)AlertDialog.Builder(host).setTitle("Обновление").setMessage("Новых стабильных версий нет. Установлена ${installedVersion()}.").setPositiveButton("Закрыть",null).show()
    else AlertDialog.Builder(host).setTitle("Доступна ${release.version}").setMessage("Установлена ${installedVersion()}\nAPK · ${"%.1f".format(release.size/1_000_000.0)} МБ"+if(release.notes.isBlank())"" else "\n\n${release.notes}").setPositiveButton("Скачать") { _,_ -> download(release) }.setNegativeButton("Позже",null).show()
   } }
  } finally { connection=null;c.disconnect() }
 }
 private fun download(release:Release)=run("Скачиваем ${release.version}…") {
  val part=File(folder,"player-update.apk.part");val file=File(folder,"player-update.apk")
  val c=ExtensionHttp.connect(release.url);connection=c
  try {
   require(c.responseCode==200) { "Не удалось скачать обновление (${c.responseCode})" }
   var size=0L;val digest=MessageDigest.getInstance("SHA-256")
   c.inputStream.use { input -> part.outputStream().use { output -> val buffer=ByteArray(65536);while(true) { active();val n=input.read(buffer);if(n<0)break;size+=n;require(size<=release.size) { "Размер APK не совпадает" };output.write(buffer,0,n);digest.update(buffer,0,n) } } }
   require(size==release.size && digest.digest().joinToString("") { "%02x".format(it) }==release.hash) { "APK повреждён. Установка отменена." }
   validate(part,release.version);active();check(part.renameTo(file)) { "Не удалось сохранить APK" }
   main.post { if(!closed && !cancelled)install() }
  } finally { connection=null;c.disconnect();part.delete() }
 }
 @Suppress("DEPRECATION") internal fun validate(file:File,expectedVersion:String) {
  val pm=host.packageManager
  val apk=pm.getPackageArchiveInfo(file.absolutePath,PackageManager.GET_SIGNATURES) ?: error("Не удалось прочитать APK обновления")
  val own=pm.getPackageInfo(host.packageName,PackageManager.GET_SIGNATURES)
  validateInfo(apk,own,host.packageName,expectedVersion)
 }

 private fun install() {
  if(!host.packageManager.canRequestPackageInstalls()) {
   AlertDialog.Builder(host).setTitle("Установка обновления").setMessage("Разреши aFolderPlayer устанавливать APK. После возврата откроется установка скачанного обновления.").setPositiveButton("Разрешить") { _,_ -> prefs.edit().putBoolean("pendingInstall",true).apply();host.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${host.packageName}"))) }.setNegativeButton("Позже",null).show();return
  }
  host.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://dev.alex.folderplayer.extensions/player-update.apk"),"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
 }
 fun resume() {
  if(!prefs.getBoolean("pendingInstall",false))return
  prefs.edit().remove("pendingInstall").apply()
  val file=File(folder,"player-update.apk")
  if(file.isFile && host.packageManager.canRequestPackageInstalls()) {
   @Suppress("DEPRECATION") val version=host.packageManager.getPackageArchiveInfo(file.absolutePath,0)?.versionName ?: return
   runCatching { validate(file,version);install() }.onFailure { file.delete();showError(it) }
  }
 }
 fun close() { closed=true;cancel();busy?.dismiss();worker.shutdownNow();main.removeCallbacksAndMessages(null) }
}
