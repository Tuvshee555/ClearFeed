package dev.directonly.app.policy

import dev.directonly.app.model.BlockReason
import dev.directonly.app.model.NavigationDecision
import dev.directonly.app.model.NavigationDisposition
import dev.directonly.app.model.PolicyMode
import dev.directonly.app.model.RouteKind
import dev.directonly.app.model.SocialPlatform

data class NavigationContext(
    val allowedYouTubeVideoId: String? = null,
    val approvedInstagramSharedContent: ApprovedInstagramSharedContent? = null,
    val userGesture: Boolean = false,
)

interface NavigationPolicy {
    fun evaluate(
        rawUrl: String?,
        mode: PolicyMode,
        context: NavigationContext = NavigationContext(),
    ): NavigationDecision
}

interface PlatformNavigationPolicy : NavigationPolicy {
    val platform: SocialPlatform
    val safeRootUrl: String
    val loginUrl: String
    val primaryOrigin: String
    val trustedOriginRules: Set<String>

    fun isTrustedTopLevelOrigin(rawUrl: String?): Boolean

    fun allowSubframe(
        rawUrl: String?,
        mode: PolicyMode,
        context: NavigationContext = NavigationContext(),
    ): Boolean
}

class DirectOnlyNavigationPolicy(
    private val classifier: InstagramRouteClassifier = InstagramRouteClassifier(),
) : PlatformNavigationPolicy {
    override val platform = SocialPlatform.INSTAGRAM
    override val safeRootUrl = INBOX_URL
    override val loginUrl = LOGIN_URL
    override val primaryOrigin = "https://www.instagram.com"
    override val trustedOriginRules = setOf(
        "https://www.instagram.com",
        "https://instagram.com",
    )

    override fun evaluate(
        rawUrl: String?,
        mode: PolicyMode,
        context: NavigationContext,
    ): NavigationDecision {
        val classification = classifier.classify(rawUrl)
        val safePath = classification.normalized?.path

        // Once a DM-created capability exists, the WebView is a sealed one-item viewer.
        // Native code clears the capability before returning to the origin thread, so no
        // other route (including Direct, auth, or an external URL) is valid in this state.
        context.approvedInstagramSharedContent?.let { approved ->
            return if (isApprovedSharedContent(classification, approved)) {
                NavigationDecision(
                    NavigationDisposition.ALLOW_SHARED_CONTENT,
                    classification.kind,
                    safePath = safePath,
                )
            } else {
                NavigationDecision(
                    NavigationDisposition.BLOCK,
                    classification.kind,
                    if (classification.blockReason != BlockReason.NONE) {
                        classification.blockReason
                    } else {
                        BlockReason.INTENT_REQUIRED
                    },
                )
            }
        }

        return when (classification.kind) {
            RouteKind.DIRECT_INBOX,
            RouteKind.DIRECT_THREAD,
            RouteKind.DIRECT_REQUESTS,
            RouteKind.DIRECT_NEW,
            -> NavigationDecision(NavigationDisposition.ALLOW_DIRECT, classification.kind, safePath = safePath)

            RouteKind.INSTAGRAM_SHARED_REEL,
            RouteKind.INSTAGRAM_SHARED_POST,
            -> NavigationDecision(
                NavigationDisposition.BLOCK,
                classification.kind,
                BlockReason.INSTAGRAM_CONTENT,
            )

            RouteKind.AUTH_LOGIN,
            RouteKind.AUTH_CHALLENGE,
            RouteKind.AUTH_RECOVERY,
            RouteKind.AUTH_CONSENT,
            -> NavigationDecision(NavigationDisposition.ALLOW_AUTH, classification.kind, safePath = safePath)

            RouteKind.EXTERNAL_HTTPS -> externalDecision(classification, mode)
            else -> NavigationDecision(
                NavigationDisposition.BLOCK,
                classification.kind,
                classification.blockReason,
            )
        }
    }

    override fun isTrustedTopLevelOrigin(rawUrl: String?): Boolean =
        classifier.isTrustedInstagramOrigin(rawUrl)

    override fun allowSubframe(
        rawUrl: String?,
        mode: PolicyMode,
        context: NavigationContext,
    ): Boolean = classifier.isTrustedInstagramOrigin(rawUrl) &&
        evaluate(rawUrl, mode, context).mayLoadInWebView

    fun createSharedContentApproval(
        rawSharedUrl: String,
        rawOriginThreadUrl: String,
        bridgeNonce: String,
        createdAtElapsedRealtimeMs: Long,
    ): ApprovedInstagramSharedContent? {
        if (!bridgeNonce.matches(BRIDGE_NONCE)) return null
        val identity = classifier.sharedContentIdentity(rawSharedUrl) ?: return null
        val thread = classifier.classify(rawOriginThreadUrl)
        if (thread.kind != RouteKind.DIRECT_THREAD || thread.normalized == null) return null
        return ApprovedInstagramSharedContent(
            kind = identity.kind,
            contentId = identity.contentId,
            canonicalPath = identity.canonicalPath,
            originatingThreadUrl = primaryOrigin + thread.normalized.path,
            bridgeNonce = bridgeNonce,
            createdAtElapsedRealtimeMs = createdAtElapsedRealtimeMs,
        )
    }

    fun viewerUrl(approval: ApprovedInstagramSharedContent): String =
        primaryOrigin + approval.canonicalPath + "#clearfeed_shared=" + approval.bridgeNonce

    private fun isApprovedSharedContent(
        classification: RouteClassification,
        approved: ApprovedInstagramSharedContent?,
    ): Boolean {
        if (approved == null || classification.normalized == null) return false
        val identity = classifier.sharedContentIdentity(classification.normalized) ?: return false
        return identity.kind == approved.kind &&
            identity.contentId == approved.contentId &&
            identity.canonicalPath == approved.canonicalPath &&
            classification.normalized.fragment == "clearfeed_shared=${approved.bridgeNonce}" &&
            classifier.classify(approved.originatingThreadUrl).kind == RouteKind.DIRECT_THREAD
    }

    private fun externalDecision(
        classification: RouteClassification,
        mode: PolicyMode,
    ): NavigationDecision {
        val host = classification.normalized?.host.orEmpty()
        val managed = ProtectedSocialHosts.contains(host) || ProtectedSocialHosts.looksDeceptive(host)
        val mayPrompt = mode != PolicyMode.AUTHENTICATING && !managed
        return NavigationDecision(
            if (mayPrompt) NavigationDisposition.OPEN_EXTERNALLY else NavigationDisposition.BLOCK,
            classification.kind,
            if (managed) BlockReason.MANAGED_SOCIAL_ESCAPE else BlockReason.DISALLOWED_HOST,
        )
    }

    companion object {
        private val BRIDGE_NONCE = Regex("[a-f0-9]{32}")
        const val INBOX_URL = "https://www.instagram.com/direct/inbox/"
        const val LOGIN_URL =
            "https://www.instagram.com/accounts/login/?next=%2Fdirect%2Finbox%2F"
    }
}

data class ApprovedInstagramSharedContent(
    val kind: InstagramSharedContentKind,
    val contentId: String,
    val canonicalPath: String,
    val originatingThreadUrl: String,
    val bridgeNonce: String,
    val createdAtElapsedRealtimeMs: Long,
)
