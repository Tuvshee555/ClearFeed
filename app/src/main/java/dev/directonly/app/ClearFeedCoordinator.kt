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
import dev.directonly.app.diagnostics.DiagnosticTrace
import dev.directonly.app.diagnostics.DiagnosticsPreferences
import dev.directonly.app.diagnostics.buildDiagnosticReport
import dev.directonly.app.diagnostics.privacySafeLocation
import dev.directonly.app.diagnostics.RemoteDiagnosticsReporter
import dev.directonly.app.diagnostics.safeDiagnosticDetail
import dev.directonly.app.limits.AccessDecision
import dev.directonly.app.limits.AccessPolicy
import dev.directonly.app.limits.LocalClock
import dev.directonly.app.limits.ServiceLimits
import dev.directonly.app.limits.ServiceUsageSummary
import dev.directonly.app.limits.SystemLocalClock
import dev.directonly.app.limits.UsageStore
import dev.directonly.app.model.AppState
import dev.directonly.app.model.NavigationDecision
import dev.directonly.app.model.NavigationDisposition
import dev.directonly.app.model.PolicyMode
import dev.directonly.app.model.RevealState
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
import dev.directonly.app.policy.ProtectedSurfaceGate
import dev.directonly.app.policy.UrlNormalizationResult
import dev.directonly.app.policy.UrlNormalizer
import dev.directonly.app.web.BridgeEvent
import dev.directonly.app.web.GuardDeadline
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
    var revealState by mutableStateOf(RevealState.CONCEALED)
        private set

    /** Retained for call sites that only care whether anything is on screen. */
    val webContentReady: Boolean
        get() = revealState.isRevealed
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
    /** Why the current service was refused, when [appState] is ACCESS_BLOCKED. */
    var accessDecision by mutableStateOf<AccessDecision>(AccessDecision.Allowed)
        private set

    /** Seconds left in a running open delay. */
    var openDelayRemainingSeconds by mutableIntStateOf(0)
        private set

    /** Bumped whenever limits change, so the UI re-reads them. */
    var limitsRevision by mutableIntStateOf(0)
        private set

    private val clock: LocalClock = SystemLocalClock()
    private val usageStore = UsageStore(context, clock)
    private var sessionStartedAtElapsedMs: Long? = null

    private var lastDiagnostic by mutableStateOf<DiagnosticRecord?>(null)
    private val diagnosticTrace = DiagnosticTrace()

    /** Bumped on every diagnostic so the UI can re-derive its report without re-keying. */
    var diagnosticRevision by mutableIntStateOf(0)
        private set
    private val diagnosticsPreferences = DiagnosticsPreferences(context)
    private val remoteDiagnostics = RemoteDiagnosticsReporter.create(context, diagnosticsPreferences)

    /** Off by default. Only failures are ever sent, and only while this is on. */
    var remoteDiagnosticsEnabled by mutableStateOf(diagnosticsPreferences.remoteReportingEnabled)
        private set

    fun updateRemoteDiagnostics(enabled: Boolean) {
        diagnosticsPreferences.remoteReportingEnabled = enabled
        remoteDiagnosticsEnabled = enabled
    }

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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainScheduler = object : GuardDeadline.Scheduler {
        override fun post(delayMs: Long, task: () -> Unit): Any =
            Runnable(task).also { mainHandler.postDelayed(it, delayMs) }

        override fun cancel(handle: Any) {
            (handle as? Runnable)?.let(mainHandler::removeCallbacks)
        }
    }

    /** Outer budget: nothing permitted has loaded at all. */
    private val guardDeadline = GuardDeadline<WebView>(GUARD_TIMEOUT_MS, mainScheduler)

    /** Inner budget: the route is allowed, so show it even if the guard is still quiet. */
    private val revealDeadline = GuardDeadline<WebView>(REVEAL_TIMEOUT_MS, mainScheduler)
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

        // Usage limits are checked before anything is created. A refused service must not
        // load a page, run a guard, or count as an open.
        val decision = AccessPolicy.evaluate(
            limits = usageStore.limitsFor(platform),
            usage = usageStore.snapshot(platform),
        )
        if (!decision.isAllowed) {
            selectedPlatform = platform
            accessDecision = decision
            appState = AppState.ACCESS_BLOCKED
            isLoading = false
            revealState = RevealState.CONCEALED
            recordDiagnostic(
                code = "CF-LIMIT-BLOCKED",
                detail = "${platform.displayName} refused by a usage limit (${decision.javaClass.simpleName})",
                url = null,
            )
            return
        }
        accessDecision = decision

        crossPlatformOrigins.clear()
        pendingPlatformEntry = null
        selectedPlatform = platform
        policyMode = PolicyMode.AUTHENTICATING
        currentUrl = null
        allowedYouTubeVideoId = null
        instagramSharedContentSession.clear()
        revealState = RevealState.CONCEALED
        recordDiagnostic(
            code = "CF-STAGE-SELECTED",
            detail = "Selected ${platform.displayName}; preparing its protected sign-in page",
            url = null,
        )

        val friction = decision as? AccessDecision.FrictionRequired
        if (friction != null) {
            // The delay runs before the WebView exists, so the wait is genuinely empty
            // rather than a page loading behind a spinner.
            appState = AppState.OPENING_DELAY
            isLoading = false
            openDelayRemainingSeconds = friction.seconds
            tickOpenDelay(platform)
            return
        }
        beginSession(platform)
    }

    private fun tickOpenDelay(platform: SocialPlatform) {
        mainHandler.postDelayed({
            if (selectedPlatform != platform || appState != AppState.OPENING_DELAY) return@postDelayed
            val remaining = openDelayRemainingSeconds - 1
            openDelayRemainingSeconds = remaining
            if (remaining > 0) tickOpenDelay(platform) else beginSession(platform)
        }, 1_000L)
    }

    /** Cancels a running open delay and returns to the service picker. */
    fun cancelOpenDelay() {
        openDelayRemainingSeconds = 0
        goHome()
    }

    private fun beginSession(platform: SocialPlatform) {
        usageStore.recordOpen(platform)
        sessionStartedAtElapsedMs = clock.elapsedRealtimeMs()
        isLoading = webViewAvailable
        appState = if (webViewAvailable) AppState.STARTING else AppState.WEBVIEW_UNAVAILABLE
        webViewGeneration++
    }

    /** Adds the foreground time of the session that just ended to today's total. */
    private fun endSession() {
        val platform = selectedPlatform ?: return
        val startedAt = sessionStartedAtElapsedMs ?: return
        sessionStartedAtElapsedMs = null
        val seconds = ((clock.elapsedRealtimeMs() - startedAt) / 1000L)
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
        usageStore.recordSession(platform, seconds)
    }

    /** Today's usage for the service picker, so limits are visible before opening anything. */
    fun usageSummary(platform: SocialPlatform): ServiceUsageSummary {
        val limits = usageStore.limitsFor(platform)
        val used = usageStore.usedSecondsToday(platform)
        return ServiceUsageSummary(
            limits = limits,
            usedSecondsToday = used,
            remainingSecondsToday = AccessPolicy.remainingSeconds(limits, used),
            opensToday = usageStore.openCountToday(platform),
        )
    }

    fun history(platform: SocialPlatform) = usageStore.history(platform)

    fun limitsFor(platform: SocialPlatform) = usageStore.limitsFor(platform)

    fun pendingLimitsFor(platform: SocialPlatform) = usageStore.pendingLimits(platform)

    /** Returns the date the change takes effect: today for a tightening, tomorrow otherwise. */
    fun updateLimits(platform: SocialPlatform, candidate: ServiceLimits) =
        usageStore.updateLimits(platform, candidate).also { limitsRevision++ }

    fun goHome() {
        endSession()
        crossPlatformOrigins.clear()
        pendingPlatformEntry = null
        selectedPlatform = null
        appState = AppState.HOME
        isLoading = false
        revealState = RevealState.CONCEALED
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
        revealState = RevealState.CONCEALED
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
        // Cancel by view identity, not by whether this is still the active view. On a
        // platform switch the new view is attached before the old one is released, so a
        // `webView === view` guard left the stale deadline queued on the main Looper,
        // holding a destroyed WebView for up to 12s and blocking the new view's watchdog.
        guardDeadline.cancelIfOwnedBy(view)
        revealDeadline.cancelIfOwnedBy(view)
        if (webView === view) {
            chromeClient?.clearActivePermissionRequest()
            chromeClient = null
            webView = null
        }
        view.stopLoading()
        view.destroy()
    }

    fun ownsWebView(view: WebView): Boolean = webView === view

    /** Releases coordinator-owned resources. WebView teardown is the composition's job. */
    fun onDestroy() {
        endSession()
        guardDeadline.cancel()
        revealDeadline.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        pendingLoadOnAttach?.let { (view, listener) -> view.removeOnAttachStateChangeListener(listener) }
        pendingLoadOnAttach = null
        remoteDiagnostics.close()
    }

    fun markWebViewUnavailable() {
        webViewAvailable = false
        if (selectedPlatform != null) appState = AppState.WEBVIEW_UNAVAILABLE
        isLoading = false
        revealState = RevealState.CONCEALED
        recordDiagnostic(
            code = "CF-WEBVIEW-UNAVAILABLE",
            detail = "Required document-start script or origin-restricted message support is unavailable",
            url = null,
        )
    }

    /** Changes whenever a new diagnostic lands, so the UI can re-derive its report. */
    val lastDiagnosticCode: String?
        get() = lastDiagnostic?.let { "${it.code}|${it.location}" }

    fun diagnosticReport(): String = buildDiagnosticReport(
        environment = DiagnosticEnvironment(
            appVersion = BuildConfig.VERSION_NAME,
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            webViewVersion = WebViewCompat.getCurrentWebViewPackage(context)?.versionName ?: "unavailable",
        ),
        trace = diagnosticTrace.snapshot(),
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
                val decision = currentPolicy().evaluate(candidate, policyMode, navigationContext())
                if (completeInstagramAuthentication(view, candidate, decision)) {
                    Unit
                } else if (selectedPlatform == SocialPlatform.FACEBOOK &&
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
                    reveal(view, RevealState.REVEALED_VERIFIED)
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
                    if (revealState != RevealState.REVEALED_VERIFIED) {
                        recordDiagnostic(
                            code = "CF-STAGE-GUARD-HEALTHY",
                            detail = "Protected interface verified and ready",
                            url = actualUrl,
                        )
                    }
                    updateAllowedState(actualUrl, decision)
                    reveal(view, RevealState.REVEALED_VERIFIED)
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
        revealState = RevealState.CONCEALED
        // A retry is a fresh attempt and deserves a full deadline, not the remainder of
        // one that already expired.
        guardDeadline.cancel()
        revealDeadline.cancel()
        navigator.retry(view, lastSafeUrls[platform])
        scheduleGuardTimeout(view)
    }

    fun resetAllSessions() {
        val view = webView ?: return
        crossPlatformOrigins.clear()
        pendingPlatformEntry = null
        isLoading = true
        revealState = RevealState.CONCEALED
        appState = AppState.STARTING
        sessionManager.resetAll(view) {
            // This continuation is deferred until CookieManager finishes, by which time the
            // user may have gone Home or switched services. Without these checks
            // currentPolicy()'s requireNotNull(selectedPlatform) throws, or loadUrl runs on
            // an already-destroyed WebView.
            if (webView !== view || selectedPlatform == null) return@resetAll
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
        return ProtectedSurfaceGate.isMessageSurface(
            policies.forPlatform(platform).evaluate(
                webView?.url,
                PolicyMode.CONTENT,
                navigationContext(),
            ),
        )
    }

    fun isTrustedActiveOrigin(url: String?): Boolean =
        selectedPlatform?.let { policies.forPlatform(it).isTrustedTopLevelOrigin(url) } == true

    fun isFullscreenAllowed(): Boolean {
        if (isMessagePageActive()) return true
        return ProtectedSurfaceGate.isFullscreenSurface(
            currentPolicy().evaluate(webView?.url, policyMode, navigationContext()),
        )
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
        // Only foreground time counts, so the session clock stops whenever the app does.
        endSession()
        chromeClient?.clearActivePermissionRequest()
        webView?.onPause()
        webView?.pauseTimers()
        sessionManager.flush()
    }

    fun onResume() {
        val view = webView ?: return
        if (sessionStartedAtElapsedMs == null && selectedPlatform != null) {
            sessionStartedAtElapsedMs = clock.elapsedRealtimeMs()
        }
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
        if (completeInstagramAuthentication(view, url, decision)) return
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
        revealState = RevealState.CONCEALED
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
        revealState = RevealState.CONCEALED
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
        revealState = RevealState.CONCEALED
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
        revealState = RevealState.CONCEALED
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
        // Posted to the coordinator's own handler, not the view's. A WebView that is
        // detached or destroyed before the delay elapses drops its queued work, which
        // left the notice on screen permanently.
        val shown = notice
        mainHandler.postDelayed({ if (notice === shown) notice = null }, NOTICE_DURATION_MS)
        val currentDecision = currentPolicy().evaluate(view.url, policyMode, navigationContext())
        if (!currentDecision.mayLoadInWebView) recoverToSafeRoot(view)
    }

    private fun completeInstagramAuthentication(
        view: WebView,
        url: String,
        decision: NavigationDecision,
    ): Boolean {
        if (selectedPlatform != SocialPlatform.INSTAGRAM ||
            policyMode != PolicyMode.AUTHENTICATING
        ) return false
        // Instagram lands a signed-in session on the home feed, which classifies as blocked
        // content, or on an unrecognised variant of it.
        val isPostLoginLanding = decision.routeKind == RouteKind.BLOCKED_INSTAGRAM_CONTENT ||
            decision.routeKind == RouteKind.UNKNOWN_INSTAGRAM
        if (!isPostLoginLanding) return false
        val normalized = (UrlNormalizer.normalize(url) as? UrlNormalizationResult.Valid)?.value
            ?: return false
        if (normalized.host !in setOf("instagram.com", "www.instagram.com") || normalized.path != "/") {
            return false
        }
        view.alpha = 0f
        view.stopLoading()
        appState = AppState.STARTING
        isLoading = true
        revealState = RevealState.CONCEALED
        policyMode = PolicyMode.DIRECT
        pendingHistoryClear = true
        recordDiagnostic(
            code = "CF-STAGE-AUTH-COMPLETE",
            detail = "Instagram authentication completed; opening Direct inbox",
            url = url,
        )
        navigator.openSafeRoot(view)
        return true
    }

    private fun recoverToSafeRoot(view: WebView) {
        if (instagramSharedContentSession.hasPendingViewer()) {
            return returnToOriginThread(view)
        }
        view.alpha = 0f
        view.stopLoading()
        appState = AppState.BLOCKED_RECOVERY
        isLoading = true
        revealState = RevealState.CONCEALED
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
        revealState = RevealState.CONCEALED
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
        guardDeadline.schedule(view, ::fireGuardTimeout)
        revealDeadline.schedule(view, ::fireRevealDeadline)
    }

    /** Shows the WebView and settles the loading state. */
    private fun reveal(view: WebView, state: RevealState) {
        guardDeadline.cancel()
        revealDeadline.cancel()
        view.alpha = 1f
        isLoading = false
        revealState = state
    }

    /**
     * Reveals a page the native policy allows but the guard has not confirmed.
     *
     * The URL allowlist is the guarantee that matters and it has already passed. Waiting
     * indefinitely for a DOM check built on provider markup is what produced blank screens
     * and endless spinners whenever Instagram or Facebook changed their HTML. The guard
     * keeps running and keeps sanitizing; it just no longer decides whether the app works.
     */
    private fun fireRevealDeadline(view: WebView) {
        if (webView !== view || revealState.isRevealed) return
        if (appState == AppState.WEB_ERROR || appState == AppState.OFFLINE) return
        val decision = currentPolicy().evaluate(view.url ?: currentUrl, policyMode, navigationContext())
        if (!decision.mayLoadInWebView) {
            // A route the policy refuses is never revealed, whatever the guard is doing.
            return
        }
        recordDiagnostic(
            code = "CF-STAGE-REVEALED-UNVERIFIED",
            detail = "Route allowed by policy; showing it before the guard confirmed the page",
            url = view.url,
        )
        updateAllowedState(view.url ?: currentUrl ?: return, decision)
        reveal(view, RevealState.REVEALED_UNVERIFIED)
    }

    private fun fireGuardTimeout(view: WebView) {
        trace("guard-timeout-fired view=${System.identityHashCode(view)} active=${webView === view} ready=$webContentReady")
        if (webContentReady || webView !== view) return

        // The page still is not showing after the full budget. If the policy allows the
        // route, this is the last chance to reveal it rather than strand the user; the
        // reveal deadline normally gets there first, but a slow login redirect chain can
        // finish after it has already passed.
        val decision = currentPolicy().evaluate(view.url ?: currentUrl, policyMode, navigationContext())
        if (decision.mayLoadInWebView) {
            recordDiagnostic(
                code = "CF-GUARD-UNVERIFIED",
                detail = "Guard did not report healthy within ${GUARD_TIMEOUT_MS}ms; " +
                    "route is policy-allowed so it is shown with reduced protection",
                url = view.url,
            )
            fireRevealDeadline(view)
            return
        }

        // Only a route the policy itself refuses becomes an error surface.
        view.alpha = 0f
        isLoading = false
        appState = AppState.WEB_ERROR
        instagramSharedContentSession.clear()
        val service = selectedPlatform?.displayName ?: "The service"
        recordDiagnostic(
            code = "CF-GUARD-TIMEOUT",
            detail = "No permitted route loaded within ${GUARD_TIMEOUT_MS}ms",
            url = view.url,
        )
        errorMessage =
            "$service didn't finish loading a page ClearFeed allows. " +
                "Error CF-GUARD-TIMEOUT. Open Diagnostics for details."
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

    private fun sameNormalizedLocation(first: String, second: String): Boolean {
        val one = (UrlNormalizer.normalize(first) as? UrlNormalizationResult.Valid)?.value
            ?: return false
        val two = (UrlNormalizer.normalize(second) as? UrlNormalizationResult.Valid)?.value
            ?: return false
        return one.scheme == two.scheme && one.host == two.host && one.path == two.path
    }

    private fun recordDiagnostic(code: String, detail: String, url: String?) {
        val record = DiagnosticRecord(
            code = code,
            service = selectedPlatform?.displayName ?: "App startup",
            location = privacySafeLocation(url),
            detail = safeDiagnosticDetail(detail),
        )
        diagnosticTrace.record(record, SystemClock.elapsedRealtime())
        lastDiagnostic = record
        diagnosticRevision++
        remoteDiagnostics.report(record)
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

        // How long a policy-allowed route waits for the guard before it is shown anyway.
        // Long enough that a healthy guard almost always wins the race and the page appears
        // fully sanitized; short enough that a drifted selector costs a moment, not the app.
        private const val REVEAL_TIMEOUT_MS = 2_500L
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
