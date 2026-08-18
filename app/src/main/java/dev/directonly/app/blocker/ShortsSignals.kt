package dev.directonly.app.blocker

/**
 * A single accessibility node's identifying attributes.
 *
 * Deliberately limited to structural fields — resource id and class name — and never text or
 * content description. Those can carry on-screen content (video titles, channel names,
 * captions), and ClearFeed's diagnostics have never collected page content; the blocker keeps
 * that same boundary. Structural ids are also the more reliable signal: YouTube's bottom
 * navigation tab is permanently labelled "Shorts" even on the Home screen, so matching on
 * visible text would misfire constantly.
 */
data class NodeSignal(
    val resourceId: String?,
    val className: String?,
)

/** A snapshot of the foreground window's structure, cheap enough to build on every event. */
data class ScreenSnapshot(
    val packageName: String,
    val nodes: List<NodeSignal>,
)

sealed interface ShortsDetection {
    data class Detected(val matchedSignal: String) : ShortsDetection
    data object NotDetected : ShortsDetection
}
