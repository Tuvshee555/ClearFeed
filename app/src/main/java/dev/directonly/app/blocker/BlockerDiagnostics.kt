package dev.directonly.app.blocker

import dev.directonly.app.diagnostics.DiagnosticTrace

/**
 * A process-wide event trace shared between the accessibility service and the app UI.
 *
 * The service and the Activity run in the same process by default, so a plain singleton is
 * enough — no cross-process IPC needed. Reuses [DiagnosticTrace], the same bounded, in-memory,
 * never-transmitted history used by the rest of the app, so the existing report-building and
 * share code works unmodified for blocker events. This trace is exactly how the last
 * on-device bug (an Instagram guard gated on one localized string) got diagnosed and fixed
 * without ever having a device available here — the blocker gets that same visibility from
 * the start rather than after the fact.
 */
object BlockerDiagnostics {
    val trace = DiagnosticTrace()
}
