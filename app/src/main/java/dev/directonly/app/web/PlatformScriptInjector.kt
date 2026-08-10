package dev.directonly.app.web

import android.content.Context
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.directonly.app.policy.PlatformNavigationPolicy
import org.json.JSONObject
import java.security.SecureRandom

class PlatformScriptInjector(
    private val context: Context,
    private val policy: PlatformNavigationPolicy,
) {
    private var scriptHandler: ScriptHandler? = null
    private var bridgeSessionToken: String? = null

    fun install(webView: WebView, onEvent: (BridgeEvent) -> Unit): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false

        WebViewCompat.addWebMessageListener(
            webView,
            BRIDGE_NAME,
            policy.trustedOriginRules,
        ) { _, message, sourceOrigin, isMainFrame, _ ->
            if (!isMainFrame || !policy.isTrustedTopLevelOrigin(sourceOrigin.toString())) {
                return@addWebMessageListener
            }
            parseEvent(message)?.let(onEvent)
        }

        val platform = policy.platform
        val eventToken = createBridgeSessionToken().also { bridgeSessionToken = it }
        val sharedRules = context.assets.open(SHARED_RULES_ASSET).bufferedReader().use { it.readText() }
        val css = context.assets.open(platform.guardCssAsset).bufferedReader().use { it.readText() }
        val template = context.assets.open(platform.guardScriptAsset).bufferedReader().use { it.readText() }
        val script = sharedRules + "\n" + template
            .replace("__CLEARFEED_GUARD_VERSION__", platform.guardVersion.toString())
            .replace("__CLEARFEED_CSS_JSON__", JSONObject.quote(css))
            .replace("__DIRECTONLY_GUARD_VERSION__", platform.guardVersion.toString())
            .replace("__DIRECTONLY_CSS_JSON__", JSONObject.quote(css))
            .replace("__CLEARFEED_BRIDGE_TOKEN_JSON__", JSONObject.quote(eventToken))
        scriptHandler = WebViewCompat.addDocumentStartJavaScript(
            webView,
            script,
            policy.trustedOriginRules,
        )
        return true
    }

    fun uninstall(webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            scriptHandler?.remove()
        }
        scriptHandler = null
        bridgeSessionToken = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.removeWebMessageListener(webView, BRIDGE_NAME)
        }
    }

    private fun parseEvent(message: WebMessageCompat): BridgeEvent? {
        val raw = message.data ?: return null
        if (raw.length > MAX_EVENT_LENGTH) return null
        return try {
            val json = JSONObject(raw)
            if (json.optString("bridgeToken") != bridgeSessionToken) return null
            when (json.optString("type")) {
                "navigation_candidate" -> BridgeEvent.NavigationCandidate(json.safePath())
                "blocked_navigation" -> BridgeEvent.BlockedNavigation(json.safePath())
                "external_navigation" -> {
                    val url = json.optString("url")
                    if (url.length in 1..MAX_EXTERNAL_URL_LENGTH) {
                        BridgeEvent.ExternalNavigation(
                            url = url,
                            userGesture = json.optBoolean("userGesture", false),
                        )
                    } else null
                }
                "intentional_video" -> BridgeEvent.IntentionalVideo(json.safePath())
                "intentional_instagram_shared_content" ->
                    BridgeEvent.IntentionalInstagramSharedContent(json.safePath())
                "authentication_required" -> BridgeEvent.AuthenticationRequired(json.safePath())
                "guard_health" -> BridgeEvent.GuardHealth(
                    path = json.safePath(),
                    healthy = json.optBoolean("healthy", false),
                    version = json.optInt("version", -1),
                )
                "guard_ready" -> BridgeEvent.GuardReady(
                    path = json.safePath(),
                    version = json.optInt("version", -1),
                )
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createBridgeSessionToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun JSONObject.safePath(): String {
        val path = optString("path")
        val containsControl = path.any { it.code < 0x20 || it.code == 0x7f }
        return if (path.startsWith('/') && path.length <= MAX_PATH_LENGTH && !containsControl) {
            path
        } else {
            "/invalid/"
        }
    }

    companion object {
        private const val BRIDGE_NAME = "clearFeedBridge"
        private const val SHARED_RULES_ASSET = "guard_rules.js"
        private const val MAX_EVENT_LENGTH = 8_192
        private const val MAX_EXTERNAL_URL_LENGTH = 4_096
        private const val MAX_PATH_LENGTH = 2_048
    }
}
