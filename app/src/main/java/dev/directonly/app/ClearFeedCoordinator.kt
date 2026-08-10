package dev.directonly.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.webkit.WebViewCompat
import dev.directonly.app.diagnostics.DiagnosticEnvironment
import dev.directonly.app.diagnostics.DiagnosticRecord
import dev.directonly.app.diagnostics.buildDiagnosticReport
import dev.directonly.app.diagnostics.privacySafeLocation
import dev.directonly.app.diagnostics.safeDiagnosticDetail
import dev.directonly.app.model.AppState
import dev.directonly.app.model.NavigationDecision
import dev.directonly.app.model.NavigationDisposition
import dev.directonly.app.model.PolicyMode
import dev.directonly.app.model.RouteKind
import dev.directonly.app.model.SocialPlatform
import dev.directonly.app.policy.NavigationContext
import dev.directonly.app.policy.DirectOnlyNavigationPolicy
import dev.directonly.app.policy.CrossPlatformNavigationStack
import dev.directonly.app.policy.CrossPlatformReturnPoint
import dev.directonly.app.policy.InstagramSharedContentSession
import dev.directonly.app.policy.PlatformNavigationPolicy
import dev.directonly.app.policy.PlatformPolicyRegistry
import dev.directonly.app.policy.ProtectedLinkDecision
import dev.directonly.app.policy.ProtectedSocialLinkRouter
import dev.directonly.app.policy.UrlNormalizationResult
import dev.directonly.app.policy.UrlNormalizer
import dev.directonly.app.web.BridgeEvent
import dev.directonly.app.web.ProtectedNavigator
import dev.directonly.app.web.ProtectedWebChromeClient
import dev.directonly.app.web.ProtectedWebViewClient
import dev.directonly.app.web.WebSessionManager
import dev.directonly.app.web.WebFailure
import java.security.SecureRandom

