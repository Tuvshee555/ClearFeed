package dev.directonly.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.directonly.app.ui.BlockerApp

/**
 * ClearFeed no longer wraps provider websites in a sandboxed WebView. That approach chased
 * Instagram/Facebook/YouTube's DOM through every redesign and still failed on-device in ways
 * that were only debuggable through a diagnostic trace shipped after the fact.
 *
 * The blocker approach it replaced with: let the real, official apps run, and use an Android
 * Accessibility Service ([dev.directonly.app.blocker.ShortsAccessibilityService]) to detect
 * the YouTube Shorts player and send the user back to their home screen. Full app features
 * (notifications, camera, everything) work because nothing is being reimplemented — only a
 * screen is being detected and reacted to.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BlockerApp() }
    }
}
