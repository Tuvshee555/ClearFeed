package dev.directonly.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDiagnosticsTest {
    @Test
    fun `diagnostic locations omit queries fragments and private identifiers`() {
        assertEquals(
            "instagram.com/direct/…",
            privacySafeLocation(
                "https://www.instagram.com/direct/t/private-thread-id/?token=secret#fragment",
            ),
        )
        assertEquals("facebook.com/", privacySafeLocation("https://facebook.com/?token=secret"))
        assertEquals("unavailable", privacySafeLocation("not a url"))
    }

    @Test
    fun `diagnostic detail is bounded and single line`() {
        val detail = safeDiagnosticDetail("  first\nsecond\t${"x".repeat(300)}  ")
        assertFalse(detail.contains('\n'))
        assertFalse(detail.contains('\t'))
        assertTrue(detail.length <= 180)
        assertTrue(detail.startsWith("first second"))
    }

    @Test
    fun `the trace keeps a bounded window of recent events oldest first`() {
        val trace = DiagnosticTrace(capacity = 3)
        listOf("CF-STAGE-SELECTED", "CF-STAGE-PAGE-STARTED", "CF-STAGE-GUARD-WAITING", "CF-GUARD-TIMEOUT")
            .forEachIndexed { index, code ->
                trace.record(record(code), nowElapsedMs = 1_000L + index * 500L)
            }
        val snapshot = trace.snapshot()
        assertEquals(3, snapshot.size)
        assertEquals(
            listOf("CF-STAGE-PAGE-STARTED", "CF-STAGE-GUARD-WAITING", "CF-GUARD-TIMEOUT"),
            snapshot.map { it.record.code },
        )
        assertEquals("CF-GUARD-TIMEOUT", trace.latest?.code)
    }

    @Test
    fun `trace timestamps are relative to the first event`() {
        val trace = DiagnosticTrace()
        // elapsedRealtime is an arbitrary boot-relative number; only the deltas are useful,
        // and they are what makes a report readable when it is pasted somewhere else.
        trace.record(record("CF-STAGE-SELECTED"), nowElapsedMs = 9_000_000L)
        trace.record(record("CF-GUARD-TIMEOUT"), nowElapsedMs = 9_012_000L)
        assertEquals(listOf(0L, 12_000L), trace.snapshot().map { it.atElapsedMs })
    }

    @Test
    fun `a report renders the whole sequence not just the last line`() {
        val trace = DiagnosticTrace()
        trace.record(record("CF-STAGE-SELECTED"), nowElapsedMs = 0L)
        trace.record(record("CF-STAGE-PAGE-STARTED"), nowElapsedMs = 800L)
        trace.record(record("CF-GUARD-UNVERIFIED"), nowElapsedMs = 3_300L)

        val report = buildDiagnosticReport(
            environment = DiagnosticEnvironment("3.6.5", "15 (API 35)", "131.0"),
            trace = trace.snapshot(),
        )
        assertTrue(report.contains("Latest event: CF-GUARD-UNVERIFIED"))
        listOf("CF-STAGE-SELECTED", "CF-STAGE-PAGE-STARTED", "CF-GUARD-UNVERIFIED").forEach { code ->
            assertTrue("trace must include $code", report.contains(code))
        }
        assertTrue("relative seconds must be shown", report.contains("3.3s"))
    }

    private fun record(code: String) = DiagnosticRecord(
        code = code,
        service = "Instagram",
        location = "instagram.com/direct/…",
        detail = "detail for $code",
    )

    @Test
    fun `report contains actionable environment and failure code`() {
        val report = buildDiagnosticReport(
            environment = DiagnosticEnvironment("3.2.1", "16 (API 36)", "140.0"),
            record = DiagnosticRecord(
                code = "CF-HTTP-403",
                service = "Instagram",
                location = "instagram.com/accounts/…",
                detail = "Forbidden",
            ),
        )
        listOf("3.2.1", "API 36", "140.0", "CF-HTTP-403", "Instagram").forEach {
            assertTrue(report.contains(it))
        }
    }
}
