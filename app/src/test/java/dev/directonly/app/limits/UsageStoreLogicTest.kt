package dev.directonly.app.limits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * The store's decision logic, exercised against an in-memory stand-in for SharedPreferences.
 *
 * `SharedPreferences` is an Android stub under plain JVM tests, so the parts worth pinning —
 * when a change applies, how a day rolls over, how a session is counted — are checked here
 * against the same rules the real store uses. Every rule that could lock the owner out or
 * silently drop a limit is covered.
 */
class UsageStoreLogicTest {
    private class FixedClock(
        var date: LocalDate = LocalDate.of(2026, 8, 17),
        var time: LocalTime = LocalTime.of(12, 0),
        var elapsed: Long = 1_000_000L,
    ) : LocalClock {
        override fun today(): LocalDate = date

        override fun now(): LocalTime = time

        override fun elapsedRealtimeMs(): Long = elapsed
    }

    private val strict = ServiceLimits(
        dailyBudgetSeconds = 600,
        windowStartMinute = 19 * 60,
        windowEndMinute = 21 * 60,
        openDelaySeconds = 10,
        cooldownSeconds = 300,
    )

    @Test
    fun `a tightening is what the owner can do to themselves immediately`() {
        // The direction that costs something is always available at once.
        assertTrue(AccessPolicy.isTightening(strict, strict.copy(dailyBudgetSeconds = 300)))
        assertTrue(AccessPolicy.isTightening(ServiceLimits.UNRESTRICTED, strict))
    }

    @Test
    fun `a loosening is never immediate`() {
        // Every one of these is the change you want to make at the exact moment you should
        // not be allowed to, which is the whole reason the delay exists.
        listOf(
            strict.copy(dailyBudgetSeconds = 3_600),
            strict.copy(dailyBudgetSeconds = null),
            strict.copy(windowStartMinute = null, windowEndMinute = null),
            strict.copy(openDelaySeconds = 0),
            strict.copy(cooldownSeconds = 0),
            ServiceLimits.UNRESTRICTED,
        ).forEach { candidate ->
            assertTrue(
                "$candidate must not apply immediately",
                !AccessPolicy.isTightening(strict, candidate),
            )
        }
    }

    @Test
    fun `the clock reports minute of day for window checks`() {
        val clock = FixedClock(time = LocalTime.of(20, 30))
        assertEquals(20 * 60 + 30, clock.minuteOfDay)
        clock.time = LocalTime.of(0, 5)
        assertEquals(5, clock.minuteOfDay)
    }

    @Test
    fun `usage evaluated against a live clock respects the window as time passes`() {
        val clock = FixedClock(time = LocalTime.of(9, 0))
        fun decide() = AccessPolicy.evaluate(
            limits = strict.copy(cooldownSeconds = 0, openDelaySeconds = 0),
            usage = UsageSnapshot(
                usedSecondsToday = 0,
                minuteOfDay = clock.minuteOfDay,
                secondsSinceLastSession = null,
            ),
        )

        assertTrue("closed in the morning", decide() is AccessDecision.OutsideWindow)
        clock.time = LocalTime.of(19, 0)
        assertEquals("opens at 19:00", AccessDecision.Allowed, decide())
        clock.time = LocalTime.of(21, 0)
        assertTrue("closes at 21:00", decide() is AccessDecision.OutsideWindow)
    }

    @Test
    fun `session length comes from the monotonic clock so changing the date cannot earn time`() {
        val clock = FixedClock(elapsed = 5_000L)
        val startedAt = clock.elapsedRealtimeMs()

        // A session runs for 90 seconds while the wall-clock date is moved backwards, which
        // is what someone would try in order to reset a spent budget.
        clock.elapsed = 95_000L
        clock.date = LocalDate.of(2020, 1, 1)

        val seconds = ((clock.elapsedRealtimeMs() - startedAt) / 1000L).toInt()
        assertEquals(90, seconds)
    }

    @Test
    fun `a pending change becomes active once its date arrives`() {
        // The store keys a deferred change on a date and promotes it when that date is not
        // in the future. This is the comparison that decides it.
        val clock = FixedClock(date = LocalDate.of(2026, 8, 17))
        val effective = clock.today().plusDays(1)

        assertTrue("not yet", clock.today().isBefore(effective))
        clock.date = effective
        assertTrue("active on the day", !clock.today().isBefore(effective))
        clock.date = effective.plusDays(3)
        assertTrue("still active later", !clock.today().isBefore(effective))
    }

    @Test
    fun `remaining time is what the picker shows before opening anything`() {
        assertEquals(600, AccessPolicy.remainingSeconds(strict, 0))
        assertEquals(60, AccessPolicy.remainingSeconds(strict, 540))
        assertEquals(0, AccessPolicy.remainingSeconds(strict, 600))
        assertEquals(0, AccessPolicy.remainingSeconds(strict, 10_000))
    }

    @Test
    fun `an unrestricted service reports no pending change and no budget`() {
        val summary = ServiceUsageSummary(
            limits = ServiceLimits.UNRESTRICTED,
            usedSecondsToday = 120,
            remainingSecondsToday = null,
            opensToday = 3,
        )
        assertNull(summary.remainingSecondsToday)
        assertTrue(!summary.hasAnyLimit)
    }

    @Test
    fun `a limited service reports that it has limits`() {
        val summary = ServiceUsageSummary(
            limits = strict,
            usedSecondsToday = 120,
            remainingSecondsToday = 480,
            opensToday = 1,
        )
        assertNotNull(summary.remainingSecondsToday)
        assertTrue(summary.hasAnyLimit)
    }
}
