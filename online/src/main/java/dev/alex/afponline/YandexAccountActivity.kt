package dev.alex.afponline

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import android.text.InputType
import android.webkit.CookieManager
import java.util.concurrent.Executors

class YandexAccountActivity : androidx.activity.ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private var dead = false
    private var generation = 0
    private lateinit var sdkLauncher: androidx.activity.result.ActivityResultLauncher<com.yandex.authsdk.YandexAuthLoginOptions>
    private fun sdkResult(result:com.yandex.authsdk.YandexAuthResult) {
        when(result) {
            is com.yandex.authsdk.YandexAuthResult.Success -> {
                val value=result.token.value
                val attempt=++generation
                status.text="Яндекс ID получен. Проверяем доступ к Музыке…"
                worker.execute {
                    val checked=runCatching { val client=YandexClient(value);client.checkSession();client.collectionPage(0) }
                    runOnUiThread {
                        if(!dead && generation==attempt) {
                            if(checked.isSuccess && runCatching { YandexSession(this).save(value) }.isSuccess) { setResult(RESULT_OK);finish() }
                            else status.text="LoginSDK не подтвердил доступ к Музыке. Сохранённый вход оставлен. Можно войти через страницу Яндекса."
                        }
                    }
                }
            }
            is com.yandex.authsdk.YandexAuthResult.Failure -> status.text="LoginSDK отклонил вход. Для собственного клиента нужны регистрация приложения и права Музыки. Доступен вход через страницу Яндекса."
            com.yandex.authsdk.YandexAuthResult.Cancelled -> status.text="Вход отменён. Сохранённая сессия оставлена."
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(BuildConfig.YANDEX_SDK_CONFIGURED) {
            val sdk=com.yandex.authsdk.YandexAuthSdk.create(com.yandex.authsdk.YandexAuthOptions(this))
            sdkLauncher=registerForActivityResult(sdk.contract) { sdkResult(it) }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24) }
        val scroll=ScrollView(this).apply { addView(root) }
        SystemBarInsets.apply(scroll)
        setContentView(scroll)
        root.addView(TextView(this).apply { text="Яндекс Музыка · эксперимент\nВход хранится только в расширении. Музыку воспроизводит aFolderPlayer." })
        status=TextView(this);root.addView(status)
        fun button(title:String,action:()->Unit) { root.addView(Button(this).apply { text=title;setOnClickListener { action() } }) }
        root.addView(TextView(this).apply { text="Откроется страница Яндекса. Используется клиент из эксперимента, не официальная интеграция aFolderPlayer. Доступ к Музыке проверяется после входа." })
        button(if(BuildConfig.YANDEX_SDK_CONFIGURED) "Войти через Яндекс ID · LoginSDK" else "Яндекс ID · требуется регистрация приложения") {
            if(!BuildConfig.YANDEX_SDK_CONFIGURED) { sdkSetup();return@button }
            generation++
            runCatching { sdkLauncher.launch(com.yandex.authsdk.YandexAuthLoginOptions()) }
                .onFailure { status.text="Не удалось запустить Яндекс ID. Доступен вход через страницу Яндекса." }
        }
        button("Войти через страницу Яндекса") { openAuth() }
        root.addView(TextView(this).apply { text="Для LoginSDK требуется собственная регистрация Android-приложения. Вход через страницу Яндекса доступен сейчас; он не требует выхода из рабочей сессии." })
        button("Выйти") {
            generation++
            YandexSession(this).clear()
            CookieManager.getInstance().removeAllCookies(null);CookieManager.getInstance().flush()
            status.text="Вход удалён. Уже открытый поток может доиграть; новые запросы потребуют входа."
        }
        button("Вставить токен вручную") {
            val field=EditText(this).apply { inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;isSaveEnabled=false;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS }
            val dialog=AlertDialog.Builder(this).setTitle("Токен своей сессии").setView(field)
                .setPositiveButton("Подключить") { _,_->
                    val value=field.text.toString().trim();val attempt=++generation;field.text.clear();status.text="Проверяем…"
                    worker.execute {
                        val result=runCatching { YandexClient(value).checkSession() }
                        runOnUiThread { if(!dead && generation==attempt) status.text=if(result.isSuccess) {
                            if(runCatching { YandexSession(this).save(value) }.isSuccess) "Подключено. Вернись в плеер" else "Не удалось сохранить вход"
                        } else (result.exceptionOrNull() as? YandexClient.Problem)?.reason ?: "Вход не подтверждён" }
                    }
                }.setNegativeButton("Отмена") { _,_->field.text.clear() }.create()
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.show()
        }
        button("Вернуться в плеер") { finish() }
        if(savedInstanceState==null && intent.getBooleanExtra("loginNow",false)) openAuth()
    }
    private fun sdkSetup() {
        AlertDialog.Builder(this).setTitle("Настройка Яндекс ID")
            .setMessage("Для LoginSDK нужно зарегистрировать наше Android-приложение в Яндекс OAuth: указать пакет и отпечаток сертификата, затем добавить полученный Client ID в сборку. Клиент веб-эксперимента для этого не подходит.\n\nОшибка 500 не подтверждает конфликт с уже выполненным входом. Выходить из рабочего аккаунта не требуется.\n\nПакет: $packageName\nСейчас можно войти через страницу Яндекса.")
            .setPositiveButton("Войти через страницу") { _,_->openAuth() }
            .setNegativeButton("Закрыть",null).show()
    }
    private fun openAuth() { generation++;startActivityForResult(Intent(this,YandexAuthActivity::class.java),71) }
    @Deprecated("Activity result callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==71 && resultCode==RESULT_OK) { setResult(RESULT_OK);finish() }
    }
    override fun onResume() { super.onResume();if(status.text.isBlank())status.text=if(YandexSession(this).read()!=null) "Подключено · Яндекс Музыка" else "Вход не выполнен" }
    override fun onDestroy() { dead=true;worker.shutdownNow();super.onDestroy() }
}
