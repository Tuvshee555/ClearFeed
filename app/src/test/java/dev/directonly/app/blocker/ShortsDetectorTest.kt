package dev.directonly.app.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every test in this file is the actual verification for the blocker's core decision — there
 * is no emulator or device available to exercise the real accessibility tree, so this pure
 * classifier is where correctness has to be proven before anything reaches a phone.
 *
 * The most important property tested here is the negative: YouTube's bottom navigation bar
 * is permanently labelled "Shorts" on every screen, including Home. A detector that matched
 * on visible text would misfire constantly and kick the user out of ordinary use — the exact
 * failure mode that would make this worse than the app it replaced.
 */
class ShortsDetectorTest {
    private fun node(resourceId: String? = null, className: String? = null) =
        NodeSignal(resourceId = resourceId, className = className)

    @Test
    fun `a different app never matches regardless of node content`() {
        val snapshot = ScreenSnapshot(
            packageName = "com.instagram.android",
            nodes = listOf(node(resourceId = "com.google.android.youtube:id/reel_player_page_container")),
        )
        assertEquals(ShortsDetection.NotDetected, ShortsDetector.classify(snapshot))
    }

    @Test
    fun `the YouTube home screen is never detected as Shorts`() {
        val snapshot = ScreenSnapshot(
            packageName = ShortsDetector.YOUTUBE_PACKAGE,
            nodes = listOf(
                node(resourceId = "com.google.android.youtube:id/bottom_bar_container"),
                node(
                    resourceId = "com.google.android.youtube:id/tab_shorts",
                    className = "android.widget.FrameLayout",
                ),
                node(resourceId = "com.google.android.youtube:id/home_feed_recycler"),
            ),
        )
        assertEquals(ShortsDetection.NotDetected, ShortsDetector.classify(snapshot))
    }

    @Test
    fun `YouTube search results are never detected as Shorts`() {
        val snapshot = ScreenSnapshot(
            packageName = ShortsDetector.YOUTUBE_PACKAGE,
            nodes = listOf(
                node(resourceId = "com.google.android.youtube:id/search_results_recycler"),
                node(resourceId = "com.google.android.youtube:id/tab_shorts"),
            ),
        )
        assertEquals(ShortsDetection.NotDetected, ShortsDetector.classify(snapshot))
    }

    @Test
    fun `a normal watch page is never detected as Shorts`() {
        val snapshot = ScreenSnapshot(
            packageName = ShortsDetector.YOUTUBE_PACKAGE,
            nodes = listOf(
                node(resourceId = "com.google.android.youtube:id/watch_player"),
                node(resourceId = "com.google.android.youtube:id/player_control_view"),
                node(resourceId = "com.google.android.youtube:id/related_videos_recycler"),
            ),
        )
        assertEquals(ShortsDetection.NotDetected, ShortsDetector.classify(snapshot))
    }

    @Test
    fun `the Shorts player is detected by its resource id`() {
        val snapshot = ScreenSnapshot(
            packageName = ShortsDetector.YOUTUBE_PACKAGE,
            nodes = listOf(
                node(resourceId = "com.google.android.youtube:id/bottom_bar_container"),
                node(resourceId = "com.google.android.youtube:id/reel_player_page_container"),
            ),
        )
        val decision = ShortsDetector.classify(snapshot)
        assertTrue(decision is ShortsDetection.Detected)
        assertEquals("resourceId~reel_player", (decision as ShortsDetection.Detected).matchedSignal)
    }

    @Test
    fun `every known resource id signal is individually sufficient`() {
        listOf(
            "reel_player", "reel_recycler", "reel_watch_fragment",
            "reel_progress", "shorts_player", "shorts_container",
        ).forEach { signal ->
            val snapshot = ScreenSnapshot(
                packageName = ShortsDetector.YOUTUBE_PACKAGE,
                nodes = listOf(node(resourceId = "com.google.android.youtube:id/${signal}_view")),
            )
            assertTrue(
                "$signal must be sufficient on its own",
                ShortsDetector.classify(snapshot) is ShortsDetection.Detected,
            )
        }
    }

    @Test
    fun `matching is case-insensitive`() {
        val snapshot = ScreenSnapshot(
            packageName = ShortsDetector.YOUTUBE_PACKAGE,
            nodes = listOf(node(resourceId = "com.google.android.youtube:id/REEL_RECYCLER_VIEW")),
        )
        assertTrue(ShortsDetector.classify(snapshot) is ShortsDetection.Detected)
    }

    @Test
    fun `a custom Shorts view class is detected even without a matching resource id`() {
        val snapshot = ScreenSnapshot(
            packageName = ShortsDetector.YOUTUBE_PACKAGE,
            nodes = listOf(node(className = "com.google.android.apps.youtube.app.watch.ReelWatchFragment")),
        )
        val decision = ShortsDetector.classify(snapshot)
        assertTrue(decision is ShortsDetection.Detected)
        assertEquals("className~ReelWatchFragment", (decision as ShortsDetection.Detected).matchedSignal)
    }

    @Test
    fun `an unrelated resource id does not match`() {
        val snapshot = ScreenSnapshot(
            packageName = ShortsDetector.YOUTUBE_PACKAGE,
            nodes = listOf(node(resourceId = "com.google.android.youtube:id/subscriptions_feed")),
        )
        assertEquals(ShortsDetection.NotDetected, ShortsDetector.classify(snapshot))
    }

    @Test
    fun `an empty screen is never detected`() {
        val snapshot = ScreenSnapshot(packageName = ShortsDetector.YOUTUBE_PACKAGE, nodes = emptyList())
        assertEquals(ShortsDetection.NotDetected, ShortsDetector.classify(snapshot))
    }

    @Test
    fun `a null resource id and class name on every node does not crash or match`() {
        val snapshot = ScreenSnapshot(
            packageName = ShortsDetector.YOUTUBE_PACKAGE,
            nodes = listOf(node(), node(), node()),
        )
        assertEquals(ShortsDetection.NotDetected, ShortsDetector.classify(snapshot))
    }

    @Test
    fun `resource id summary is deduplicated, capped, and excludes blanks`() {
        val snapshot = ScreenSnapshot(
            packageName = ShortsDetector.YOUTUBE_PACKAGE,
            nodes = listOf(
                node(resourceId = "com.google.android.youtube:id/a"),
                node(resourceId = "com.google.android.youtube:id/a"),
                node(resourceId = "com.google.android.youtube:id/b"),
                node(resourceId = null),
            ),
        )
        assertEquals("a, b", ShortsDetector.summarizeResourceIds(snapshot, limit = 5))
    }

    @Test
    fun `resource id summary respects its cap`() {
        val snapshot = ScreenSnapshot(
            packageName = ShortsDetector.YOUTUBE_PACKAGE,
            nodes = (1..20).map { node(resourceId = "com.google.android.youtube:id/item_$it") },
        )
        val summary = ShortsDetector.summarizeResourceIds(snapshot, limit = 3)
        assertEquals(3, summary.split(", ").size)
    }

    @Test
    fun `an empty screenshot summarizes as no resource ids`() {
        val snapshot = ScreenSnapshot(packageName = ShortsDetector.YOUTUBE_PACKAGE, nodes = emptyList())
        assertEquals("(no resource ids)", ShortsDetector.summarizeResourceIds(snapshot))
    }
}
