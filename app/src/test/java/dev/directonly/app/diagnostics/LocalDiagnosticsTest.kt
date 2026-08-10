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
