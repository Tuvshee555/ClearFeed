package dev.directonly.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import dev.directonly.app.BuildConfig

object ProtectedWebViewFactory {
    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun create(
        context: Context,
        sessionManager: WebSessionManager,
        webViewClient: ProtectedWebViewClient,
        chromeClient: ProtectedWebChromeClient,
    ): WebView = WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        alpha = 0f
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            builtInZoomControls = false
            displayZoomControls = false
            setGeolocationEnabled(false)
            saveFormData = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        sessionManager.configureCookies(this)
        this.webViewClient = webViewClient
        webChromeClient = chromeClient
        setDownloadListener { _, _, _, _, _ -> Unit }
        setOnLongClickListener {
            when (hitTestResult?.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
                WebView.HitTestResult.IMAGE_ANCHOR_TYPE,
                -> true
                else -> false
            }
        }
    }
}
