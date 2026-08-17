package dev.directonly.app.limits

import dev.directonly.app.model.SocialPlatform

/**
 * How much of a service is available today, and when.
 *
 * Times are minutes past local midnight so a window is comparable without a date, and a
 * window whose end is not after its start is treated as crossing midnight (22:00–01:00).
 */
data class ServiceLimits(
    /** Daily budget in seconds. Zero means the service is off entirely. Null means no cap. */
    val dailyBudgetSeconds: Int?,
    /** Inclusive start of the allowed window, minutes past midnight. Null means any time. */
    val windowStartMinute: Int?,
    /** Exclusive end of the allowed window, minutes past midnight. Null means any time. */
    val windowEndMinute: Int?,
    /** Seconds of deliberate delay before the service opens. */
    val openDelaySeconds: Int,
    /** Minimum seconds between the end of one session and the start of the next. */
    val cooldownSeconds: Int,
) {
    companion object {
        /**
         * Unrestricted. Limits are opt-in, so a fresh install behaves exactly as before and
         * nothing is silently imposed.
         */
        val UNRESTRICTED = ServiceLimits(
            dailyBudgetSeconds = null,
            windowStartMinute = null,
            windowEndMinute = null,
            openDelaySeconds = 0,
            cooldownSeconds = 0,
        )
    }
}

/** What today's usage of one service looks like at the moment of the check. */
data class UsageSnapshot(
    val usedSecondsToday: Int,
    /** Local minutes past midnight, so the caller owns all timezone handling. */
    val minuteOfDay: Int,
    /** Seconds since the previous session ended, or null if there was none today. */
    val secondsSinceLastSession: Int?,
)

sealed interface AccessDecision {
    /** The service may open now. */
    data object Allowed : AccessDecision

    /** The service may open after [seconds] of deliberate delay. */
    data class FrictionRequired(val seconds: Int) : AccessDecision

    /** Today's budget is spent. [remainingSecondsToday] is always zero; kept for symmetry. */
    data class BudgetExhausted(
        val budgetSeconds: Int,
        val remainingSecondsToday: Int = 0,
    ) : AccessDecision

    /** Outside the allowed window. [opensAtMinute] is minutes past midnight. */
    data class OutsideWindow(val opensAtMinute: Int, val closesAtMinute: Int) : AccessDecision

    /** A session ended too recently. */
    data class Cooling(val secondsRemaining: Int) : AccessDecision

    val isAllowed: Boolean
        get() = this is Allowed || this is FrictionRequired
}

/**
 * Decides whether a service may be opened.
 *
 * Deliberately pure: no Android types, no clock, no storage. Everything it needs is in its
 * arguments, so the rules that decide whether the app opens at all are unit-testable
 * without a device — which matters here, because a bug in this file locks you out of your
 * own messages.
 *
 * Checks run cheapest-and-hardest first: a closed window and a spent budget are absolute,
 * cooldown is next, and friction is the softest since it only delays.
 */
object AccessPolicy {
    fun evaluate(
        limits: ServiceLimits,
        usage: UsageSnapshot,
    ): AccessDecision {
        val budget = limits.dailyBudgetSeconds
        if (budget != null && usage.usedSecondsToday >= budget) {
            return AccessDecision.BudgetExhausted(budgetSeconds = budget)
        }

        val start = limits.windowStartMinute
        val end = limits.windowEndMinute
        if (start != null && end != null && !isInsideWindow(usage.minuteOfDay, start, end)) {
            return AccessDecision.OutsideWindow(opensAtMinute = start, closesAtMinute = end)
        }

        val sinceLast = usage.secondsSinceLastSession
        if (limits.cooldownSeconds > 0 && sinceLast != null && sinceLast < limits.cooldownSeconds) {
            return AccessDecision.Cooling(secondsRemaining = limits.cooldownSeconds - sinceLast)
        }

        if (limits.openDelaySeconds > 0) {
            return AccessDecision.FrictionRequired(seconds = limits.openDelaySeconds)
        }
        return AccessDecision.Allowed
    }

    /** Seconds of budget left today, or null when the service is uncapped. */
    fun remainingSeconds(limits: ServiceLimits, usedSecondsToday: Int): Int? =
        limits.dailyBudgetSeconds?.let { (it - usedSecondsToday).coerceAtLeast(0) }

