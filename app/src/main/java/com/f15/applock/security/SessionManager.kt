package com.f15.applock.security

import android.os.SystemClock
import com.f15.applock.domain.model.SessionTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the in-memory authentication session state and configurable background expiration timeouts.
 */
class SessionManager {

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private var lastBackgroundTimeMs: Long = 0L

    /**
     * Marks the current session as authenticated.
     */
    fun onAuthenticationSuccess() {
        _isAuthenticated.value = true
        lastBackgroundTimeMs = 0L
    }

    /**
     * Called when the application moves to the background (e.g. onStop).
     */
    fun onAppBackgrounded() {
        if (_isAuthenticated.value) {
            lastBackgroundTimeMs = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Called when the application returns to the foreground (e.g. onStart).
     * Validates if the elapsed background duration has exceeded the configured timeout.
     */
    fun onAppForegrounded(timeout: SessionTimeout) {
        if (!_isAuthenticated.value) return

        if (timeout == SessionTimeout.IMMEDIATELY) {
            _isAuthenticated.value = false
            return
        }

        if (lastBackgroundTimeMs > 0) {
            val elapsed = SystemClock.elapsedRealtime() - lastBackgroundTimeMs
            if (elapsed >= timeout.durationMillis) {
                _isAuthenticated.value = false
            }
        }
    }

    /**
     * Immediately terminates the active session, locking the application.
     */
    fun lockSession() {
        _isAuthenticated.value = false
        lastBackgroundTimeMs = 0L
    }
}
