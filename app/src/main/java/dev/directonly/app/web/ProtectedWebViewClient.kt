package dev.directonly.app.web

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import dev.directonly.app.model.NavigationDecision
import dev.directonly.app.model.NavigationDisposition
import dev.directonly.app.model.PolicyMode
import dev.directonly.app.policy.NavigationContext
import dev.directonly.app.policy.PlatformNavigationPolicy

class ProtectedWebViewClient(
    private val policy: PlatformNavigationPolicy,
    private val modeProvider: () -> PolicyMode,
    private val contextProvider: () -> NavigationContext,
    private val callbacks: Callbacks,
) : WebViewClient() {
    interface Callbacks {
        fun onAllowedNavigation(view: WebView, url: String, decision: NavigationDecision)
        fun onBlockedNavigation(
            view: WebView,
            url: String,
            decision: NavigationDecision,
            userGesture: Boolean,
        )
        fun onExternalNavigation(url: String, userGesture: Boolean)
        fun onPageStarted(view: WebView, url: String, decision: NavigationDecision)
        fun onPageFinished(view: WebView, url: String, decision: NavigationDecision)
        fun onWebError(failure: WebFailure)
        fun onRendererGone(view: WebView)
    }

    interface NetworkAwareCallbacks {
        fun isOffline(): Boolean
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (!request.isForMainFrame) {
            return !policy.allowSubframe(url, modeProvider(), contextProvider())
        }
        return handleMainFrameCandidate(
            view,
            url,
            contextProvider().copy(userGesture = request.hasGesture()),
        )
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        handleMainFrameCandidate(view, url, contextProvider())

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        view.alpha = 0f
        val decision = policy.evaluate(url, modeProvider(), contextProvider())
        if (!decision.mayLoadInWebView) {
            view.stopLoading()
            callbacks.onBlockedNavigation(view, url, decision, false)
            return
        }
        callbacks.onPageStarted(view, url, decision)
    }

    override fun onPageCommitVisible(view: WebView, url: String) = auditCommittedLocation(view, url)

    override fun onPageFinished(view: WebView, url: String) {
        val decision = policy.evaluate(url, modeProvider(), contextProvider())
        if (!decision.mayLoadInWebView) {
            view.alpha = 0f
            view.stopLoading()
            callbacks.onBlockedNavigation(view, url, decision, false)
            return
        }
        callbacks.onPageFinished(view, url, decision)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) =
        auditCommittedLocation(view, url)

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            val offline = callbacksIsOffline()
            callbacks.onWebError(
                WebFailure(
                    code = if (offline) "CF-OFFLINE" else "CF-NET-${error.errorCode}",
                    detail = error.description.toString(),
                    url = request.url.toString(),
                    offline = offline,
                ),
            )
        }
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (request.isForMainFrame && errorResponse.statusCode >= 400) {
            val offline = callbacksIsOffline()
            callbacks.onWebError(
                WebFailure(
                    code = if (offline) "CF-OFFLINE" else "CF-HTTP-${errorResponse.statusCode}",
                    detail = errorResponse.reasonPhrase,
                    url = request.url.toString(),
                    offline = offline,
                ),
            )
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        view.stopLoading()
        callbacks.onWebError(
            WebFailure(
                code = "CF-TLS-${error.primaryError}",
                detail = "TLS certificate validation failed",
                url = error.url,
            ),
        )
    }

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    override fun onSafeBrowsingHit(
        view: WebView,
        request: WebResourceRequest,
        threatType: Int,
        callback: SafeBrowsingResponse,
    ) {
        callback.backToSafety(true)
        callbacks.onWebError(
            WebFailure(
                code = "CF-SAFE-$threatType",
                detail = "Android Safe Browsing blocked the page",
                url = request.url.toString(),
            ),
        )
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        callbacks.onRendererGone(view)
        return true
    }

    private fun handleMainFrameCandidate(
        view: WebView,
        url: String,
        context: NavigationContext,
    ): Boolean {
        val decision = policy.evaluate(url, modeProvider(), context)
        return when (decision.disposition) {
            NavigationDisposition.ALLOW_AUTH,
            NavigationDisposition.ALLOW_DIRECT,
            NavigationDisposition.ALLOW_SHARED_CONTENT,
            NavigationDisposition.ALLOW_CONTENT,
            -> {
                callbacks.onAllowedNavigation(view, url, decision)
                false
            }
            NavigationDisposition.OPEN_EXTERNALLY -> {
                callbacks.onExternalNavigation(url, context.userGesture)
                true
            }
            NavigationDisposition.BLOCK -> {
                callbacks.onBlockedNavigation(view, url, decision, context.userGesture)
                true
            }
        }
    }

    private fun auditCommittedLocation(view: WebView, url: String) {
        val decision = policy.evaluate(url, modeProvider(), contextProvider())
        if (decision.mayLoadInWebView) {
            callbacks.onAllowedNavigation(view, url, decision)
        } else {
            view.alpha = 0f
            view.stopLoading()
            callbacks.onBlockedNavigation(view, url, decision, false)
        }
    }

    private fun callbacksIsOffline(): Boolean =
        callbacks is NetworkAwareCallbacks && callbacks.isOffline()
}
