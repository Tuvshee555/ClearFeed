package dev.directonly.app.policy

import dev.directonly.app.model.BlockReason
import dev.directonly.app.model.PolicyMode
import dev.directonly.app.model.RouteKind
import dev.directonly.app.model.SocialPlatform
import java.net.IDN
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object ProtectedSocialHosts {
    private val instagramBases = setOf("instagram.com", "instagr.am")
    private val youtubeBases = setOf("youtube.com", "youtu.be", "youtube-nocookie.com")
    private val facebookBases = setOf("facebook.com", "messenger.com", "fb.watch", "fb.me")
    private val youtubeAuthHosts = setOf("accounts.google.com", "consent.google.com")
    private val protectedBrandLabels = setOf("instagram", "youtube", "youtu", "facebook", "messenger")

    fun platformForHost(rawHost: String): SocialPlatform? {
        val host = normalizedHost(rawHost) ?: return null
        return when {
            instagramBases.any { host.isDomainOrSubdomainOf(it) } -> SocialPlatform.INSTAGRAM
            youtubeBases.any { host.isDomainOrSubdomainOf(it) } || host in youtubeAuthHosts ->
                SocialPlatform.YOUTUBE
            facebookBases.any { host.isDomainOrSubdomainOf(it) } -> SocialPlatform.FACEBOOK
            else -> null
        }
    }

    fun contains(rawHost: String): Boolean = platformForHost(rawHost) != null

    fun isYouTubeAuthenticationHost(rawHost: String): Boolean =
        normalizedHost(rawHost) in youtubeAuthHosts

    fun looksDeceptive(rawHost: String): Boolean {
        val host = normalizedHost(rawHost) ?: return true
        return platformForHost(host) == null && host.split('.').any { label ->
            protectedBrandLabels.any(label::contains)
        }
    }

    private fun normalizedHost(rawHost: String): String? = try {
        IDN.toASCII(rawHost.trim().trimEnd('.'), IDN.USE_STD3_ASCII_RULES)
            .lowercase(Locale.ROOT)
            .takeIf(String::isNotBlank)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun String.isDomainOrSubdomainOf(base: String): Boolean =
        this == base || endsWith(".$base")
}

sealed interface ProtectedLinkDecision {
    data class RouteInternally(
        val platform: SocialPlatform,
        val url: String,
        val navigationContext: NavigationContext = NavigationContext(),
    ) : ProtectedLinkDecision

    data class ExternalWeb(val url: String) : ProtectedLinkDecision

    data class Block(val reason: BlockReason) : ProtectedLinkDecision
}

class ProtectedSocialLinkRouter(
    private val policies: PlatformPolicyRegistry,
) {
    fun decide(
        rawUrl: String?,
        sourcePlatform: SocialPlatform,
        userGesture: Boolean,
        sourceMode: PolicyMode = PolicyMode.CONTENT,
    ): ProtectedLinkDecision {
        val recovered = recoverHttps(rawUrl, 0)
            ?: return ProtectedLinkDecision.Block(BlockReason.DISALLOWED_SCHEME)
        val destination = unwrapKnownRedirect(recovered, 0)
        val normalized = (UrlNormalizer.normalize(destination) as? UrlNormalizationResult.Valid)?.value
            ?: return ProtectedLinkDecision.Block(BlockReason.INVALID_URL)
        if (normalized.scheme != "https") {
            return ProtectedLinkDecision.Block(BlockReason.DISALLOWED_SCHEME)
        }

        val targetPlatform = ProtectedSocialHosts.platformForHost(normalized.host)
        if (targetPlatform == null) {
            if (ProtectedSocialHosts.looksDeceptive(normalized.host)) {
                return ProtectedLinkDecision.Block(BlockReason.DISALLOWED_HOST)
            }
            return if (userGesture && sourceMode != PolicyMode.AUTHENTICATING) {
                ProtectedLinkDecision.ExternalWeb(destination)
            } else {
                ProtectedLinkDecision.Block(BlockReason.INTENT_REQUIRED)
            }
        }
        if (!userGesture) return ProtectedLinkDecision.Block(BlockReason.INTENT_REQUIRED)
        if (ProtectedSocialHosts.isYouTubeAuthenticationHost(normalized.host) &&
            sourcePlatform != SocialPlatform.YOUTUBE
        ) {
            return ProtectedLinkDecision.Block(BlockReason.MANAGED_SOCIAL_ESCAPE)
        }

        val canonical = canonicalize(targetPlatform, normalized, destination)
            ?: return ProtectedLinkDecision.Block(BlockReason.UNKNOWN_YOUTUBE_ROUTE)
        val canonicalNormalized =
            (UrlNormalizer.normalize(canonical) as? UrlNormalizationResult.Valid)?.value
                ?: return ProtectedLinkDecision.Block(BlockReason.INVALID_URL)
        val targetContext = when {
            targetPlatform == SocialPlatform.YOUTUBE && canonicalNormalized.path == "/watch/" -> {
                if (sourcePlatform == SocialPlatform.YOUTUBE) {
                    return ProtectedLinkDecision.Block(BlockReason.INTENT_REQUIRED)
                }
                val videoId = queryValue(canonicalNormalized.query, "v")
                    ?.takeIf { it.matches(YOUTUBE_VIDEO_ID) }
                    ?: return ProtectedLinkDecision.Block(BlockReason.YOUTUBE_CONTENT)
                NavigationContext(allowedYouTubeVideoId = videoId, userGesture = true)
            }
            else -> NavigationContext(userGesture = true)
        }
        val policyDecision = policies.forPlatform(targetPlatform).evaluate(
            canonical,
            PolicyMode.CONTENT,
            targetContext,
        )
        return if (policyDecision.mayLoadInWebView) {
            ProtectedLinkDecision.RouteInternally(targetPlatform, canonical, targetContext)
        } else {
            ProtectedLinkDecision.Block(
                policyDecision.blockReason.takeIf { it != BlockReason.NONE }
                    ?: BlockReason.MANAGED_SOCIAL_ESCAPE,
            )
        }
    }

    private fun canonicalize(
        platform: SocialPlatform,
        normalized: NormalizedUrl,
        destination: String,
    ): String? = when (platform) {
        SocialPlatform.INSTAGRAM -> destination
        SocialPlatform.FACEBOOK -> if (
            normalized.host.isFacebookRootHost() &&
            (normalized.path == "/" || normalized.path == "/home.php/")
        ) {
            policies.forPlatform(SocialPlatform.FACEBOOK).safeRootUrl
        } else {
            destination
        }
        SocialPlatform.YOUTUBE -> when {
            normalized.host == "youtu.be" || normalized.host.endsWith(".youtu.be") -> {
                val videoId = normalized.path.trim('/').takeIf { it.matches(YOUTUBE_VIDEO_ID) }
                    ?: return null
                "https://m.youtube.com/watch?v=$videoId"
            }
            else -> destination
        }
    }

    private fun unwrapKnownRedirect(rawUrl: String, depth: Int): String {
        if (depth >= MAX_UNWRAP_DEPTH) return rawUrl
        val normalized = (UrlNormalizer.normalize(rawUrl) as? UrlNormalizationResult.Valid)?.value
            ?: return rawUrl
        val target = when {
            normalized.host in setOf("l.facebook.com", "lm.facebook.com") &&
                normalized.path == "/l.php/" -> queryValue(normalized.query, "u")
            normalized.host == "l.instagram.com" && normalized.path == "/" ->
                queryValue(normalized.query, "u")
            ProtectedSocialHosts.platformForHost(normalized.host) == SocialPlatform.YOUTUBE &&
                normalized.path == "/redirect/" ->
                queryValue(normalized.query, "q") ?: queryValue(normalized.query, "url")
            else -> null
        } ?: return rawUrl
        val recovered = recoverHttps(target, 0) ?: return rawUrl
        return unwrapKnownRedirect(recovered, depth + 1)
    }

    private fun recoverHttps(rawUrl: String?, depth: Int): String? {
        if (rawUrl.isNullOrBlank() || rawUrl.length > MAX_URL_LENGTH || depth > MAX_DEEP_LINK_DEPTH) {
            return null
        }
        val trimmed = rawUrl.trim()
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return null
        }
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "https" -> uri.toASCIIString()
            "intent" -> recoverIntent(trimmed, depth + 1)
            "youtube", "vnd.youtube" -> recoverYouTubeScheme(uri)
            "instagram", "fb", "messenger" -> recoverHttpsParameter(uri, depth + 1)
            else -> null
        }
    }

    private fun recoverIntent(rawUrl: String, depth: Int): String? {
        val marker = rawUrl.indexOf("#Intent;")
        if (marker <= 0 || !rawUrl.endsWith(";end")) return null
        val base = rawUrl.substring(0, marker)
        val attributes = rawUrl.substring(marker + "#Intent;".length, rawUrl.length - ";end".length)
            .split(';')
            .mapNotNull { entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0) null else entry.substring(0, separator) to entry.substring(separator + 1)
            }
            .toMap()
        val declaredScheme = attributes["scheme"]?.lowercase(Locale.ROOT)
        val payload = base.substringAfter("://", "").takeIf(String::isNotBlank) ?: return null
        val reconstructed = when (declaredScheme) {
            "https" -> "https://$payload"
            "youtube", "vnd.youtube", "instagram", "fb", "messenger" ->
                "$declaredScheme://$payload"
            else -> null
        }
        recoverHttps(reconstructed, depth)?.let { return it }
        val fallback = attributes["S.browser_fallback_url"]?.let(::percentDecode) ?: return null
        return recoverHttps(fallback, depth)
    }

    private fun recoverYouTubeScheme(uri: URI): String? {
        val host = uri.host.orEmpty()
        val videoId = when {
            host.equals("watch", ignoreCase = true) -> queryValue(uri.rawQuery, "v")
            host.matches(YOUTUBE_VIDEO_ID) && uri.rawPath.isNullOrEmpty() -> host
            else -> null
        }?.takeIf { it.matches(YOUTUBE_VIDEO_ID) } ?: return null
        return "https://m.youtube.com/watch?v=$videoId"
    }

    private fun recoverHttpsParameter(uri: URI, depth: Int): String? {
        val names = listOf("url", "link", "href", "target", "browser_fallback_url")
        val target = names.firstNotNullOfOrNull { queryValue(uri.rawQuery, it) } ?: return null
        return recoverHttps(target, depth)
    }

    private fun queryValue(rawQuery: String?, name: String): String? = rawQuery
        ?.split('&')
        ?.firstNotNullOfOrNull { pair ->
            val rawName = pair.substringBefore('=')
            if (percentDecode(rawName) == name) percentDecode(pair.substringAfter('=', "")) else null
        }
        ?.takeIf(String::isNotBlank)

    private fun percentDecode(value: String): String? = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun String.isFacebookRootHost(): Boolean =
        this == "facebook.com" || this == "www.facebook.com" || this == "m.facebook.com"

    companion object {
        private val YOUTUBE_VIDEO_ID = Regex("[A-Za-z0-9_-]{6,32}")
        private const val MAX_URL_LENGTH = 8_192
        private const val MAX_DEEP_LINK_DEPTH = 3
        private const val MAX_UNWRAP_DEPTH = 2
    }
}
