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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24) }
        setContentView(root)
        root.addView(TextView(this).apply { text="Яндекс Музыка · эксперимент\nВход хранится только в расширении. Музыку воспроизводит aFolderPlayer." })
        status=TextView(this);root.addView(status)
        fun button(title:String,action:()->Unit) { root.addView(Button(this).apply { text=title;setOnClickListener { action() } }) }
        button("Войти в Яндекс") {
            AlertDialog.Builder(this).setTitle("Вход через Яндекс")
                .setMessage("Откроется страница oauth.yandex.ru. Используется клиент авторизации из успешного эксперимента; это пока не официальная интеграция aFolderPlayer. Пароль обрабатывает страница Яндекса.")
                .setPositiveButton("Продолжить") { _,_->startActivity(Intent(this,YandexAuthActivity::class.java)) }
                .setNegativeButton("Отмена",null).show()
        }
        button("Выйти") {
            YandexSession(this).clear()
            CookieManager.getInstance().removeAllCookies(null);CookieManager.getInstance().flush()
            status.text="Вход удалён. Уже открытый поток может доиграть; новые запросы потребуют входа."
        }
        button("Вставить токен вручную") {
            val field=EditText(this).apply { inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;isSaveEnabled=false;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS }
            val dialog=AlertDialog.Builder(this).setTitle("Токен своей сессии").setView(field)
                .setPositiveButton("Подключить") { _,_->
                    val value=field.text.toString().trim();field.text.clear();status.text="Проверяем…"
                    worker.execute {
                        val result=runCatching { YandexClient(value).checkSession();YandexSession(this).save(value) }
                        runOnUiThread { if(!dead) status.text=if(result.isSuccess) "Подключено. Вернись в плеер" else (result.exceptionOrNull() as? YandexClient.Problem)?.reason ?: "Не удалось сохранить вход" }
                    }
                }.setNegativeButton("Отмена") { _,_->field.text.clear() }.create()
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.show()
        }
        button("Вернуться в плеер") { finish() }
    }
    override fun onResume() { super.onResume();status.text=if(YandexSession(this).read()!=null) "Подключено · Яндекс Музыка" else "Вход не выполнен" }
    override fun onDestroy() { dead=true;worker.shutdownNow();super.onDestroy() }
}
