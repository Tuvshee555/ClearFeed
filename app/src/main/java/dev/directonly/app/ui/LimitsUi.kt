package dev.directonly.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.directonly.app.limits.AccessDecision
import dev.directonly.app.limits.DayUsage
import dev.directonly.app.limits.ServiceLimits
import dev.directonly.app.limits.ServiceUsageSummary
import dev.directonly.app.model.SocialPlatform
import java.time.format.TextStyle as JavaTextStyle
import java.time.LocalDate

/** Formats a duration the way someone reads their own screen time. */
internal fun formatDuration(seconds: Int): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3_600 -> "${seconds / 60}m"
    seconds % 3_600 == 0 -> "${seconds / 3_600}h"
    else -> "${seconds / 3_600}h ${(seconds % 3_600) / 60}m"
}

internal fun formatMinuteOfDay(minuteOfDay: Int): String {
    val hour = (minuteOfDay / 60) % 24
    val minute = minuteOfDay % 60
    return "%02d:%02d".format(hour, minute)
}

/**
 * Shown instead of the service when a usage limit refuses it.
 *
 * It always says which limit stopped this and when the service comes back. A bare refusal
 * invites a fight with the app; a specific one is a reminder of a decision already made.
 */
@Composable
internal fun AccessBlockedSurface(
    platform: SocialPlatform,
    decision: AccessDecision,
    onBack: () -> Unit,
) {
    val (headline, detail) = when (decision) {
        is AccessDecision.BudgetExhausted ->
            "${platform.displayName} is done for today" to
                "You've used your full ${formatDuration(decision.budgetSeconds)} today. " +
                    "It opens again after midnight."

        is AccessDecision.OutsideWindow ->
            "${platform.displayName} is closed right now" to
                "You set it to open between ${formatMinuteOfDay(decision.opensAtMinute)} and " +
                    "${formatMinuteOfDay(decision.closesAtMinute)}."

        is AccessDecision.Cooling ->
            "Just a bit longer" to
                "You set a gap between sessions. ${platform.displayName} opens in " +
                    "${formatDuration(decision.secondsRemaining)}."

        else -> "${platform.displayName} is unavailable" to "A usage limit is active."
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                headline,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onBack) { Text("Back to services") }
        }
    }
}

/**
 * The deliberate pause before a service opens.
 *
 * Intentionally empty. The whole value of the delay is that there is nothing to look at, so
 * a reflex check has time to become a decision.
 */
@Composable
internal fun OpeningDelaySurface(
    platform: SocialPlatform,
    secondsRemaining: Int,
    onCancel: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "$secondsRemaining",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                "Opening ${platform.displayName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onCancel) { Text("Never mind") }
        }
    }
}

