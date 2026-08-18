package dev.directonly.app.model

enum class AppState {
    HOME,

    /** A usage limit refused this service; the surface explains why and when it reopens. */
    ACCESS_BLOCKED,

    /** A deliberate delay is running before the service opens. */
    OPENING_DELAY,

    STARTING,
    AUTHENTICATING,
    CONTENT,
    SHARED_CONTENT_VIEWER,
    BLOCKED_RECOVERY,
    OFFLINE,
    WEB_ERROR,
    WEBVIEW_UNAVAILABLE,
}

enum class PolicyMode {
    AUTHENTICATING,
    DIRECT,
    CONTENT,
}
