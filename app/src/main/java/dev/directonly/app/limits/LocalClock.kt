package dev.directonly.app.limits

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Local time, injectable so tests do not depend on the machine's clock or zone.
 *
 * Kept its own package/file after the usage-limits feature it was built for was removed —
 * [dev.directonly.app.blocker.BlockerStats] still needs local-date rollover logic, and this
 * is small and generic enough to reuse rather than duplicate.
 */
interface LocalClock {
    fun today(): LocalDate

    fun now(): LocalTime

    /** Monotonic milliseconds, so measured durations cannot be manufactured by moving the clock. */
    fun elapsedRealtimeMs(): Long

    val minuteOfDay: Int
        get() = now().hour * 60 + now().minute
}

/** The real device clock. */
class SystemLocalClock(private val zone: ZoneId = ZoneId.systemDefault()) : LocalClock {
    override fun today(): LocalDate = LocalDate.now(zone)

    override fun now(): LocalTime = LocalTime.now(zone)

    override fun elapsedRealtimeMs(): Long = android.os.SystemClock.elapsedRealtime()
}