    /**
     * A window whose end is not after its start wraps past midnight, so 22:00–01:00 covers
     * 23:30 and 00:30 but not 12:00.
     */
    fun isInsideWindow(minuteOfDay: Int, startMinute: Int, endMinute: Int): Boolean =
        if (startMinute < endMinute) {
            minuteOfDay >= startMinute && minuteOfDay < endMinute
        } else {
            minuteOfDay >= startMinute || minuteOfDay < endMinute
        }

    /**
     * Whether [candidate] is strictly stricter than [current] for every dimension it changes.
     *
     * Loosening a limit is exactly the decision you cannot trust yourself to make in the
     * moment you want to make it, so the caller defers a loosening until tomorrow while
     * applying a tightening at once. Encoded here so the rule is testable and lives next to
     * the limits it governs rather than in UI code.
     */
    fun isTightening(current: ServiceLimits, candidate: ServiceLimits): Boolean {
        val budgetTighter = compareNullableBudget(current.dailyBudgetSeconds, candidate.dailyBudgetSeconds)
        val windowTighter = compareWindow(current, candidate)
        val delayTighter = compareInt(current.openDelaySeconds, candidate.openDelaySeconds)
        val cooldownTighter = compareInt(current.cooldownSeconds, candidate.cooldownSeconds)
        val changes = listOf(budgetTighter, windowTighter, delayTighter, cooldownTighter)
        // Nothing loosened, and at least one thing tightened.
        return changes.none { it == Change.LOOSER } && changes.any { it == Change.TIGHTER }
    }

    private enum class Change { TIGHTER, SAME, LOOSER }

    /** A smaller budget is tighter; null (uncapped) is the loosest value there is. */
    private fun compareNullableBudget(current: Int?, candidate: Int?): Change = when {
        current == candidate -> Change.SAME
        candidate == null -> Change.LOOSER
        current == null -> Change.TIGHTER
        candidate < current -> Change.TIGHTER
        else -> Change.LOOSER
    }

    private fun compareInt(current: Int, candidate: Int): Change = when {
        candidate == current -> Change.SAME
        candidate > current -> Change.TIGHTER
        else -> Change.LOOSER
    }

    /** A shorter allowed window is tighter; removing the window entirely is loosest. */
    private fun compareWindow(current: ServiceLimits, candidate: ServiceLimits): Change {
        val currentLength = windowLength(current.windowStartMinute, current.windowEndMinute)
        val candidateLength = windowLength(candidate.windowStartMinute, candidate.windowEndMinute)
        val sameBounds = current.windowStartMinute == candidate.windowStartMinute &&
            current.windowEndMinute == candidate.windowEndMinute
        return when {
            sameBounds -> Change.SAME
            candidateLength == null -> Change.LOOSER
            currentLength == null -> Change.TIGHTER
            candidateLength < currentLength -> Change.TIGHTER
            candidateLength > currentLength -> Change.LOOSER
            // Same length, different hours: moving the window is not a tightening.
            else -> Change.LOOSER
        }
    }

    /** Null when unbounded. */
    private fun windowLength(startMinute: Int?, endMinute: Int?): Int? {
        if (startMinute == null || endMinute == null) return null
        val length = endMinute - startMinute
        return if (length > 0) length else length + MINUTES_PER_DAY
    }

    const val MINUTES_PER_DAY = 24 * 60
}

/** Today's position against a service's limits, for display before opening anything. */
data class ServiceUsageSummary(
    val limits: ServiceLimits,
    val usedSecondsToday: Int,
    /** Null when the service is uncapped. */
    val remainingSecondsToday: Int?,
    val opensToday: Int,
) {
    val hasAnyLimit: Boolean
        get() = limits != ServiceLimits.UNRESTRICTED
}

/** All limits, keyed by service. */
data class LimitsConfiguration(
    val perPlatform: Map<SocialPlatform, ServiceLimits>,
) {
    fun forPlatform(platform: SocialPlatform): ServiceLimits =
        perPlatform[platform] ?: ServiceLimits.UNRESTRICTED

    companion object {
        val UNRESTRICTED = LimitsConfiguration(emptyMap())
    }
}
