package com.f15.applock.domain.model

/**
 * Explicit foreground security state model governing real-time app protection transitions.
 *
 * Distinguishes Home, unprotected apps, and same-package authorized navigation
 * to guarantee fail-closed enforcement and eliminate re-entry bypass windows.
 */
enum class ForegroundSecurityState {
    UNKNOWN,
    HOME,
    UNPROTECTED_APP,
    PROTECTED_APP_AUTHORIZED,
    PROTECTED_APP_LOCKED,
    AUTHENTICATING
}
