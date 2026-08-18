package dev.directonly.app.blocker

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Walks an accessibility node tree into a [ScreenSnapshot].
 *
 * Bounded on both node count and depth. Content-changed events fire frequently while
 * scrolling, and YouTube's window can be deep with overlays layered on top of Shorts
 * specifically, so an unbounded walk risks a slow frame on every event.
 *
 * This is a thin, low-logic adapter by design: it depends on `android.view.accessibility`,
 * which is not available under plain JVM unit tests (calling it throws "not mocked" the same
 * way `org.json` did before that dependency was added — see `RemoteDiagnosticsReporter`'s
 * history). All the interesting decision logic lives in [ShortsDetector], which is pure
 * Kotlin and is exhaustively tested; this class stays deliberately dumb.
 */
object AccessibilityNodeWalker {
    private const val MAX_NODES = 400
    private const val MAX_DEPTH = 40

    fun snapshot(packageName: String, root: AccessibilityNodeInfo?): ScreenSnapshot {
        if (root == null) return ScreenSnapshot(packageName, emptyList())
        val nodes = mutableListOf<NodeSignal>()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty() && nodes.size < MAX_NODES) {
            val (node, depth) = stack.removeLast()
            nodes += NodeSignal(
                resourceId = node.viewIdResourceName,
                className = node.className?.toString(),
            )
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { stack.addLast(it to depth + 1) }
                }
            }
        }
        return ScreenSnapshot(packageName, nodes)
    }
}
