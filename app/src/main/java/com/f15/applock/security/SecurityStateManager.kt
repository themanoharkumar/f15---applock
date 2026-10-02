package com.f15.applock.security

import android.content.Context
import android.util.Log
import com.f15.applock.accessibility.AppLockAccessibilityService
import com.f15.applock.data.storage.AppLockPreferences
import com.f15.applock.data.storage.SecureCredentialStore
import com.f15.applock.device.DeviceOwnerIntegrityResult
import com.f15.applock.device.DeviceOwnerManager
import com.f15.applock.knox.KnoxManagerImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Centralized security state model representing the overall posture of App Lock.
 *
 * Evaluation Priority:
 * RECOVERY_REQUIRED > COMPROMISED > DEGRADED > SECURE
 */
enum class SecurityPostureState {
    SECURE,
    DEGRADED,
    COMPROMISED,
    RECOVERY_REQUIRED
}

typealias SecurityState = SecurityPostureState

/**
 * Diagnostic issue identified during security state reconciliation.
 */
data class SecurityPostureIssue(
    val severity: SecurityPostureState,
    val title: String,
    val description: String,
    val component: String,
    val isRecoverableAutomatically: Boolean = true
)

/**
 * Complete immutable posture audit report.
 */
data class SecurityPostureReport(
    val state: SecurityPostureState,
    val issues: List<SecurityPostureIssue>,
    val isDeviceOwnerActive: Boolean,
    val isKnoxActive: Boolean,
    val isAccessibilityRunning: Boolean,
    val isConfigIntegrityValid: Boolean,
    val isPinConfigured: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Centralized security state manager.
 *
 * Coordinates:
 * 1. Authoritative Device Owner verification & package identity check
 * 2. Knox application policy scope and self-protection status
 * 3. AccessibilityService real-time monitoring state
 * 4. Master PIN credential store verification
 * 5. Hardware-backed configuration HMAC integrity
 * 6. Audit event logging on every state transition
 */
class SecurityStateManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SecurityStateManager"

        @Volatile
        private var INSTANCE: SecurityStateManager? = null

        fun getInstance(context: Context): SecurityStateManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecurityStateManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * Pure function to compute the security state from discrete diagnostic inputs.
         * Used for deterministic state evaluation and unit testing.
         */
        fun computeSecurityState(
            isAppIdentityValid: Boolean,
            deviceOwnerResult: DeviceOwnerIntegrityResult,
            isScopeClean: Boolean,
            hasBroadRestrictions: Boolean,
            isAccessibilityRunning: Boolean,
            isMasterPinSet: Boolean,
            isConfigValid: Boolean
        ): SecurityPostureState {
            val issues = mutableListOf<SecurityPostureIssue>()
            if (!isAppIdentityValid) {
                issues.add(SecurityPostureIssue(SecurityPostureState.COMPROMISED, "Package Identity Mismatch", "", "DeviceOwner"))
            }
            if (hasBroadRestrictions) {
                issues.add(SecurityPostureIssue(SecurityPostureState.RECOVERY_REQUIRED, "Global Restriction Detected", "", "Knox"))
            } else if (!isScopeClean) {
                issues.add(SecurityPostureIssue(SecurityPostureState.COMPROMISED, "Unsafe Knox Scope", "", "Knox"))
            }
            when (deviceOwnerResult) {
                is DeviceOwnerIntegrityResult.Lost -> issues.add(SecurityPostureIssue(SecurityPostureState.RECOVERY_REQUIRED, "DO Lost", "", "DeviceOwner"))
                is DeviceOwnerIntegrityResult.IdentityMismatch -> issues.add(SecurityPostureIssue(SecurityPostureState.COMPROMISED, "DO Mismatch", "", "DeviceOwner"))
                is DeviceOwnerIntegrityResult.NotProvisioned -> issues.add(SecurityPostureIssue(SecurityPostureState.DEGRADED, "DO Not Provisioned", "", "DeviceOwner"))
                is DeviceOwnerIntegrityResult.VerifiedActive -> {}
            }
            if (!isAccessibilityRunning) {
                issues.add(SecurityPostureIssue(SecurityPostureState.DEGRADED, "Accessibility Stopped", "", "Accessibility"))
            }
            if (!isMasterPinSet) {
                issues.add(SecurityPostureIssue(SecurityPostureState.DEGRADED, "PIN Not Set", "", "Auth"))
            }
            if (!isConfigValid) {
                issues.add(SecurityPostureIssue(SecurityPostureState.COMPROMISED, "Config Tampered", "", "ConfigIntegrity"))
            }

            return when {
                issues.any { it.severity == SecurityPostureState.RECOVERY_REQUIRED } -> SecurityPostureState.RECOVERY_REQUIRED
                issues.any { it.severity == SecurityPostureState.COMPROMISED } -> SecurityPostureState.COMPROMISED
                issues.any { it.severity == SecurityPostureState.DEGRADED } -> SecurityPostureState.DEGRADED
                else -> SecurityPostureState.SECURE
            }
        }
    }

    private val deviceOwnerManager by lazy { DeviceOwnerManager.getInstance(context) }
    private val knoxManager by lazy { KnoxManagerImpl.getInstance(context) }
    private val preferences by lazy { AppLockPreferences(context) }
    private val credentialStore by lazy { SecureCredentialStore(context) }

    private val _postureFlow = MutableStateFlow(
        SecurityPostureReport(
            state = SecurityPostureState.DEGRADED,
            issues = emptyList(),
            isDeviceOwnerActive = false,
            isKnoxActive = false,
            isAccessibilityRunning = false,
            isConfigIntegrityValid = true,
            isPinConfigured = false
        )
    )
    val postureFlow: StateFlow<SecurityPostureReport> = _postureFlow.asStateFlow()

    private var previousState: SecurityPostureState? = null

    /**
     * Authoritatively evaluates system-wide security posture.
     * Guaranteed to reflect platform truth without relying on optimistic cached flags.
     */
    suspend fun evaluateSecurityPosture(): SecurityPostureReport {
        val issues = mutableListOf<SecurityPostureIssue>()

        // 1. Package Identity Verification
        val expectedPackage = "com.f15.applock"
        val actualPackage = context.packageName
        val isIdentityValid = actualPackage.equals(expectedPackage, ignoreCase = true)
        if (!isIdentityValid) {
            issues.add(
                SecurityPostureIssue(
                    severity = SecurityPostureState.COMPROMISED,
                    title = "Package Identity Mismatch",
                    description = "Expected package '$expectedPackage' but running as '$actualPackage'",
                    component = "DeviceOwner",
                    isRecoverableAutomatically = false
                )
            )
        }

        // 2. Device Owner Integrity
        val doResult = deviceOwnerManager.verifyDeviceOwnerIntegrity()
        val isDOActive = doResult is DeviceOwnerIntegrityResult.VerifiedActive
        when (doResult) {
            is DeviceOwnerIntegrityResult.VerifiedActive -> {
                // DO active and healthy
            }
            is DeviceOwnerIntegrityResult.Lost -> {
                issues.add(
                    SecurityPostureIssue(
                        severity = SecurityPostureState.RECOVERY_REQUIRED,
                        title = "Device Owner Privilege Lost",
                        description = doResult.message,
                        component = "DeviceOwner",
                        isRecoverableAutomatically = false
                    )
                )
            }
            is DeviceOwnerIntegrityResult.IdentityMismatch -> {
                issues.add(
                    SecurityPostureIssue(
                        severity = SecurityPostureState.COMPROMISED,
                        title = "Device Owner Identity Mismatch",
                        description = "Platform DO package does not match '$expectedPackage'",
                        component = "DeviceOwner",
                        isRecoverableAutomatically = false
                    )
                )
            }
            is DeviceOwnerIntegrityResult.NotProvisioned -> {
                issues.add(
                    SecurityPostureIssue(
                        severity = SecurityPostureState.DEGRADED,
                        title = "Device Owner Not Active",
                        description = "App Lock has not been provisioned as Android Enterprise Device Owner",
                        component = "DeviceOwner",
                        isRecoverableAutomatically = true
                    )
                )
            }
        }

        // 3. Knox Management & Policy Scope Integrity
        val isKnoxAvailable = knoxManager.isAvailable()
        val isScopeClean = knoxManager.verifyScopeIntegrity()
        if (!isScopeClean) {
            issues.add(
                SecurityPostureIssue(
                    severity = SecurityPostureState.COMPROMISED,
                    title = "Global Restriction Detected",
                    description = "Overly broad global policy restriction was detected on the device",
                    component = "SamsungKnox",
                    isRecoverableAutomatically = true
                )
            )
        }

        val knoxStatus = knoxManager.getAppProtectionStatus(context.packageName)
        if (isDOActive && !knoxStatus.uninstallProtection.isConfirmedActive) {
            issues.add(
                SecurityPostureIssue(
                    severity = SecurityPostureState.DEGRADED,
                    title = "App Lock Protection Inactive",
                    description = "Self-protection uninstallation policy is not active for App Lock",
                    component = "SamsungKnox",
                    isRecoverableAutomatically = true
                )
            )
        }

        // 4. Accessibility Service Integrity
        val a11yGranted = AppLockAccessibilityService.isAccessibilityPermissionGranted(context)
        val a11yRunning = AppLockAccessibilityService.isServiceRunning
        val isA11yHealthy = a11yGranted && a11yRunning
        if (!isA11yHealthy) {
            val a11yDesc = when {
                !a11yGranted -> "Accessibility permission disabled in device settings"
                !a11yRunning -> "Accessibility service permission granted but service is not running"
                else -> "Accessibility service degraded"
            }
            issues.add(
                SecurityPostureIssue(
                    severity = SecurityPostureState.DEGRADED,
                    title = "Real-Time Enforcement Degraded",
                    description = a11yDesc,
                    component = "Accessibility",
                    isRecoverableAutomatically = true
                )
            )
        }

        // 5. Master PIN Authentication Configuration
        val isPinConfigured = credentialStore.isPinConfigured()
        if (!isPinConfigured) {
            issues.add(
                SecurityPostureIssue(
                    severity = SecurityPostureState.DEGRADED,
                    title = "Master PIN Not Configured",
                    description = "No master PIN has been initialized for authentication",
                    component = "Authentication",
                    isRecoverableAutomatically = true
                )
            )
        }

        // 6. Configuration & Protected Apps HMAC Integrity
        val integrityCheck = preferences.verifyIntegrity()
        val isConfigValid = integrityCheck is IntegrityCheckResult.Valid
        if (integrityCheck is IntegrityCheckResult.Compromised) {
            issues.add(
                SecurityPostureIssue(
                    severity = SecurityPostureState.COMPROMISED,
                    title = "Configuration Integrity Failure",
                    description = "${integrityCheck.reason}: ${integrityCheck.details}",
                    component = "ConfigIntegrity",
                    isRecoverableAutomatically = false
                )
            )
        }

        // Priority calculation: RECOVERY_REQUIRED > COMPROMISED > DEGRADED > SECURE
        val overallState = when {
            issues.any { it.severity == SecurityPostureState.RECOVERY_REQUIRED } -> SecurityPostureState.RECOVERY_REQUIRED
            issues.any { it.severity == SecurityPostureState.COMPROMISED } -> SecurityPostureState.COMPROMISED
            issues.any { it.severity == SecurityPostureState.DEGRADED } -> SecurityPostureState.DEGRADED
            else -> SecurityPostureState.SECURE
        }

        val report = SecurityPostureReport(
            state = overallState,
            issues = issues,
            isDeviceOwnerActive = isDOActive,
            isKnoxActive = isKnoxAvailable,
            isAccessibilityRunning = isA11yHealthy,
            isConfigIntegrityValid = isConfigValid,
            isPinConfigured = isPinConfigured
        )

        // Audit state changes
        if (previousState != null && previousState != overallState) {
            val primaryIssue = issues.firstOrNull()?.title ?: "All security invariants verified"
            SecurityEventLogger.log(
                type = SecurityEventType.SECURITY_STATE_CHANGED,
                details = "Security posture state changed from ${previousState?.name} to ${overallState.name}. Reason: $primaryIssue",
                severity = when (overallState) {
                    SecurityPostureState.RECOVERY_REQUIRED, SecurityPostureState.COMPROMISED -> SecurityEventSeverity.CRITICAL
                    SecurityPostureState.DEGRADED -> SecurityEventSeverity.WARNING
                    SecurityPostureState.SECURE -> SecurityEventSeverity.INFO
                },
                component = "SecurityState",
                result = overallState.name,
                securityState = overallState.name
            )
        }
        previousState = overallState

        _postureFlow.value = report
        return report
    }

    /**
     * Executes startup reconciliation on boot and process restart.
     */
    suspend fun reconcileOnStartup(): SecurityPostureReport {
        Log.i(TAG, "[AERA-SECURITY] Running startup security reconciliation")
        val report = evaluateSecurityPosture()
        SecurityEventLogger.log(
            type = SecurityEventType.BOOT_RECONCILIATION,
            details = "Startup reconciliation complete. Overall security state: ${report.state.name} (${report.issues.size} issues detected)",
            severity = if (report.state == SecurityPostureState.SECURE) SecurityEventSeverity.INFO else SecurityEventSeverity.WARNING,
            component = "SecurityState",
            result = report.state.name,
            securityState = report.state.name
        )
        return report
    }
}
