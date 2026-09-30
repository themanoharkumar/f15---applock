package com.f15.applock.domain.model

import com.f15.applock.R

/**
 * Configurable timeout duration for the authenticated session.
 *
 * @property durationSeconds Duration in seconds before the session expires when backgrounded.
 * @property labelResId String resource identifier for UI display.
 */
enum class SessionTimeout(val durationSeconds: Long, val labelResId: Int) {
    IMMEDIATELY(0L, R.string.timeout_immediately),
    SECONDS_30(30L, R.string.timeout_30s),
    MINUTE_1(60L, R.string.timeout_1m),
    MINUTES_5(300L, R.string.timeout_5m);

    val durationMillis: Long
        get() = durationSeconds * 1000L

    companion object {
        fun fromDuration(seconds: Long): SessionTimeout {
            return entries.find { it.durationSeconds == seconds } ?: MINUTE_1
        }
    }
}
