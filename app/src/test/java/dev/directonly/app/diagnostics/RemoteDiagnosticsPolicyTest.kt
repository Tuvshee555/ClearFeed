package dev.directonly.app.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two independent conditions that gate every outbound diagnostic.
 *
 * Both matter. Transmitting the `CF-STAGE-*` progress events alongside a stable
 * per-process session identifier produced a server-side timeline of which service was
 * opened and when, which is behavioural telemetry no matter how well individual fields are
 * redacted — and both README.md and the in-app privacy dialog denied it was happening.
 */
class RemoteDiagnosticsPolicyTest {
    @Test
    fun `navigation stage events are never eligible for transmission`() {
        listOf(
            "CF-STAGE-SELECTED",
            "CF-STAGE-WAITING-FOR-WEBVIEW",
            "CF-STAGE-LOAD-DISPATCHED",
            "CF-STAGE-PAGE-STARTED",
            "CF-STAGE-PAGE-FINISHED",
            "CF-STAGE-GUARD-READY",
            "CF-STAGE-GUARD-HEALTHY",
            "CF-STAGE-GUARD-WAITING",
            "CF-STAGE-LOGIN-READY",
            "CF-STAGE-AUTH-COMPLETE",
        ).forEach { code ->
            assertFalse("$code is a progress stage and must never be sent", isFailureDiagnostic(code))
        }
    }

    @Test
    fun `genuine failures remain eligible including the dynamic codes`() {
        listOf(
            "CF-GUARD-TIMEOUT",
            "CF-RENDERER",
            "CF-WEBVIEW-UNAVAILABLE",
            "CF-OFFLINE",
            "CF-NET-2",
            "CF-HTTP-503",
            "CF-TLS-3",
            "CF-SAFE-1",
        ).forEach { code ->
            assertTrue("$code is a failure and should be reportable", isFailureDiagnostic(code))
        }
    }

    @Test
    fun `an opted-out reporter transmits nothing at all`() {
        val sent = mutableListOf<String>()
        val reporter = reporter(enabled = false, sent = sent)
        reporter.report(failureRecord())
        reporter.report(stageRecord())
        assertTrue("Nothing may be sent while the opt-in is off", sent.isEmpty())
    }

    @Test
    fun `an opted-in reporter transmits failures and still withholds stages`() {
        val sent = mutableListOf<String>()
        val reporter = reporter(enabled = true, sent = sent)

        reporter.report(stageRecord())
        assertTrue("Stage events stay local even when opted in", sent.isEmpty())

        reporter.report(failureRecord())
        assertTrue("A failure is sent once opted in", sent.size == 1)
        assertTrue("The failure code is present", sent.single().contains("CF-GUARD-TIMEOUT"))
    }

    @Test
    fun `a transmitted failure carries no query string fragment or raw url`() {
        val sent = mutableListOf<String>()
        reporter(enabled = true, sent = sent).report(
            DiagnosticRecord(
                code = "CF-HTTP-500",
                service = "Instagram",
                location = privacySafeLocation(
                    "https://www.instagram.com/direct/t/112233445566/?token=secret#clearfeed_shared=abc",
                ),
                detail = safeDiagnosticDetail("net::ERR_FAILED on /direct/t/112233445566/"),
            ),
        )
        val payload = sent.single()
        assertFalse("No query string may be transmitted", payload.contains("token=secret"))
        assertFalse("No fragment may be transmitted", payload.contains("clearfeed_shared"))
        assertFalse("No thread identifier may be transmitted", payload.contains("112233445566"))
        assertTrue("The redacted location is still useful", payload.contains("instagram.com/direct/"))
    }

    /** Exercises the real reporter with its transport and dispatch replaced. */
    private fun reporter(enabled: Boolean, sent: MutableList<String>) = RemoteDiagnosticsReporter(
        environment = DiagnosticEnvironment(
            appVersion = "3.6.5",
            androidVersion = "15 (API 35)",
            webViewVersion = "test",
        ),
        isEnabled = { enabled },
        transport = { payload -> sent += payload },
        dispatch = { task -> task.run() },
    )

    private fun failureRecord() = DiagnosticRecord(
        code = "CF-GUARD-TIMEOUT",
        service = "Instagram",
        location = "instagram.com/direct/…",
        detail = "The protected interface did not report healthy",
    )

    private fun stageRecord() = DiagnosticRecord(
        code = "CF-STAGE-SELECTED",
        service = "Instagram",
        location = "unavailable",
        detail = "Selected Instagram",
    )
}