class ClearFeedCoordinator(
    private val context: Context,
    private val policies: PlatformPolicyRegistry,
    private val sessionManager: WebSessionManager,
    private val socialLinkRouter: ProtectedSocialLinkRouter,
) : ProtectedWebViewClient.Callbacks, ProtectedWebViewClient.NetworkAwareCallbacks {
    var appState by mutableStateOf(AppState.HOME)
        private set
    var selectedPlatform by mutableStateOf<SocialPlatform?>(null)
        private set
    var policyMode by mutableStateOf(PolicyMode.AUTHENTICATING)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var webContentReady by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var errorMessage by mutableStateOf("The protected site couldn't load.")
        private set
    var pendingExternalUrl by mutableStateOf<String?>(null)
        private set
    var webViewGeneration by mutableIntStateOf(0)
        private set
    var webViewAvailable by mutableStateOf(true)
        private set
    private var lastDiagnostic by mutableStateOf<DiagnosticRecord?>(null)

    private val lastSafeUrls = SocialPlatform.entries.associateWith {
        policies.forPlatform(it).safeRootUrl
    }.toMutableMap()
    private val navigator = ProtectedNavigator(
        policyProvider = ::currentPolicy,
        modeProvider = { policyMode },
        contextProvider = ::navigationContext,
    )
    private var webView: WebView? = null
    private var chromeClient: ProtectedWebChromeClient? = null
    private var currentUrl: String? = null
    private var allowedYouTubeVideoId: String? = null
    private val instagramSharedContentSession = InstagramSharedContentSession(SystemClock::elapsedRealtime)
    private var pendingHistoryClear = false
    private var guardTimeout: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingLoadOnAttach: Pair<WebView, View.OnAttachStateChangeListener>? = null
    private val crossPlatformOrigins = CrossPlatformNavigationStack()
    private var pendingPlatformEntry: PendingPlatformEntry? = null

    fun currentPolicy(): PlatformNavigationPolicy =
        policies.forPlatform(requireNotNull(selectedPlatform) { "No ClearFeed service selected" })

    fun navigationContext(): NavigationContext = NavigationContext(
        allowedYouTubeVideoId = allowedYouTubeVideoId,
        approvedInstagramSharedContent = instagramSharedContentSession.activeApproval(),
    )

    fun selectPlatform(platform: SocialPlatform) {
        if (selectedPlatform == platform) return
        crossPlatformOrigins.clear()
        pendingPlatformEntry = null
        selectedPlatform = platform
        policyMode = PolicyMode.AUTHENTICATING
        currentUrl = null
        allowedYouTubeVideoId = null
        instagramSharedContentSession.clear()
        isLoading = webViewAvailable
        webContentReady = false
        appState = if (webViewAvailable) AppState.STARTING else AppState.WEBVIEW_UNAVAILABLE
        recordDiagnostic(
            code = "CF-STAGE-SELECTED",
            detail = "Selected ${platform.displayName}; preparing its protected sign-in page",
            url = null,
        )
        webViewGeneration++
    }

    fun goHome() {
        crossPlatformOrigins.clear()
        pendingPlatformEntry = null
        selectedPlatform = null
        appState = AppState.HOME
        isLoading = false
        webContentReady = false
        pendingExternalUrl = null
        allowedYouTubeVideoId = null
        instagramSharedContentSession.clear()
    }

    fun attachWebView(view: WebView, chromeClient: ProtectedWebChromeClient) {
        trace("attach view=${System.identityHashCode(view)} platform=${selectedPlatform?.name}")
        this.webView = view
        this.chromeClient = chromeClient
        appState = AppState.STARTING
        isLoading = true
        webContentReady = false
        val entry = pendingPlatformEntry?.takeIf { it.platform == selectedPlatform }
        pendingPlatformEntry = null
        recordDiagnostic(
            code = "CF-STAGE-WAITING-FOR-WEBVIEW",
            detail = "Waiting for Android to attach the protected WebView",
            url = entry?.url ?: currentPolicy().loginUrl,
        )
        dispatchWhenAttached(view) {
            if (webView !== view || selectedPlatform == null) return@dispatchWhenAttached
            trace("dispatch view=${System.identityHashCode(view)} platform=${selectedPlatform?.name}")
            recordDiagnostic(
                code = "CF-STAGE-LOAD-DISPATCHED",
                detail = "WebView attached; protected navigation dispatched",
                url = entry?.url ?: currentPolicy().loginUrl,
            )
            scheduleGuardTimeout(view)
            if (entry != null) {
                policyMode = PolicyMode.CONTENT
                allowedYouTubeVideoId = entry.allowedYouTubeVideoId
                val decision = currentPolicy().evaluate(entry.url, policyMode, navigationContext())
                if (decision.mayLoadInWebView) {
                    navigator.openIntentional(view, entry.url)
                } else {
                    allowedYouTubeVideoId = null
                    policyMode = PolicyMode.AUTHENTICATING
                    navigator.openSafeRoot(view)
                }
            } else {
                policyMode = PolicyMode.AUTHENTICATING
                if (selectedPlatform == SocialPlatform.YOUTUBE) {
                    navigator.openSafeRoot(view)
                } else {
                    navigator.openLogin(view)
                }
            }
        }
    }

    fun releaseWebView(view: WebView) {
        trace("release view=${System.identityHashCode(view)} active=${webView === view}")
        pendingLoadOnAttach?.takeIf { it.first === view }?.let { (_, listener) ->
            view.removeOnAttachStateChangeListener(listener)
            pendingLoadOnAttach = null
        }
        if (webView === view) {
            cancelGuardTimeout(view)
            chromeClient?.clearActivePermissionRequest()
            chromeClient = null
            webView = null
        }
        view.stopLoading()
        view.destroy()
    }

    fun markWebViewUnavailable() {
        webViewAvailable = false
        if (selectedPlatform != null) appState = AppState.WEBVIEW_UNAVAILABLE
        isLoading = false
        webContentReady = false
        recordDiagnostic(
            code = "CF-WEBVIEW-UNAVAILABLE",
            detail = "Required document-start script or origin-restricted message support is unavailable",
            url = null,
        )
    }

    fun diagnosticReport(): String = buildDiagnosticReport(
        environment = DiagnosticEnvironment(
            appVersion = BuildConfig.VERSION_NAME,
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            webViewVersion = WebViewCompat.getCurrentWebViewPackage(context)?.versionName ?: "unavailable",
        ),
        record = lastDiagnostic,
    )

    fun onBridgeEvent(event: BridgeEvent) {
        val view = webView ?: return
        trace("bridge view=${System.identityHashCode(view)} event=${event.javaClass.simpleName}")
        when (event) {
            is BridgeEvent.NavigationCandidate -> {
                val candidate = trustedUrlForPath(view, event.path)
                val decision = currentPolicy().evaluate(candidate, policyMode, navigationContext())
                if (decision.mayLoadInWebView) updateAllowedState(candidate, decision) else recoverToSafeRoot(view)
            }
            is BridgeEvent.BlockedNavigation -> {
                val candidate = trustedUrlForPath(view, event.path)
                if (selectedPlatform == SocialPlatform.FACEBOOK &&
                    sameNormalizedLocation(candidate, view.url ?: candidate)
                ) {
                    recoverToSafeRoot(view)
                } else {
                    onBlockedAttempt(view)
                }
            }
            is BridgeEvent.ExternalNavigation -> handleLinkCandidate(
                view = view,
                rawUrl = event.url,
                userGesture = event.userGesture,
            )
            is BridgeEvent.IntentionalVideo -> openIntentionalYouTubeVideo(view, event.pathAndQuery)
            is BridgeEvent.IntentionalInstagramSharedContent ->
                openIntentionalInstagramSharedContent(view, event.pathAndQuery)
            is BridgeEvent.AuthenticationRequired -> {
                val candidate = trustedUrlForPath(view, event.path)
                val decision = currentPolicy().evaluate(candidate, policyMode, navigationContext())
                if (selectedPlatform == SocialPlatform.FACEBOOK &&
                    decision.routeKind == RouteKind.FACEBOOK_FEED
                ) {
                    policyMode = PolicyMode.AUTHENTICATING
                    appState = AppState.AUTHENTICATING
                    cancelGuardTimeout(view)
                    view.alpha = 1f
                    isLoading = false
                    webContentReady = true
                    recordDiagnostic(
                        code = "CF-STAGE-LOGIN-READY",
                        detail = "Facebook sign-in form is ready on its protected entry page",
                        url = view.url,
                    )
                } else {
                    onBlockedAttempt(view)
                }
            }
            is BridgeEvent.GuardHealth -> {
                val candidate = trustedUrlForPath(view, event.path)
                val actualUrl = view.url ?: candidate
                val decision = currentPolicy().evaluate(actualUrl, policyMode, navigationContext())
                val correctVersion = event.version == currentPolicy().platform.guardVersion
                if (event.healthy && correctVersion && decision.mayLoadInWebView &&
                    sameNormalizedLocation(candidate, actualUrl)
                ) {
                    if (!webContentReady) {
                        recordDiagnostic(
                            code = "CF-STAGE-GUARD-HEALTHY",
                            detail = "Protected interface verified and ready",
                            url = actualUrl,
                        )
                    }
                    cancelGuardTimeout(view)
                    updateAllowedState(actualUrl, decision)
                    view.alpha = 1f
                    isLoading = false
                    webContentReady = true
                } else {
                    recordDiagnostic(
                        code = "CF-STAGE-GUARD-WAITING",
                        detail = "Guard report received but the route is not ready or did not match",
                        url = actualUrl,
                    )
                }
            }
            is BridgeEvent.GuardReady -> recordDiagnostic(
                code = "CF-STAGE-GUARD-READY",
                detail = "Protection script started; waiting for a healthy page",
                url = view.url,
            )
        }
    }

    fun retry() {
        val view = webView ?: return
        val platform = selectedPlatform ?: return
        appState = AppState.STARTING
        isLoading = true
        webContentReady = false
        navigator.retry(view, lastSafeUrls[platform])
    }

    fun resetAllSessions() {
        val view = webView ?: return
        crossPlatformOrigins.clear()
        pendingPlatformEntry = null
        isLoading = true
        webContentReady = false
        appState = AppState.STARTING
        sessionManager.resetAll(view) {
            policyMode = PolicyMode.AUTHENTICATING
            currentUrl = null
            allowedYouTubeVideoId = null
            instagramSharedContentSession.clear()
            SocialPlatform.entries.forEach { lastSafeUrls[it] = policies.forPlatform(it).safeRootUrl }
            navigator.openLogin(view)
        }
    }

    fun clearExternalPrompt() {
        pendingExternalUrl = null
    }

    fun isMessagePageActive(): Boolean {
        val platform = selectedPlatform ?: return false
        val decision = policies.forPlatform(platform).evaluate(
            webView?.url,
            PolicyMode.CONTENT,
            navigationContext(),
        )
        return decision.routeKind in setOf(
            RouteKind.DIRECT_INBOX,
            RouteKind.DIRECT_THREAD,
            RouteKind.DIRECT_REQUESTS,
            RouteKind.DIRECT_NEW,
            RouteKind.FACEBOOK_MESSAGES,
        )
    }

    fun isTrustedActiveOrigin(url: String?): Boolean =
        selectedPlatform?.let { policies.forPlatform(it).isTrustedTopLevelOrigin(url) } == true

    fun isFullscreenAllowed(): Boolean {
        if (isMessagePageActive()) return true
        val route = currentPolicy().evaluate(webView?.url, policyMode, navigationContext()).routeKind
        return route == RouteKind.YOUTUBE_WATCH ||
            route == RouteKind.INSTAGRAM_SHARED_REEL ||
            route == RouteKind.INSTAGRAM_SHARED_POST
    }

    fun handleBack(exit: () -> Unit) {
        if (crossPlatformOrigins.isNotEmpty()) {
            returnToCrossPlatformOrigin()
            return
        }
        val view = webView
        val decision = selectedPlatform?.let {
            policies.forPlatform(it).evaluate(currentUrl ?: view?.url, policyMode, navigationContext())
        }
        when (decision?.routeKind) {
            RouteKind.INSTAGRAM_SHARED_REEL,
            RouteKind.INSTAGRAM_SHARED_POST,
            -> if (view != null) returnToOriginThread(view) else goHome()

            RouteKind.DIRECT_INBOX,
            RouteKind.YOUTUBE_SUBSCRIPTIONS,
            RouteKind.FACEBOOK_FEED,
            -> goHome()

            RouteKind.AUTH_LOGIN,
            RouteKind.AUTH_CHALLENGE,
            RouteKind.AUTH_RECOVERY,
            RouteKind.AUTH_CONSENT,
            -> if (view != null && hasProvablySafePreviousEntry(view)) view.goBack() else goHome()

            null -> exit()
            else -> if (view != null && hasProvablySafePreviousEntry(view)) {
                view.goBack()
            } else if (view != null) {
                navigator.openSafeRoot(view)
            } else {
                goHome()
            }
        }
    }

    fun onPause() {
        chromeClient?.clearActivePermissionRequest()
        webView?.onPause()
        webView?.pauseTimers()
        sessionManager.flush()
    }

    fun onResume() {
        val view = webView ?: return
        view.resumeTimers()
        view.onResume()
        val decision = currentPolicy().evaluate(view.url, policyMode, navigationContext())
        if (!decision.mayLoadInWebView) recoverToSafeRoot(view)
    }

    override fun onAllowedNavigation(view: WebView, url: String, decision: NavigationDecision) {
        if (appState == AppState.WEB_ERROR || appState == AppState.OFFLINE) return
        trace("allowed view=${System.identityHashCode(view)} route=${decision.routeKind} shape=${diagnosticRouteShape(url)}")
        updateAllowedState(url, decision)
    }

    override fun onBlockedNavigation(
        view: WebView,
        url: String,
        decision: NavigationDecision,
        userGesture: Boolean,
    ) {
        trace("blocked view=${System.identityHashCode(view)} route=${decision.routeKind} shape=${diagnosticRouteShape(url)} gesture=$userGesture")
        if (userGesture) {
            handleLinkCandidate(view, url, userGesture = true)
        } else {
            recoverToSafeRoot(view)
        }
    }

    override fun onExternalNavigation(url: String, userGesture: Boolean) {
        handleLinkCandidate(webView, url, userGesture)
    }

    override fun onPageStarted(view: WebView, url: String, decision: NavigationDecision) {
        if (appState == AppState.WEB_ERROR || appState == AppState.OFFLINE) return
        trace("page-start view=${System.identityHashCode(view)} route=${decision.routeKind}")
        updateAllowedState(url, decision)
        view.alpha = 0f
        isLoading = true
        webContentReady = false
        recordDiagnostic(
            code = "CF-STAGE-PAGE-STARTED",
            detail = "Page navigation started (${decision.routeKind})",
            url = url,
        )
        scheduleGuardTimeout(view)
    }

    override fun onPageFinished(view: WebView, url: String, decision: NavigationDecision) {
        if (appState == AppState.WEB_ERROR || appState == AppState.OFFLINE) return
        trace("page-finish view=${System.identityHashCode(view)} route=${decision.routeKind}")
        updateAllowedState(url, decision)
        recordDiagnostic(
            code = "CF-STAGE-PAGE-FINISHED",
            detail = "Page finished loading (${decision.routeKind}); checking protection",
            url = url,
        )
        if (pendingHistoryClear && isSafeRoot(decision.routeKind)) {
            view.clearHistory()
            pendingHistoryClear = false
        }
        // A completed navigation does not prove that a sign-in form is painted.
        // Keep the WebView concealed until the origin-restricted guard verifies a
        // visible login control; otherwise a clipped/blank page becomes a false
        // "ready" state on some Android System WebView versions.
        scheduleGuardTimeout(view)
    }

    override fun onWebError(failure: WebFailure) {
        webView?.alpha = 0f
        webContentReady = false
        isLoading = false
        appState = if (failure.offline) AppState.OFFLINE else AppState.WEB_ERROR
        instagramSharedContentSession.clear()
        val service = selectedPlatform?.displayName ?: "The service"
        recordDiagnostic(failure.code, failure.detail, failure.url)
        errorMessage = if (failure.offline) {
            "ClearFeed needs a connection to reach $service. Error ${failure.code}."
        } else {
            "$service couldn't load. Error ${failure.code}. Open Diagnostics for details."
        }
    }

    override fun onRendererGone(view: WebView) {
        // WebView.destroy() can report renderer termination asynchronously. Ignore a
        // callback from a released service; it must never corrupt the newly active one.
        if (webView !== view) {
            trace("renderer-gone-stale view=${System.identityHashCode(view)}")
            return
        }
        trace("renderer-gone-active view=${System.identityHashCode(view)}")
        webView = null
        chromeClient = null
        appState = AppState.WEB_ERROR
        instagramSharedContentSession.clear()
        recordDiagnostic(
            code = "CF-RENDERER",
            detail = "Android System WebView stopped unexpectedly",
            url = view.url,
        )
        errorMessage =
            "Android System WebView stopped unexpectedly. Error CF-RENDERER. Open Diagnostics for details."
        isLoading = false
        webContentReady = false
        webViewGeneration++
    }

    override fun isOffline(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return true
        val capabilities = manager.getNetworkCapabilities(network) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun updateAllowedState(url: String, decision: NavigationDecision) {
        val wasSharedViewer = appState == AppState.SHARED_CONTENT_VIEWER
        currentUrl = url
        when (decision.disposition) {
            NavigationDisposition.ALLOW_DIRECT -> {
                policyMode = PolicyMode.DIRECT
                appState = AppState.CONTENT
                selectedPlatform?.let { lastSafeUrls[it] = url }
                if (wasSharedViewer) instagramSharedContentSession.clear()
            }
            NavigationDisposition.ALLOW_SHARED_CONTENT -> {
                policyMode = PolicyMode.DIRECT
                appState = AppState.SHARED_CONTENT_VIEWER
            }
            NavigationDisposition.ALLOW_CONTENT -> {
                policyMode = PolicyMode.CONTENT
                appState = AppState.CONTENT
                if (selectedPlatform == SocialPlatform.YOUTUBE &&
                    decision.routeKind != RouteKind.YOUTUBE_WATCH
                ) {
                    allowedYouTubeVideoId = null
                }
                selectedPlatform?.let { lastSafeUrls[it] = url }
            }
            NavigationDisposition.ALLOW_AUTH -> {
                policyMode = PolicyMode.AUTHENTICATING
                appState = AppState.AUTHENTICATING
                if (wasSharedViewer) instagramSharedContentSession.clear()
            }
            else -> Unit
        }
    }

    private fun openIntentionalYouTubeVideo(view: WebView, pathAndQuery: String) {
        if (selectedPlatform != SocialPlatform.YOUTUBE) return onBlockedAttempt(view)
        val source = currentPolicy().evaluate(view.url, policyMode, navigationContext()).routeKind
        if (source != RouteKind.YOUTUBE_SUBSCRIPTIONS && source != RouteKind.YOUTUBE_SEARCH) {
            return onBlockedAttempt(view)
        }
        val candidate = currentPolicy().primaryOrigin + pathAndQuery
        val videoId = candidate.toUri().getQueryParameter("v")
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,32}")) }
            ?: return onBlockedAttempt(view)
        allowedYouTubeVideoId = videoId
        val decision = currentPolicy().evaluate(candidate, PolicyMode.CONTENT, navigationContext())
        if (decision.routeKind == RouteKind.YOUTUBE_WATCH && decision.mayLoadInWebView) {
            policyMode = PolicyMode.CONTENT
            navigator.openIntentional(view, candidate)
        } else {
            allowedYouTubeVideoId = null
            onBlockedAttempt(view)
        }
    }

    private fun openIntentionalInstagramSharedContent(view: WebView, pathAndQuery: String) {
        if (selectedPlatform != SocialPlatform.INSTAGRAM) return onBlockedAttempt(view)
        val instagramPolicy = currentPolicy() as? DirectOnlyNavigationPolicy
            ?: return onBlockedAttempt(view)
        val sourceUrl = view.url ?: currentUrl ?: return onBlockedAttempt(view)
        val sourceDecision = instagramPolicy.evaluate(sourceUrl, policyMode, navigationContext())
        if (sourceDecision.routeKind != RouteKind.DIRECT_THREAD) return onBlockedAttempt(view)

        val candidate = instagramPolicy.primaryOrigin + pathAndQuery
        val approval = instagramPolicy.createSharedContentApproval(
            rawSharedUrl = candidate,
            rawOriginThreadUrl = sourceUrl,
            bridgeNonce = createSharedContentNonce(),
            createdAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
        ) ?: return onBlockedAttempt(view)
        instagramSharedContentSession.grant(approval)
        val viewerUrl = instagramPolicy.viewerUrl(approval)
        val decision = instagramPolicy.evaluate(viewerUrl, PolicyMode.DIRECT, navigationContext())
        if (decision.disposition == NavigationDisposition.ALLOW_SHARED_CONTENT) {
            policyMode = PolicyMode.DIRECT
            navigator.openIntentional(view, viewerUrl)
        } else {
            instagramSharedContentSession.clear()
            onBlockedAttempt(view)
        }
    }

    private fun handleLinkCandidate(view: WebView?, rawUrl: String, userGesture: Boolean) {
        val sourcePlatform = selectedPlatform ?: return
        val sourceUrl = view?.url ?: currentUrl
        val sourceDecision = currentPolicy().evaluate(sourceUrl, policyMode, navigationContext())
        if (!sourceDecision.mayLoadInWebView ||
            sourceDecision.routeKind == RouteKind.INSTAGRAM_SHARED_REEL ||
            sourceDecision.routeKind == RouteKind.INSTAGRAM_SHARED_POST
        ) {
            view?.let(::onBlockedAttempt)
            return
        }
        if (sourcePlatform == SocialPlatform.INSTAGRAM &&
            sourceDecision.routeKind != RouteKind.DIRECT_THREAD
        ) {
            view?.let(::onBlockedAttempt)
            return
        }

        when (val linkDecision = socialLinkRouter.decide(
            rawUrl = rawUrl,
            sourcePlatform = sourcePlatform,
            userGesture = userGesture,
            sourceMode = policyMode,
        )) {
            is ProtectedLinkDecision.ExternalWeb -> pendingExternalUrl = linkDecision.url
            is ProtectedLinkDecision.Block -> view?.let(::onBlockedAttempt)
            is ProtectedLinkDecision.RouteInternally -> {
                val activeView = view ?: return
                if (linkDecision.platform == sourcePlatform) {
                    allowedYouTubeVideoId = linkDecision.navigationContext.allowedYouTubeVideoId
                    policyMode = PolicyMode.CONTENT
                    val decision = currentPolicy().evaluate(
                        linkDecision.url,
                        policyMode,
                        navigationContext(),
                    )
                    if (decision.mayLoadInWebView) {
                        navigator.openIntentional(activeView, linkDecision.url)
                    } else {
                        onBlockedAttempt(activeView)
                    }
                    return
                }

                crossPlatformOrigins.push(
                    CrossPlatformReturnPoint(
                        platform = sourcePlatform,
                        returnUrl = requireNotNull(sourceUrl),
                        allowedYouTubeVideoId = allowedYouTubeVideoId,
                    ),
                )
                activeView.alpha = 0f
                activeView.stopLoading()
                switchToPlatform(
                    PendingPlatformEntry(
                        platform = linkDecision.platform,
                        url = linkDecision.url,
                        allowedYouTubeVideoId = linkDecision.navigationContext.allowedYouTubeVideoId,
                    ),
                )
            }
        }
    }

    private fun returnToCrossPlatformOrigin() {
        val origin = crossPlatformOrigins.pop() ?: return
        webView?.apply {
            alpha = 0f
            stopLoading()
        }
        switchToPlatform(
            PendingPlatformEntry(
                platform = origin.platform,
                url = origin.returnUrl,
                allowedYouTubeVideoId = origin.allowedYouTubeVideoId,
            ),
        )
    }

    private fun switchToPlatform(entry: PendingPlatformEntry) {
        selectedPlatform = entry.platform
        pendingPlatformEntry = entry
        policyMode = PolicyMode.CONTENT
        currentUrl = null
        allowedYouTubeVideoId = entry.allowedYouTubeVideoId
        instagramSharedContentSession.clear()
        pendingExternalUrl = null
        pendingHistoryClear = false
        isLoading = webViewAvailable
        webContentReady = false
        appState = if (webViewAvailable) AppState.STARTING else AppState.WEBVIEW_UNAVAILABLE
        webViewGeneration++
    }

    private fun onBlockedAttempt(view: WebView) {
        if (instagramSharedContentSession.hasPendingViewer()) {
            return returnToOriginThread(view)
        }
        val platform = selectedPlatform ?: return
        notice = when (platform) {
            SocialPlatform.INSTAGRAM -> "ClearFeed keeps Instagram inside Messages"
            SocialPlatform.YOUTUBE -> "ClearFeed keeps YouTube intentional and Shorts-free"
            SocialPlatform.FACEBOOK -> "ClearFeed blocks Facebook video and discovery surfaces"
        }
        view.postDelayed({ if (notice != null) notice = null }, NOTICE_DURATION_MS)
        val currentDecision = currentPolicy().evaluate(view.url, policyMode, navigationContext())
        if (!currentDecision.mayLoadInWebView) recoverToSafeRoot(view)
    }

    private fun recoverToSafeRoot(view: WebView) {
        if (instagramSharedContentSession.hasPendingViewer()) {
            return returnToOriginThread(view)
        }
        view.alpha = 0f
        view.stopLoading()
        appState = AppState.BLOCKED_RECOVERY
        isLoading = true
        webContentReady = false
        policyMode = PolicyMode.CONTENT
        if (selectedPlatform == SocialPlatform.YOUTUBE) allowedYouTubeVideoId = null
        pendingHistoryClear = true
        navigator.openSafeRoot(view)
    }

    private fun returnToOriginThread(view: WebView) {
        val originThread = instagramSharedContentSession.returnDestinationAndClear()
            ?: return recoverToSafeRoot(view)
        view.alpha = 0f
        view.stopLoading()
        appState = AppState.BLOCKED_RECOVERY
        isLoading = true
        webContentReady = false
        policyMode = PolicyMode.DIRECT
        pendingHistoryClear = false
        navigator.openIntentional(view, originThread)
    }

    private fun createSharedContentNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun scheduleGuardTimeout(view: WebView) {
        // Redirects and repeated callbacks must not extend loading forever.
        if (guardTimeout != null) return
        val timeout = Runnable {
            trace("guard-timeout-fired view=${System.identityHashCode(view)} active=${webView === view} ready=$webContentReady")
            if (!webContentReady && webView === view) {
                view.alpha = 0f
                isLoading = false
                appState = AppState.WEB_ERROR
                instagramSharedContentSession.clear()
                val service = selectedPlatform?.displayName ?: "The service"
                recordDiagnostic(
                    code = "CF-GUARD-TIMEOUT",
                    detail = "The protected interface did not report healthy within ${GUARD_TIMEOUT_MS}ms",
                    url = view.url,
                )
                errorMessage =
                    "ClearFeed couldn't verify $service's protected interface. " +
                        "Error CF-GUARD-TIMEOUT. Open Diagnostics for details."
            }
        }
        guardTimeout = timeout
        mainHandler.postDelayed(timeout, GUARD_TIMEOUT_MS)
    }

    private fun dispatchWhenAttached(view: WebView, action: () -> Unit) {
        pendingLoadOnAttach?.let { (oldView, oldListener) ->
            oldView.removeOnAttachStateChangeListener(oldListener)
        }
        pendingLoadOnAttach = null
        if (view.isAttachedToWindow) {
            view.post(action)
            return
        }
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(attachedView: View) {
                view.removeOnAttachStateChangeListener(this)
                if (pendingLoadOnAttach?.second === this) pendingLoadOnAttach = null
                view.post(action)
            }

            override fun onViewDetachedFromWindow(detachedView: View) = Unit
        }
        pendingLoadOnAttach = view to listener
        view.addOnAttachStateChangeListener(listener)
    }

    private fun cancelGuardTimeout(view: WebView) {
        guardTimeout?.let(mainHandler::removeCallbacks)
        guardTimeout = null
    }

    private fun hasProvablySafePreviousEntry(view: WebView): Boolean {
        val history = view.copyBackForwardList()
        if (history.currentIndex <= 0) return false
        val previous = history.getItemAtIndex(history.currentIndex - 1)?.url ?: return false
        return currentPolicy().evaluate(previous, PolicyMode.CONTENT, navigationContext()).mayLoadInWebView
    }

    private fun trustedUrlForPath(view: WebView, path: String): String {
        val current = (view.url ?: currentPolicy().primaryOrigin).toUri()
        val origin = if (current.scheme == "https" && current.host != null &&
            currentPolicy().isTrustedTopLevelOrigin("https://${current.host}/")
        ) {
            "https://${current.host}"
        } else {
            currentPolicy().primaryOrigin
        }
        return origin + path
    }

    private fun isSafeRoot(kind: RouteKind): Boolean = kind in setOf(
        RouteKind.DIRECT_INBOX,
        RouteKind.YOUTUBE_SUBSCRIPTIONS,
        RouteKind.FACEBOOK_FEED,
    )

    private fun isAuthenticationRoute(kind: RouteKind): Boolean = kind in setOf(
        RouteKind.AUTH_LOGIN,
        RouteKind.AUTH_CHALLENGE,
        RouteKind.AUTH_RECOVERY,
        RouteKind.AUTH_CONSENT,
    )

    private fun isFacebookRedirectedLogin(url: String): Boolean {
        if (selectedPlatform != SocialPlatform.FACEBOOK) return false
        val normalized = (UrlNormalizer.normalize(url) as? UrlNormalizationResult.Valid)?.value
            ?: return false
        return normalized.path == "/" && normalized.query.orEmpty().split('&')
            .map { it.substringBefore('=') }
            .let { keys -> "_rdr" in keys && keys.all { it == "_rdr" || it == "__mmr" } }
    }

    private fun sameNormalizedLocation(first: String, second: String): Boolean {
        val one = (UrlNormalizer.normalize(first) as? UrlNormalizationResult.Valid)?.value
            ?: return false
        val two = (UrlNormalizer.normalize(second) as? UrlNormalizationResult.Valid)?.value
            ?: return false
        return one.scheme == two.scheme && one.host == two.host && one.path == two.path
    }

    private fun recordDiagnostic(code: String, detail: String, url: String?) {
        lastDiagnostic = DiagnosticRecord(
            code = code,
            service = selectedPlatform?.displayName ?: "App startup",
            location = privacySafeLocation(url),
            detail = safeDiagnosticDetail(detail),
        )
    }

    private fun diagnosticRouteShape(url: String): String {
        val normalized = (UrlNormalizer.normalize(url) as? UrlNormalizationResult.Valid)?.value
            ?: return "invalid"
        val keys = normalized.query.orEmpty().split('&')
            .map { it.substringBefore('=') }
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
        return normalized.path + if (keys.isEmpty()) "" else "?keys=${keys.joinToString(",")}" 
    }

    companion object {
        private const val LOG_TAG = "ClearFeedLifecycle"
        private const val GUARD_TIMEOUT_MS = 12_000L
        private const val NOTICE_DURATION_MS = 3_500L
    }

    private fun trace(message: String) {
        Log.i(LOG_TAG, message)
    }
}

private data class PendingPlatformEntry(
    val platform: SocialPlatform,
    val url: String,
    val allowedYouTubeVideoId: String?,
)
