package dev.alex.afponline

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.webkit.*
import android.widget.*
import java.security.SecureRandom
import android.util.Base64
import java.util.concurrent.Executors

/** Visible first-party login page; no JS bridge, password capture or cookie extraction. */
class YandexAuthActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val state = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private lateinit var web: WebView
    private lateinit var status: TextView
    private var consumed = false
    @Volatile private var dead = false
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        SystemBarInsets.apply(root)
        status = TextView(this).apply { text = "Вход на странице Яндекса · oauth.yandex.ru"; setPadding(20,16,20,16) }
        root.addView(status)
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(-1,0,1f)); setContentView(root)
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false); saveFormData = false
        }
        web.isSaveEnabled = false
        web.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (capture(request.url.toString())) return true
                if (!YandexAuth.navigationAllowed(request.url.toString())) { status.text = "Переход вне доменов Яндекса остановлен"; return true }
                status.text = "Вход · ${request.url.host}"
                return false
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { url?.let { capture(it) } }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { url?.let { capture(it) } }
            override fun onPageFinished(view: WebView, url: String?) { url?.let { capture(it) } }
        }
        web.loadUrl(YandexAuth.authorize(state))
    }
    private fun capture(url: String): Boolean {
        if (consumed) return true
        val token = try { YandexAuth.callback(url,state) } catch (_: Exception) {
            consumed = true; web.stopLoading(); status.text = "Вход не подтверждён. Закрой экран и повтори"; return true
        } ?: return false
        consumed = true; web.stopLoading(); web.loadUrl("about:blank")
        status.text = "Проверяем доступ к Музыке…"
        worker.execute {
            val result = runCatching { YandexClient(token).checkSession() }
            runOnUiThread {
                if (!dead) if (result.isSuccess) {
                    if (runCatching { YandexSession(this).save(token) }.isSuccess) { setResult(RESULT_OK); finish() }
                    else status.text = "Не удалось сохранить вход"
                }
                else status.text = (result.exceptionOrNull() as? YandexClient.Problem)?.reason ?: "Не удалось сохранить вход"
            }
        }
        return true
    }
    override fun onDestroy() {
        dead = true; worker.shutdownNow()
        web.stopLoading(); web.clearHistory(); web.destroy()
        super.onDestroy()
    }
}
