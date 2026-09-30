package com.f15.applock.domain.model

/**
 * Represents a detected foreground application event.
 *
 * @property packageName Package identifier of the active foreground app.
 * @property timestamp Milliseconds timestamp of the event.
 */
data class ForegroundApp(
    val packageName: String,
    val timestamp: Long = System.currentTimeMillis()
)
