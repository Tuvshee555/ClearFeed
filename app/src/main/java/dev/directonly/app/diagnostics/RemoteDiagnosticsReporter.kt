package dev.directonly.app.diagnostics

import android.os.Build
import androidx.webkit.WebViewCompat
import dev.directonly.app.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

class RemoteDiagnosticsReporter(
    private val environment: DiagnosticEnvironment,
    private val isEnabled: () -> Boolean,
    private val endpoint: String = ENDPOINT,
    /**
     * How a payload leaves the process. Injectable so tests can assert what would be sent
     * without opening a socket, and so the opt-in gate is verified on the real reporter
     * rather than on a copy of its logic.
     */
    private val transport: (String) -> Unit = ::postJson,
    /** Runs the transport. Overridden in tests to keep assertions deterministic. */
    private val dispatch: (Runnable) -> Unit = defaultDispatch(),
) {
    private val session = UUID.randomUUID().toString()
    private var lastFingerprint = ""
    private var lastSentAt = 0L

    fun report(record: DiagnosticRecord) {
        // Off unless the owner opted in, and failures only. Transmitting the CF-STAGE-*
        // progress events alongside a stable session id produced a per-session record of
        // which service was opened and when — telemetry, not diagnostics.
        if (!isEnabled() || !isFailureDiagnostic(record.code)) return
        val fingerprint = "${record.code}|${record.service}|${record.location}|${record.detail}"
        val now = System.currentTimeMillis()
        if (fingerprint == lastFingerprint && now - lastSentAt < DUPLICATE_WINDOW_MS) return
        lastFingerprint = fingerprint
        lastSentAt = now

        val payload = JSONObject()
            .put("session", session)
            .put("appVersion", environment.appVersion)
            .put("androidVersion", environment.androidVersion)
            .put("webViewVersion", environment.webViewVersion)
            .put("code", record.code)
            .put("service", record.service)
            .put("location", record.location)
            .put("detail", record.detail)
            .toString()
        dispatch(Runnable { runCatching { transport(payload) } })
    }

    fun close() {
        (dispatch as? ExecutorDispatch)?.shutdown()
    }

    /** A dispatch that owns an executor, so [close] can actually shut it down. */
    private class ExecutorDispatch : (Runnable) -> Unit {
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "clearfeed-diagnostics").apply { isDaemon = true }
        }

        override fun invoke(task: Runnable) {
            if (!executor.isShutdown) executor.execute(task)
        }

        fun shutdown() {
            executor.shutdownNow()
        }
    }

    companion object {
        private const val ENDPOINT = "https://clear-feed-six.vercel.app/api/log"
        private const val TIMEOUT_MS = 8_000
        private const val DUPLICATE_WINDOW_MS = 10_000L

        private fun defaultDispatch(): (Runnable) -> Unit = ExecutorDispatch()

        private fun postJson(payload: String) {
            val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }

        fun create(
            context: android.content.Context,
            preferences: DiagnosticsPreferences = DiagnosticsPreferences(context),
        ) = RemoteDiagnosticsReporter(
            environment = DiagnosticEnvironment(
                appVersion = BuildConfig.VERSION_NAME,
                androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                webViewVersion = WebViewCompat.getCurrentWebViewPackage(context)?.versionName ?: "unavailable",
            ),
            isEnabled = preferences::remoteReportingEnabled,
        )
    }
}
