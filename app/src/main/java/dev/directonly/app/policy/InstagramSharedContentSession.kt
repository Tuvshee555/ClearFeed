package dev.directonly.app.policy

/**
 * Volatile, one-viewer Instagram capability state. It deliberately has no persistence API:
 * process recreation starts without an approved item.
 */
class InstagramSharedContentSession(
    private val elapsedRealtimeMs: () -> Long,
    private val authorizationTtlMs: Long = DEFAULT_AUTHORIZATION_TTL_MS,
) {
    private var pendingApproval: ApprovedInstagramSharedContent? = null

    fun grant(approval: ApprovedInstagramSharedContent) {
        pendingApproval = approval
    }

    /** Returns the capability only while it is live; an expired item can still return safely. */
    fun activeApproval(): ApprovedInstagramSharedContent? = pendingApproval?.takeIf {
        val age = elapsedRealtimeMs() - it.createdAtElapsedRealtimeMs
        age in 0..authorizationTtlMs
    }

    fun hasPendingViewer(): Boolean = pendingApproval != null

    fun returnDestinationAndClear(): String? {
        val destination = pendingApproval?.originatingThreadUrl
        pendingApproval = null
        return destination
    }

    fun clear() {
        pendingApproval = null
    }

    companion object {
        const val DEFAULT_AUTHORIZATION_TTL_MS = 30 * 60 * 1_000L
    }
}
