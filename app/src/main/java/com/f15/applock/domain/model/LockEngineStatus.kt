package com.f15.applock.domain.model

/**
 * Operational readiness status of the App Lock enforcement engine.
 */
enum class LockEngineMode {
    ACTIVE_ACCESSIBILITY,   // Real-time event-driven via AccessibilityService
    ACTIVE_USAGE_STATS,     // Fallback polling via UsageStatsManager
    LIMITED,                // Accessibility missing; only Usage Access fallback
    DISABLED                // Required permissions missing
}

/**
 * Diagnostic source reporting a foreground window/app event.
 */
enum class DetectionSource {
    ACCESSIBILITY,
    USAGE_STATS,
    MANUAL
}

/**
 * State machine representing the lock controller lifecycle.
 */
sealed class LockEngineState {
    object Idle : LockEngineState()
    data class ProtectedAppDetected(val packageName: String) : LockEngineState()
    data class LockScreenShown(val packageName: String, val timestamp: Long) : LockEngineState()
    data class Authenticating(val packageName: String) : LockEngineState()
    data class AccessGranted(val packageName: String, val expiresAt: Long) : LockEngineState()
}

/**
 * Central monitoring status representation for UI display.
 */
data class MonitoringStatus(
    val isAccessibilityEnabled: Boolean = false,
    val hasUsageAccess: Boolean = false,
    val protectedAppCount: Int = 0,
    val engineMode: LockEngineMode = LockEngineMode.DISABLED,
    val engineState: LockEngineState = LockEngineState.Idle,
    val currentPackage: String? = null,
    val previousPackage: String? = null,
    val detectionSource: DetectionSource = DetectionSource.ACCESSIBILITY,
    val lastLockedPackage: String? = null,
    val lastLockTimestamp: Long = 0L
)
