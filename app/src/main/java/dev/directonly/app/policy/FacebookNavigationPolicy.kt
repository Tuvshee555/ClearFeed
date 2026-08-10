package dev.directonly.app.policy

import dev.directonly.app.model.BlockReason
import dev.directonly.app.model.NavigationDecision
import dev.directonly.app.model.NavigationDisposition
import dev.directonly.app.model.PolicyMode
import dev.directonly.app.model.RouteKind
import dev.directonly.app.model.SocialPlatform

class FacebookNavigationPolicy : PlatformNavigationPolicy {
    override val platform = SocialPlatform.FACEBOOK
    override val safeRootUrl = FEED_URL
    override val loginUrl = LOGIN_URL
    override val primaryOrigin = "https://m.facebook.com"
    override val trustedOriginRules = setOf(
        "https://www.facebook.com",
        "https://facebook.com",
        "https://m.facebook.com",
        "https://www.messenger.com",
        "https://messenger.com",
    )

    private val facebookHosts = setOf("facebook.com", "www.facebook.com", "m.facebook.com")
    private val messengerHosts = setOf("messenger.com", "www.messenger.com")
    private val authPrefixes = listOf(
        "/login/", "/checkpoint/", "/recover/", "/two_step_verification/",
        "/privacy/consent/", "/cookie/consent/",
    )
    private val blockedPrefixes = listOf(
        "/reel/", "/reels/", "/watch/", "/watch.php/", "/video/", "/video.php/", "/videos/", "/live/",
        "/stories/", "/story.php/", "/marketplace/", "/gaming/",
    )
    private val reservedSingleSegments = setOf(
        "ads", "bookmarks", "developers", "help", "home.php", "login", "logout",
        "marketplace", "reel", "reels", "stories", "watch", "watch.php", "video",
        "video.php", "videos", "live", "gaming", "login.php", "logout.php",
        "settings", "saved", "fundraisers", "memories",
    )
    private val groupRoute = Regex("^/groups/[A-Za-z0-9._-]+/(?:posts/[A-Za-z0-9._-]+/)?$")
    private val eventRoute = Regex("^/events/[A-Za-z0-9._-]+/$")
    private val pageRoute = Regex("^/pages/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/$")
    private val profileRoute = Regex("^/[A-Za-z0-9._-]+/$")
    private val profilePostRoute = Regex("^/[A-Za-z0-9._-]+/posts/[A-Za-z0-9._-]+/$")

    override fun evaluate(
        rawUrl: String?,
        mode: PolicyMode,
        context: NavigationContext,
    ): NavigationDecision {
        val normalized = validUrl(rawUrl) ?: return invalidDecision(rawUrl)
        if (normalized.scheme != "https") return blocked(RouteKind.INVALID, BlockReason.DISALLOWED_SCHEME)
        if (normalized.host !in facebookHosts && normalized.host !in messengerHosts) {
            return external(normalized, mode)
        }
        if (normalized.host in messengerHosts) return classifyMessenger(normalized)

        val path = normalized.path
        return when {
            path == "/login.php/" || path == "/unified/login_via/app/" ||
                authPrefixes.any(path::startsWith) -> allowedAuth(normalized)
            path == "/" && isRedirectedLoginQuery(normalized.query) -> allowedAuth(normalized)
            path == "/" && isNewestFeedsQuery(normalized.query) ->
                allowedContent(RouteKind.FACEBOOK_FEED, normalized)
            path == "/" || path == "/home.php/" ->
                blocked(RouteKind.BLOCKED_FACEBOOK_CONTENT, BlockReason.FACEBOOK_CONTENT)
            path == "/messages/" || path.startsWith("/messages/t/") ||
                path.startsWith("/messages/e2ee/t/") -> allowedContent(RouteKind.FACEBOOK_MESSAGES, normalized)
            path == "/notifications/" -> allowedContent(RouteKind.FACEBOOK_NOTIFICATIONS, normalized)
            path.startsWith("/search/") -> allowedContent(RouteKind.FACEBOOK_SEARCH, normalized)
            path == "/friends/" || path == "/friends/list/" || path == "/friends/requests/" ->
                allowedContent(RouteKind.FACEBOOK_FRIENDS, normalized)
            groupRoute.matches(path) -> allowedContent(RouteKind.FACEBOOK_GROUP, normalized)
            eventRoute.matches(path) -> allowedContent(RouteKind.FACEBOOK_EVENT, normalized)
            blockedPrefixes.any(path::startsWith) ->
                blocked(RouteKind.BLOCKED_FACEBOOK_CONTENT, BlockReason.FACEBOOK_CONTENT)
            pageRoute.matches(path) -> allowedContent(RouteKind.FACEBOOK_PAGE, normalized)
            path == "/profile.php/" -> allowedContent(RouteKind.FACEBOOK_PAGE, normalized)
            path == "/permalink.php/" || path == "/photo.php/" || path == "/photo/" ||
                profilePostRoute.matches(path) -> allowedContent(RouteKind.FACEBOOK_POST, normalized)
            profileRoute.matches(path) && path.trim('/') !in reservedSingleSegments ->
                allowedContent(RouteKind.FACEBOOK_PAGE, normalized)
            else -> blocked(RouteKind.UNKNOWN_FACEBOOK, BlockReason.UNKNOWN_FACEBOOK_ROUTE)
        }
    }

    override fun isTrustedTopLevelOrigin(rawUrl: String?): Boolean {
        val normalized = validUrl(rawUrl) ?: return false
        return normalized.scheme == "https" &&
            (normalized.host in facebookHosts || normalized.host in messengerHosts)
    }

    override fun allowSubframe(
        rawUrl: String?,
        mode: PolicyMode,
        context: NavigationContext,
    ): Boolean {
        val normalized = validUrl(rawUrl) ?: return false
        if (normalized.scheme != "https") return false
        val host = normalized.host
        return host in facebookHosts || host in messengerHosts ||
            host.endsWith(".fbcdn.net") || host.endsWith(".facebook.com")
    }

    private fun classifyMessenger(normalized: NormalizedUrl): NavigationDecision = when {
        normalized.path == "/" || normalized.path == "/login/password/" ||
            authPrefixes.any(normalized.path::startsWith) -> allowedAuth(normalized)
        normalized.path == "/t/" || normalized.path.startsWith("/t/") ||
            normalized.path == "/new/" -> allowedContent(RouteKind.FACEBOOK_MESSAGES, normalized)
        else -> blocked(RouteKind.UNKNOWN_FACEBOOK, BlockReason.UNKNOWN_FACEBOOK_ROUTE)
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

    private fun isNewestFeedsQuery(query: String?): Boolean {
        val values = query.orEmpty().split('&').associate { pair ->
            pair.substringBefore('=') to pair.substringAfter('=', "")
        }
        return values["sk"] == "h_chr" && values["filter"] in FEEDS_FILTERS
    }

    private fun isRedirectedLoginQuery(query: String?): Boolean {
        val keys = query.orEmpty().split('&')
            .map { it.substringBefore('=') }
            .filter(String::isNotBlank)
            .toSet()
        return "_rdr" in keys && keys.all { it == "_rdr" || it == "__mmr" }
    }

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

    companion object {
        private val FEEDS_FILTERS = setOf("all", "favorites", "friends", "groups", "pages")
        const val FACEBOOK_FEED_POST_LIMIT = 8
        const val FEED_URL = "https://m.facebook.com/?filter=all&sk=h_chr"
        const val LOGIN_URL = "https://m.facebook.com/login/"
    }
}
