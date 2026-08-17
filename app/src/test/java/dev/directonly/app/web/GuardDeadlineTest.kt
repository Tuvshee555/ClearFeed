package dev.directonly.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watchdog that turns a page which never reports healthy into a visible error.
 *
 * The first test here is the one that matters: before the deadline was extracted, the
 * pending task was cleared on cancel but not on expiry, and scheduling was skipped whenever
 * a pending task existed. So the watchdog fired exactly once per process. Pressing "Try
 * again" on a hung page then produced an indefinite loading spinner with no error and no
 * diagnostic — the single most likely cause of a real "it just never loads" report.
 */
class GuardDeadlineTest {
    /** A scheduler that runs nothing until told to, so expiry is deterministic. */
    private class FakeScheduler : GuardDeadline.Scheduler {
        private val pending = LinkedHashMap<Any, () -> Unit>()
        var scheduleCount = 0
            private set
        var cancelCount = 0
            private set

        override fun post(delayMs: Long, task: () -> Unit): Any {
            scheduleCount++
            val handle = Any()
            pending[handle] = task
            return handle
        }

        override fun cancel(handle: Any) {
            if (pending.remove(handle) != null) cancelCount++
        }

        val pendingCount: Int get() = pending.size

        /** Fires every pending task, as the main looper would once the delay elapsed. */
        fun elapse() {
            val tasks = pending.values.toList()
            pending.clear()
            tasks.forEach { it() }
        }
    }

    private val owner = Any()
    private val other = Any()

    private fun deadline(scheduler: FakeScheduler) =
        GuardDeadline<Any>(timeoutMs = 12_000L, scheduler = scheduler)

    @Test
    fun `the watchdog can be re-armed after it has already fired`() {
        val scheduler = FakeScheduler()
        val deadline = deadline(scheduler)
        val expired = mutableListOf<Any>()

        assertTrue(deadline.schedule(owner) { expired += it })
        scheduler.elapse()
        assertEquals("the first load times out", 1, expired.size)
        assertFalse("an expired deadline must not stay armed", deadline.isArmed)

        // This is the retry. It must arm a fresh deadline.
        assertTrue("a retry must arm a new deadline", deadline.schedule(owner) { expired += it })
        scheduler.elapse()
        assertEquals("the retry must also be able to time out", 2, expired.size)
    }

    @Test
    fun `an existing deadline for the same owner is not extended`() {
        val scheduler = FakeScheduler()
        val deadline = deadline(scheduler)

        assertTrue(deadline.schedule(owner) {})
        // Redirects and repeated lifecycle callbacks must not push the deadline out forever.
        assertFalse(deadline.schedule(owner) {})
        assertFalse(deadline.schedule(owner) {})
        assertEquals("only one deadline may be scheduled", 1, scheduler.scheduleCount)
        assertEquals(1, scheduler.pendingCount)
    }

    @Test
    fun `a deadline for a different owner replaces the previous one`() {
        val scheduler = FakeScheduler()
        val deadline = deadline(scheduler)
        val expired = mutableListOf<Any>()

        deadline.schedule(owner) { expired += it }
        // A service switch attaches the new WebView before releasing the old one.
        assertTrue(deadline.schedule(other) { expired += it })
        assertEquals("the stale deadline must be cancelled", 1, scheduler.cancelCount)
        assertEquals("only the new deadline stays queued", 1, scheduler.pendingCount)
        assertSame(other, deadline.armedFor())

        scheduler.elapse()
        assertEquals(listOf(other), expired)
    }

    @Test
    fun `cancellation is by owner identity and not by whichever owner is active`() {
        val scheduler = FakeScheduler()
        val deadline = deadline(scheduler)

        deadline.schedule(owner) {}
        deadline.cancelIfOwnedBy(other)
        assertTrue("an unrelated owner must not cancel this deadline", deadline.isArmed)

        deadline.cancelIfOwnedBy(owner)
        assertFalse("the owning view must cancel its own deadline", deadline.isArmed)
        assertEquals("nothing may stay queued for a released view", 0, scheduler.pendingCount)
    }

    @Test
    fun `expiry reports the owner it was armed for`() {
        val scheduler = FakeScheduler()
        val deadline = deadline(scheduler)
        var reported: Any? = null

        deadline.schedule(owner) { reported = it }
        scheduler.elapse()
        assertSame(owner, reported)
    }

    @Test
    fun `a cancelled deadline never fires`() {
        val scheduler = FakeScheduler()
        val deadline = deadline(scheduler)
        var fired = false

        deadline.schedule(owner) { fired = true }
        deadline.cancel()
        scheduler.elapse()
        assertFalse(fired)
        assertFalse(deadline.isArmed)
    }

    @Test
    fun `re-arming from inside the expiry callback is honoured`() {
        val scheduler = FakeScheduler()
        val deadline = deadline(scheduler)
        var rearmed = false

        deadline.schedule(owner) {
            // recoverToSafeRoot navigates, which schedules a fresh deadline.
            rearmed = deadline.schedule(owner) {}
        }
        scheduler.elapse()
        assertTrue("a deadline armed during expiry must not be swallowed", rearmed)
        assertTrue(deadline.isArmed)
    }
}
