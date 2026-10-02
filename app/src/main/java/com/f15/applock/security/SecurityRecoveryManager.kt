package com.f15.applock.security

import android.content.Context
import android.util.Log
import com.f15.applock.data.storage.AppLockPreferences
import com.f15.applock.data.storage.SecureCredentialStore
import com.f15.applock.knox.KnoxManagerImpl

/**
 * Result of an authenticated administrator recovery operation.
 */
data class RecoveryResult(
    val isSuccess: Boolean,
    val message: String,
    val postRecoveryPosture: SecurityPostureReport? = null
)

/**
 * Secure Administrator Recovery Manager.
 *
 * Guarantees:
 * - NO secret backdoors, master passwords, or bypass gestures.
 * - Requires explicit administrator authentication (verified PIN) before any recovery action.
 * - Every recovery action is auditable and logged to [SecurityEventLogger].
 * - Safely resolves configuration integrity violations, restores package-specific policies,
 *   and purges accidental global restrictions.
 */
class SecurityRecoveryManager(
    private val context: Context,
    private val credentialStore: SecureCredentialStore,
    private val preferences: AppLockPreferences,
    private val stateManager: SecurityStateManager
) {

    companion object {
        private const val TAG = "SecurityRecovery"
    }

    private val knoxManager by lazy { KnoxManagerImpl.getInstance(context) }

    /**
     * Executes authenticated administrator recovery.
     * Verifies the administrator's PIN before allowing any configuration or policy restoration.
     */
    suspend fun executeRecovery(adminPin: String): RecoveryResult {
        // 1. Verify administrator credentials
        if (!credentialStore.isPinConfigured()) {
            return RecoveryResult(
                isSuccess = false,
                message = "Cannot enter recovery mode: Master PIN is not configured"
            )
        }

        val isPinValid = credentialStore.verifyPin(adminPin)
        if (!isPinValid) {
            SecurityEventLogger.log(
                type = SecurityEventType.AUTHENTICATION_FAILURE,
                details = "Unauthorized attempt to access Administrator Recovery Mode",
                severity = SecurityEventSeverity.CRITICAL,
                component = "Recovery",
                result = "AUTH_FAILED"
            )
            return RecoveryResult(
                isSuccess = false,
                message = "Invalid administrator PIN"
            )
        }

        Log.w(TAG, "[AERA-SECURITY] Administrator Recovery Mode initiated with verified PIN")
        SecurityEventLogger.log(
            type = SecurityEventType.RECOVERY_REQUIRED,
            details = "Administrator Recovery Mode unlocked with valid credentials. Beginning repair sequence.",
            severity = SecurityEventSeverity.WARNING,
            component = "Recovery",
            result = "UNLOCKED"
        )

        // 2. Clear any lingering global restrictions
        try {
            knoxManager.reconcilePolicies(context.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Error reconciling Knox policies during recovery", e)
        }

        // 3. Re-sign configuration HMAC baseline
        val configRecovered = preferences.recoverAndSignConfiguration()

        // 4. Re-evaluate post-recovery security posture
        val newReport = stateManager.evaluateSecurityPosture()

        SecurityEventLogger.log(
            type = SecurityEventType.SERVICE_LIFECYCLE,
            details = "Administrator recovery sequence completed. New security posture: ${newReport.state.name}",
            severity = SecurityEventSeverity.INFO,
            component = "Recovery",
            result = if (configRecovered) "SUCCESS" else "PARTIAL"
        )

        return RecoveryResult(
            isSuccess = configRecovered,
            message = if (configRecovered) {
                "Recovery completed successfully. Security posture: ${newReport.state.name}"
            } else {
                "Recovery executed with warnings. Check diagnostics."
            },
            postRecoveryPosture = newReport
        )
    }
}
