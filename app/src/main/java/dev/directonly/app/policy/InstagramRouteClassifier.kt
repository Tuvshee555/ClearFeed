package dev.directonly.app.policy

import dev.directonly.app.model.BlockReason
import dev.directonly.app.model.RouteKind

data class RouteClassification(
    val kind: RouteKind,
    val blockReason: BlockReason = BlockReason.NONE,
    val normalized: NormalizedUrl? = null,
)

class InstagramRouteClassifier {
    private val instagramHosts = setOf("instagram.com", "www.instagram.com")
    private val directThread = Regex("^/direct/t/[A-Za-z0-9_-]+/$")
    private val challengeRoute = Regex("^/(?:challenge|checkpoint)(?:/[A-Za-z0-9_-]+)*/$")
    private val sharedReel = Regex("^/(?:reel|reels)/([A-Za-z0-9_-]+)/$")
    private val sharedPost = Regex("^/p/([A-Za-z0-9_-]+)/$")

    private val explicitBlockedPrefixes = listOf(
        "/reel/",
        "/reels/",
        "/explore/",
        "/stories/",
        "/p/",
        "/tags/",
        "/locations/",
        "/shopping/",
        "/accounts/activity/",
        "/accounts/edit/",
    )

    fun classify(rawUrl: String?): RouteClassification {
        val normalized = when (val result = UrlNormalizer.normalize(rawUrl)) {
            is UrlNormalizationResult.Valid -> result.value
            is UrlNormalizationResult.Invalid -> {
                val reason = when (result.reason) {
                    NormalizationFailure.EXPLICIT_PORT -> BlockReason.DISALLOWED_PORT
                    NormalizationFailure.UNSAFE_PATH_ENCODING -> BlockReason.UNSAFE_ENCODING
                    else -> BlockReason.INVALID_URL
                }
                return RouteClassification(RouteKind.INVALID, reason)
            }
        }

        if (normalized.scheme != "https") {
            return RouteClassification(RouteKind.INVALID, BlockReason.DISALLOWED_SCHEME, normalized)
        }
        if (normalized.host !in instagramHosts && normalized.host.contains("instagram")) {
            return RouteClassification(RouteKind.INVALID, BlockReason.DISALLOWED_HOST, normalized)
        }
        if (normalized.host !in instagramHosts) {
            return RouteClassification(RouteKind.EXTERNAL_HTTPS, BlockReason.DISALLOWED_HOST, normalized)
        }

        val path = normalized.path
        val kind = when {
            path == "/direct/inbox/" -> RouteKind.DIRECT_INBOX
            path == "/direct/requests/" -> RouteKind.DIRECT_REQUESTS
            path == "/direct/new/" -> RouteKind.DIRECT_NEW
            directThread.matches(path) -> RouteKind.DIRECT_THREAD
            sharedReel.matches(path) -> RouteKind.INSTAGRAM_SHARED_REEL
            sharedPost.matches(path) -> RouteKind.INSTAGRAM_SHARED_POST

            path == "/accounts/login/" || path == "/accounts/login/two_factor/" ||
                path == "/accounts/onetap/" -> RouteKind.AUTH_LOGIN

            challengeRoute.matches(path) || path == "/accounts/confirm_email/" ||
                path == "/accounts/confirm_phone/" -> RouteKind.AUTH_CHALLENGE

            path.startsWith("/accounts/password/reset/") ||
                path == "/accounts/account_recovery/" -> RouteKind.AUTH_RECOVERY

            path == "/accounts/consent/" || path.startsWith("/privacy/checks/") ->
                RouteKind.AUTH_CONSENT

            path == "/" || explicitBlockedPrefixes.any(path::startsWith) ->
                RouteKind.BLOCKED_INSTAGRAM_CONTENT

            else -> RouteKind.UNKNOWN_INSTAGRAM
        }

        val reason = when (kind) {
            RouteKind.BLOCKED_INSTAGRAM_CONTENT -> BlockReason.INSTAGRAM_CONTENT
            RouteKind.UNKNOWN_INSTAGRAM -> BlockReason.UNKNOWN_INSTAGRAM_ROUTE
            else -> BlockReason.NONE
        }
        return RouteClassification(kind, reason, normalized)
    }

    fun isTrustedInstagramOrigin(rawUrl: String?): Boolean {
        val normalized = (UrlNormalizer.normalize(rawUrl) as? UrlNormalizationResult.Valid)?.value
            ?: return false
        return normalized.scheme == "https" && normalized.host in instagramHosts
    }

    fun sharedContentIdentity(rawUrl: String?): InstagramSharedContentIdentity? {
        val normalized = (UrlNormalizer.normalize(rawUrl) as? UrlNormalizationResult.Valid)?.value
            ?: return null
        if (normalized.scheme != "https" || normalized.host !in instagramHosts) return null
        return sharedContentIdentity(normalized)
    }

    fun sharedContentIdentity(normalized: NormalizedUrl): InstagramSharedContentIdentity? {
        val reelId = sharedReel.matchEntire(normalized.path)?.groupValues?.get(1)
        if (reelId != null) {
            return InstagramSharedContentIdentity(
                kind = InstagramSharedContentKind.REEL,
                contentId = reelId,
                canonicalPath = "/reel/$reelId/",
            )
        }
        val postId = sharedPost.matchEntire(normalized.path)?.groupValues?.get(1)
        if (postId != null) {
            return InstagramSharedContentIdentity(
                kind = InstagramSharedContentKind.POST,
                contentId = postId,
                canonicalPath = "/p/$postId/",
            )
        }
        return null
    }
}

enum class InstagramSharedContentKind {
    REEL,
    POST,
}

data class InstagramSharedContentIdentity(
    val kind: InstagramSharedContentKind,
    val contentId: String,
    val canonicalPath: String,
)
