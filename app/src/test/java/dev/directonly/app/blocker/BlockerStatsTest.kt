package dev.directonly.app.blocker

import android.content.SharedPreferences
import dev.directonly.app.limits.LocalClock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * BlockerStats wraps SharedPreferences, which is a non-functional stub under plain JVM
 * tests (same class of problem RemoteDiagnosticsReporter hit with org.json). A tiny
 * in-memory fake stands in for it here so the actual rollover and accumulation logic is
 * verified without needing Robolectric.
 */
class BlockerStatsTest {
    private class FakePreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
        override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
        override fun getAll() = values.toMap()
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getBoolean(key: String, defValue: Boolean) = defValue
        override fun contains(key: String) = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending[key] = REMOVE_MARKER }
            override fun clear() = apply { values.clear() }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                pending.forEach { (key, value) ->
                    if (value === REMOVE_MARKER) values.remove(key) else values[key] = value
                }
                pending.clear()
            }
        }

        private companion object {
            val REMOVE_MARKER = Any()
        }
    }

    private class FixedClock(var date: LocalDate) : LocalClock {
        override fun today() = date
        override fun now(): LocalTime = LocalTime.NOON
        override fun elapsedRealtimeMs() = 0L
    }

    private fun stats(clock: LocalClock): BlockerStats = BlockerStats(FakePreferences(), clock)

    @Test
    fun `no blocks means zero counts`() {
        val s = stats(FixedClock(LocalDate.of(2026, 8, 18)))
        assertEquals(0, s.todayCount())
        assertEquals(0, s.totalCount())
    }

    @Test
    fun `blocks accumulate today and in total`() {
        val s = stats(FixedClock(LocalDate.of(2026, 8, 18)))
        repeat(3) { s.recordBlock() }
        assertEquals(3, s.todayCount())
        assertEquals(3, s.totalCount())
    }

    @Test
    fun `today count resets at midnight but total keeps accumulating`() {
        val clock = FixedClock(LocalDate.of(2026, 8, 18))
        val s = stats(clock)
        s.recordBlock()
        s.recordBlock()
        assertEquals(2, s.todayCount())

        clock.date = LocalDate.of(2026, 8, 19)
        assertEquals("a new day starts at zero", 0, s.todayCount())
        assertEquals("the running total is unaffected", 2, s.totalCount())

        s.recordBlock()
        assertEquals(1, s.todayCount())
        assertEquals(3, s.totalCount())
    }
}
