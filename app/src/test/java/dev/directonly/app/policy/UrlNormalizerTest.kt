package dev.directonly.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlNormalizerTest {
    @Test
    fun `normalizes host case and a missing trailing slash`() {
        val result = UrlNormalizer.normalize("HTTPS://WWW.INSTAGRAM.COM/direct/inbox?x=1#ignored")
        assertTrue(result is UrlNormalizationResult.Valid)
        result as UrlNormalizationResult.Valid
        assertEquals("https", result.value.scheme)
        assertEquals("www.instagram.com", result.value.host)
        assertEquals("/direct/inbox/", result.value.path)
    }

    @Test
    fun `rejects encoded traversal and slash variants`() {
        listOf(
            "https://www.instagram.com/direct/%2e%2e/reels/",
            "https://www.instagram.com/direct%2finbox/",
            "https://www.instagram.com/direct/%5C/reels/",
            "https://www.instagram.com/direct/%00/inbox/",
        ).forEach { candidate ->
            val result = UrlNormalizer.normalize(candidate)
            assertTrue("Expected unsafe encoding rejection for $candidate", result is UrlNormalizationResult.Invalid)
            result as UrlNormalizationResult.Invalid
            assertEquals(NormalizationFailure.UNSAFE_PATH_ENCODING, result.reason)
        }
    }

    @Test
    fun `rejects user info and explicit ports`() {
        assertTrue(
            UrlNormalizer.normalize("https://instagram.com@evil.example/direct/inbox/")
                is UrlNormalizationResult.Invalid,
        )
        val port = UrlNormalizer.normalize("https://www.instagram.com:443/direct/inbox/")
        assertTrue(port is UrlNormalizationResult.Invalid)
        assertEquals(NormalizationFailure.EXPLICIT_PORT, (port as UrlNormalizationResult.Invalid).reason)
    }

    @Test
    fun `rejects malformed and relative URLs`() {
        listOf(null, "", "/direct/inbox/", "not a url", "https:///direct/inbox/").forEach {
            assertTrue(UrlNormalizer.normalize(it) is UrlNormalizationResult.Invalid)
        }
    }
}
