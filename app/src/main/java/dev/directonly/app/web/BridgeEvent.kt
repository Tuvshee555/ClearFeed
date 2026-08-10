package dev.directonly.app.web

sealed interface BridgeEvent {
    data class NavigationCandidate(val path: String) : BridgeEvent
    data class BlockedNavigation(val path: String) : BridgeEvent
    data class ExternalNavigation(val url: String, val userGesture: Boolean) : BridgeEvent
    data class IntentionalVideo(val pathAndQuery: String) : BridgeEvent
    data class IntentionalInstagramSharedContent(val pathAndQuery: String) : BridgeEvent
    data class AuthenticationRequired(val path: String) : BridgeEvent
    data class GuardHealth(val path: String, val healthy: Boolean, val version: Int) : BridgeEvent
    data class GuardReady(val path: String, val version: Int) : BridgeEvent
}
