package dev.directonly.app.blocker

/**
 * Classifies whether a screen snapshot from the YouTube app is its Shorts player.
 *
 * Pure Kotlin, no Android framework dependency, so it is unit-testable without a device or an
 * emulator — which matters here specifically, because this environment has neither. The
 * accessibility-tree walk that produces a [ScreenSnapshot] cannot be verified without a real
 * phone; this classifier is where the actual decision logic lives, and it is exhaustively
 * tested against hand-built fixtures for Home, a normal watch page, and the Shorts player.
 */
object ShortsDetector {
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"

    // Resource-id substrings that appear only on the Shorts player surface itself, never on
    // Home, Subscriptions, Search, or a normal watch page. YouTube's internal name for Shorts
    // is "Reel", which is why most of these say "reel" rather than "shorts" — the bottom
    // navigation tab is literally labelled "Shorts" and is present on every screen, which is
    // exactly the false-positive trap that ruled out matching on visible text.
    //
    // This is a best-effort list assembled without access to a live, current build of the
    // YouTube app; the exact ids can and do change between app versions. If detection misses
    // on a real device, the fix is to extend this set from the activity log (open ClearFeed →
    // View activity, while actually watching a Short), not to change the detection strategy.
    private val RESOURCE_ID_SIGNALS = setOf(
        "reel_player",
        "reel_recycler",
        "reel_watch_fragment",
        "reel_progress",
        "shorts_player",
        "shorts_container",
    )

    private val CLASS_NAME_SIGNALS = setOf(
        "ReelWatchFragment",
        "ReelPlayerFragment",
        "ShortsPlayerFragment",
    )

    fun classify(snapshot: ScreenSnapshot): ShortsDetection {
        if (snapshot.packageName != YOUTUBE_PACKAGE) return ShortsDetection.NotDetected
        for (node in snapshot.nodes) {
            node.resourceId?.let { resourceId ->
                RESOURCE_ID_SIGNALS.firstOrNull { resourceId.contains(it, ignoreCase = true) }
                    ?.let { return ShortsDetection.Detected("resourceId~$it") }
            }
            node.className?.let { className ->
                CLASS_NAME_SIGNALS.firstOrNull { className.contains(it, ignoreCase = true) }
                    ?.let { return ShortsDetection.Detected("className~$it") }
            }
        }
        return ShortsDetection.NotDetected
    }

    /**
     * Distinct resource-id suffixes present in [snapshot], capped and de-duplicated.
     *
     * Used only for the local activity log when a screen is *not* detected as Shorts, so that
     * if detection is wrong, the actual ids YouTube used on the device are visible without
     * needing to inspect the phone directly.
     */
    fun summarizeResourceIds(snapshot: ScreenSnapshot, limit: Int = 8): String {
        val suffixes = snapshot.nodes
            .mapNotNull { it.resourceId?.substringAfterLast('/') }
            .filter(String::isNotBlank)
            .distinct()
            .take(limit)
        return if (suffixes.isEmpty()) "(no resource ids)" else suffixes.joinToString(", ")
    }
}
