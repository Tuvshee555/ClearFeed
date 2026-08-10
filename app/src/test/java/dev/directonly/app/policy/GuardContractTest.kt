package dev.directonly.app.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GuardContractTest {
    @Test
    fun `every guard uses document mutation enforcement and the restricted bridge`() {
        listOf("instagram_guard.js", "youtube_guard.js", "facebook_guard.js").forEach { name ->
            val script = asset(name)
            assertTrue("$name must observe regenerated DOM", script.contains("MutationObserver"))
            assertTrue("$name must use the restricted bridge", script.contains("clearFeedBridge"))
            assertTrue("$name must authenticate bridge events", script.contains("BRIDGE_TOKEN"))
            assertTrue("$name must authenticate user handoffs", script.contains("event.isTrusted"))
            assertTrue("$name must report gesture context", script.contains("userGesture"))
            assertTrue("$name must prevent popup escapes", script.contains("window, 'open'"))
            assertFalse("$name must never read cookies", script.contains("document.cookie"))
            assertFalse("$name must never install a JavascriptInterface", script.contains("addJavascriptInterface"))
        }
    }

    @Test
    fun `Instagram guard requires a trusted DM tap and seals one exact item`() {
        val script = asset("guard_rules.js") + asset("instagram_guard.js") + asset("instagram_guard.css")
        listOf(
            "instagramDmTapDecision",
            "event.isTrusted",
            "intentional_instagram_shared_content",
            "clearfeed_shared",
            "instagramSealedItemDecision",
            "instagramSealedLinkDecision",
            "instagramSealedGestureDecision",
            "instagramSealedMediaEndDecision",
            "data-directonly-sealed-stream",
            "touchmove",
            "ended",
            "comment",
            "routeKind(location.href) === 'auth'",
            "repairAuthenticationLayout",
            "loginSurfaceIsVisible",
            "min-height",
        ).forEach { marker ->
            assertTrue("Missing Instagram sealed-viewer marker: $marker", script.contains(marker))
        }
    }

    @Test
    fun `YouTube guard removes Shorts comments recommendations and autoplay-next UI`() {
        val script = asset("youtube_guard.js") + asset("youtube_guard.css")
        listOf(
            "/shorts/",
            "ytm-reel-shelf-renderer",
            "ytm-shorts-lockup-view-model",
            "pivot-shorts",
            "ytm-comment",
            "watch-next-results",
            "ytp-autonav",
            "intentional_video",
        ).forEach { marker -> assertTrue("Missing YouTube guard marker: $marker", script.contains(marker)) }
    }

    @Test
    fun `Facebook guard semantically removes videos stories recommendations and caps eight posts`() {
        val script = asset("guard_rules.js") + asset("facebook_guard.js") + asset("facebook_guard.css")
        listOf(
            "FACEBOOK_FEED_POST_LIMIT = 8",
            "facebookFeedRouteDecision",
            "filter=all&sk=h_chr",
            "[role=\"article\"]",
            "Actions for this post",
            "articleIsReady",
            "articleStates",
            "authentication_required",
            "repairAuthenticationLayout",
            "loginSurfaceIsVisible",
            "input[type=\"password\"]",
            "querySelector?.('video')",
            "Stories",
            "suggested for you",
            "clearfeed-feed-end",
        ).forEach { marker -> assertTrue("Missing Facebook guard marker: $marker", script.contains(marker, ignoreCase = true)) }
    }

    private fun asset(name: String): String {
        val candidates = listOf(
            File("src/main/assets/$name"),
            File("app/src/main/assets/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate asset $name from ${File(".").absolutePath}")
    }
}
