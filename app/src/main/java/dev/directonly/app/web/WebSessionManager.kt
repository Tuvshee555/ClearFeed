package dev.directonly.app.web

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView

class WebSessionManager {
    fun configureCookies(webView: WebView) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }
    }

    fun flush() = CookieManager.getInstance().flush()

    fun resetAll(webView: WebView, onComplete: () -> Unit) {
        webView.stopLoading()
        webView.clearCache(true)
        webView.clearFormData()
        webView.clearHistory()
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            onComplete()
        }
    }
}
