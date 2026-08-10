package dev.directonly.app.model

enum class SocialPlatform(
    val displayName: String,
    val description: String,
    val guardScriptAsset: String,
    val guardCssAsset: String,
    val guardVersion: Int,
) {
    INSTAGRAM(
        displayName = "Instagram",
        description = "DMs and only the exact posts or Reels sent to you",
        guardScriptAsset = "instagram_guard.js",
        guardCssAsset = "instagram_guard.css",
        guardVersion = 6,
    ),
    YOUTUBE(
        displayName = "YouTube",
        description = "Subscriptions, search, and videos you intentionally choose",
        guardScriptAsset = "youtube_guard.js",
        guardCssAsset = "youtube_guard.css",
        guardVersion = 4,
    ),
    FACEBOOK(
        displayName = "Facebook",
        description = "Newest feed, capped at 8 non-video posts, plus Messages",
        guardScriptAsset = "facebook_guard.js",
        guardCssAsset = "facebook_guard.css",
        guardVersion = 7,
    ),
}
