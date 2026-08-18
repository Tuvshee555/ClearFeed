package dev.directonly.app.diagnostics

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persists whether the owner has opted in to sending failure diagnostics off the device.
 *
 * The default is off. ClearFeed's stated privacy position is that nothing leaves the
 * device unless the person using it asks for that, so the opt-in has to be an explicit,
 * remembered choice rather than something enabled by shipping a build.
 */
class DiagnosticsPreferences(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var remoteReportingEnabled: Boolean
        get() = preferences.getBoolean(KEY_REMOTE_REPORTING, false)
        set(value) = preferences.edit { putBoolean(KEY_REMOTE_REPORTING, value) }

    private companion object {
        const val FILE_NAME = "clearfeed_diagnostics"
        const val KEY_REMOTE_REPORTING = "remote_reporting_enabled"
    }
}
