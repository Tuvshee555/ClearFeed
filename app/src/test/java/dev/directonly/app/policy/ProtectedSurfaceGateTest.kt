package dev.directonly.app.policy

import dev.directonly.app.model.BlockReason
import dev.directonly.app.model.NavigationDecision
import dev.directonly.app.model.NavigationDisposition
import dev.directonly.app.model.PolicyMode
import dev.directonly.app.model.RouteKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera, microphone, file picker and fullscreen video gates.
 *
 * These ran on `routeKind` alone before, which is not sufficient: a blocked decision still
 * reports the route kind it was blocked *as*. The tests below drive real policy output
 * rather than hand-built decisions wherever possible, so they fail if the policies ever
 * start reporting a different kind for a blocked route.
 */
class ProtectedSurfaceGateTest {
    private val instagram = DirectOnlyNavigationPolicy()
    private val facebook = FacebookNavigationPolicy()
    private val youtube = YouTubeNavigationPolicy()

    @Test
    fun `active conversation routes may use capture and the file picker`() {
        listOf(
            "https://www.instagram.com/direct/inbox/",
            "https://www.instagram.com/direct/t/12345/",
            "https://www.instagram.com/direct/requests/",
        ).forEach { url ->
            assertTrue(url, ProtectedSurfaceGate.isMessageSurface(instagram.evaluate(url, PolicyMode.DIRECT)))
        }
        assertTrue(
            ProtectedSurfaceGate.isMessageSurface(
                facebook.evaluate("https://www.facebook.com/messages/t/123/", PolicyMode.CONTENT),
            ),
        )
    }

    @Test
    fun `a blocked Direct thread cannot receive capture permission`() {
        // While a sealed shared-content capability is active, every non-matching route is
        // BLOCK — including the origin thread — yet still reports DIRECT_THREAD. The gate
        // used to see only the route kind and granted camera and microphone anyway.
        val approval = instagram.createSharedContentApproval(
            rawSharedUrl = "https://www.instagram.com/reel/REEL_A/",
            rawOriginThreadUrl = "https://www.instagram.com/direct/t/12345/",
            bridgeNonce = "a".repeat(32),
            createdAtElapsedRealtimeMs = 1_000L,
        )
        checkNotNull(approval) { "fixture must produce a capability" }
        val context = NavigationContext(approvedInstagramSharedContent = approval)

        val threadDecision = instagram.evaluate(
            "https://www.instagram.com/direct/t/12345/",
            PolicyMode.DIRECT,
            context,
        )
        assertFalse("the origin thread is blocked while sealed", threadDecision.mayLoadInWebView)
        assertTrue("but still reports its route kind", threadDecision.routeKind == RouteKind.DIRECT_THREAD)
        assertFalse(
            "a blocked route must never receive capture permission",
            ProtectedSurfaceGate.isMessageSurface(threadDecision),
        )
    }

    @Test
    fun `an unauthorized Reel cannot open fullscreen video`() {
        // No capability: the decision is BLOCK but the kind is still INSTAGRAM_SHARED_REEL.
        val decision = instagram.evaluate("https://www.instagram.com/reel/REEL_A/", PolicyMode.DIRECT)
        assertFalse(decision.mayLoadInWebView)
        assertTrue(decision.routeKind == RouteKind.INSTAGRAM_SHARED_REEL)
        assertFalse(
            "fullscreen must not open on a page the policy rejected",
            ProtectedSurfaceGate.isFullscreenSurface(decision),
        )
    }

    @Test
    fun `an untokened YouTube watch page cannot open fullscreen video`() {
        val decision = youtube.evaluate("https://m.youtube.com/watch?v=dQw4w9WgXcQ", PolicyMode.CONTENT)
        assertFalse(decision.mayLoadInWebView)
        assertFalse(ProtectedSurfaceGate.isFullscreenSurface(decision))
    }

    @Test
    fun `an authorized watch page and an approved sealed item may open fullscreen`() {
        assertTrue(
            ProtectedSurfaceGate.isFullscreenSurface(
                youtube.evaluate(
                    "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
                    PolicyMode.CONTENT,
                    NavigationContext(allowedYouTubeVideoId = "dQw4w9WgXcQ"),
                ),
            ),
        )

        val approval = checkNotNull(
            instagram.createSharedContentApproval(
                rawSharedUrl = "https://www.instagram.com/reel/REEL_A/",
                rawOriginThreadUrl = "https://www.instagram.com/direct/t/12345/",
                bridgeNonce = "b".repeat(32),
                createdAtElapsedRealtimeMs = 1_000L,
            ),
        )
        val context = NavigationContext(approvedInstagramSharedContent = approval)
        val viewer = instagram.evaluate(instagram.viewerUrl(approval), PolicyMode.DIRECT, context)
        assertTrue(viewer.mayLoadInWebView)
        assertTrue(ProtectedSurfaceGate.isFullscreenSurface(viewer))
    }

    @Test
    fun `discovery and video surfaces never reach either gate`() {
        listOf(
            facebook.evaluate("https://m.facebook.com/reels/", PolicyMode.CONTENT),
            facebook.evaluate("https://m.facebook.com/Reels/", PolicyMode.CONTENT),
            facebook.evaluate("https://m.facebook.com/groups/", PolicyMode.CONTENT),
            youtube.evaluate("https://m.youtube.com/shorts/abc123", PolicyMode.CONTENT),
            instagram.evaluate("https://www.instagram.com/explore/", PolicyMode.DIRECT),
        ).forEach { decision ->
            assertFalse(ProtectedSurfaceGate.isMessageSurface(decision))
            assertFalse(ProtectedSurfaceGate.isFullscreenSurface(decision))
        }
    }

    @Test
    fun `a null decision is never a privileged surface`() {
        assertFalse(ProtectedSurfaceGate.isMessageSurface(null))
        assertFalse(ProtectedSurfaceGate.isFullscreenSurface(null))
    }

    @Test
    fun `a hand-built blocked decision for every message route is refused`() {
        // Belt and braces: whatever a future policy reports, BLOCK must lose.
        listOf(
            RouteKind.DIRECT_INBOX,
            RouteKind.DIRECT_THREAD,
            RouteKind.DIRECT_REQUESTS,
            RouteKind.DIRECT_NEW,
            RouteKind.FACEBOOK_MESSAGES,
            RouteKind.YOUTUBE_WATCH,
            RouteKind.INSTAGRAM_SHARED_REEL,
            RouteKind.INSTAGRAM_SHARED_POST,
        ).forEach { kind ->
            val blocked = NavigationDecision(NavigationDisposition.BLOCK, kind, BlockReason.INTENT_REQUIRED)
            assertFalse(kind.name, ProtectedSurfaceGate.isMessageSurface(blocked))
            assertFalse(kind.name, ProtectedSurfaceGate.isFullscreenSurface(blocked))
        }
    }
}
