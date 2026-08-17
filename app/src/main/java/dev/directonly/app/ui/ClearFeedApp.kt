package dev.directonly.app.ui

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.directonly.app.BuildConfig
import dev.directonly.app.ClearFeedCoordinator
import dev.directonly.app.limits.ServiceLimits
import dev.directonly.app.limits.ServiceUsageSummary
import dev.directonly.app.model.AppState
import dev.directonly.app.model.RevealState
import dev.directonly.app.model.SocialPlatform
import java.net.URI
import java.util.Locale

private val LightColors = lightColorScheme(
    primary = Color(0xFF4053B5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E6FF),
    onPrimaryContainer = Color(0xFF101B5C),
    secondary = Color(0xFF006B5B),
    secondaryContainer = Color(0xFF9EF2DD),
    background = Color(0xFFF8F9FD),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE4E6EF),
    onBackground = Color(0xFF191B21),
    onSurface = Color(0xFF191B21),
    onSurfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC6C6D0),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    onPrimary = Color(0xFF0A247A),
    primaryContainer = Color(0xFF273B9B),
    onPrimaryContainer = Color(0xFFE0E6FF),
    secondary = Color(0xFF82D5C1),
    secondaryContainer = Color(0xFF005144),
    background = Color(0xFF111318),
    surface = Color(0xFF191B20),
    surfaceVariant = Color(0xFF45464F),
    onBackground = Color(0xFFE3E2E9),
    onSurface = Color(0xFFE3E2E9),
    onSurfaceVariant = Color(0xFFC6C6D0),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF45464F),
    error = Color(0xFFFFB4AB),
)

private val ClearFeedTypography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp,
        lineHeight = 29.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
)

private val ClearFeedShapes = Shapes(
    extraLarge = RoundedCornerShape(28.dp),
    large = RoundedCornerShape(22.dp),
    medium = RoundedCornerShape(16.dp),
    small = RoundedCornerShape(12.dp),
)

