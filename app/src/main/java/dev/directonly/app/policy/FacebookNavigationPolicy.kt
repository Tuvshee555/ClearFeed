package dev.directonly.app.policy

import dev.directonly.app.model.BlockReason
import dev.directonly.app.model.NavigationDecision
import dev.directonly.app.model.NavigationDisposition
import dev.directonly.app.model.PolicyMode
import dev.directonly.app.model.RouteKind
import dev.directonly.app.model.SocialPlatform
import java.util.Locale

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
    // A Facebook username is an arbitrary single path segment, so profiles cannot be
    // recognized by shape alone. Everything Facebook reserves for its own surfaces has
    // to be named here, or a discovery directory would read as somebody's profile.
    private val reservedSingleSegments = setOf(
        "ads", "bookmarks", "developers", "help", "home.php", "login", "logout",
        "marketplace", "reel", "reels", "stories", "watch", "watch.php", "video",
        "video.php", "videos", "live", "gaming", "login.php", "logout.php",
        "settings", "saved", "fundraisers", "memories",
        // Discovery, recommendation and directory surfaces. Kept to segments Facebook
        // genuinely reserves: anything listed here can never be reached as a profile, so
        // plausible usernames (news, music, sports, notes, topic…) must stay off this list.
        "groups", "events", "pages", "dating", "games", "photos", "photo",
        "watch_videos", "watchparty", "friends_center", "friends", "notifications",
        "messages", "search", "explore", "discover", "feed", "story.php",
        "birthdays", "fundraiser", "hashtag", "gaming_video",
        "settings.php", "profile.php", "permalink.php", "photo.php",
        "api", "graphql", "ajax", "plugins",
    )
    // Facebook reserves these where a group or page identifier would otherwise sit.
    private val reservedSubSegments = setOf(
        "feed", "discover", "create", "joins", "browse", "search", "category",
        "categories", "your_groups", "invites", "requests", "calendar", "explore",
    )
    private val groupRoute = Regex("^/groups/[a-z0-9._-]+/(?:posts/[a-z0-9._-]+/)?$")
    private val eventRoute = Regex("^/events/[a-z0-9._-]+/$")
    private val pageRoute = Regex("^/pages/[a-z0-9._-]+/[a-z0-9._-]+/$")
    private val profileRoute = Regex("^/[a-z0-9._-]+/$")
    private val profilePostRoute = Regex("^/[a-z0-9._-]+/posts/[a-z0-9._-]+/$")

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
        if (normalized.host in messengerHosts) return classifyMessenger(normalized, mode)

        // Facebook resolves its paths case-insensitively, so `/Reels/` reaches the same
        // surface as `/reels/`. Match on a lowercased copy or a single capital letter
        // walks straight past every blocklist below and lands on the profile route.
        val path = normalized.path.lowercase(Locale.ROOT)
        return when {
            path == "/login.php/" || path == "/unified/login_via/app/" ||
                authPrefixes.any(path::startsWith) -> allowedAuth(normalized)
            path == "/" && isRedirectedLoginQuery(normalized.query) -> allowedAuth(normalized)
            path == "/" && isNewestFeedsQuery(normalized.query) ->
                allowedContent(RouteKind.FACEBOOK_FEED, normalized)
            // Facebook lands a freshly signed-in session on bare `/`, and mobile also
            // canonicalizes the verified newest-feed URL back to `/`. Accepting it only in
            // CONTENT mode meant the post-login landing was BLOCK (the mode is still
            // AUTHENTICATING at that moment), which bounced the user through
            // recoverToSafeRoot on every sign-in. The route is classified as the feed in any
            // mode; the navigator canonicalizes it to the newest-feeds query, and the guard
            // still enforces the eight-post limit on whatever renders.
            //
            // Only a *bare* root counts as the landing. A root carrying any other query is
            // asking for something specific — another sort order (`?sk=nf` is ranked Home),
            // or a redirect target (`?next=/reels/`) — and is still refused. That costs one
            // extra navigation through the canonical feed URL, which is cheap now that the
            // canonical URL reliably loads.
            path == "/" && normalized.query.isNullOrBlank() ->
                allowedContent(RouteKind.FACEBOOK_FEED, normalized)
            path == "/" || path == "/home.php/" ->
                blocked(RouteKind.BLOCKED_FACEBOOK_CONTENT, BlockReason.FACEBOOK_CONTENT)
            // Every video/discovery denial is evaluated before any allow branch, so an
            // allowed prefix such as `/search/` can never shadow `/search/videos/`.
            blockedPrefixes.any(path::startsWith) || isBlockedVideoSurface(path) ->
                blocked(RouteKind.BLOCKED_FACEBOOK_CONTENT, BlockReason.FACEBOOK_CONTENT)
            path == "/messages/" || path.startsWith("/messages/t/") ||
                path.startsWith("/messages/e2ee/t/") -> allowedContent(RouteKind.FACEBOOK_MESSAGES, normalized)
            path == "/notifications/" -> allowedContent(RouteKind.FACEBOOK_NOTIFICATIONS, normalized)
            path.startsWith("/search/") -> allowedContent(RouteKind.FACEBOOK_SEARCH, normalized)
            path == "/friends/" || path == "/friends/list/" || path == "/friends/requests/" ->
                allowedContent(RouteKind.FACEBOOK_FRIENDS, normalized)
            groupRoute.matches(path) && segmentAt(path, 1) !in reservedSubSegments ->
                allowedContent(RouteKind.FACEBOOK_GROUP, normalized)
            eventRoute.matches(path) && segmentAt(path, 1) !in reservedSubSegments ->
                allowedContent(RouteKind.FACEBOOK_EVENT, normalized)
            pageRoute.matches(path) && segmentAt(path, 1) !in reservedSubSegments ->
                allowedContent(RouteKind.FACEBOOK_PAGE, normalized)
            path == "/profile.php/" -> allowedContent(RouteKind.FACEBOOK_PAGE, normalized)
            path == "/permalink.php/" || path == "/photo.php/" || path == "/photo/" ||
                profilePostRoute.matches(path) -> allowedContent(RouteKind.FACEBOOK_POST, normalized)
            profileRoute.matches(path) && path.trim('/') !in reservedSingleSegments ->
                allowedContent(RouteKind.FACEBOOK_PAGE, normalized)
            else -> blocked(RouteKind.UNKNOWN_FACEBOOK, BlockReason.UNKNOWN_FACEBOOK_ROUTE)
        }
    }

    // Video surfaces that hang off an otherwise allowed prefix, e.g. Facebook's
    // video-only search tab.
    private fun isBlockedVideoSurface(path: String): Boolean =
        path.startsWith("/search/videos/") || path.startsWith("/search/live/") ||
            path.startsWith("/search/reels/")

    private fun segmentAt(path: String, index: Int): String =
        path.trim('/').split('/').getOrNull(index).orEmpty()

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

    private fun classifyMessenger(normalized: NormalizedUrl, mode: PolicyMode): NavigationDecision {
        val path = normalized.path.lowercase(Locale.ROOT)
        return when {
            path == "/login/password/" || authPrefixes.any(path::startsWith) -> allowedAuth(normalized)
            // The Messenger root is the sign-in page only while unauthenticated; once
            // signed in it is the inbox. Classifying it as auth in content mode left
            // attachments, camera and microphone refused on the real inbox.
            path == "/" -> if (mode == PolicyMode.AUTHENTICATING) {
                allowedAuth(normalized)
            } else {
                allowedContent(RouteKind.FACEBOOK_MESSAGES, normalized)
            }
            path == "/t/" || path.startsWith("/t/") || path == "/new/" ->
                allowedContent(RouteKind.FACEBOOK_MESSAGES, normalized)
            else -> blocked(RouteKind.UNKNOWN_FACEBOOK, BlockReason.UNKNOWN_FACEBOOK_ROUTE)
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
