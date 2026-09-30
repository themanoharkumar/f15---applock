package com.f15.applock.domain.model

/**
 * Encapsulates an authentication lock request emitted when a protected application
 * enters the foreground without an active authenticated session.
 *
 * @property packageName Package identifier of the protected application.
 * @property timestamp Milliseconds timestamp of the request.
 */
data class LockRequest(
    val packageName: String,
    val timestamp: Long = System.currentTimeMillis()
)
