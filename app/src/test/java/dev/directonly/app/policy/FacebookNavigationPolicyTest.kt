package dev.directonly.app.policy

import dev.directonly.app.model.NavigationDisposition
import dev.directonly.app.model.PolicyMode
import dev.directonly.app.model.RouteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FacebookNavigationPolicyTest {
    private val policy = FacebookNavigationPolicy()

    @Test
    fun `allows the limited feed and intentional utility surfaces`() {
        mapOf(
            "https://www.facebook.com/?filter=all&sk=h_chr" to RouteKind.FACEBOOK_FEED,
            "https://www.facebook.com/?filter=friends&sk=h_chr" to RouteKind.FACEBOOK_FEED,
            "https://www.facebook.com/messages/" to RouteKind.FACEBOOK_MESSAGES,
            "https://www.facebook.com/messages/e2ee/t/123456789/" to RouteKind.FACEBOOK_MESSAGES,
            "https://www.messenger.com/t/123456789/" to RouteKind.FACEBOOK_MESSAGES,
            "https://www.facebook.com/notifications/" to RouteKind.FACEBOOK_NOTIFICATIONS,
            "https://www.facebook.com/search/top?q=friend" to RouteKind.FACEBOOK_SEARCH,
            "https://www.facebook.com/friends/" to RouteKind.FACEBOOK_FRIENDS,
            "https://www.facebook.com/groups/example.group/" to RouteKind.FACEBOOK_GROUP,
            "https://www.facebook.com/events/123456789/" to RouteKind.FACEBOOK_EVENT,
            "https://www.facebook.com/example.page/" to RouteKind.FACEBOOK_PAGE,
            "https://www.facebook.com/example.page/posts/123456789/" to RouteKind.FACEBOOK_POST,
            "https://www.facebook.com/?_rdr" to RouteKind.AUTH_LOGIN,
            "https://m.facebook.com/unified/login_via/app/?tade=example&lid=example" to RouteKind.AUTH_LOGIN,
        ).forEach { (url, kind) ->
            val decision = policy.evaluate(url, PolicyMode.CONTENT)
            assertEquals("Wrong route for $url", kind, decision.routeKind)
            assertTrue("Expected allowed route: $url", decision.mayLoadInWebView)
        }
    }

    @Test
    fun `feed post limit is permanent and fixed at eight`() {
        assertEquals(8, FacebookNavigationPolicy.FACEBOOK_FEED_POST_LIMIT)
    }

    @Test
    fun `ranked Home is blocked in favor of verified newest Feeds`() {
        listOf(
            "https://www.facebook.com/",
            "https://www.facebook.com/home.php",
            "https://www.facebook.com/?sk=nf",
            "https://www.facebook.com/?filter=all",
            "https://www.facebook.com/?filter=unknown&sk=h_chr",
            "https://www.facebook.com/?_rdr&next=/reels/",
        ).forEach(::assertBlocked)
        assertEquals(
            "https://m.facebook.com/?filter=all&sk=h_chr",
            policy.safeRootUrl,
        )
    }

    @Test
    fun `blocks every known video reel story and discovery route`() {
        listOf(
            "https://www.facebook.com/reels/",
            "https://www.facebook.com/reel/123456789/",
            "https://www.facebook.com/watch/",
            "https://www.facebook.com/watch.php?v=123456789",
            "https://www.facebook.com/videos/123456789/",
            "https://www.facebook.com/video/123456789/",
            "https://www.facebook.com/video.php?v=123456789",
            "https://www.facebook.com/live/",
            "https://www.facebook.com/stories/example/123/",
            "https://www.facebook.com/marketplace/",
            "https://www.facebook.com/gaming/",
        ).forEach(::assertBlocked)
    }

    @Test
    fun `allows narrow authentication routes and fails closed on unknown Facebook paths`() {
        listOf(
            "https://www.facebook.com/login/",
            "https://www.facebook.com/checkpoint/123/",
            "https://www.facebook.com/recover/initiate/",
            "https://www.messenger.com/login/password/",
        ).forEach { url ->
            assertEquals(NavigationDisposition.ALLOW_AUTH, policy.evaluate(url, PolicyMode.AUTHENTICATING).disposition)
        }
        listOf(
            "https://www.facebook.com/api/graphql/",
            "https://www.facebook.com/unknown/surface/extra/",
            "https://www.messenger.com/discover/",
        ).forEach(::assertBlocked)
    }

    @Test
    fun `deceptive hosts and cross-platform social escapes are blocked`() {
        listOf(
            "http://www.facebook.com/messages/",
            "https://www.facebook.com.evil.example/messages/",
            "https://www.facebook.com:443/messages/",
            "https://www.facebook.com/%2e%2e/reels/",
            "https://m.youtube.com/feed/subscriptions/",
            "fb://profile/123456789",
            "intent://www.facebook.com/reel/123#Intent;scheme=https;end",
            "javascript:location='/reels/'",
        ).forEach(::assertBlocked)
        assertEquals(
            NavigationDisposition.OPEN_EXTERNALLY,
            policy.evaluate("https://example.com/article", PolicyMode.CONTENT).disposition,
        )
        assertFalse(policy.isTrustedTopLevelOrigin("https://facebook.example/messages/"))
    }

    private fun assertBlocked(url: String) {
        val decision = policy.evaluate(url, PolicyMode.CONTENT)
        assertEquals("Expected block for $url", NavigationDisposition.BLOCK, decision.disposition)
        assertFalse(decision.mayLoadInWebView)
    }
}
