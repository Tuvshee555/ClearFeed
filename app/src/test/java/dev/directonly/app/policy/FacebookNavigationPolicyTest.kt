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
            "https://www.facebook.com/home.php",
            "https://www.facebook.com/?sk=nf",
            "https://www.facebook.com/?filter=all",
            "https://www.facebook.com/?filter=unknown&sk=h_chr",
            "https://www.facebook.com/?_rdr&next=/reels/",
        ).forEach(::assertBlocked)
        val canonicalPostLoginFeed = policy.evaluate(
            "https://www.facebook.com/",
            PolicyMode.CONTENT,
        )
        assertEquals(NavigationDisposition.ALLOW_CONTENT, canonicalPostLoginFeed.disposition)
        assertEquals(RouteKind.FACEBOOK_FEED, canonicalPostLoginFeed.routeKind)
        // The bare root is the feed landing in AUTHENTICATING mode too. This assertion used
        // to expect BLOCK, which is exactly what made every successful sign-in bounce
        // through recoverToSafeRoot: Facebook drops the user on `/` while the mode is still
        // AUTHENTICATING. Ranked Home stays blocked through `/?sk=nf`, asserted above.
        assertEquals(
            NavigationDisposition.ALLOW_CONTENT,
            policy.evaluate("https://www.facebook.com/", PolicyMode.AUTHENTICATING).disposition,
        )
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
    fun `the post-login landing on the bare root is the feed in every mode`() {
        // Facebook drops a freshly signed-in session on `/`, at which point policyMode is
        // still AUTHENTICATING. Allowing that only in CONTENT mode made every sign-in a
        // BLOCK, which bounced the user through recoverToSafeRoot and, when the guard then
        // failed to verify, straight back around again.
        listOf(PolicyMode.AUTHENTICATING, PolicyMode.CONTENT, PolicyMode.DIRECT).forEach { mode ->
            val decision = policy.evaluate("https://m.facebook.com/", mode)
            assertEquals("bare root in $mode", RouteKind.FACEBOOK_FEED, decision.routeKind)
            assertTrue("bare root must load in $mode", decision.mayLoadInWebView)
        }
    }

    @Test
    fun `only a bare root is a landing and every other root query stays refused`() {
        // A root carrying a query is asking for something specific, including a redirect
        // target, so it is refused and recovery canonicalizes to the verified feed instead.
        listOf(
            "https://www.facebook.com/?sk=nf",
            "https://www.facebook.com/?filter=all",
            "https://www.facebook.com/?sk=h_chr",
            "https://www.facebook.com/?filter=unknown&sk=h_chr",
            "https://www.facebook.com/?next=/reels/",
        ).forEach(::assertBlocked)
        assertTrue(
            policy.evaluate("https://www.facebook.com/?filter=all&sk=h_chr", PolicyMode.CONTENT)
                .mayLoadInWebView,
        )
    }

    @Test
    fun `plausible usernames are not mistaken for reserved surfaces`() {
        // A previous over-broad reserved list swallowed ordinary words that are perfectly
        // valid Facebook usernames, so real profiles stopped opening.
        listOf("news", "music", "sports", "notes", "topic", "weather", "jobs", "offers", "terms")
            .forEach { username ->
                val decision = policy.evaluate("https://www.facebook.com/$username/", PolicyMode.CONTENT)
                assertEquals("/$username/ is a profile", RouteKind.FACEBOOK_PAGE, decision.routeKind)
                assertTrue("/$username/ must load", decision.mayLoadInWebView)
            }
    }

    @Test
    fun `capitalized blocked routes cannot slip past the lowercase prefix list`() {
        // Facebook serves its paths case-insensitively, so a single capital letter
        // must not turn a blocked video surface into an allowed profile route.
        listOf(
            "https://www.facebook.com/Reels/",
            "https://www.facebook.com/REELS/",
            "https://www.facebook.com/Reel/123456789/",
            "https://www.facebook.com/Watch/",
            "https://www.facebook.com/WATCH/",
            "https://www.facebook.com/Videos/123456789/",
            "https://www.facebook.com/Video/123456789/",
            "https://www.facebook.com/Live/",
            "https://www.facebook.com/Stories/example/123/",
            "https://www.facebook.com/Marketplace/",
            "https://www.facebook.com/Gaming/",
            "https://www.facebook.com/Home.php",
        ).forEach(::assertBlocked)
    }

    @Test
    fun `bare discovery directories fail closed instead of passing as profiles`() {
        // The single-segment profile route must not admit Facebook's own
        // recommendation and discovery surfaces.
        listOf(
            "https://www.facebook.com/groups/",
            "https://www.facebook.com/events/",
            "https://www.facebook.com/pages/",
            "https://www.facebook.com/watch_videos/",
            "https://www.facebook.com/dating/",
            "https://www.facebook.com/games/",
            "https://www.facebook.com/photos/",
            "https://www.facebook.com/saved/",
            "https://www.facebook.com/memories/",
            "https://www.facebook.com/friends_center/",
            "https://www.facebook.com/groups/feed/",
            "https://www.facebook.com/groups/discover/",
        ).forEach(::assertBlocked)
    }

    @Test
    fun `intentional profiles pages groups and events still resolve`() {
        // The fix for the discovery directories must not break the surfaces
        // README documents as allowed.
        mapOf(
            "https://www.facebook.com/zuck/" to RouteKind.FACEBOOK_PAGE,
            "https://www.facebook.com/example.page/" to RouteKind.FACEBOOK_PAGE,
            "https://www.facebook.com/pages/Example-Page/123456789/" to RouteKind.FACEBOOK_PAGE,
            "https://www.facebook.com/groups/example.group/" to RouteKind.FACEBOOK_GROUP,
            "https://www.facebook.com/groups/123456789/posts/987654321/" to RouteKind.FACEBOOK_GROUP,
            "https://www.facebook.com/events/123456789/" to RouteKind.FACEBOOK_EVENT,
        ).forEach { (url, kind) ->
            val decision = policy.evaluate(url, PolicyMode.CONTENT)
            assertEquals("Wrong route for $url", kind, decision.routeKind)
            assertTrue("Expected allowed route: $url", decision.mayLoadInWebView)
        }
    }

    @Test
    fun `video search results are blocked even though search is allowed`() {
        assertBlocked("https://www.facebook.com/search/videos/?q=example")
        assertEquals(
            RouteKind.FACEBOOK_SEARCH,
            policy.evaluate("https://www.facebook.com/search/top?q=friend", PolicyMode.CONTENT).routeKind,
        )
    }

    @Test
    fun `a signed-in Messenger root is the inbox and not an authentication page`() {
        val decision = policy.evaluate("https://www.messenger.com/", PolicyMode.CONTENT)
        assertEquals(RouteKind.FACEBOOK_MESSAGES, decision.routeKind)
        assertTrue(decision.mayLoadInWebView)
        // While unauthenticated it must still be reachable as a sign-in surface.
        assertTrue(
            policy.evaluate("https://www.messenger.com/", PolicyMode.AUTHENTICATING).mayLoadInWebView,
        )
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