@Composable
fun ClearFeedApp(
    coordinator: ClearFeedCoordinator,
    webViewFactory: (Context, SocialPlatform) -> WebView,
    webViewRelease: (WebView) -> Unit,
    openExternal: (String) -> Unit,
    exit: () -> Unit,
    handleSystemBack: () -> Boolean,
) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = ClearFeedTypography,
        shapes = ClearFeedShapes,
    ) {
        var menuExpanded by rememberSaveable { mutableStateOf(false) }
        var resetPrompt by rememberSaveable { mutableStateOf(false) }
        var aboutVisible by rememberSaveable { mutableStateOf(false) }
        var privacyVisible by rememberSaveable { mutableStateOf(false) }
        var diagnosticsVisible by rememberSaveable { mutableStateOf(false) }
        var statsVisible by rememberSaveable { mutableStateOf(false) }

        BackHandler {
            when {
                menuExpanded -> menuExpanded = false
                resetPrompt -> resetPrompt = false
                aboutVisible -> aboutVisible = false
                privacyVisible -> privacyVisible = false
                diagnosticsVisible -> diagnosticsVisible = false
                statsVisible -> statsVisible = false
                coordinator.pendingExternalUrl != null -> coordinator.clearExternalPrompt()
                handleSystemBack() -> Unit
                coordinator.selectedPlatform != null -> coordinator.handleBack(exit)
                else -> exit()
            }
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                ClearFeedHeader(
                    selectedPlatform = coordinator.selectedPlatform,
                    onHome = coordinator::goHome,
                    menuExpanded = menuExpanded,
                    onMenuExpandedChange = { menuExpanded = it },
                    onReset = { resetPrompt = true },
                    onAbout = { aboutVisible = true },
                    onPrivacy = { privacyVisible = true },
                    onDiagnostics = { diagnosticsVisible = true },
                    onStats = { statsVisible = true },
                )

                val platform = coordinator.selectedPlatform
                when {
                    statsVisible -> UsageStatsScreen(
                        historyByPlatform = remember(coordinator.limitsRevision, statsVisible) {
                            SocialPlatform.entries.associateWith(coordinator::history)
                        },
                        limitsFor = coordinator::limitsFor,
                        pendingFor = coordinator::pendingLimitsFor,
                        onLimitsChange = coordinator::updateLimits,
                        onDismiss = { statsVisible = false },
                    )

                    platform == null -> ServicePicker(
                        onSelect = coordinator::selectPlatform,
                        usageFor = { service ->
                            // Re-read whenever limits change or a session ends, so the
                            // remaining-time note is current when returning from a service.
                            key(coordinator.limitsRevision, coordinator.appState) {
                                coordinator.usageSummary(service)
                            }
                        },
                    )

                    coordinator.appState == AppState.ACCESS_BLOCKED -> AccessBlockedSurface(
                        platform = platform,
                        decision = coordinator.accessDecision,
                        onBack = coordinator::goHome,
                    )

                    coordinator.appState == AppState.OPENING_DELAY -> OpeningDelaySurface(
                        platform = platform,
                        secondsRemaining = coordinator.openDelayRemainingSeconds,
                        onCancel = coordinator::cancelOpenDelay,
                    )

                    else -> ProtectedService(
                        platform = platform,
                        coordinator = coordinator,
                        webViewFactory = webViewFactory,
                        webViewRelease = webViewRelease,
                        exit = coordinator::goHome,
                        onDiagnostics = { diagnosticsVisible = true },
                    )
                }
            }
        }

        if (resetPrompt) {
            AlertDialog(
                onDismissRequest = { resetPrompt = false },
                icon = { Icon(Icons.Default.Lock, contentDescription = null) },
                title = { Text("Reset all web logins?") },
                text = {
                    Text(
                        "Instagram, YouTube, Facebook, Messenger, and Google will be signed out " +
                            "inside ClearFeed. Your permanent restrictions will stay on.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            resetPrompt = false
                            coordinator.resetAllSessions()
                        },
                    ) { Text("Reset logins") }
                },
                dismissButton = {
                    TextButton(onClick = { resetPrompt = false }) { Text("Cancel") }
                },
            )
        }

        if (aboutVisible) {
            InfoDialog(
                title = "About ClearFeed ${BuildConfig.VERSION_NAME}",
                message = "A permanent, low-distraction gateway for Instagram DMs, intentional " +
                    "YouTube, and a limited non-video Facebook feed. There is no unrestricted " +
                    "mode or temporary unlock.",
                onDismiss = { aboutVisible = false },
            )
        }

        if (privacyVisible) {
            InfoDialog(
                title = "Privacy",
                message = "ClearFeed has no analytics and no private social API. Sign-in and " +
                    "content stay in each provider's website and Android WebView storage. The " +
                    "native bridge carries protected route and health events only.\n\n" +
                    "Diagnostics are recorded on this device. Nothing is sent anywhere unless " +
                    "you turn on \"Send failure reports\" under More options, which is off by " +
                    "default. When it is on, only failure events are sent — never the " +
                    "navigation stages shown in Diagnostics.",
                onDismiss = { privacyVisible = false },
            )
        }

        if (diagnosticsVisible) {
            DiagnosticsDialog(
                report = remember(coordinator.diagnosticRevision) { coordinator.diagnosticReport() },
                remoteEnabled = coordinator.remoteDiagnosticsEnabled,
                onRemoteEnabledChange = coordinator::updateRemoteDiagnostics,
                onDismiss = { diagnosticsVisible = false },
            )
        }

        coordinator.pendingExternalUrl?.let { url ->
            val destination = externalDestinationLabel(url)
            AlertDialog(
                onDismissRequest = coordinator::clearExternalPrompt,
                title = { Text("Leave ClearFeed?") },
                text = {
                    Text(
                        "Open $destination in your browser? Ordinary websites open outside the " +
                            "protected view. Instagram, YouTube, and Facebook destinations can " +
                            "never use this exit.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            coordinator.clearExternalPrompt()
                            openExternal(url)
                        },
                    ) { Text("Open $destination") }
                },
                dismissButton = {
                    TextButton(onClick = coordinator::clearExternalPrompt) { Text("Stay here") }
                },
            )
        }
    }
}

