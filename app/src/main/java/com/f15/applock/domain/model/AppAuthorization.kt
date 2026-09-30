package com.f15.applock.domain.model

/**
 * Tracks the temporary authentication grant for a specific protected application.
 *
 * Per-app authorization guarantees that unlocking WhatsApp does NOT automatically
 * unlock Instagram, Chrome, or other protected apps unless configured globally.
 *
 * @property packageName Identifier of the authorized package.
 * @property authenticatedAt Timestamp (SystemClock.elapsedRealtime) when authentication succeeded.
 * @property expiresAt Timestamp (SystemClock.elapsedRealtime) when this authorization expires.
 */
data class AppAuthorization(
    val packageName: String,
    val authenticatedAt: Long,
    val expiresAt: Long
) {
    /**
     * Checks if this authorization has expired based on current elapsed realtime.
     */
    fun isExpired(currentElapsedRealtime: Long): Boolean =
        currentElapsedRealtime >= expiresAt
}
