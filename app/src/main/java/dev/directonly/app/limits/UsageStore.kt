package dev.directonly.app.limits

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.directonly.app.model.SocialPlatform
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Local time, injectable so the tests do not depend on the machine's clock or zone. */
interface LocalClock {
    fun today(): LocalDate

    fun now(): LocalTime

    /** Monotonic milliseconds, for measuring session length across a clock change. */
    fun elapsedRealtimeMs(): Long

    val minuteOfDay: Int
        get() = now().hour * 60 + now().minute
}

/**
 * Persists limits, today's usage, and the seven-day history behind them.
 *
 * Everything stays on the device: the point of these limits is to change your own behaviour,
 * which needs no server, works with no signal, and produces no record of your habits anywhere
 * you do not control.
 *
 * Usage is accumulated in whole seconds against a *local date* key, so a day rolls over at
 * local midnight rather than at some fixed offset. Session length is measured with a
 * monotonic clock, so changing the device time cannot manufacture extra budget.
 */
class UsageStore(
    context: Context,
    private val clock: LocalClock,
) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- limits

    fun limits(): LimitsConfiguration = LimitsConfiguration(
        SocialPlatform.entries.associateWith(::limitsFor),
    )

    fun limitsFor(platform: SocialPlatform): ServiceLimits {
        // A pending loosening becomes active once its effective date has arrived.
        val pendingFrom = preferences.getString(key(platform, PENDING_FROM), null)
        if (pendingFrom != null && !clock.today().isBefore(LocalDate.parse(pendingFrom))) {
            promotePending(platform)
        }
        return ServiceLimits(
            dailyBudgetSeconds = preferences.getInt(key(platform, BUDGET), NO_VALUE).takeIf { it != NO_VALUE },
            windowStartMinute = preferences.getInt(key(platform, WINDOW_START), NO_VALUE).takeIf { it != NO_VALUE },
            windowEndMinute = preferences.getInt(key(platform, WINDOW_END), NO_VALUE).takeIf { it != NO_VALUE },
            openDelaySeconds = preferences.getInt(key(platform, OPEN_DELAY), 0),
            cooldownSeconds = preferences.getInt(key(platform, COOLDOWN), 0),
        )
    }

    /**
     * Applies [candidate].
     *
     * A tightening takes effect immediately. A loosening is stored and applied from tomorrow,
     * because the moment you want to raise a limit is exactly the moment you should not be
     * able to. Returns the date the change becomes active.
     */
    fun updateLimits(platform: SocialPlatform, candidate: ServiceLimits): LocalDate {
        val current = limitsFor(platform)
        if (AccessPolicy.isTightening(current, candidate) || current == candidate) {
            preferences.edit { writeLimits(platform, candidate, prefix = "") }
            preferences.edit { remove(key(platform, PENDING_FROM)) }
            return clock.today()
        }
        val effective = clock.today().plusDays(1)
        preferences.edit {
            writeLimits(platform, candidate, prefix = PENDING_PREFIX)
            putString(key(platform, PENDING_FROM), effective.toString())
        }
        return effective
    }

    /** A loosening that has been accepted but is not active yet, if any. */
    fun pendingLimits(platform: SocialPlatform): Pair<ServiceLimits, LocalDate>? {
        val from = preferences.getString(key(platform, PENDING_FROM), null) ?: return null
        return ServiceLimits(
            dailyBudgetSeconds = preferences.getInt(key(platform, PENDING_PREFIX + BUDGET), NO_VALUE)
                .takeIf { it != NO_VALUE },
            windowStartMinute = preferences.getInt(key(platform, PENDING_PREFIX + WINDOW_START), NO_VALUE)
                .takeIf { it != NO_VALUE },
            windowEndMinute = preferences.getInt(key(platform, PENDING_PREFIX + WINDOW_END), NO_VALUE)
                .takeIf { it != NO_VALUE },
            openDelaySeconds = preferences.getInt(key(platform, PENDING_PREFIX + OPEN_DELAY), 0),
            cooldownSeconds = preferences.getInt(key(platform, PENDING_PREFIX + COOLDOWN), 0),
        ) to LocalDate.parse(from)
    }

    private fun promotePending(platform: SocialPlatform) {
        val pending = pendingLimits(platform)?.first ?: return
        preferences.edit {
            writeLimits(platform, pending, prefix = "")
            remove(key(platform, PENDING_FROM))
        }
    }

    private fun SharedPreferences.Editor.writeLimits(
        platform: SocialPlatform,
        limits: ServiceLimits,
        prefix: String,
    ) {
        putInt(key(platform, prefix + BUDGET), limits.dailyBudgetSeconds ?: NO_VALUE)
        putInt(key(platform, prefix + WINDOW_START), limits.windowStartMinute ?: NO_VALUE)
        putInt(key(platform, prefix + WINDOW_END), limits.windowEndMinute ?: NO_VALUE)
        putInt(key(platform, prefix + OPEN_DELAY), limits.openDelaySeconds)
        putInt(key(platform, prefix + COOLDOWN), limits.cooldownSeconds)
    }

    // ----------------------------------------------------------------- usage

    fun usedSecondsToday(platform: SocialPlatform): Int =
        preferences.getInt(usageKey(platform, clock.today()), 0)

    fun openCountToday(platform: SocialPlatform): Int =
        preferences.getInt(opensKey(platform, clock.today()), 0)

    fun snapshot(platform: SocialPlatform): UsageSnapshot {
        val lastEndedAt = preferences.getLong(key(platform, LAST_SESSION_END), NOT_RECORDED)
        val sinceLast = if (lastEndedAt == NOT_RECORDED) {
            null
        } else {
            ((clock.elapsedRealtimeMs() - lastEndedAt) / 1000L)
                .coerceIn(0L, Int.MAX_VALUE.toLong())
                .toInt()
        }
        return UsageSnapshot(
            usedSecondsToday = usedSecondsToday(platform),
            minuteOfDay = clock.minuteOfDay,
            secondsSinceLastSession = sinceLast,
        )
    }

    fun recordOpen(platform: SocialPlatform) {
        val today = clock.today()
        preferences.edit {
            putInt(opensKey(platform, today), preferences.getInt(opensKey(platform, today), 0) + 1)
        }
        pruneHistory()
    }

    /** Adds foreground time and remembers when the session ended. */
    fun recordSession(platform: SocialPlatform, seconds: Int) {
        if (seconds <= 0) return
        val today = clock.today()
        preferences.edit {
            putInt(usageKey(platform, today), preferences.getInt(usageKey(platform, today), 0) + seconds)
            putLong(key(platform, LAST_SESSION_END), clock.elapsedRealtimeMs())
        }
    }

    /** Seconds used per day for the last [days] days, most recent last. */
    fun history(platform: SocialPlatform, days: Int = HISTORY_DAYS): List<DayUsage> =
        (days - 1 downTo 0).map { back ->
            val date = clock.today().minusDays(back.toLong())
            DayUsage(
                date = date,
                seconds = preferences.getInt(usageKey(platform, date), 0),
                opens = preferences.getInt(opensKey(platform, date), 0),
            )
        }

    /** Drops usage keys older than the retained window so the file cannot grow without bound. */
    private fun pruneHistory() {
        val keep = (0 until HISTORY_DAYS)
            .map { clock.today().minusDays(it.toLong()).toString() }
            .toSet()
        val stale = preferences.all.keys.filter { entry ->
            (entry.contains(USAGE) || entry.contains(OPENS)) &&
                keep.none { retained -> entry.endsWith(retained) }
        }
        if (stale.isEmpty()) return
        preferences.edit { stale.forEach(::remove) }
    }

    private fun key(platform: SocialPlatform, suffix: String) = "${platform.name}_$suffix"

    private fun usageKey(platform: SocialPlatform, date: LocalDate) =
        "${platform.name}_${USAGE}_$date"

    private fun opensKey(platform: SocialPlatform, date: LocalDate) =
        "${platform.name}_${OPENS}_$date"

    companion object {
        const val HISTORY_DAYS = 7
        private const val FILE_NAME = "clearfeed_usage"
        private const val NO_VALUE = -1
        private const val NOT_RECORDED = -1L
        private const val BUDGET = "budget"
        private const val WINDOW_START = "window_start"
        private const val WINDOW_END = "window_end"
        private const val OPEN_DELAY = "open_delay"
        private const val COOLDOWN = "cooldown"
        private const val PENDING_PREFIX = "pending_"
        private const val PENDING_FROM = "pending_from"
        private const val LAST_SESSION_END = "last_session_end"
        private const val USAGE = "usage"
        private const val OPENS = "opens"
    }
}

data class DayUsage(
    val date: LocalDate,
    val seconds: Int,
    val opens: Int,
)

/** The real device clock. */
class SystemLocalClock(private val zone: ZoneId = ZoneId.systemDefault()) : LocalClock {
    override fun today(): LocalDate = LocalDate.now(zone)

    override fun now(): LocalTime = LocalTime.now(zone)

    override fun elapsedRealtimeMs(): Long = android.os.SystemClock.elapsedRealtime()
}
