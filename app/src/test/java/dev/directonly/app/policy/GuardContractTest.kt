package dev.directonly.app.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level prohibitions on the injected guards.
 *
 * This class deliberately contains only *absence* assertions. You cannot demonstrate that
 * a capability is never used by exercising behaviour — no amount of passing DOM tests
 * proves `document.cookie` is never read — so these are checked against the source text.
 *
 * The previous version of this class also asserted the *presence* of substrings such as
 * `"MutationObserver"` and `"instagramDmTapDecision"`. Those assertions verified nothing:
 * they passed while `inDirectMessageSurface` was a tautology that authorized any anchor in
 * the thread body, and they broke on harmless refactors. Guard behaviour is now verified
 * by executing the guards in a real DOM — see `tools/guard-dom-tests.js` — and their pure
 * decision rules by `tools/guard-fixture-tests.js`.
 */
class GuardContractTest {
    private val platformGuards = listOf("instagram_guard.js", "youtube_guard.js", "facebook_guard.js")

    @Test
    fun `guards never read cookies or install a JavaScript interface`() {
        (platformGuards + "guard_rules.js").forEach { name ->
            val script = asset(name)
            assertFalse("$name must never read cookies", script.contains("document.cookie"))
            assertFalse(
                "$name must never install a JavascriptInterface",
                script.contains("addJavascriptInterface"),
            )
            assertFalse("$name must never use eval", script.contains("eval("))
            assertFalse(
                "$name must never post to the network directly",
                script.contains("XMLHttpRequest") || script.contains("navigator.sendBeacon"),
            )
        }
    }

    @Test
    fun `guards never read credential or private message values`() {
        platformGuards.forEach { name ->
            val script = asset(name)
            // Guards inspect route metadata, element types and semantic attributes. Reading
            // input values would put credentials and message text inside guard scope.
            assertFalse("$name must not read input values", script.contains(".value"))
            assertFalse("$name must not read storage", script.contains("localStorage"))
            assertFalse("$name must not read storage", script.contains("sessionStorage"))
            assertFalse("$name must not read storage", script.contains("indexedDB"))
        }
    }

    @Test
    fun `no guard offers a bypass toggle`() {
        // README and SECURITY.md both state the restrictions are fixed policy rather than
        // preferences. A toggle appearing in guard source would contradict that.
        val forbidden = listOf(
            "strictMode", "filterEnabled", "reelsEnabled", "shortsEnabled", "allowReels",
            "allowShorts", "allowFeed", "feedLimitEnabled", "temporaryUnlock",
            "disableProtection", "bypass", "focusMode", "unrestricted",
        )
        (platformGuards + "guard_rules.js").forEach { name ->
            val script = asset(name)
            forbidden.forEach { token ->
                assertFalse("$name must not contain a bypass toggle: $token", script.contains(token))
            }
        }
    }

    @Test
    fun `the Facebook feed limit is stated identically in Kotlin and in the guard rules`() {
        // The limit is enforced in guard_rules.js but documented as a Kotlin constant, so
        // the two can drift apart silently.
        assertTrue(
            "guard_rules.js must declare the same eight-post limit as FacebookNavigationPolicy",
            asset("guard_rules.js").contains(
                "FACEBOOK_FEED_POST_LIMIT = ${FacebookNavigationPolicy.FACEBOOK_FEED_POST_LIMIT}",
            ),
        )
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
