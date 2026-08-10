package dev.directonly.app.policy

import dev.directonly.app.model.BlockReason
import dev.directonly.app.model.NavigationDecision
import dev.directonly.app.model.NavigationDisposition
import dev.directonly.app.model.PolicyMode
import dev.directonly.app.model.RouteKind
import dev.directonly.app.model.SocialPlatform

class YouTubeNavigationPolicy : PlatformNavigationPolicy {
    override val platform = SocialPlatform.YOUTUBE
    override val safeRootUrl = SUBSCRIPTIONS_URL
    override val loginUrl = SUBSCRIPTIONS_URL
    override val primaryOrigin = "https://m.youtube.com"
    override val trustedOriginRules = setOf(
        "https://m.youtube.com",
        "https://www.youtube.com",
        "https://youtube.com",
        "https://accounts.google.com",
        "https://consent.google.com",
        "https://consent.youtube.com",
    )

    private val youtubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com")
    private val authHosts = setOf("accounts.google.com", "consent.google.com", "consent.youtube.com")
    private val authPrefixes = listOf(
        "/signin/", "/ServiceLogin/", "/AccountChooser/", "/v3/signin/",
        "/o/oauth2/", "/privacy/", "/m/",
    )
    private val blockedPrefixes = listOf(
        "/shorts/", "/feed/trending/", "/feed/history/", "/feed/library/",
        "/playlist/", "/gaming/", "/live/", "/channel/", "/@",
    )

    override fun evaluate(
        rawUrl: String?,
        mode: PolicyMode,
        context: NavigationContext,
    ): NavigationDecision {
        val normalized = validUrl(rawUrl) ?: return invalidDecision(rawUrl)
        if (normalized.scheme != "https") return blocked(RouteKind.INVALID, BlockReason.DISALLOWED_SCHEME)

        if (normalized.host in authHosts) {
            val allowed = authPrefixes.any(normalized.path::startsWith)
            return if (allowed) allowedAuth(normalized) else blocked(RouteKind.INVALID, BlockReason.UNKNOWN_YOUTUBE_ROUTE)
        }
        if (normalized.host !in youtubeHosts) return external(normalized, mode)

        return when {
            normalized.path == "/feed/subscriptions/" -> allowedContent(RouteKind.YOUTUBE_SUBSCRIPTIONS, normalized)
            normalized.path == "/results/" -> allowedContent(RouteKind.YOUTUBE_SEARCH, normalized)
            normalized.path == "/watch/" -> evaluateWatch(normalized, context)
            normalized.path == "/signin/" || normalized.path.startsWith("/o/oauth2/") -> allowedAuth(normalized)
            normalized.path == "/" || blockedPrefixes.any(normalized.path::startsWith) ->
                blocked(RouteKind.BLOCKED_YOUTUBE_CONTENT, BlockReason.YOUTUBE_CONTENT)
            else -> blocked(RouteKind.UNKNOWN_YOUTUBE, BlockReason.UNKNOWN_YOUTUBE_ROUTE)
        }
    }

    override fun isTrustedTopLevelOrigin(rawUrl: String?): Boolean {
        val normalized = validUrl(rawUrl) ?: return false
        return normalized.scheme == "https" && (normalized.host in youtubeHosts || normalized.host in authHosts)
    }

    override fun allowSubframe(
        rawUrl: String?,
        mode: PolicyMode,
        context: NavigationContext,
    ): Boolean {
        val normalized = validUrl(rawUrl) ?: return false
        if (normalized.scheme != "https") return false
        val host = normalized.host
        return host in youtubeHosts || host in authHosts ||
            host.endsWith(".googlevideo.com") || host.endsWith(".ytimg.com") ||
            host.endsWith(".gstatic.com") || host.endsWith(".google.com")
    }

    private fun evaluateWatch(
        normalized: NormalizedUrl,
        context: NavigationContext,
    ): NavigationDecision {
        val videoId = queryValue(normalized.query, "v")
        val intentionallyAllowed = videoId != null && videoId == context.allowedYouTubeVideoId
        return if (intentionallyAllowed) {
            allowedContent(RouteKind.YOUTUBE_WATCH, normalized)
        } else {
            blocked(RouteKind.BLOCKED_YOUTUBE_CONTENT, BlockReason.INTENT_REQUIRED)
        }
    }

    private fun external(normalized: NormalizedUrl, mode: PolicyMode): NavigationDecision {
        val managed = ProtectedSocialHosts.contains(normalized.host) ||
            ProtectedSocialHosts.looksDeceptive(normalized.host)
        val mayPrompt = mode != PolicyMode.AUTHENTICATING && !managed
        return NavigationDecision(
            if (mayPrompt) NavigationDisposition.OPEN_EXTERNALLY else NavigationDisposition.BLOCK,
            RouteKind.EXTERNAL_HTTPS,
            if (managed) BlockReason.MANAGED_SOCIAL_ESCAPE else BlockReason.DISALLOWED_HOST,
        )
    }

    private fun allowedContent(kind: RouteKind, normalized: NormalizedUrl) =
        NavigationDecision(NavigationDisposition.ALLOW_CONTENT, kind, safePath = normalized.path)

    private fun allowedAuth(normalized: NormalizedUrl) =
        NavigationDecision(NavigationDisposition.ALLOW_AUTH, RouteKind.AUTH_LOGIN, safePath = normalized.path)

    private fun blocked(kind: RouteKind, reason: BlockReason) =
        NavigationDecision(NavigationDisposition.BLOCK, kind, reason)

    private fun validUrl(rawUrl: String?): NormalizedUrl? =
        (UrlNormalizer.normalize(rawUrl) as? UrlNormalizationResult.Valid)?.value

    private fun invalidDecision(rawUrl: String?): NavigationDecision {
        val result = UrlNormalizer.normalize(rawUrl)
        val reason = if (result is UrlNormalizationResult.Invalid) {
            when (result.reason) {
                NormalizationFailure.EXPLICIT_PORT -> BlockReason.DISALLOWED_PORT
                NormalizationFailure.UNSAFE_PATH_ENCODING -> BlockReason.UNSAFE_ENCODING
                else -> BlockReason.INVALID_URL
            }
        } else BlockReason.INVALID_URL
        return blocked(RouteKind.INVALID, reason)
    }

    private fun queryValue(query: String?, name: String): String? = query
        ?.split('&')
        ?.firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', "")
        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,32}")) }

    companion object {
        const val SUBSCRIPTIONS_URL =
            "https://m.youtube.com/feed/subscriptions?persist_app=1&app=m"
    }
}
