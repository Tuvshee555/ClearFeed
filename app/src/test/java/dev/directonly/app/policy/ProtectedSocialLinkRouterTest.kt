package dev.directonly.app.policy

import dev.directonly.app.model.BlockReason
import dev.directonly.app.model.PolicyMode
import dev.directonly.app.model.SocialPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedSocialLinkRouterTest {
    private val router = ProtectedSocialLinkRouter(PlatformPolicyRegistry())

    @Test
    fun `protected host classifier uses exact domain boundaries`() {
        mapOf(
            "instagram.com" to SocialPlatform.INSTAGRAM,
            "www.instagram.com" to SocialPlatform.INSTAGRAM,
            "l.instagram.com" to SocialPlatform.INSTAGRAM,
            "youtube.com" to SocialPlatform.YOUTUBE,
            "m.youtube.com" to SocialPlatform.YOUTUBE,
            "youtu.be" to SocialPlatform.YOUTUBE,
            "facebook.com" to SocialPlatform.FACEBOOK,
            "m.facebook.com" to SocialPlatform.FACEBOOK,
            "l.facebook.com" to SocialPlatform.FACEBOOK,
            "messenger.com" to SocialPlatform.FACEBOOK,
        ).forEach { (host, platform) ->
            assertEquals(host, platform, ProtectedSocialHosts.platformForHost(host))
        }
        listOf(
            "instagram.com.evil.example",
            "fakeinstagram.com",
            "youtube.example.com",
            "notfacebook.com",
            "messenger.evil.example",
        ).forEach { host ->
            assertEquals(host, null, ProtectedSocialHosts.platformForHost(host))
            assertTrue("Brand-lookalike must fail closed: $host", ProtectedSocialHosts.looksDeceptive(host))
        }
        assertFalse(ProtectedSocialHosts.looksDeceptive("example.com"))
    }

    @Test
    fun `Instagram DM normal YouTube link becomes one intentional protected watch`() {
        val decision = route(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            SocialPlatform.INSTAGRAM,
        )
        assertEquals(SocialPlatform.YOUTUBE, decision.platform)
        assertEquals("dQw4w9WgXcQ", decision.navigationContext.allowedYouTubeVideoId)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", decision.url)

        val shortLink = route("https://youtu.be/dQw4w9WgXcQ?si=tracking", SocialPlatform.FACEBOOK)
        assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ", shortLink.url)
        assertEquals("dQw4w9WgXcQ", shortLink.navigationContext.allowedYouTubeVideoId)
    }

    @Test
    fun `YouTube Shorts handoffs always block`() {
        listOf(
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            "https://m.youtube.com/shorts/dQw4w9WgXcQ",
            "intent://m.youtube.com/shorts/dQw4w9WgXcQ#Intent;scheme=https;end",
        ).forEach { url -> assertBlocked(url, SocialPlatform.INSTAGRAM) }
    }

    @Test
    fun `Facebook destinations are re-evaluated by Facebook policy`() {
        assertEquals(
            SocialPlatform.FACEBOOK,
            route(
                "https://www.facebook.com/example/posts/123456/",
                SocialPlatform.INSTAGRAM,
            ).platform,
        )
        assertEquals(
            SocialPlatform.FACEBOOK,
            route("https://www.facebook.com/groups/123456/", SocialPlatform.YOUTUBE).platform,
        )
        listOf(
            "https://www.facebook.com/reel/123456/",
            "https://www.facebook.com/watch/?v=123456",
            "https://www.facebook.com/videos/123456/",
            "https://www.facebook.com/stories/example/123456/",
        ).forEach { url -> assertBlocked(url, SocialPlatform.INSTAGRAM) }
    }

    @Test
    fun `Facebook root handoff lands on verified newest Feeds route`() {
        val decision = route("https://m.facebook.com/", SocialPlatform.INSTAGRAM)
        assertEquals(FacebookNavigationPolicy.FEED_URL, decision.url)
    }

    @Test
    fun `Facebook origin never grants Instagram DM content capability`() {
        listOf(
            "https://www.instagram.com/reel/REEL_A/",
            "https://www.instagram.com/p/POST_A/",
            "https://www.instagram.com/creator/",
            "https://www.instagram.com/explore/",
        ).forEach { url -> assertBlocked(url, SocialPlatform.FACEBOOK) }
    }

    @Test
    fun `YouTube description Facebook links use Facebook policy`() {
        assertEquals(
            SocialPlatform.FACEBOOK,
            route("https://www.facebook.com/safe_page/", SocialPlatform.YOUTUBE).platform,
        )
        assertBlocked("https://www.facebook.com/reel/123456/", SocialPlatform.YOUTUBE)
        assertBlocked("https://www.facebook.com/video.php?v=123456", SocialPlatform.YOUTUBE)
    }

    @Test
    fun `known outbound wrappers are unwrapped before classification`() {
        val instagramToYoutube = route(
            "https://l.instagram.com/?u=https%3A%2F%2Fyoutu.be%2FdQw4w9WgXcQ",
            SocialPlatform.INSTAGRAM,
        )
        assertEquals(SocialPlatform.YOUTUBE, instagramToYoutube.platform)

        val facebookToArticle = router.decide(
            "https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.com%2Fnews",
            SocialPlatform.FACEBOOK,
            userGesture = true,
        ) as ProtectedLinkDecision.ExternalWeb
        assertEquals("https://example.com/news", facebookToArticle.url)

        assertBlocked(
            "https://www.youtube.com/redirect?q=https%3A%2F%2Fwww.instagram.com%2Freel%2FABC123%2F",
            SocialPlatform.YOUTUBE,
        )
    }

    @Test
    fun `official app schemes are recovered only when an exact HTTPS target is provable`() {
        val youtubeScheme = route("youtube://watch?v=dQw4w9WgXcQ", SocialPlatform.INSTAGRAM)
        assertEquals(SocialPlatform.YOUTUBE, youtubeScheme.platform)
        assertEquals("dQw4w9WgXcQ", youtubeScheme.navigationContext.allowedYouTubeVideoId)

        val youtubeIntent = route(
            "intent://www.youtube.com/watch?v=dQw4w9WgXcQ#Intent;scheme=https;package=com.google.android.youtube;end",
            SocialPlatform.FACEBOOK,
        )
        assertEquals(SocialPlatform.YOUTUBE, youtubeIntent.platform)
        val fallbackIntent = route(
            "intent://unrecoverable#Intent;scheme=unknown;S.browser_fallback_url=https%3A%2F%2Fyoutu.be%2FdQw4w9WgXcQ;end",
            SocialPlatform.INSTAGRAM,
        )
        assertEquals(SocialPlatform.YOUTUBE, fallbackIntent.platform)

        listOf(
            "instagram://profile/creator",
            "fb://profile/123456",
            "messenger://thread/123456",
            "intent://unsafe#Intent;scheme=javascript;end",
            "tel:+123456789",
        ).forEach { url -> assertBlocked(url, SocialPlatform.FACEBOOK) }
    }

    @Test
    fun `same-platform YouTube links cannot create a next-video capability`() {
        assertBlocked("https://youtu.be/dQw4w9WgXcQ", SocialPlatform.YOUTUBE)
        assertBlocked("youtube://watch?v=dQw4w9WgXcQ", SocialPlatform.YOUTUBE)
    }

    @Test
    fun `scripted redirects and authentication state cannot trigger handoff or prompt`() {
        assertBlocked(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            SocialPlatform.INSTAGRAM,
            userGesture = false,
        )
        val authDecision = router.decide(
            "https://example.com/article",
            SocialPlatform.INSTAGRAM,
            userGesture = true,
            sourceMode = PolicyMode.AUTHENTICATING,
        )
        assertTrue(authDecision is ProtectedLinkDecision.Block)
    }

    @Test
    fun `ordinary external HTTPS needs a tap and social lookalikes fail closed`() {
        val external = router.decide(
            "https://example.com/?url=instagram.com",
            SocialPlatform.INSTAGRAM,
            userGesture = true,
        ) as ProtectedLinkDecision.ExternalWeb
        assertEquals("https://example.com/?url=instagram.com", external.url)

        listOf(
            "https://instagram.com.evil.example/path",
            "https://fakeinstagram.com/path",
            "https://youtube.example.com/watch?v=dQw4w9WgXcQ",
        ).forEach { url -> assertBlocked(url, SocialPlatform.FACEBOOK) }
    }

    @Test
    fun `no protected social destination is ever classified as external web`() {
        listOf(
            "https://www.instagram.com/creator/",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            "https://www.facebook.com/reel/123456/",
            "https://www.messenger.com/t/123456/",
            "instagram://profile/creator",
            "fb://profile/123456",
            "youtube://watch?v=dQw4w9WgXcQ",
        ).forEach { url ->
            val decision = router.decide(url, SocialPlatform.FACEBOOK, userGesture = true)
            assertFalse("Protected URL escaped externally: $url", decision is ProtectedLinkDecision.ExternalWeb)
        }
    }

    private fun route(url: String, source: SocialPlatform): ProtectedLinkDecision.RouteInternally =
        router.decide(url, source, userGesture = true) as ProtectedLinkDecision.RouteInternally

    private fun assertBlocked(
        url: String,
        source: SocialPlatform,
        userGesture: Boolean = true,
    ) {
        val decision = router.decide(url, source, userGesture)
        assertTrue("Expected blocked: $url but got $decision", decision is ProtectedLinkDecision.Block)
    }
}
