package com.vkturn.proxy.ui

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.os.Handler
import android.os.Looper

class WebAppInterface(private val onDismiss: () -> Unit) {
    @android.webkit.JavascriptInterface
    fun closeDialog() {
        Handler(Looper.getMainLooper()).post {
            onDismiss()
        }
    }
}

@Composable
fun CaptchaWebViewDialog(captchaUrl: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Card(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        
                        addJavascriptInterface(WebAppInterface(onDismiss), "Android")

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url.toString()
                                // Allow all captcha-related domains to load inside WebView
                                val allowedDomains = listOf("localhost", "127.0.0.1", "fapi.vk.com", "login.vk.com", "id.vk.ru", "api.vk.com", "captcha.vk.com", "vk.com", "vk.ru")
                                val isAllowed = allowedDomains.any { url.contains(it) }
                                // Only dismiss if navigating to a completely unrelated page
                                if (!isAllowed) {
                                    onDismiss()
                                    return true
                                }
                                return false
                            }
                            
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val js = """
                                    javascript:(function() {
                                        var checkInterval = setInterval(function() {
                                            if (document.body && document.body.textContent && document.body.textContent.indexOf("Done! You can close the page.") !== -1) {
                                                clearInterval(checkInterval);
                                                var newBody = document.createElement('body');
                                                newBody.style.backgroundColor = '#1e1f22';
                                                newBody.style.margin = '0';
                                                newBody.innerHTML = '<div style="font-family:sans-serif; text-align:center; padding: 30px; margin: 20px; background-color:#2e3035; color:white; border-radius:12px; font-size:18px;">' +
                                                    '<h2 style="color:#00e676; margin-top:0;">Капча пройдена!</h2>' +
                                                    '<p>Сессия успешно подтверждена.</p>' +
                                                    '<p>Закрытие через <b id="timer" style="color:#2196f3; font-size:24px;">3</b> сек...</p>' +
                                                    '</div>';
                                                document.documentElement.replaceChild(newBody, document.body);
                                                
                                                var count = 3;
                                                var timerInterval = setInterval(function() {
                                                    count--;
                                                    var t = document.getElementById("timer");
                                                    if (t) t.innerText = count;
                                                    if (count <= 0) {
                                                        clearInterval(timerInterval);
                                                        Android.closeDialog();
                                                    }
                                                }, 1000);
                                            }
                                        }, 100);
                                    })();
                                """.trimIndent()
                                view?.evaluateJavascript(js, null)
                            }
                        }
                        loadUrl(captchaUrl)
                    }
                }
            )
        }
    }
}