/** A one-line usage note under a service on the picker. */
@Composable
internal fun ServiceUsageNote(summary: ServiceUsageSummary) {
    if (!summary.hasAnyLimit && summary.usedSecondsToday == 0) return
    val remaining = summary.remainingSecondsToday
    val text = when {
        remaining != null && remaining <= 0 -> "No time left today"
        remaining != null -> "${formatDuration(remaining)} left today"
        summary.usedSecondsToday > 0 -> "${formatDuration(summary.usedSecondsToday)} today"
        else -> return
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (remaining != null && remaining <= 0) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/** Seven-day usage plus the limits editor. */
@Composable
internal fun UsageStatsScreen(
    historyByPlatform: Map<SocialPlatform, List<DayUsage>>,
    limitsFor: (SocialPlatform) -> ServiceLimits,
    pendingFor: (SocialPlatform) -> Pair<ServiceLimits, LocalDate>?,
    onLimitsChange: (SocialPlatform, ServiceLimits) -> LocalDate,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Usage and limits",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Tightening a limit applies straight away. Loosening one applies tomorrow — " +
                "the moment you want more time is the moment the limit is doing its job.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SocialPlatform.entries.forEach { platform ->
            LimitsEditor(
                platform = platform,
                limits = limitsFor(platform),
                pending = pendingFor(platform),
                onChange = { candidate -> onLimitsChange(platform, candidate) },
            )
        }
        Text(
            "Last 7 days",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        historyByPlatform.forEach { (platform, history) ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val total = history.sumOf { it.seconds }
                    val opens = history.sumOf { it.opens }
                    Text(platform.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${formatDuration(total)} across $opens opens",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val busiest = history.maxOfOrNull { it.seconds } ?: 0
                    history.forEach { day ->
                        DayUsageRow(day = day, busiestSeconds = busiest)
                    }
                }
            }
        }
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

@Composable
private fun DayUsageRow(day: DayUsage, busiestSeconds: Int) {
    // Read through LocalConfiguration so the labels recompose if the device language
    // changes while this screen is open.
    val locale = LocalConfiguration.current.locales[0]
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            day.date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, locale),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(44.dp),
        )
        // A bar relative to the busiest day reads faster than a column of numbers.
        LinearProgressIndicator(
            progress = {
                if (busiestSeconds <= 0) 0f else day.seconds.toFloat() / busiestSeconds
            },
            modifier = Modifier
                .weight(1f)
                .height(8.dp),
        )
        Text(
            if (day.seconds == 0) "–" else formatDuration(day.seconds),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * Editor for one service's limits.
 *
 * Presets rather than free numeric entry: the useful values are few, and picking from a
 * short list is faster on a phone than typing minutes into a field.
 */
@Composable
private fun LimitsEditor(
    platform: SocialPlatform,
    limits: ServiceLimits,
    pending: Pair<ServiceLimits, LocalDate>?,
    onChange: (ServiceLimits) -> LocalDate,
) {
    var deferredUntil by remember(platform, limits) { mutableStateOf<LocalDate?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(platform.displayName, style = MaterialTheme.typography.titleMedium)

            LimitRow(label = "Daily limit") {
                BUDGET_PRESETS.forEach { (label, seconds) ->
                    LimitChip(
                        label = label,
                        selected = limits.dailyBudgetSeconds == seconds,
                        onClick = {
                            deferredUntil = onChange(limits.copy(dailyBudgetSeconds = seconds))
                        },
                    )
                }
            }

            LimitRow(label = "Open only between") {
                WINDOW_PRESETS.forEach { (label, window) ->
                    LimitChip(
                        label = label,
                        selected = limits.windowStartMinute == window?.first &&
                            limits.windowEndMinute == window?.second,
                        onClick = {
                            deferredUntil = onChange(
                                limits.copy(
                                    windowStartMinute = window?.first,
                                    windowEndMinute = window?.second,
                                ),
                            )
                        },
                    )
                }
            }

            LimitRow(label = "Pause before opening") {
                DELAY_PRESETS.forEach { (label, seconds) ->
                    LimitChip(
                        label = label,
                        selected = limits.openDelaySeconds == seconds,
                        onClick = { deferredUntil = onChange(limits.copy(openDelaySeconds = seconds)) },
                    )
                }
            }

            LimitRow(label = "Gap between sessions") {
                COOLDOWN_PRESETS.forEach { (label, seconds) ->
                    LimitChip(
                        label = label,
                        selected = limits.cooldownSeconds == seconds,
                        onClick = { deferredUntil = onChange(limits.copy(cooldownSeconds = seconds)) },
                    )
                }
            }

            pending?.let { (_, from) ->
                Text(
                    "A change you made is waiting until $from.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            deferredUntil?.let { date ->
                if (pending == null) return@let
                Text(
                    "That loosens the limit, so it takes effect on $date.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun LimitRow(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun LimitChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier.semantics { if (selected) this.selected = true },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

private val BUDGET_PRESETS = listOf<Pair<String, Int?>>(
    "Off" to 0,
    "10m" to 600,
    "20m" to 1_200,
    "45m" to 2_700,
    "None" to null,
)

private val WINDOW_PRESETS = listOf<Pair<String, Pair<Int, Int>?>>(
    "Any time" to null,
    "12–13" to (12 * 60 to 13 * 60),
    "18–21" to (18 * 60 to 21 * 60),
    "Not at night" to (7 * 60 to 22 * 60),
)

private val DELAY_PRESETS = listOf(
    "None" to 0,
    "5s" to 5,
    "15s" to 15,
    "30s" to 30,
)

private val COOLDOWN_PRESETS = listOf(
    "None" to 0,
    "15m" to 900,
    "1h" to 3_600,
    "3h" to 10_800,
)

/** A compact summary card used at the top of the limits editor. */
@Composable
internal fun LimitsSummaryCard(platform: SocialPlatform, summary: ServiceUsageSummary) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(platform.displayName, style = MaterialTheme.typography.titleMedium)
            val budget = summary.limits.dailyBudgetSeconds
            Text(
                if (budget == null) {
                    "No daily limit · ${formatDuration(summary.usedSecondsToday)} used today"
                } else {
                    "${formatDuration(summary.usedSecondsToday)} of ${formatDuration(budget)} used today"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            val start = summary.limits.windowStartMinute
            val end = summary.limits.windowEndMinute
            if (start != null && end != null) {
                Text(
                    "Opens ${formatMinuteOfDay(start)}–${formatMinuteOfDay(end)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
