package dev.directonly.app.limits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether a service opens at all.
 *
 * A bug here locks the owner out of their own messages, or silently lets a limit lapse, and
 * neither failure is visible from the outside. Since there is no emulator available for this
 * project, this is the only place these rules get exercised before they reach a phone, so
 * the coverage is deliberately exhaustive around every boundary.
 */
class AccessPolicyTest {
    private val noUsage = UsageSnapshot(
        usedSecondsToday = 0,
        minuteOfDay = 12 * 60,
        secondsSinceLastSession = null,
    )

    @Test
    fun `an unrestricted service always opens immediately`() {
        assertEquals(
            AccessDecision.Allowed,
            AccessPolicy.evaluate(ServiceLimits.UNRESTRICTED, noUsage),
        )
    }

    @Test
    fun `the budget blocks only once it is fully spent`() {
        val limits = ServiceLimits.UNRESTRICTED.copy(dailyBudgetSeconds = 900)

        assertEquals(AccessDecision.Allowed, AccessPolicy.evaluate(limits, noUsage))
        assertEquals(
            AccessDecision.Allowed,
            AccessPolicy.evaluate(limits, noUsage.copy(usedSecondsToday = 899)),
        )
        // Exactly at the budget is spent, not "one more second".
        assertTrue(
            AccessPolicy.evaluate(limits, noUsage.copy(usedSecondsToday = 900))
                is AccessDecision.BudgetExhausted,
        )
        assertTrue(
            AccessPolicy.evaluate(limits, noUsage.copy(usedSecondsToday = 5_000))
                is AccessDecision.BudgetExhausted,
        )
    }

    @Test
    fun `a zero budget turns the service off entirely`() {
        val decision = AccessPolicy.evaluate(
            ServiceLimits.UNRESTRICTED.copy(dailyBudgetSeconds = 0),
            noUsage,
        )
        assertTrue(decision is AccessDecision.BudgetExhausted)
        assertFalse(decision.isAllowed)
    }

    @Test
    fun `remaining budget never goes negative and is null when uncapped`() {
        val limits = ServiceLimits.UNRESTRICTED.copy(dailyBudgetSeconds = 600)
        assertEquals(600, AccessPolicy.remainingSeconds(limits, 0))
        assertEquals(100, AccessPolicy.remainingSeconds(limits, 500))
        assertEquals(0, AccessPolicy.remainingSeconds(limits, 900))
        assertEquals(null, AccessPolicy.remainingSeconds(ServiceLimits.UNRESTRICTED, 900))
    }

    @Test
    fun `a normal window is inclusive of its start and exclusive of its end`() {
        // 19:00 to 21:00
        val start = 19 * 60
        val end = 21 * 60
        assertFalse(AccessPolicy.isInsideWindow(start - 1, start, end))
        assertTrue(AccessPolicy.isInsideWindow(start, start, end))
        assertTrue(AccessPolicy.isInsideWindow(end - 1, start, end))
        assertFalse(AccessPolicy.isInsideWindow(end, start, end))
    }

    @Test
    fun `a window whose end precedes its start wraps past midnight`() {
        // 22:00 to 01:00
        val start = 22 * 60
        val end = 1 * 60
        assertTrue("23:30 is inside", AccessPolicy.isInsideWindow(23 * 60 + 30, start, end))
        assertTrue("00:30 is inside", AccessPolicy.isInsideWindow(30, start, end))
        assertTrue("22:00 is inside", AccessPolicy.isInsideWindow(start, start, end))
        assertFalse("01:00 is outside", AccessPolicy.isInsideWindow(end, start, end))
        assertFalse("midday is outside", AccessPolicy.isInsideWindow(12 * 60, start, end))
    }

    @Test
    fun `outside the window the service refuses and says when it opens`() {
        val limits = ServiceLimits.UNRESTRICTED.copy(
            windowStartMinute = 19 * 60,
            windowEndMinute = 21 * 60,
        )
        val decision = AccessPolicy.evaluate(limits, noUsage.copy(minuteOfDay = 9 * 60))
        assertEquals(AccessDecision.OutsideWindow(19 * 60, 21 * 60), decision)
        assertFalse(decision.isAllowed)

        assertTrue(
            AccessPolicy.evaluate(limits, noUsage.copy(minuteOfDay = 20 * 60)).isAllowed,
        )
    }

    @Test
    fun `a half-specified window imposes no restriction`() {
        // Both bounds are required; one alone is meaningless and must not lock anything out.
        listOf(
            ServiceLimits.UNRESTRICTED.copy(windowStartMinute = 19 * 60),
            ServiceLimits.UNRESTRICTED.copy(windowEndMinute = 21 * 60),
        ).forEach { limits ->
            assertEquals(
                AccessDecision.Allowed,
                AccessPolicy.evaluate(limits, noUsage.copy(minuteOfDay = 3 * 60)),
            )
        }
    }

