package dev.directonly.app.ui

import dev.directonly.app.model.SocialPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClearFeedUiContractTest {
    @Test
    fun `external confirmation shows only a normalized destination host`() {
        assertEquals(
            "example.com",
            externalDestinationLabel("https://www.Example.com/private/path?token=secret"),
        )
        assertEquals(
            "xn--bcher-kva.example",
            externalDestinationLabel("https://xn--bcher-kva.example/path"),
        )
        assertEquals("external website", externalDestinationLabel("javascript:alert(1)"))
        assertEquals("external website", externalDestinationLabel("not a URL"))
    }

    @Test
    fun `service picker states each permanent product boundary`() {
        assertTrue(SocialPlatform.INSTAGRAM.description.contains("exact", ignoreCase = true))
        assertTrue(SocialPlatform.YOUTUBE.description.contains("intentionally", ignoreCase = true))
        assertTrue(SocialPlatform.FACEBOOK.description.contains("8 non-video posts", ignoreCase = true))
    }

    @Test
    fun `the in-app privacy statement matches what the app actually does`() {
        // This dialog is the privacy notice most users will ever read. It previously stated
        // "ClearFeed has no backend, analytics, or private social API" while the app posted
        // diagnostics to a remote endpoint on every navigation stage. A false claim here is
        // worse than a false claim in a document, so it is pinned.
        val source = uiSource()
        assertFalse(
            "The privacy dialog must not claim there is no backend while a reporting " +
                "endpoint exists in the app",
            source.contains("no backend, analytics, or private social API"),
        )
        assertTrue(
            "The privacy dialog must disclose that reporting is opt-in",
            source.contains("off by") && source.contains("Send failure reports"),
        )
    }

    private fun uiSource(): String {
        val relative = "src/main/java/dev/directonly/app/ui/ClearFeedApp.kt"
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate ClearFeedApp.kt from ${File(".").absolutePath}")
    }
}
