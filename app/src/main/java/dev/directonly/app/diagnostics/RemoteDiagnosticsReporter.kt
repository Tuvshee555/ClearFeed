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
    private val endpoint: String = ENDPOINT,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val session = UUID.randomUUID().toString()
    private var lastFingerprint = ""
    private var lastSentAt = 0L

    fun report(record: DiagnosticRecord) {
        val fingerprint = "${record.code}|${record.service}|${record.location}|${record.detail}"
        val now = System.currentTimeMillis()
        if (fingerprint == lastFingerprint && now - lastSentAt < DUPLICATE_WINDOW_MS) return
        lastFingerprint = fingerprint
        lastSentAt = now

        executor.execute {
            runCatching {
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
                val connection = URL(endpoint).openConnection() as HttpURLConnection
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
        }
    }

    fun close() = executor.shutdownNow()

    companion object {
        private const val ENDPOINT = "https://clear-feed-six.vercel.app/api/log"
        private const val TIMEOUT_MS = 8_000
        private const val DUPLICATE_WINDOW_MS = 10_000L

        fun create(context: android.content.Context) = RemoteDiagnosticsReporter(
            DiagnosticEnvironment(
                appVersion = BuildConfig.VERSION_NAME,
                androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                webViewVersion = WebViewCompat.getCurrentWebViewPackage(context)?.versionName ?: "unavailable",
            ),
        )
    }
}
