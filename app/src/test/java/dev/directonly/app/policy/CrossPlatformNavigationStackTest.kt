package dev.directonly.app.policy

import dev.directonly.app.model.SocialPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossPlatformNavigationStackTest {
    @Test
    fun `repeated Back returns through exact safe origins without WebView history`() {
        val stack = CrossPlatformNavigationStack()
        stack.push(
            CrossPlatformReturnPoint(
                platform = SocialPlatform.INSTAGRAM,
                returnUrl = "https://www.instagram.com/direct/t/thread_1/",
                allowedYouTubeVideoId = null,
            ),
        )
        stack.push(
            CrossPlatformReturnPoint(
                platform = SocialPlatform.YOUTUBE,
                returnUrl = "https://m.youtube.com/watch?v=chosen123",
                allowedYouTubeVideoId = "chosen123",
            ),
        )

        val youtube = requireNotNull(stack.pop())
        assertEquals(SocialPlatform.YOUTUBE, youtube.platform)
        assertEquals("chosen123", youtube.allowedYouTubeVideoId)
        val instagram = requireNotNull(stack.pop())
        assertEquals(SocialPlatform.INSTAGRAM, instagram.platform)
        assertEquals("https://www.instagram.com/direct/t/thread_1/", instagram.returnUrl)
        assertFalse(stack.isNotEmpty())
        assertNull(stack.pop())
    }

    @Test
    fun `manual home clears every pending cross-platform return`() {
        val stack = CrossPlatformNavigationStack()
        stack.push(
            CrossPlatformReturnPoint(
                SocialPlatform.FACEBOOK,
                FacebookNavigationPolicy.FEED_URL,
                null,
            ),
        )
        assertTrue(stack.isNotEmpty())
        stack.clear()
        assertFalse(stack.isNotEmpty())
    }

    @Test
    fun `process recreation cannot restore handoff metadata`() {
        val oldProcess = CrossPlatformNavigationStack()
        oldProcess.push(
            CrossPlatformReturnPoint(
                SocialPlatform.INSTAGRAM,
                "https://www.instagram.com/direct/t/thread_1/",
                null,
            ),
        )

        val recreatedProcess = CrossPlatformNavigationStack()
        assertFalse(recreatedProcess.isNotEmpty())
    }
}
