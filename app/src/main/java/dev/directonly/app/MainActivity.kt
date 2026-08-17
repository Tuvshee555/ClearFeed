package dev.directonly.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.directonly.app.model.SocialPlatform
import dev.directonly.app.policy.PlatformPolicyRegistry
import dev.directonly.app.policy.ProtectedLinkDecision
import dev.directonly.app.policy.ProtectedSocialLinkRouter
import dev.directonly.app.ui.ClearFeedApp
import dev.directonly.app.web.PlatformScriptInjector
import dev.directonly.app.web.ProtectedWebChromeClient
import dev.directonly.app.web.ProtectedWebViewClient
import dev.directonly.app.web.ProtectedWebViewFactory
import dev.directonly.app.web.WebSessionManager
import java.util.WeakHashMap

class MainActivity : ComponentActivity() {
    private lateinit var coordinator: ClearFeedCoordinator
    private val sessionManager = WebSessionManager()
    private val policies = PlatformPolicyRegistry()
    private val socialLinkRouter = ProtectedSocialLinkRouter(policies)
    private val injectors = WeakHashMap<WebView, PlatformScriptInjector>()
    private val chromeClients = WeakHashMap<WebView, ProtectedWebChromeClient>()

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingPermissionClient: ProtectedWebChromeClient? = null
    private var pendingWebResources: Array<String> = emptyArray()
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = pendingFileCallback ?: return@registerForActivityResult
        pendingFileCallback = null
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            ?.filter { it.scheme == "content" || it.scheme == "android.resource" }
            ?.toTypedArray()
        callback.onReceiveValue(uris)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val request = pendingPermissionRequest ?: return@registerForActivityResult
        val client = pendingPermissionClient
        val resources = pendingWebResources
        pendingPermissionRequest = null
        pendingPermissionClient = null
        pendingWebResources = emptyArray()
        if (results.isNotEmpty() && results.values.all { it }) request.grant(resources) else request.deny()
        client?.completePermissionRequest(request)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator = ClearFeedCoordinator(this, policies, sessionManager, socialLinkRouter)

        val webViewReady = WebViewCompat.getCurrentWebViewPackage(this) != null &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        if (!webViewReady) coordinator.markWebViewUnavailable()

