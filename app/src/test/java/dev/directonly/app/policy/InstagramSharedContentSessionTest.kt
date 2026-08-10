package dev.directonly.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramSharedContentSessionTest {
    private var now = 1_000L

    @Test
    fun `approval is in memory and active only for its short lifetime`() {
        val session = InstagramSharedContentSession({ now }, authorizationTtlMs = 500L)
        val approval = approval(createdAt = now)
        session.grant(approval)
        assertSame(approval, session.activeApproval())

        now += 501L
        assertNull(session.activeApproval())
        assertTrue("origin remains available for fail-safe recovery", session.hasPendingViewer())
    }

    @Test
    fun `Back consumes the exact origin thread and clears the capability`() {
        val session = InstagramSharedContentSession({ now })
        session.grant(approval(createdAt = now))

        assertEquals(ORIGIN_THREAD, session.returnDestinationAndClear())
        assertNull(session.activeApproval())
        assertFalse(session.hasPendingViewer())
        assertNull("a repeated Back cannot reopen the viewer", session.returnDestinationAndClear())
    }

    @Test
    fun `errors and service switches clear authorization`() {
        val session = InstagramSharedContentSession({ now })
        session.grant(approval(createdAt = now))
        session.clear()
        assertNull(session.activeApproval())
        assertFalse(session.hasPendingViewer())
    }

    @Test
    fun `a recreated process has no stale viewer authorization`() {
        val oldProcess = InstagramSharedContentSession({ now })
        oldProcess.grant(approval(createdAt = now))

        val recreatedProcess = InstagramSharedContentSession({ now })
        assertNull(recreatedProcess.activeApproval())
        assertFalse(recreatedProcess.hasPendingViewer())
    }

    private fun approval(createdAt: Long) = ApprovedInstagramSharedContent(
        kind = InstagramSharedContentKind.REEL,
        contentId = "REEL_A",
        canonicalPath = "/reel/REEL_A/",
        originatingThreadUrl = ORIGIN_THREAD,
        bridgeNonce = "a".repeat(32),
        createdAtElapsedRealtimeMs = createdAt,
    )

    companion object {
        private const val ORIGIN_THREAD = "https://www.instagram.com/direct/t/thread_1/"
    }
}
