package dev.directonly.app.policy

import dev.directonly.app.model.SocialPlatform

data class CrossPlatformReturnPoint(
    val platform: SocialPlatform,
    val returnUrl: String,
    val allowedYouTubeVideoId: String?,
)

/** Volatile safe-navigation metadata only; a new process starts with an empty stack. */
class CrossPlatformNavigationStack {
    private val returnPoints = ArrayDeque<CrossPlatformReturnPoint>()

    fun push(returnPoint: CrossPlatformReturnPoint) {
        returnPoints.addLast(returnPoint)
    }

    fun pop(): CrossPlatformReturnPoint? = returnPoints.removeLastOrNull()

    fun isNotEmpty(): Boolean = returnPoints.isNotEmpty()

    fun clear() = returnPoints.clear()
}
