package dev.directonly.app.web

import android.webkit.WebView
import dev.directonly.app.model.PolicyMode
import dev.directonly.app.policy.NavigationContext
import dev.directonly.app.policy.PlatformNavigationPolicy

class ProtectedNavigator(
    private val policyProvider: () -> PlatformNavigationPolicy,
    private val modeProvider: () -> PolicyMode,
    private val contextProvider: () -> NavigationContext,
) {
    fun openSafeRoot(webView: WebView) = loadVerified(webView, policyProvider().safeRootUrl)

    fun openLogin(webView: WebView) = loadVerified(webView, policyProvider().loginUrl)

    fun retry(webView: WebView, candidate: String?) {
        val policy = policyProvider()
        val url = candidate?.takeIf {
            policy.evaluate(it, modeProvider(), contextProvider()).mayLoadInWebView
        } ?: policy.safeRootUrl
        loadVerified(webView, url)
    }

    fun openIntentional(webView: WebView, url: String) = loadVerified(webView, url)

    private fun loadVerified(webView: WebView, url: String) {
        val policy = policyProvider()
        check(policy.evaluate(url, modeProvider(), contextProvider()).mayLoadInWebView) {
            "Attempted to load a URL outside the selected ClearFeed platform policy"
        }
        webView.loadUrl(url)
    }
}
