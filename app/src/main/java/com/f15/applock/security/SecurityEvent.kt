package com.f15.applock.security

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Severity level for security audit events.
 */
enum class SecurityEventSeverity {
    INFO,
    WARNING,
    ERROR,
    CRITICAL
}

/**
 * Types of security-relevant events tracked by the local security audit logger.
 * Covers Phase 8 anti-tamper, Device Owner, Knox, Accessibility, and authentication lifecycle.
 */
enum class SecurityEventType {
    // Phase 8 Anti-Tamper & Hardening Events
    DEVICE_OWNER_VERIFIED,
    DEVICE_OWNER_LOST,
    KNOX_INITIALIZED,
    KNOX_UNAVAILABLE,
    KNOX_POLICY_VERIFIED,
    KNOX_POLICY_MISMATCH,
    ACCESSIBILITY_ENABLED,
    ACCESSIBILITY_DISABLED,
    PROTECTED_APP_ADDED,
    PROTECTED_APP_REMOVED,
    SECURITY_CONFIG_INTEGRITY_FAILURE,
    POLICY_VALIDATION_REJECTED,
    POLICY_APPLICATION_FAILED,
    SECURITY_STATE_CHANGED,
    AUTHENTICATION_FAILURE,
    AUTHENTICATION_SUCCESS,
    AUTH_SUCCESS,
    AUTH_FAILED,
    BOOT_RECONCILIATION,
    RECOVERY_REQUIRED,

    // Core App Lock Operational Events (Preserved for compatibility)
    PROTECTED_APP_OPENED,
    AUTH_REQUESTED,
    AUTH_CANCELLED,
    ACCESSIBILITY_STATE_CHANGED,
    USAGE_ACCESS_STATE_CHANGED,
    APP_PROTECTION_TOGGLED,
    PIN_CHANGED,
    PIN_REMOVED,
    SERVICE_LIFECYCLE,
    DEVICE_BOOT_RESTORED,
    CLEANUP_STALE_PACKAGES,
    SCREEN_OFF_LOCK
}

/**
 * Represents a single sanitized security event.
 *
 * Guaranteed Privacy:
 * Never stores or logs PINs, passwords, biometric templates, encryption keys, tokens,
 * or private user content.
 */
data class SecurityEvent(
    val id: Long,
    val timestamp: Long,
    val eventType: SecurityEventType,
    val severity: SecurityEventSeverity = SecurityEventSeverity.INFO,
    val component: String = "",
    val result: String = "SUCCESS",
    val packageName: String? = null,
    val securityState: String? = null,
    val details: String
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    val formattedDateTime: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
