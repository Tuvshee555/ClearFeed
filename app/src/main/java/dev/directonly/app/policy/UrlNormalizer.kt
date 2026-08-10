package dev.directonly.app.policy

import java.net.IDN
import java.net.URI
import java.util.Locale

data class NormalizedUrl(
    val scheme: String,
    val host: String,
    val path: String,
    val query: String?,
    val fragment: String?,
    val externalForm: String,
)

sealed interface UrlNormalizationResult {
    data class Valid(val value: NormalizedUrl) : UrlNormalizationResult
    data class Invalid(val reason: NormalizationFailure) : UrlNormalizationResult
}

enum class NormalizationFailure {
    MALFORMED,
    MISSING_HOST,
    USER_INFO,
    EXPLICIT_PORT,
    UNSAFE_PATH_ENCODING,
}

object UrlNormalizer {
    private val unsafeEncodedPath = Regex("%(?:2e|2f|5c|00)", RegexOption.IGNORE_CASE)
    private val malformedPercent = Regex("%(?![0-9a-fA-F]{2})")

    fun normalize(rawUrl: String?): UrlNormalizationResult {
        if (rawUrl.isNullOrBlank() || rawUrl.length > 8_192) {
            return UrlNormalizationResult.Invalid(NormalizationFailure.MALFORMED)
        }

        val uri = try {
            URI(rawUrl.trim())
        } catch (_: Exception) {
            return UrlNormalizationResult.Invalid(NormalizationFailure.MALFORMED)
        }

        if (!uri.isAbsolute || uri.host.isNullOrBlank()) {
            return UrlNormalizationResult.Invalid(NormalizationFailure.MISSING_HOST)
        }
        if (uri.rawUserInfo != null) {
            return UrlNormalizationResult.Invalid(NormalizationFailure.USER_INFO)
        }
        if (uri.port != -1) {
            return UrlNormalizationResult.Invalid(NormalizationFailure.EXPLICIT_PORT)
        }

        val rawPath = uri.rawPath.ifEmpty { "/" }
        if (rawPath.contains('\\') || malformedPercent.containsMatchIn(rawPath) ||
            unsafeEncodedPath.containsMatchIn(rawPath)
        ) {
            return UrlNormalizationResult.Invalid(NormalizationFailure.UNSAFE_PATH_ENCODING)
        }

        val host = try {
            IDN.toASCII(uri.host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        } catch (_: IllegalArgumentException) {
            return UrlNormalizationResult.Invalid(NormalizationFailure.MALFORMED)
        }
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val path = normalizePath(rawPath)

        return UrlNormalizationResult.Valid(
            NormalizedUrl(
                scheme = scheme,
                host = host,
                path = path,
                query = uri.rawQuery,
                fragment = uri.rawFragment,
                externalForm = uri.toASCIIString(),
            ),
        )
    }

    private fun normalizePath(rawPath: String): String {
        val withLeadingSlash = if (rawPath.startsWith('/')) rawPath else "/$rawPath"
        if (withLeadingSlash == "/") return "/"
        return if (withLeadingSlash.endsWith('/')) withLeadingSlash else "$withLeadingSlash/"
    }
}
