package dev.directonly.app.blocker

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import dev.directonly.app.diagnostics.DiagnosticRecord

/**
 * Watches the real YouTube app for its Shorts player and sends the user home when it opens.
 *
 * This is the whole mechanism: no WebView, no reimplementation of YouTube's UI, no DOM
 * policy. The system delivers accessibility events whenever YouTube's foreground window
 * changes; each one is turned into a bounded [ScreenSnapshot] by [AccessibilityNodeWalker]
 * and classified by [ShortsDetector]. A match triggers [GLOBAL_ACTION_HOME] — the same
 * "kicked out of the app" behaviour a published blocker like NoScroll uses, and the one
 * explicitly asked for over continuing to patch a WebView sandbox.
 *
 * Two timers keep this cheap and quiet:
 * - [MIN_PROCESS_INTERVAL_MS] skips re-walking the tree on every single content-changed
 *   event, which can fire many times per second while scrolling.
 * - [ACTION_COOLDOWN_MS] stops a second `performGlobalAction` from firing immediately after
 *   the first — once the user is sent home, YouTube is backgrounded, so further events from
 *   it should not arrive until it is reopened, but the cooldown protects against a burst of
 *   events on the way to the background.
 *
 * Every action, and every screen not detected as Shorts, is logged into [BlockerDiagnostics]
 * — the "not detected" log carries only structural resource-id suffixes, not text, page
 * content, or anything else the account holder typed or watched. That log is how a wrong
 * detection gets fixed: there is no device or emulator in the environment building this, so
 * the log is the only way to see what YouTube's real view hierarchy looks like.
 */
class ShortsAccessibilityService : AccessibilityService() {
    private var lastProcessedAtMs = 0L
    private var lastActionAtMs = 0L
    private var lastSeenLogAtMs = 0L
    private val stats by lazy { BlockerStats(this) }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName != ShortsDetector.YOUTUBE_PACKAGE) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastProcessedAtMs < MIN_PROCESS_INTERVAL_MS) return
        lastProcessedAtMs = now

        val snapshot = AccessibilityNodeWalker.snapshot(packageName, rootInActiveWindow)
        when (val decision = ShortsDetector.classify(snapshot)) {
            is ShortsDetection.Detected -> handleDetected(decision, now)
            ShortsDetection.NotDetected -> handleNotDetected(snapshot, now)
        }
    }

    private fun handleDetected(decision: ShortsDetection.Detected, now: Long) {
        if (now - lastActionAtMs < ACTION_COOLDOWN_MS) return
        lastActionAtMs = now
        stats.recordBlock()
        log(
            code = "BLOCK-SHORTS",
            location = "Shorts player",
            detail = "Matched ${decision.matchedSignal}; sent home",
            now = now,
        )
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun handleNotDetected(snapshot: ScreenSnapshot, now: Long) {
        if (now - lastSeenLogAtMs < SEEN_LOG_INTERVAL_MS) return
        lastSeenLogAtMs = now
        log(
            code = "SEEN-YOUTUBE",
            location = "foreground",
            detail = "No Shorts signal. Ids: ${ShortsDetector.summarizeResourceIds(snapshot)}",
            now = now,
        )
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        log(
            code = "SERVICE-CONNECTED",
            location = "accessibility",
            detail = "Shorts blocker is running",
            now = SystemClock.elapsedRealtime(),
        )
    }

    private fun log(code: String, location: String, detail: String, now: Long) {
        BlockerDiagnostics.trace.record(
            DiagnosticRecord(code = code, service = "YouTube", location = location, detail = detail),
            now,
        )
    }

    private companion object {
        const val MIN_PROCESS_INTERVAL_MS = 200L
        const val ACTION_COOLDOWN_MS = 1_500L
        const val SEEN_LOG_INTERVAL_MS = 4_000L
    }
}
