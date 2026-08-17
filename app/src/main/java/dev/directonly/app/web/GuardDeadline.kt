package dev.directonly.app.web

/**
 * Bookkeeping for the "the guard never reported healthy" watchdog.
 *
 * Extracted from `ClearFeedCoordinator` because the original inline version had a defect
 * that no test could reach: the pending task was cleared when it was *cancelled* but not
 * when it *fired*, and scheduling was skipped whenever a pending task existed. After the
 * first timeout the field stayed non-null forever, so no watchdog could ever be armed
 * again — pressing Try again on a hung page left the app on the loading spinner with no
 * error and no diagnostic.
 *
 * It also keyed nothing to the view it belonged to. On a service switch the new WebView is
 * attached before the old one is released, so cancelling only when `webView === view` left
 * a stale task queued on the main thread holding a destroyed WebView, while simultaneously
 * blocking the new view's deadline.
 *
 * The rules this encodes:
 * - an existing deadline for the *same* owner is left alone, so redirects and repeated
 *   lifecycle callbacks cannot extend loading indefinitely;
 * - a deadline for a *different* owner is replaced;
 * - firing clears the deadline, so the next navigation or retry can arm a fresh one;
 * - cancellation is by owner identity, independent of which owner is currently active.
 *
 * @param scheduler posts a task after a delay and returns a handle used to cancel it.
 */
class GuardDeadline<OWNER : Any>(
    private val timeoutMs: Long,
    private val scheduler: Scheduler,
) {
    interface Scheduler {
        fun post(delayMs: Long, task: () -> Unit): Any

        fun cancel(handle: Any)
    }

    private var owner: OWNER? = null
    private var handle: Any? = null

    val isArmed: Boolean
        get() = handle != null

    /** The owner the current deadline belongs to, or null when nothing is armed. */
    fun armedFor(): OWNER? = owner

    /**
     * Arms a deadline for [owner], unless one is already armed for that same owner.
     *
     * @return true when a new deadline was armed.
     */
    fun schedule(owner: OWNER, onExpired: (OWNER) -> Unit): Boolean {
        if (handle != null && this.owner === owner) return false
        cancel()
        this.owner = owner
        handle = scheduler.post(timeoutMs) {
            // Clear before invoking, so a re-arm from inside the callback is not discarded
            // and so an expired deadline never blocks the next one.
            val expiredOwner = this.owner
            this.owner = null
            handle = null
            if (expiredOwner != null) onExpired(expiredOwner)
        }
        return true
    }

    /** Cancels any armed deadline. */
    fun cancel() {
        handle?.let(scheduler::cancel)
        handle = null
        owner = null
    }

    /** Cancels only when the armed deadline belongs to [candidate]. */
    fun cancelIfOwnedBy(candidate: OWNER) {
        if (owner === candidate) cancel()
    }
}
