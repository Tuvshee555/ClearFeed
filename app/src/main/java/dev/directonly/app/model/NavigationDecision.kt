package dev.directonly.app.model

enum class NavigationDisposition {
    ALLOW_AUTH,
    ALLOW_DIRECT,
    ALLOW_SHARED_CONTENT,
    ALLOW_CONTENT,
    OPEN_EXTERNALLY,
    BLOCK,
}

enum class RouteKind {
    AUTH_LOGIN,
    AUTH_CHALLENGE,
    AUTH_RECOVERY,
    AUTH_CONSENT,
    DIRECT_INBOX,
    DIRECT_THREAD,
    DIRECT_REQUESTS,
    DIRECT_NEW,
    INSTAGRAM_SHARED_REEL,
    INSTAGRAM_SHARED_POST,
    YOUTUBE_SUBSCRIPTIONS,
    YOUTUBE_SEARCH,
    YOUTUBE_WATCH,
    FACEBOOK_FEED,
    FACEBOOK_MESSAGES,
    FACEBOOK_NOTIFICATIONS,
    FACEBOOK_SEARCH,
    FACEBOOK_FRIENDS,
    FACEBOOK_GROUP,
    FACEBOOK_EVENT,
    FACEBOOK_PAGE,
    FACEBOOK_POST,
    EXTERNAL_HTTPS,
    BLOCKED_INSTAGRAM_CONTENT,
    BLOCKED_YOUTUBE_CONTENT,
    BLOCKED_FACEBOOK_CONTENT,
    UNKNOWN_INSTAGRAM,
    UNKNOWN_YOUTUBE,
    UNKNOWN_FACEBOOK,
    INVALID,
}

enum class BlockReason {
    NONE,
    INVALID_URL,
    DISALLOWED_SCHEME,
    DISALLOWED_HOST,
    DISALLOWED_PORT,
    UNSAFE_ENCODING,
    INSTAGRAM_CONTENT,
    UNKNOWN_INSTAGRAM_ROUTE,
    YOUTUBE_CONTENT,
    UNKNOWN_YOUTUBE_ROUTE,
    FACEBOOK_CONTENT,
    UNKNOWN_FACEBOOK_ROUTE,
    MANAGED_SOCIAL_ESCAPE,
    INTENT_REQUIRED,
}

data class NavigationDecision(
    val disposition: NavigationDisposition,
    val routeKind: RouteKind,
    val blockReason: BlockReason = BlockReason.NONE,
    val safePath: String? = null,
) {
    val mayLoadInWebView: Boolean
        get() = disposition == NavigationDisposition.ALLOW_AUTH ||
            disposition == NavigationDisposition.ALLOW_DIRECT ||
            disposition == NavigationDisposition.ALLOW_SHARED_CONTENT ||
            disposition == NavigationDisposition.ALLOW_CONTENT
}
