package dev.directonly.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.directonly.app.BuildConfig
import dev.directonly.app.blocker.BlockerDiagnostics
import dev.directonly.app.blocker.BlockerStats
import dev.directonly.app.blocker.ShortsAccessibilityService
import dev.directonly.app.diagnostics.DiagnosticEnvironment
import dev.directonly.app.diagnostics.buildDiagnosticReport

@Composable
fun BlockerApp() {
    MaterialTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            color = MaterialTheme.colorScheme.background,
        ) {
            BlockerHomeScreen()
        }
    }
}

@Composable
private fun BlockerHomeScreen() {
    val context = LocalContext.current
    var serviceEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var activityVisible by rememberSaveable { mutableStateOf(false) }
    val stats = remember { BlockerStats(context) }

    // The user grants this in system Settings, outside this screen's control, so status is
    // re-checked whenever the Activity resumes rather than assumed from a one-time read.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceEnabled = isAccessibilityServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "ClearFeed",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Shorts blocker",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.size(28.dp))

        StatusCard(enabled = serviceEnabled, onOpenSettings = { openAccessibilitySettings(context) })

        if (serviceEnabled) {
            Spacer(Modifier.size(16.dp))
            StatsRow(todayCount = stats.todayCount(), totalCount = stats.totalCount())
        }

        Spacer(Modifier.size(20.dp))
        TextButton(onClick = { activityVisible = true }) { Text("View activity") }

        Spacer(Modifier.size(24.dp))
        Text(
            "Watches YouTube for its Shorts player and returns you to your home screen when it " +
                "opens. Instagram Reels and Facebook Reels aren't covered yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "ClearFeed ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (activityVisible) {
        ActivityDialog(context = context, onDismiss = { activityVisible = false })
    }
}

@Composable
private fun StatusCard(enabled: Boolean, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (enabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    if (enabled) "Protected" else "Not protected",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.size(8.dp))
            if (enabled) {
                Text(
                    "The Shorts blocker is running. Use YouTube normally — if Shorts opens, " +
                        "you'll be sent back to your home screen.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    "Turn on ClearFeed's accessibility service to start blocking Shorts.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(12.dp))
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Accessibility Settings")
                }
                Spacer(Modifier.size(12.dp))
                Text(
                    "If Android says the setting is restricted: open App info → tap the ⋮ " +
                        "menu in the top right → \"Allow restricted settings\" → then come " +
                        "back here. That's an Android requirement for any app installed " +
                        "outside the Play Store, not something ClearFeed can skip.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = { openAppInfo(context) }) { Text("Open App info") }
            }
        }
    }
}

@Composable
private fun StatsRow(todayCount: Int, totalCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatTile(label = "Blocked today", value = todayCount, modifier = Modifier.weight(1f))
        StatTile(label = "Blocked total", value = totalCount, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("$value", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The local activity log, reusing the same [dev.directonly.app.diagnostics.DiagnosticTrace]
 * report format as the rest of the app. This is the debugging channel for the blocker: if
 * detection is ever wrong on a real device, this is what closes the gap between "it doesn't
 * work" and a fix, the same way it did for the Instagram guard bug.
 */
@Composable
private fun ActivityDialog(context: Context, onDismiss: () -> Unit) {
    val report = remember {
        buildDiagnosticReport(
            environment = DiagnosticEnvironment(
                appVersion = BuildConfig.VERSION_NAME,
                androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                webViewVersion = "n/a",
            ),
            trace = BlockerDiagnostics.trace.snapshot(),
        )
    }
    var copied by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Activity") },
        text = {
            SelectionContainer(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(report, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                            ClipData.newPlainText("ClearFeed activity", report),
                        )
                        copied = true
                    },
                ) { Text(if (copied) "Copied" else "Copy") }
                TextButton(
                    onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "ClearFeed activity")
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        runCatching { context.startActivity(Intent.createChooser(share, "Share activity")) }
                    },
                ) { Text("Share") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val expected = "${context.packageName}/${ShortsAccessibilityService::class.java.name}"
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun openAccessibilitySettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
}

private fun openAppInfo(context: Context) {
    runCatching {
        val uri: Uri = "package:${context.packageName}".toUri()
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
    }
}