    @Test
    fun `cooldown blocks a reopen and reports the remaining wait`() {
        val limits = ServiceLimits.UNRESTRICTED.copy(cooldownSeconds = 600)

        assertEquals(
            AccessDecision.Cooling(secondsRemaining = 400),
            AccessPolicy.evaluate(limits, noUsage.copy(secondsSinceLastSession = 200)),
        )
        // At the boundary the cooldown is over.
        assertEquals(
            AccessDecision.Allowed,
            AccessPolicy.evaluate(limits, noUsage.copy(secondsSinceLastSession = 600)),
        )
        // No previous session today means nothing to cool down from.
        assertEquals(
            AccessDecision.Allowed,
            AccessPolicy.evaluate(limits, noUsage.copy(secondsSinceLastSession = null)),
        )
    }

    @Test
    fun `friction delays rather than refuses`() {
        val decision = AccessPolicy.evaluate(
            ServiceLimits.UNRESTRICTED.copy(openDelaySeconds = 10),
            noUsage,
        )
        assertEquals(AccessDecision.FrictionRequired(10), decision)
        assertTrue("friction still opens the service", decision.isAllowed)
    }

    @Test
    fun `a hard refusal wins over friction`() {
        // Friction is the softest control, so it must never mask a spent budget, a closed
        // window, or an active cooldown.
        val limits = ServiceLimits(
            dailyBudgetSeconds = 600,
            windowStartMinute = 19 * 60,
            windowEndMinute = 21 * 60,
            openDelaySeconds = 10,
            cooldownSeconds = 300,
        )
        assertTrue(
            AccessPolicy.evaluate(limits, noUsage.copy(usedSecondsToday = 600, minuteOfDay = 20 * 60))
                is AccessDecision.BudgetExhausted,
        )
        assertTrue(
            AccessPolicy.evaluate(limits, noUsage.copy(minuteOfDay = 9 * 60))
                is AccessDecision.OutsideWindow,
        )
        assertTrue(
            AccessPolicy.evaluate(
                limits,
                noUsage.copy(minuteOfDay = 20 * 60, secondsSinceLastSession = 60),
            ) is AccessDecision.Cooling,
        )
    }

    // ------------------------------------------------------------ tightening

    private val baseline = ServiceLimits(
        dailyBudgetSeconds = 900,
        windowStartMinute = 19 * 60,
        windowEndMinute = 21 * 60,
        openDelaySeconds = 10,
        cooldownSeconds = 300,
    )

    @Test
    fun `reducing a budget, shortening a window, or adding delay is tightening`() {
        listOf(
            baseline.copy(dailyBudgetSeconds = 600),
            baseline.copy(windowEndMinute = 20 * 60),
            baseline.copy(openDelaySeconds = 30),
            baseline.copy(cooldownSeconds = 600),
        ).forEach { candidate ->
            assertTrue("$candidate should be tightening", AccessPolicy.isTightening(baseline, candidate))
        }
    }

    @Test
    fun `raising a budget, widening a window, or removing a limit is not tightening`() {
        listOf(
            baseline.copy(dailyBudgetSeconds = 3_600),
            baseline.copy(dailyBudgetSeconds = null),
            baseline.copy(windowEndMinute = 23 * 60),
            baseline.copy(windowStartMinute = null, windowEndMinute = null),
            baseline.copy(openDelaySeconds = 0),
            baseline.copy(cooldownSeconds = 0),
        ).forEach { candidate ->
            assertFalse("$candidate should not be tightening", AccessPolicy.isTightening(baseline, candidate))
        }
    }

    @Test
    fun `capping a previously uncapped service is tightening`() {
        val uncapped = ServiceLimits.UNRESTRICTED
        assertTrue(
            AccessPolicy.isTightening(uncapped, uncapped.copy(dailyBudgetSeconds = 900)),
        )
        assertTrue(
            AccessPolicy.isTightening(
                uncapped,
                uncapped.copy(windowStartMinute = 19 * 60, windowEndMinute = 21 * 60),
            ),
        )
    }

    @Test
    fun `an unchanged configuration is not a tightening`() {
        assertFalse(AccessPolicy.isTightening(baseline, baseline))
    }

    @Test
    fun `tightening one dimension while loosening another does not count`() {
        // Otherwise "cut the budget by a minute" would smuggle through an all-day window.
        val candidate = baseline.copy(
            dailyBudgetSeconds = 840,
            windowStartMinute = null,
            windowEndMinute = null,
        )
        assertFalse(AccessPolicy.isTightening(baseline, candidate))
    }

    @Test
    fun `moving a window without shortening it is not a tightening`() {
        // Same two hours, different time of day: that is a preference change, not a
        // restriction, so it waits until tomorrow like any other loosening.
        val moved = baseline.copy(windowStartMinute = 9 * 60, windowEndMinute = 11 * 60)
        assertFalse(AccessPolicy.isTightening(baseline, moved))
    }

    @Test
    fun `a window that wraps midnight is measured by its real length`() {
        val overnight = ServiceLimits.UNRESTRICTED.copy(
            windowStartMinute = 22 * 60,
            windowEndMinute = 1 * 60,
        )
        // Three hours down to two is a tightening even though the end time went backwards.
        assertTrue(
            AccessPolicy.isTightening(overnight, overnight.copy(windowEndMinute = 0)),
        )
    }
}
