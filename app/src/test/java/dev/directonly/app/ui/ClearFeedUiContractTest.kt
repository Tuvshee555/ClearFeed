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
    fun `native shell keeps responsive and accessible UI contracts`() {
        val source = uiSource()
        listOf(
            "WindowInsets.safeDrawing",
            "heightIn(min = 60.dp)",
            "contentDescription = \"Back to ClearFeed services\"",
            "contentDescription = \"More options\"",
            "LiveRegionMode.Polite",
            "semantics(mergeDescendants = true)",
            "externalDestinationLabel(url)",
            "DiagnosticsDialog(",
            "Copy diagnostics",
            "View diagnostics",
            "BuildConfig.VERSION_NAME",
            "verticalScroll(rememberScrollState())",
        ).forEach { marker ->
            assertTrue("Missing polished UI contract: $marker", source.contains(marker))
        }
        assertFalse("The header must grow with font scale", source.contains(".height(50.dp)"))
    }

    private fun uiSource(): String {
        val relative = "src/main/java/dev/directonly/app/ui/ClearFeedApp.kt"
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate ClearFeedApp.kt from ${File(".").absolutePath}")
    }
}