        setContent {
            ClearFeedApp(
                coordinator = coordinator,
                webViewFactory = ::createProtectedWebView,
                webViewRelease = ::releaseProtectedWebView,
                openExternal = ::openExternalBrowser,
                exit = ::finish,
                handleSystemBack = ::hideFullscreen,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (::coordinator.isInitialized) coordinator.onResume()
    }

    override fun onPause() {
        val pendingRequest = pendingPermissionRequest
        pendingRequest?.deny()
        pendingPermissionClient?.let { client -> pendingRequest?.let(client::completePermissionRequest) }
        pendingPermissionRequest = null
        pendingPermissionClient = null
        pendingWebResources = emptyArray()
        if (::coordinator.isInitialized) coordinator.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        hideFullscreen()
        // Compose disposes its composition after onDestroy and runs AndroidView's
        // onRelease, which routes to releaseProtectedWebView for the same instances.
        // Releasing them here as well called stopLoading()/destroy() twice on one
        // WebView. releaseProtectedWebView is now idempotent, so let the composition
        // own WebView teardown and only shut down coordinator-owned resources here.
        if (::coordinator.isInitialized) coordinator.onDestroy()
        super.onDestroy()
    }

    private fun createProtectedWebView(context: android.content.Context, platform: SocialPlatform): WebView {
        val policy = policies.forPlatform(platform)
        lateinit var chromeClient: ProtectedWebChromeClient
        chromeClient = ProtectedWebChromeClient(
            onFileChooser = ::launchFileChooser,
            onPermission = { request -> handleWebPermissionRequest(request, chromeClient) },
            onPermissionCanceled = ::handleWebPermissionCanceled,
            onShowFullscreen = ::showFullscreen,
            onHideFullscreen = { hideFullscreen() },
        )
        val webClient = ProtectedWebViewClient(
            policy = policy,
            modeProvider = { coordinator.policyMode },
            contextProvider = coordinator::navigationContext,
            callbacks = coordinator,
        )
        val view = ProtectedWebViewFactory.create(context, sessionManager, webClient, chromeClient)
        // Throwing from a Compose factory crashes the app. A missing or corrupt guard
        // asset, or a WebView provider that no longer offers document-start scripts, is a
        // degraded-mode condition the coordinator already knows how to present, so route
        // it there instead. installProtection() reports rather than throws.
        val injector = PlatformScriptInjector(context, policy)
        if (!installProtection(injector, view)) {
            coordinator.markWebViewUnavailable()
            return view
        }
        injectors[view] = injector
        chromeClients[view] = chromeClient
        coordinator.attachWebView(view, chromeClient)
        return view
    }

    private fun installProtection(injector: PlatformScriptInjector, view: WebView): Boolean =
        try {
            injector.install(view, coordinator::onBridgeEvent)
        } catch (_: Exception) {
            false
        }

    private fun releaseProtectedWebView(view: WebView) {
        // Idempotent: Compose may release a view the Activity has already torn down.
        val injector = injectors.remove(view)
        val chromeClient = chromeClients.remove(view)
        if (injector == null && chromeClient == null && !coordinator.ownsWebView(view)) return
        injector?.uninstall(view)
        chromeClient?.clearActivePermissionRequest()
        coordinator.releaseWebView(view)
    }

    private fun launchFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        // The WebChromeClient contract is: return true and invoke filePathCallback exactly
        // once, or return false and never touch it. Doing both lets WebView run its own
        // default handling over a consumed callback, which can leave the page's file input
        // permanently broken. Every path below that consumes the callback returns true.
        if (!coordinator.isMessagePageActive()) {
            callback.onReceiveValue(null)
            return true
        }
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = callback
        return try {
            val chooser = Intent.createChooser(params.createIntent(), "Choose media for this message")
            fileChooserLauncher.launch(chooser)
            true
        } catch (_: Exception) {
            pendingFileCallback = null
            callback.onReceiveValue(null)
            true
        }
    }

    private fun handleWebPermissionRequest(
        request: PermissionRequest,
        chromeClient: ProtectedWebChromeClient,
    ) {
        if (!coordinator.isTrustedActiveOrigin(request.origin.toString()) ||
            !coordinator.isMessagePageActive()
        ) {
            request.deny()
            chromeClient.completePermissionRequest(request)
            return
        }

        val webResources = request.resources.filter {
            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
        }
        if (webResources.isEmpty()) {
            request.deny()
            chromeClient.completePermissionRequest(request)
            return
        }

        val androidPermissions = buildList {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in webResources) add(Manifest.permission.CAMERA)
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in webResources) add(Manifest.permission.RECORD_AUDIO)
        }
        val missing = androidPermissions.filter {
            ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            request.grant(webResources.toTypedArray())
            chromeClient.completePermissionRequest(request)
            return
        }

        pendingPermissionRequest?.deny()
        pendingPermissionClient?.let { client ->
            pendingPermissionRequest?.let(client::completePermissionRequest)
        }
        pendingPermissionRequest = request
        pendingPermissionClient = chromeClient
        pendingWebResources = webResources.toTypedArray()
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun handleWebPermissionCanceled(request: PermissionRequest) {
        if (pendingPermissionRequest === request) {
            pendingPermissionRequest = null
            pendingPermissionClient = null
            pendingWebResources = emptyArray()
        }
    }

    private fun showFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (!coordinator.isFullscreenAllowed()) {
            callback.onCustomViewHidden()
            return
        }
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        (window.decorView as ViewGroup).addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        WindowCompat.getInsetsController(window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun hideFullscreen(): Boolean {
        val view = fullscreenView ?: return false
        (view.parent as? ViewGroup)?.removeView(view)
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        return true
    }

    private fun openExternalBrowser(url: String) {
        val platform = coordinator.selectedPlatform ?: return
        val decision = socialLinkRouter.decide(
            rawUrl = url,
            sourcePlatform = platform,
            userGesture = true,
            sourceMode = coordinator.policyMode,
        )
        if (decision !is ProtectedLinkDecision.ExternalWeb || decision.url != url) return
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        startActivity(Intent.createChooser(intent, "Open link"))
    }
}
