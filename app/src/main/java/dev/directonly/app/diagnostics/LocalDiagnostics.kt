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

fun privacySafeLocation(rawUrl: String?): String {
    val uri = runCatching { URI(rawUrl.orEmpty()) }.getOrNull() ?: return "unavailable"
    val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return "unavailable"
    val firstSegment = uri.path.orEmpty().split('/').firstOrNull(String::isNotBlank)
    return if (firstSegment == null) "$host/" else "$host/$firstSegment/…"
}

fun safeDiagnosticDetail(rawDetail: String): String = rawDetail
    .replace(Regex("[\\r\\n\\t]+"), " ")
    .replace(Regex("\\s{2,}"), " ")
    .trim()
    .take(MAX_DETAIL_LENGTH)
    .ifBlank { "No additional detail" }

private const val MAX_DETAIL_LENGTH = 180
