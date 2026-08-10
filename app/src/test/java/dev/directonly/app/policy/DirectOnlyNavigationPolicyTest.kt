package dev.directonly.app.policy

import dev.directonly.app.model.NavigationDisposition
import dev.directonly.app.model.PolicyMode
import dev.directonly.app.model.RouteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectOnlyNavigationPolicyTest {
    private val policy = DirectOnlyNavigationPolicy()

    @Test
    fun `allows only verified Direct routes`() {
        mapOf(
            "https://www.instagram.com/direct/inbox/" to RouteKind.DIRECT_INBOX,
            "https://instagram.com/direct/inbox?folder=primary" to RouteKind.DIRECT_INBOX,
            "https://www.instagram.com/direct/t/17894261123456789/" to RouteKind.DIRECT_THREAD,
            "https://www.instagram.com/direct/t/thread_ABC-123/" to RouteKind.DIRECT_THREAD,
            "https://www.instagram.com/direct/requests/" to RouteKind.DIRECT_REQUESTS,
            "https://www.instagram.com/direct/new/" to RouteKind.DIRECT_NEW,
        ).forEach { (url, kind) ->
            val result = policy.evaluate(url, PolicyMode.DIRECT)
            assertEquals("Expected $url to be allowed", NavigationDisposition.ALLOW_DIRECT, result.disposition)
            assertEquals(kind, result.routeKind)
            assertTrue(result.mayLoadInWebView)
        }
    }

    @Test
    fun `allows only explicit authentication and recovery routes`() {
        listOf(
            "https://www.instagram.com/accounts/login/",
            "https://www.instagram.com/accounts/login/two_factor/",
            "https://www.instagram.com/accounts/onetap/",
            "https://www.instagram.com/challenge/",
            "https://www.instagram.com/challenge/123456/",
            "https://www.instagram.com/checkpoint/abc_DEF-123/",
            "https://www.instagram.com/accounts/confirm_email/",
            "https://www.instagram.com/accounts/confirm_phone/",
            "https://www.instagram.com/accounts/password/reset/",
            "https://www.instagram.com/accounts/password/reset/confirm/abc/token/",
            "https://www.instagram.com/accounts/account_recovery/",
            "https://www.instagram.com/accounts/consent/",
            "https://www.instagram.com/privacy/checks/age/",
        ).forEach { url ->
            val result = policy.evaluate(url, PolicyMode.AUTHENTICATING)
            assertEquals("Expected auth route: $url", NavigationDisposition.ALLOW_AUTH, result.disposition)
        }
    }

    @Test
    fun `blocks all known distracting Instagram destinations`() {
        listOf(
            "https://www.instagram.com/",
            "https://www.instagram.com/reels/",
            "https://www.instagram.com/reel/ABC123/",
            "https://www.instagram.com/explore/",
            "https://www.instagram.com/stories/example/123/",
            "https://www.instagram.com/p/ABC123/",
            "https://www.instagram.com/some_username/",
            "https://www.instagram.com/tags/kotlin/",
            "https://www.instagram.com/locations/123/example/",
            "https://www.instagram.com/accounts/activity/",
            "https://www.instagram.com/accounts/edit/",
            "https://www.instagram.com/shopping/",
        ).forEach(::assertBlocked)
    }

    @Test
    fun `unknown Instagram destinations fail closed`() {
        listOf(
            "https://www.instagram.com/future-content-surface/",
            "https://www.instagram.com/direct/unknown/",
            "https://www.instagram.com/direct/t/",
            "https://www.instagram.com/direct/t/id/extra/",
            "https://www.instagram.com/accounts/signup/",
            "https://www.instagram.com/api/v1/direct/",
        ).forEach(::assertBlocked)
    }

    @Test
    fun `blocks deceptive hosts schemes and encodings`() {
        listOf(
            "http://www.instagram.com/direct/inbox/",
            "file:///direct/inbox/",
            "intent://www.instagram.com/direct/inbox/#Intent;scheme=https;end",
            "javascript:location='/direct/inbox/'",
            "data:text/html,direct",
            "content://www.instagram.com/direct/inbox/",
            "https://www.instagram.com.evil.example/direct/inbox/",
            "https://evilinstagram.example/direct/inbox/",
            "https://instagram.example/direct/inbox/",
            "https://www.instagram.com:443/direct/inbox/",
            "https://www.instagram.com/direct/%2e%2e/reels/",
            "https://www.instagram.com/direct%2finbox/",
            "https://www.instagram.com/?next=/direct/inbox/",
            "https://www.instagram.com/reels/?next=/direct/inbox/",
        ).forEach(::assertBlocked)
    }

    @Test
    fun `external HTTPS is never allowed in the privileged WebView`() {
        val result = policy.evaluate("https://example.com/direct/inbox/", PolicyMode.DIRECT)
        assertEquals(NavigationDisposition.OPEN_EXTERNALLY, result.disposition)
        assertFalse(result.mayLoadInWebView)

        val duringAuthentication = policy.evaluate(
            "https://example.com/direct/inbox/",
            PolicyMode.AUTHENTICATING,
        )
        assertEquals(NavigationDisposition.BLOCK, duringAuthentication.disposition)
        assertFalse(duringAuthentication.mayLoadInWebView)
    }

    @Test
    fun `final redirect destination is independently classified`() {
        val initial = policy.evaluate("https://www.instagram.com/direct/inbox/", PolicyMode.DIRECT)
        val redirected = policy.evaluate("https://www.instagram.com/explore/", PolicyMode.DIRECT)
        assertTrue(initial.mayLoadInWebView)
        assertEquals(NavigationDisposition.BLOCK, redirected.disposition)
    }

    @Test
    fun `DM thread can mint a capability for one exact shared Reel`() {
        val approval = requireNotNull(
            policy.createSharedContentApproval(
                rawSharedUrl = "https://www.instagram.com/reels/REEL_A/?utm_source=ig_web_copy_link",
                rawOriginThreadUrl = "https://www.instagram.com/direct/t/thread_ABC-123/",
                bridgeNonce = NONCE,
                createdAtElapsedRealtimeMs = 1234L,
            ),
        )
        assertEquals(InstagramSharedContentKind.REEL, approval.kind)
        assertEquals("REEL_A", approval.contentId)
        assertEquals("/reel/REEL_A/", approval.canonicalPath)
        assertEquals(
            "https://www.instagram.com/direct/t/thread_ABC-123/",
            approval.originatingThreadUrl,
        )

        val decision = policy.evaluate(
            policy.viewerUrl(approval),
            PolicyMode.DIRECT,
            NavigationContext(approvedInstagramSharedContent = approval),
        )
        assertEquals(NavigationDisposition.ALLOW_SHARED_CONTENT, decision.disposition)
        assertEquals(RouteKind.INSTAGRAM_SHARED_REEL, decision.routeKind)
    }

    @Test
    fun `the same Reel URL is blocked without a DM-created capability`() {
        listOf(
            "https://www.instagram.com/reel/REEL_A/",
            "https://www.instagram.com/reel/REEL_A/#clearfeed_shared=$NONCE",
            "https://www.instagram.com/reels/REEL_A/#clearfeed_shared=$NONCE",
        ).forEach(::assertBlocked)
    }

    @Test
    fun `sealed viewer allows harmless URL variants of only the approved stable identity`() {
        val approval = reelApproval()
        val context = NavigationContext(approvedInstagramSharedContent = approval)
        listOf(
            "https://www.instagram.com/reel/REEL_A/?utm_source=copy#clearfeed_shared=$NONCE",
            "https://www.instagram.com/reels/REEL_A/#clearfeed_shared=$NONCE",
        ).forEach { url ->
            val decision = policy.evaluate(url, PolicyMode.DIRECT, context)
            assertEquals(url, NavigationDisposition.ALLOW_SHARED_CONTENT, decision.disposition)
        }
    }

    @Test
    fun `sealed viewer blocks every second hop and direct escape`() {
        val context = NavigationContext(approvedInstagramSharedContent = reelApproval())
        listOf(
            "https://www.instagram.com/reel/REEL_B/#clearfeed_shared=$NONCE",
            "https://www.instagram.com/reel/REEL_A/#clearfeed_shared=${"b".repeat(32)}",
            "https://www.instagram.com/p/POST_A/#clearfeed_shared=$NONCE",
            "https://www.instagram.com/reels/",
            "https://www.instagram.com/explore/",
            "https://www.instagram.com/stories/friend/123/",
            "https://www.instagram.com/creator/",
            "https://www.instagram.com/direct/inbox/",
            "https://www.instagram.com/direct/t/another_thread/",
            "https://www.instagram.com/accounts/login/",
            "https://example.com/",
        ).forEach { url ->
            val decision = policy.evaluate(url, PolicyMode.DIRECT, context)
            assertEquals("Expected sealed viewer to block $url", NavigationDisposition.BLOCK, decision.disposition)
            assertFalse(decision.mayLoadInWebView)
        }
    }

    @Test
    fun `ordinary post capability is exact and permits its own carousel route`() {
        val approval = requireNotNull(
            policy.createSharedContentApproval(
                rawSharedUrl = "https://www.instagram.com/p/POST_A/",
                rawOriginThreadUrl = "https://www.instagram.com/direct/t/thread_1/",
                bridgeNonce = NONCE,
                createdAtElapsedRealtimeMs = 10L,
            ),
        )
        val context = NavigationContext(approvedInstagramSharedContent = approval)
        val samePost = policy.evaluate(
            "https://www.instagram.com/p/POST_A/?img_index=3#clearfeed_shared=$NONCE",
            PolicyMode.DIRECT,
            context,
        )
        assertEquals(NavigationDisposition.ALLOW_SHARED_CONTENT, samePost.disposition)
        assertEquals(RouteKind.INSTAGRAM_SHARED_POST, samePost.routeKind)

        val relatedPost = policy.evaluate(
            "https://www.instagram.com/p/POST_B/#clearfeed_shared=$NONCE",
            PolicyMode.DIRECT,
            context,
        )
        assertEquals(NavigationDisposition.BLOCK, relatedPost.disposition)
    }

    @Test
    fun `capabilities cannot be minted outside an exact Direct thread`() {
        listOf(
            "https://www.instagram.com/direct/inbox/",
            "https://www.instagram.com/explore/",
            "https://www.instagram.com/creator/",
            "https://www.instagram.com/reel/REEL_B/",
        ).forEach { origin ->
            assertEquals(
                origin,
                null,
                policy.createSharedContentApproval(
                    rawSharedUrl = "https://www.instagram.com/reel/REEL_A/",
                    rawOriginThreadUrl = origin,
                    bridgeNonce = NONCE,
                    createdAtElapsedRealtimeMs = 1L,
                ),
            )
        }
        assertEquals(
            null,
            policy.createSharedContentApproval(
                rawSharedUrl = "https://www.instagram.com/stories/friend/123/",
                rawOriginThreadUrl = "https://www.instagram.com/direct/t/thread_1/",
                bridgeNonce = NONCE,
                createdAtElapsedRealtimeMs = 1L,
            ),
        )
        assertEquals(
            null,
            policy.createSharedContentApproval(
                rawSharedUrl = "https://www.instagram.com/reel/REEL_A/",
                rawOriginThreadUrl = "https://www.instagram.com/direct/t/thread_1/",
                bridgeNonce = "not-a-capability",
                createdAtElapsedRealtimeMs = 1L,
            ),
        )
    }

    @Test
    fun `subframes cannot widen the sealed capability`() {
        val context = NavigationContext(approvedInstagramSharedContent = reelApproval())
        assertTrue(
            policy.allowSubframe(
                "https://www.instagram.com/reel/REEL_A/#clearfeed_shared=$NONCE",
                PolicyMode.DIRECT,
                context,
            ),
        )
        assertFalse(
            policy.allowSubframe(
                "https://www.instagram.com/reel/REEL_B/#clearfeed_shared=$NONCE",
                PolicyMode.DIRECT,
                context,
            ),
        )
    }

    private fun reelApproval(): ApprovedInstagramSharedContent = requireNotNull(
        policy.createSharedContentApproval(
            rawSharedUrl = "https://www.instagram.com/reel/REEL_A/",
            rawOriginThreadUrl = "https://www.instagram.com/direct/t/thread_1/",
            bridgeNonce = NONCE,
            createdAtElapsedRealtimeMs = 1L,
        ),
    )

    private fun assertBlocked(url: String) {
        val result = policy.evaluate(url, PolicyMode.DIRECT)
        assertEquals("Expected blocked route: $url", NavigationDisposition.BLOCK, result.disposition)
        assertFalse(result.mayLoadInWebView)
    }

    companion object {
        private val NONCE = "a".repeat(32)
    }
}
