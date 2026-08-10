package dev.directonly.app.model

enum class AppState {
    HOME,
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
