package dev.directonly.app.policy

import dev.directonly.app.model.NavigationDecision
import dev.directonly.app.model.RouteKind

/**
 * Decides which privileged device capabilities a already-evaluated route may receive.
 *
 * This exists as a separate, pure object because the decision was previously inlined in
 * `ClearFeedCoordinator` — which has no tests — and got it wrong in a way that mattered. A
 * [NavigationDecision] carries its [RouteKind] *and* its disposition independently: an
 * unauthorized Instagram Reel is `BLOCK` while still reporting
 * `RouteKind.INSTAGRAM_SHARED_REEL`, and a Direct thread is `BLOCK` while still reporting
 * `RouteKind.DIRECT_THREAD` whenever a sealed-viewer capability is active. Branching on the
 * route kind alone therefore granted fullscreen video and camera/microphone access on routes
 * the policy had just rejected.
 *
 * Every gate here requires [NavigationDecision.mayLoadInWebView] first. Keeping that rule in
 * one testable place is the point.
 */
object ProtectedSurfaceGate {
    private val MESSAGE_ROUTES = setOf(
        RouteKind.DIRECT_INBOX,
        RouteKind.DIRECT_THREAD,
        RouteKind.DIRECT_REQUESTS,
        RouteKind.DIRECT_NEW,
        RouteKind.FACEBOOK_MESSAGES,
    )

    private val FULLSCREEN_ROUTES = setOf(
        RouteKind.YOUTUBE_WATCH,
        RouteKind.INSTAGRAM_SHARED_REEL,
        RouteKind.INSTAGRAM_SHARED_POST,
    )

    /**
     * Whether an active conversation route may use the camera, microphone and file picker.
     */
    fun isMessageSurface(decision: NavigationDecision?): Boolean =
        decision != null && decision.mayLoadInWebView && decision.routeKind in MESSAGE_ROUTES

    /** Whether a route may enter fullscreen video. */
    fun isFullscreenSurface(decision: NavigationDecision?): Boolean {
        if (decision == null || !decision.mayLoadInWebView) return false
        return decision.routeKind in FULLSCREEN_ROUTES || decision.routeKind in MESSAGE_ROUTES
    }
}
