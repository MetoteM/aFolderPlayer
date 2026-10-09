package dev.alex.folderplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.widget.EditText
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

internal class ExtensionManager(private val host:Activity,private val engine:TranslationClient,private val message:(String)->Unit,private val importModel:()->Unit,private val changed:()->Unit={}) {
 companion object { const val DEFAULT_CATALOG="https://github.com/MetoteM/aFolderPlayer/releases/download/extensions-v1/" }
 private val worker=Executors.newSingleThreadExecutor()
 private val main=Handler(Looper.getMainLooper())
 private val prefs=host.getSharedPreferences("extensions",0)
 private val folder=File(host.filesDir,"extensions").apply { mkdirs() }
 @Volatile private var closed=false
 private var busy:AlertDialog?=null
 @Volatile private var cancelled=false
 @Volatile private var connection:java.net.HttpURLConnection?=null
 fun show() {
  val installed=engine.installed();val choices=mutableListOf<String>()
  choices.add("Машинный перевод: "+if(prefs.getBoolean("enabled",false))"включён" else "выключен")
  choices.add("Скачать расширения")
  choices.add("Источник загрузки")
  choices.add("Установить движок из APK-файла")
  if(installed) { choices.add("Установить языковой пакет из ZIP");choices.add("Языковой пакет: размер / удалить");choices.add("Удалить движок AFP Translate");if(File(host.filesDir,"translation/opus-en-ru-v1/verified").isFile)choices.add("Перенести модель из 0.4.0") }
  if(File(host.filesDir,"translation/opus-en-ru-v1").exists())choices.add("Удалить оставшуюся модель из 0.4.0")
  AlertDialog.Builder(host).setTitle(if(installed)"Расширения · движок установлен" else "Расширения · без движка").setItems(choices.toTypedArray()) { _,index ->
   when(choices[index]) {
    choices[0] -> { prefs.edit().putBoolean("enabled",!prefs.getBoolean("enabled",false)).apply();changed();show() }
    "Скачать расширения" -> fetchCatalog()
    "Источник загрузки" -> address()
    "Установить движок из APK-файла" -> host.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/vnd.android.package-archive").addCategory(Intent.CATEGORY_OPENABLE),104)
    "Установить языковой пакет из ZIP" -> importModel()
    "Языковой пакет: размер / удалить" -> run("Проверяем пакет…") {
     val bytes=engine.modelBytes();main.post { if(!closed)AlertDialog.Builder(host).setTitle(engine.modelTitle).setMessage(if(bytes<0)"Пакет не установлен" else "На устройстве: ${bytes/1_000_000} МБ").setPositiveButton("Закрыть",null).apply { if(bytes>=0)setNegativeButton("Удалить пакет") { _,_ -> run("Удаляем пакет…") { engine.removeModel();main.post { message("Пакет удалён. Сохранённые переводы остались") } } } }.show() }
    }
    "Перенести модель из 0.4.0" -> run("Переносим модель в AFP Translate…") {
     val old=File(host.filesDir,"translation/opus-en-ru-v1");val zip=File(folder,"migration.zip")
     try {
      java.util.zip.ZipOutputStream(zip.outputStream()).use { out -> out.setLevel(0);for(name in listOf("encoder.onnx","decoder.onnx","tokenizer.json")) { check(!cancelled) { "Отменено" };out.putNextEntry(java.util.zip.ZipEntry(name));File(old,name).inputStream().use { it.copyTo(out) };out.closeEntry() } }
      ParcelFileDescriptor.open(zip,ParcelFileDescriptor.MODE_READ_ONLY).use { engine.install(it) };enable()
      main.post { if(!closed) { changed();message("Модель перенесена. Старую копию можно удалить в расширениях") } }
     } finally { zip.delete() }
    }
    "Удалить движок AFP Translate" -> host.startActivity(Intent(Intent.ACTION_DELETE,Uri.parse("package:${TranslationClient.PACKAGE}")))
    else -> AlertDialog.Builder(host).setTitle("Модель из 0.4.0").setMessage("Удалить старую модель из плеера? Сохранённые переводы останутся. Для отдельного движка пакет устанавливается заново.").setPositiveButton("Удалить") { _,_ -> run("Удаляем старую модель…") { File(host.filesDir,"translation/opus-en-ru-v1").deleteRecursively();main.post { message("Старая модель удалена") } } }.setNegativeButton("Отмена",null).show()
   }
  }.setNegativeButton("Закрыть",null).show()
 }
 fun enabled()=prefs.getBoolean("enabled",false)
 fun enable() { prefs.edit().putBoolean("enabled",true).apply() }
 private fun address() {
  val field=EditText(host).apply { setText(prefs.getString("server",DEFAULT_CATALOG));hint="https://…/afp/";inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
  val dialog=AlertDialog.Builder(host).setTitle("Сервер расширений").setMessage("Собственный HTTPS-сервер или зеркало. Адрес сохраняется без подключения; запрос — только по кнопке загрузки.").setView(field).setPositiveButton("Сохранить",null).setNegativeButton("Отмена",null).create()
  dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
   runCatching { val value=field.text.toString().trim();if(value.isNotEmpty())ExtensionCatalog.base(value);prefs.edit().putString("server",value).apply();dialog.dismiss() }.onFailure { field.error=it.message }
  } };dialog.show()
 }
 private fun run(title:String,action:()->Unit) {
  if(busy!=null)return
  cancelled=false;busy=AlertDialog.Builder(host).setMessage(title).setNegativeButton("Отмена") { _,_ -> cancelled=true;connection?.disconnect() }.create().apply { setOnCancelListener { cancelled=true;connection?.disconnect() };show() }
  worker.execute {
   val result=runCatching(action)
   main.post { busy?.dismiss();busy=null;if(!closed && !cancelled)result.onFailure { message(it.message ?: "Не удалось выполнить действие") } }
  }
 }
 private fun fetchCatalog() {
  val server=prefs.getString("server",DEFAULT_CATALOG).orEmpty().ifBlank { DEFAULT_CATALOG }
  run("Загружаем каталог…") {
   val base=ExtensionCatalog.base(server);val c=ExtensionCatalog.connect(base.resolve("catalog.json"));connection=c
   try {
    check(c.responseCode==200) { "Сервер каталога недоступен (${c.responseCode})" }
    val bytes=c.inputStream.use { ExtensionCatalog.read(it,200_000) };val cert=host.assets.open("catalog_certificate.der").use { it.readBytes() }
    val catalog=ExtensionCatalog.verify(bytes,cert,prefs.getInt("catalogVersion",1));prefs.edit().putInt("catalogVersion",catalog.version).apply()
    main.post { if(!closed && !cancelled)AlertDialog.Builder(host).setTitle("Выбери расширение").setItems(catalog.entries.map { "${it.title} · ${it.size/1_000_000} МБ" }.toTypedArray()) { _,index -> confirm(base,catalog.entries[index]) }.setNegativeButton("Закрыть",null).show() }
   } finally { connection=null;c.disconnect() }
  }
 }
 private fun confirm(base:java.net.URI,entry:ExtensionCatalog.Entry) {
  if(entry.kind=="model" && !engine.installed()) { message("Сначала установи AFP Translate");return }
  AlertDialog.Builder(host).setTitle(entry.title).setMessage("Скачать ${entry.size/1_000_000} МБ с ${base.host}?"+(if(entry.kind=="engine")" Затем Android попросит подтвердить установку." else " Пакет будет установлен в AFP Translate.")).setPositiveButton("Скачать") { _,_ -> download(base,entry) }.setNegativeButton("Отмена",null).show()
 }
 private fun download(base:java.net.URI,entry:ExtensionCatalog.Entry) = run("Скачиваем ${entry.title}…") {
  val destination=File(folder,if(entry.kind=="engine")"engine.apk" else "model.zip");val part=File(folder,destination.name+".part");val c=ExtensionCatalog.connect(base.resolve(entry.file));connection=c
  try {
   check(c.responseCode==200) { "Сервер загрузки недоступен (${c.responseCode})" }
   val digest=MessageDigest.getInstance("SHA-256");var size=0L
   c.inputStream.use { input -> part.outputStream().use { out -> val buffer=ByteArray(65536);while(true) { check(!cancelled && !closed) { "Загрузка отменена" };val n=input.read(buffer);if(n<0)break;size+=n;check(size<=entry.size) { "Размер файла не совпадает" };out.write(buffer,0,n);digest.update(buffer,0,n) } } }
   check(size==entry.size && digest.digest().joinToString("") { "%02x".format(it) }==entry.hash) { "Файл повреждён: проверка не пройдена" }
   check(part.renameTo(destination)) { "Не удалось сохранить файл" }
   if(entry.kind=="engine") { validateEngine(destination);main.post { if(!closed && !cancelled)installEngine() } }
   else { ParcelFileDescriptor.open(destination,ParcelFileDescriptor.MODE_READ_ONLY).use { engine.install(it) };engine.selectModel(entry.model,entry.title);enable();destination.delete();main.post { if(!closed) { changed();message("Языковой пакет установлен") } } }
  } finally { connection=null;c.disconnect();part.delete() }
 }
 fun importEngine(uri:Uri) = run("Проверяем APK движка…") {
  val file=File(folder,"engine.apk");val part=File(folder,"engine.apk.part")
  try {
   host.contentResolver.openInputStream(uri)?.use { input -> part.outputStream().use { out -> var count=0L;val buffer=ByteArray(65536);while(true) { check(!cancelled) { "Отменено" };val n=input.read(buffer);if(n<0)break;count+=n;require(count<=60_000_000) { "APK слишком большой" };out.write(buffer,0,n) } } } ?: error("Не удалось открыть APK")
   validateEngine(part);check(part.renameTo(file));main.post { if(!closed && !cancelled)installEngine() }
  } finally { part.delete() }
 }
 @Suppress("DEPRECATION") private fun validateEngine(file:File) {
  val pm=host.packageManager;val info=pm.getPackageArchiveInfo(file.absolutePath,PackageManager.GET_SIGNATURES) ?: error("Не удалось прочитать APK")
  require(info.packageName==TranslationClient.PACKAGE) { "Это не AFP Translate" }
  val own=pm.getPackageInfo(host.packageName,PackageManager.GET_SIGNATURES)
  require(info.signatures?.toSet()==own.signatures?.toSet() && !info.signatures.isNullOrEmpty()) { "Подпись движка не совпадает с плеером" }
 }
 private fun installEngine() {
  if(!host.packageManager.canRequestPackageInstalls()) {
   AlertDialog.Builder(host).setTitle("Установка расширения").setMessage("Разреши aFolderPlayer устанавливать выбранный APK. После возврата откроется установка; файл уже скачан.").setPositiveButton("Разрешить") { _,_ -> prefs.edit().putBoolean("pendingInstall",true).apply();host.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${host.packageName}"))) }.setNegativeButton("Позже",null).show();return
  }
  host.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://dev.alex.folderplayer.extensions/engine.apk"),"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));enable()
 }
 fun resume() { if(prefs.getBoolean("pendingInstall",false) && host.packageManager.canRequestPackageInstalls()) { prefs.edit().putBoolean("pendingInstall",false).apply();if(File(folder,"engine.apk").isFile)installEngine() } }
 fun close() { closed=true;cancelled=true;connection?.disconnect();busy?.dismiss();worker.shutdownNow();main.removeCallbacksAndMessages(null) }
}
