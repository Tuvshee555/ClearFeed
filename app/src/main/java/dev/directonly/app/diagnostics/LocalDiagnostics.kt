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

/** A recorded event plus when it happened, relative to the first event in the trace. */
data class TimedDiagnosticRecord(
    val atElapsedMs: Long,
    val record: DiagnosticRecord,
)

/**
 * A bounded, in-memory trace of recent diagnostics.
 *
 * Only the single most recent record used to be kept, which meant that by the time anyone
 * opened the Diagnostics dialog after a failure the interesting sequence was already gone —
 * and the sequence is the whole story. "Selected Facebook → page started → guard waiting →
 * guard waiting → timeout" and "…→ page started → page started → page started" are very
 * different bugs that both end on the same final record.
 *
 * Nothing here is persisted or transmitted; it lives for the life of the process.
 */
class DiagnosticTrace(private val capacity: Int = MAX_EVENTS) {
    private val events = ArrayDeque<TimedDiagnosticRecord>()
    private var firstAtElapsedMs: Long? = null

    val latest: DiagnosticRecord?
        get() = events.lastOrNull()?.record

    fun snapshot(): List<TimedDiagnosticRecord> = events.toList()

    fun record(record: DiagnosticRecord, nowElapsedMs: Long) {
        val origin = firstAtElapsedMs ?: nowElapsedMs.also { firstAtElapsedMs = it }
        events.addLast(TimedDiagnosticRecord(nowElapsedMs - origin, record))
        while (events.size > capacity) events.removeFirst()
    }

    fun clear() {
        events.clear()
        firstAtElapsedMs = null
    }

    private companion object {
        const val MAX_EVENTS = 50
    }
}

fun buildDiagnosticReport(
    environment: DiagnosticEnvironment,
    record: DiagnosticRecord?,
): String = buildDiagnosticReport(
    environment = environment,
    trace = record?.let { listOf(TimedDiagnosticRecord(0L, it)) }.orEmpty(),
)

fun buildDiagnosticReport(
    environment: DiagnosticEnvironment,
    trace: List<TimedDiagnosticRecord>,
): String = buildString {
    appendLine("ClearFeed diagnostics")
    appendLine("App: ${environment.appVersion}")
    appendLine("Android: ${environment.androidVersion}")
    appendLine("System WebView: ${environment.webViewVersion}")
    if (trace.isEmpty()) {
        append("Latest event: none recorded")
        return@buildString
    }
    val latest = trace.last().record
    appendLine("Latest event: ${latest.code}")
    appendLine("Service: ${latest.service}")
    appendLine("Location: ${latest.location}")
    appendLine("Detail: ${latest.detail}")
    appendLine()
    appendLine("Recent events (oldest first, seconds since first):")
    trace.forEach { (atElapsedMs, event) ->
        val seconds = atElapsedMs / 1000.0
        appendLine(
            "%6.1fs  %-28s %-12s %s".format(seconds, event.code, event.service, event.location),
        )
        if (event.detail.isNotBlank()) appendLine("        ${event.detail}")
    }
    // Trailing newline from the loop is intentional; the dialog trims for display.
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
