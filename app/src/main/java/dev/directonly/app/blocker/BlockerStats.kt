package dev.directonly.app.blocker

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.directonly.app.limits.LocalClock
import dev.directonly.app.limits.SystemLocalClock

/**
 * Counts how many times the blocker has intervened, today and in total.
 *
 * Local only, same as everything else in this app — nothing here is transmitted. Reuses
 * [LocalClock] from the usage-limits package rather than duplicating date-rollover logic;
 * that interface was already built generic enough for this.
 */
class BlockerStats(
    private val preferences: SharedPreferences,
    private val clock: LocalClock = SystemLocalClock(),
) {
    constructor(context: Context, clock: LocalClock = SystemLocalClock()) : this(
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
        clock,
    )

    fun recordBlock() {
        val today = clock.today().toString()
        preferences.edit {
            putInt(KEY_TOTAL, preferences.getInt(KEY_TOTAL, 0) + 1)
            if (preferences.getString(KEY_TODAY_DATE, null) == today) {
                putInt(KEY_TODAY_COUNT, preferences.getInt(KEY_TODAY_COUNT, 0) + 1)
            } else {
                putString(KEY_TODAY_DATE, today)
                putInt(KEY_TODAY_COUNT, 1)
            }
        }
    }

    fun todayCount(): Int {
        val today = clock.today().toString()
        return if (preferences.getString(KEY_TODAY_DATE, null) == today) {
            preferences.getInt(KEY_TODAY_COUNT, 0)
        } else {
            0
        }
    }

    fun totalCount(): Int = preferences.getInt(KEY_TOTAL, 0)

    private companion object {
        const val FILE_NAME = "clearfeed_blocker_stats"
        const val KEY_TOTAL = "total"
        const val KEY_TODAY_COUNT = "today_count"
        const val KEY_TODAY_DATE = "today_date"
    }
}
