package dev.directonly.app.web

data class WebFailure(
    val code: String,
    val detail: String,
    val url: String?,
    val offline: Boolean = false,
)
