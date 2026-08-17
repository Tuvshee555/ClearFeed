package dev.directonly.app.diagnostics

import java.net.URI
import java.util.Locale

data class DiagnosticRecord(
    val code: String,
    val service: String,
    val location: String,
    val detail: String,
)

data class DiagnosticEnvironment(
    val appVersion: String,
    val androidVersion: String,
    val webViewVersion: String,
)

fun buildDiagnosticReport(
    environment: DiagnosticEnvironment,
    record: DiagnosticRecord?,
): String = buildString {
    appendLine("ClearFeed diagnostics")
    appendLine("App: ${environment.appVersion}")
    appendLine("Android: ${environment.androidVersion}")
    appendLine("System WebView: ${environment.webViewVersion}")
    if (record == null) {
        append("Latest event: none recorded")
    } else {
        appendLine("Latest event: ${record.code}")
        appendLine("Service: ${record.service}")
        appendLine("Location: ${record.location}")
        append("Detail: ${record.detail}")
    }
}

/** Prefix marking a normal progress stage rather than a failure. */
const val PROGRESS_CODE_PREFIX = "CF-STAGE-"

/**
 * Whether a code describes an actual failure.
 *
 * Failure codes are partly dynamic (`CF-NET-<code>`, `CF-HTTP-<status>`, `CF-TLS-<err>`,
 * `CF-SAFE-<threat>`), so they cannot be enumerated. Progress codes all share one prefix
 * and are the ones that matter for privacy: a stream of stage events is a per-session
 * navigation trace, not diagnostics. Stage events are still recorded locally for the
 * Diagnostics dialog; they are simply never transmitted.
 */
fun isFailureDiagnostic(code: String): Boolean = !code.startsWith(PROGRESS_CODE_PREFIX)

fun privacySafeLocation(rawUrl: String?): String {
    val uri = runCatching { URI(rawUrl.orEmpty()) }.getOrNull() ?: return "unavailable"
    val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return "unavailable"
    val firstSegment = uri.path.orEmpty().split('/').firstOrNull(String::isNotBlank)
    return if (firstSegment == null) "$host/" else "$host/$firstSegment/…"
}

fun safeDiagnosticDetail(rawDetail: String): String = rawDetail
    .replace(Regex("[\\r\\n\\t]+"), " ")
    .replace(Regex("\\s{2,}"), " ")
    // Detail text is partly outside ClearFeed's control: it can come from
    // WebResourceError.description or an HTTP reason phrase. SECURITY.md states that
    // message and thread identifiers are excluded, so redact anything identifier-shaped
    // rather than trusting the provider's error string not to embed one. `net::ERR_*`
    // names and short numbers such as timeout durations are unaffected.
    .replace(Regex("\\b[0-9]{7,}\\b"), "…")
    .replace(Regex("/[A-Za-z0-9_-]{12,}(?=/|\\b)"), "/…")
    .trim()
    .take(MAX_DETAIL_LENGTH)
    .ifBlank { "No additional detail" }

private const val MAX_DETAIL_LENGTH = 180
