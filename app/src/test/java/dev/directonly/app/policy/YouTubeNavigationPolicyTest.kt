package dev.directonly.app.policy

import dev.directonly.app.model.NavigationDisposition
import dev.directonly.app.model.PolicyMode
import dev.directonly.app.model.RouteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeNavigationPolicyTest {
    private val policy = YouTubeNavigationPolicy()

    @Test
    fun `subscriptions and deliberate search are allowed`() {
        mapOf(
            "https://m.youtube.com/feed/subscriptions?persist_app=1&app=m" to RouteKind.YOUTUBE_SUBSCRIPTIONS,
            "https://www.youtube.com/feed/subscriptions/" to RouteKind.YOUTUBE_SUBSCRIPTIONS,
            "https://m.youtube.com/results?search_query=android+webview" to RouteKind.YOUTUBE_SEARCH,
        ).forEach { (url, kind) ->
            val decision = policy.evaluate(url, PolicyMode.CONTENT)
            assertEquals(kind, decision.routeKind)
            assertEquals(NavigationDisposition.ALLOW_CONTENT, decision.disposition)
        }
    }

    @Test
    fun `watch requires the exact native intentional-video token`() {
        val url = "https://m.youtube.com/watch?v=dQw4w9WgXcQ"
        assertFalse(policy.evaluate(url, PolicyMode.CONTENT).mayLoadInWebView)
        assertFalse(
            policy.evaluate(
                url,
                PolicyMode.CONTENT,
                NavigationContext(allowedYouTubeVideoId = "differentId1"),
            ).mayLoadInWebView,
        )

        val allowed = policy.evaluate(
            url,
            PolicyMode.CONTENT,
            NavigationContext(allowedYouTubeVideoId = "dQw4w9WgXcQ"),
        )
        assertEquals(RouteKind.YOUTUBE_WATCH, allowed.routeKind)
        assertEquals(NavigationDisposition.ALLOW_CONTENT, allowed.disposition)
    }

    @Test
    fun `an intentional token authorizes one video and never a queued player mode`() {
        val context = NavigationContext(allowedYouTubeVideoId = "dQw4w9WgXcQ")
        // The capability is for one video ID, not for the surrounding player mode.
        // A playlist or radio queue auto-advances and defeats the one-video promise.
        listOf(
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890",
            "https://m.youtube.com/watch?list=PL1234567890&v=dQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ&start_radio=1&list=RDdQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ&playlist=abc123def456",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ&index=4&list=PL1234567890",
        ).forEach { url ->
            val decision = policy.evaluate(url, PolicyMode.CONTENT, context)
            assertEquals("Expected block for $url", NavigationDisposition.BLOCK, decision.disposition)
            assertFalse("Expected block for $url", decision.mayLoadInWebView)
        }
        // The plain authorized watch URL, and benign playback parameters, still work.
        listOf(
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=42s",
            "https://m.youtube.com/watch?persist_app=1&v=dQw4w9WgXcQ",
        ).forEach { url ->
            assertTrue("Expected allow for $url", policy.evaluate(url, PolicyMode.CONTENT, context).mayLoadInWebView)
        }
    }

    @Test
    fun `home shorts recommendation and channel surfaces stay blocked`() {
        listOf(
            "https://m.youtube.com/",
            "https://m.youtube.com/shorts/dQw4w9WgXcQ",
            "https://m.youtube.com/feed/trending/",
            "https://m.youtube.com/playlist?list=PL123",
            "https://m.youtube.com/channel/UC123456/",
            "https://m.youtube.com/@example/",
            "https://m.youtube.com/watch?v=not_deliberate",
            "http://m.youtube.com/feed/subscriptions/",
            "https://m.youtube.com:443/feed/subscriptions/",
            "https://m.youtube.com.evil.example/feed/subscriptions/",
            "youtube://watch?v=dQw4w9WgXcQ",
            "vnd.youtube://dQw4w9WgXcQ",
            "intent://m.youtube.com/shorts/dQw4w9WgXcQ#Intent;scheme=https;end",
        ).forEach { url ->
            assertEquals("Expected block for $url", NavigationDisposition.BLOCK, policy.evaluate(url, PolicyMode.CONTENT).disposition)
        }
    }

    @Test
    fun `known Google sign-in paths are allowed but arbitrary account pages fail closed`() {
        listOf(
            "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fm.youtube.com",
            "https://accounts.google.com/v3/signin/identifier",
            "https://accounts.google.com/AccountChooser",
            "https://consent.google.com/m?continue=https%3A%2F%2Fm.youtube.com",
        ).forEach { url ->
            assertEquals(NavigationDisposition.ALLOW_AUTH, policy.evaluate(url, PolicyMode.AUTHENTICATING).disposition)
        }
        assertEquals(
            NavigationDisposition.BLOCK,
            policy.evaluate("https://accounts.google.com/b/0/ManageAccount", PolicyMode.AUTHENTICATING).disposition,
        )
    }

    @Test
    fun `external sites may prompt only after authentication and managed social escapes never do`() {
        assertEquals(
            NavigationDisposition.OPEN_EXTERNALLY,
            policy.evaluate("https://example.com/article", PolicyMode.CONTENT).disposition,
        )
        assertEquals(
            NavigationDisposition.BLOCK,
            policy.evaluate("https://example.com/article", PolicyMode.AUTHENTICATING).disposition,
        )
        assertEquals(
            NavigationDisposition.BLOCK,
            policy.evaluate("https://www.instagram.com/direct/inbox/", PolicyMode.CONTENT).disposition,
        )
        assertTrue(policy.isTrustedTopLevelOrigin("https://m.youtube.com/results?q=test"))
        assertFalse(policy.isTrustedTopLevelOrigin("https://m.youtube.com.evil.example/results?q=test"))
    }
}