@Composable
private fun ClearFeedHeader(
    selectedPlatform: SocialPlatform?,
    onHome: () -> Unit,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    onAbout: () -> Unit,
    onPrivacy: () -> Unit,
    onDiagnostics: () -> Unit,
    onStats: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectedPlatform != null) {
                    IconButton(onClick = onHome) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to ClearFeed services",
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (selectedPlatform == null) 8.dp else 2.dp),
                ) {
                    Text(
                        text = selectedPlatform?.displayName ?: "ClearFeed",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (selectedPlatform == null) {
                            "Useful social tools, without the endless feed"
                        } else {
                            "Protected by ClearFeed"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { onMenuExpandedChange(true) }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { onMenuExpandedChange(false) },
                    ) {
                        if (selectedPlatform != null) {
                            DropdownMenuItem(
                                text = { Text("Reset all web logins") },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                onClick = {
                                    onMenuExpandedChange(false)
                                    onReset()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Diagnostics") },
                            onClick = {
                                onMenuExpandedChange(false)
                                onDiagnostics()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Usage and limits") },
                            onClick = {
                                onMenuExpandedChange(false)
                                onStats()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Privacy") },
                            onClick = {
                                onMenuExpandedChange(false)
                                onPrivacy()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("About ClearFeed") },
                            onClick = {
                                onMenuExpandedChange(false)
                                onAbout()
                            },
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun ServicePicker(
    onSelect: (SocialPlatform) -> Unit,
    usageFor: @Composable (SocialPlatform) -> ServiceUsageSummary,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(54.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(25.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Choose what you came for",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Each service opens in a protected view. Your sign-ins remain available when you " +
                "return to this screen.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        SocialPlatform.entries.forEach { platform ->
            ServiceCard(
                platform = platform,
                summary = usageFor(platform),
                onClick = { onSelect(platform) },
            )
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    "No feed switch. No temporary unlock. Your guardrails stay on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "ClearFeed ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ServiceCard(
    platform: SocialPlatform,
    summary: ServiceUsageSummary,
    onClick: () -> Unit,
) {
    val accent = platformAccent(platform)
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 104.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription =
                    "${platform.displayName}. ${platform.description}. Open protected service."
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, pressedElevation = 0.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(16.dp),
                color = accent.copy(alpha = if (isSystemInDarkTheme()) 0.24f else 0.12f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        platformMark(platform),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(platform.displayName, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    platform.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Remaining time is shown before opening, so the budget is a decision made
                // at the door rather than an interruption partway through.
                ServiceUsageNote(summary)
            }
            Text(
                "Open",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ProtectedService(
    platform: SocialPlatform,
    coordinator: ClearFeedCoordinator,
    webViewFactory: (Context, SocialPlatform) -> WebView,
    webViewRelease: (WebView) -> Unit,
    exit: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (coordinator.webViewAvailable) {
            key(platform, coordinator.webViewGeneration) {
                AndroidView(
                    factory = { context -> webViewFactory(context, platform) },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = webViewRelease,
                )
            }
        }

        when (coordinator.appState) {
            AppState.OFFLINE -> ErrorSurface(
                title = "You're offline",
                message = coordinator.errorMessage,
                action = "Retry",
                onAction = coordinator::retry,
                secondaryAction = "View diagnostics",
                onSecondaryAction = onDiagnostics,
            )
            AppState.WEB_ERROR -> ErrorSurface(
                title = "${platform.displayName} couldn't load",
                message = coordinator.errorMessage,
                action = "Try again",
                onAction = coordinator::retry,
                secondaryAction = "View diagnostics",
                onSecondaryAction = onDiagnostics,
            )
            AppState.WEBVIEW_UNAVAILABLE -> ErrorSurface(
                title = "Update Android System WebView",
                message = "ClearFeed needs a current Android System WebView to enforce its " +
                    "protected interface before showing social content.",
                action = "Back to services",
                onAction = exit,
                secondaryAction = "View diagnostics",
                onSecondaryAction = onDiagnostics,
            )
            else -> if (coordinator.isLoading || !coordinator.webContentReady) {
                LoadingSurface(platform)
            }
        }

        // The route passed the URL policy but the page-level guard never confirmed it, so
        // some of the on-page filtering may not have been applied. Worth saying quietly;
        // not worth blocking the app over, which is what it used to do.
        AnimatedVisibility(
            visible = coordinator.revealState == RevealState.REVEALED_UNVERIFIED,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                tonalElevation = 2.dp,
            ) {
                Text(
                    "Reduced filtering on this page",
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }

        AnimatedVisibility(
            visible = coordinator.notice != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut(),
        ) {
            coordinator.notice?.let { message ->
                Surface(
                    modifier = Modifier
                        .padding(16.dp)
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = message
                        },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shadowElevation = 8.dp,
                ) {
                    Text(
                        message,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingSurface(platform: SocialPlatform) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                "Opening ${platform.displayName}",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Checking the protected interface before showing content…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ErrorSurface(
    title: String,
    message: String,
    action: String,
    onAction: () -> Unit,
    secondaryAction: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "!",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAction) { Text(action) }
            if (secondaryAction != null && onSecondaryAction != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onSecondaryAction) { Text(secondaryAction) }
            }
        }
    }
}

@Composable
private fun InfoDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun DiagnosticsDialog(
    report: String,
    remoteEnabled: Boolean,
    onRemoteEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Keyed on the dialog instance, not on the report text. A new diagnostic arriving
    // while the dialog is open used to re-key this and flip "Copied" back mid-read.
    var copied by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnostics") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // The trace can run to fifty lines, so it scrolls rather than pushing the
                // opt-in switch and the buttons off screen.
                SelectionContainer(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        report,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                HorizontalDivider()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {},
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Send failure reports", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Off by default. Sends only failure events — never the navigation " +
                                "stages above — to the ClearFeed maintainer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = remoteEnabled,
                        onCheckedChange = onRemoteEnabledChange,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                            ClipData.newPlainText("ClearFeed diagnostics", report),
                        )
                        copied = true
                    },
                ) { Text(if (copied) "Copied" else "Copy") }
                TextButton(
                    onClick = {
                        // Sharing keeps the report on the device until the user picks a
                        // destination themselves; nothing is transmitted by ClearFeed.
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "ClearFeed diagnostics")
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(share, "Share diagnostics"))
                        }
                    },
                ) { Text("Share") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

internal fun externalDestinationLabel(rawUrl: String): String = runCatching {
    URI(rawUrl).host
        ?.lowercase(Locale.ROOT)
        ?.removePrefix("www.")
        ?.takeIf { it.isNotBlank() }
}.getOrNull() ?: "external website"

private fun platformMark(platform: SocialPlatform): String = when (platform) {
    SocialPlatform.INSTAGRAM -> "IG"
    SocialPlatform.YOUTUBE -> "▶"
    SocialPlatform.FACEBOOK -> "f"
}

@Composable
private fun platformAccent(platform: SocialPlatform): Color = when (platform) {
    SocialPlatform.INSTAGRAM -> if (isSystemInDarkTheme()) Color(0xFFFF78B9) else Color(0xFF982569)
    SocialPlatform.YOUTUBE -> if (isSystemInDarkTheme()) Color(0xFFFF7489) else Color(0xFFB00020)
    SocialPlatform.FACEBOOK -> if (isSystemInDarkTheme()) Color(0xFF8AB4F8) else Color(0xFF0B57D0)
}

private fun previewUsageSummary() = ServiceUsageSummary(
    limits = ServiceLimits.UNRESTRICTED,
    usedSecondsToday = 0,
    remainingSecondsToday = null,
    opensToday = 0,
)

@Preview(name = "Service picker · light", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun ServicePickerLightPreview() {
    MaterialTheme(colorScheme = LightColors, typography = ClearFeedTypography, shapes = ClearFeedShapes) {
        Surface(color = MaterialTheme.colorScheme.background) { ServicePicker(onSelect = {}, usageFor = { previewUsageSummary() }) }
    }
}

@Preview(
    name = "Service picker · dark",
    showBackground = true,
    widthDp = 360,
    heightDp = 800,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ServicePickerDarkPreview() {
    MaterialTheme(colorScheme = DarkColors, typography = ClearFeedTypography, shapes = ClearFeedShapes) {
        Surface(color = MaterialTheme.colorScheme.background) { ServicePicker(onSelect = {}, usageFor = { previewUsageSummary() }) }
    }
}
