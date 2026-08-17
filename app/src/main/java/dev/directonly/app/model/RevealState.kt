package dev.directonly.app.model

/**
 * How much ClearFeed can currently vouch for what the WebView is showing.
 *
 * ClearFeed originally kept every page hidden until the injected guard confirmed the DOM was
 * safe. That made the injected JavaScript — which depends on provider markup that Instagram
 * and Facebook reship constantly — the gatekeeper for whether the app worked at all. When a
 * selector drifted, the page stayed hidden, the spinner never ended, and the watchdog
 * eventually painted an error. The app broke every time a provider shipped a redesign.
 *
 * The native URL policy is now the gatekeeper, and the guard is a sanitizer. The policy is
 * deterministic, exhaustively tested, and does not depend on markup, so it is the right
 * thing to bet visibility on. [RevealedUnverified] is the state where the URL is provably an
 * allowed route but the guard has not (yet) confirmed the DOM: the page is shown and the
 * guard keeps sanitizing in the background.
 *
 * A page whose URL the policy *blocks* is never revealed in any state.
 */
enum class RevealState {
    /** Nothing is shown: still loading, or the route is not permitted. */
    CONCEALED,

    /** The URL is an allowed route, but the guard has not confirmed the DOM. */
    REVEALED_UNVERIFIED,

    /** The guard confirmed a healthy, sanitized page for this exact route. */
    REVEALED_VERIFIED,
    ;

    val isRevealed: Boolean
        get() = this != CONCEALED
}
