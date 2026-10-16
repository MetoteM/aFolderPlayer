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

class YandexAccountActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private var dead = false
    private var generation = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24) }
        setContentView(root)
        root.addView(TextView(this).apply { text="Яндекс Музыка · эксперимент\nВход хранится только в расширении. Музыку воспроизводит aFolderPlayer." })
        status=TextView(this);root.addView(status)
        fun button(title:String,action:()->Unit) { root.addView(Button(this).apply { text=title;setOnClickListener { action() } }) }
        root.addView(TextView(this).apply { text="Откроется страница Яндекса. Используется клиент из эксперимента, не официальная интеграция aFolderPlayer. Доступ к Музыке проверяется после входа." })
        button("Войти в Яндекс") { openAuth() }
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
    private fun openAuth() { startActivityForResult(Intent(this,YandexAuthActivity::class.java),71) }
    @Deprecated("Activity result callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==71 && resultCode==RESULT_OK) { setResult(RESULT_OK);finish() }
    }
    override fun onResume() { super.onResume();status.text=if(YandexSession(this).read()!=null) "Подключено · Яндекс Музыка" else "Вход не выполнен" }
    override fun onDestroy() { dead=true;worker.shutdownNow();super.onDestroy() }
}
