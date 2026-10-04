package com.f15.applock.knox

import android.os.Bundle
import android.os.UserManager
import android.util.Log
import com.f15.applock.security.SecurityEventLogger
import com.f15.applock.security.SecurityEventSeverity
import com.f15.applock.security.SecurityEventType

/**
 * Result of policy transaction validation.
 */
sealed class PolicyValidationResult {
    data object Approved : PolicyValidationResult()
    data class Rejected(val reason: String, val violation: String) : PolicyValidationResult()

    val isApproved: Boolean get() = this is Approved
}

/**
 * Validates Samsung Knox and Android Enterprise policy requests prior to application.
 *
 * Invariant Enforcement (Phase 8 Hardening):
 * - Guarantees App Lock self-protection policies apply ONLY to "com.f15.applock".
 * - Strictly rejects wildcard package patterns ("*").
 * - Strictly rejects empty package lists where empty could mean "all applications".
 * - Strictly rejects unexpectedly large package lists.
 * - Strictly rejects global user restrictions (DISALLOW_APPS_CONTROL, DISALLOW_UNINSTALL_APPS).
 * - Logs all rejections to [SecurityEventLogger] with [SecurityEventType.POLICY_VALIDATION_REJECTED].
 * - Prevents regressions of the Phase 7 global app uninstallation issue.
 */
object PolicyTransactionValidator {

    private const val TAG = "PolicyValidator"
    const val EXPECTED_SELF_PACKAGE = "com.f15.applock"
    private val PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    /**
     * Validates that an application policy request targets exclusively the App Lock package.
     */
    fun validateSelfProtectionTarget(targetPackage: String): PolicyValidationResult {
        val trimmed = targetPackage.trim()

        if (trimmed.isEmpty()) {
            return reject(
                reason = "Empty package identifier",
                violation = "Empty target package could be interpreted as global policy by Knox SDK"
            )
        }

        if (trimmed == "*" || trimmed.contains("*")) {
            return reject(
                reason = "Wildcard package rejected",
                violation = "Wildcard '*' would apply policy globally across all applications"
            )
        }

        if (!trimmed.equals(EXPECTED_SELF_PACKAGE, ignoreCase = true)) {
            return reject(
                reason = "Overly broad target package",
                violation = "Expected target '$EXPECTED_SELF_PACKAGE' but received '$trimmed'"
            )
        }

        if (!PACKAGE_REGEX.matches(trimmed)) {
            return reject(
                reason = "Malformed package format",
                violation = "Package string '$trimmed' does not conform to RFC package naming specifications"
            )
        }

        return PolicyValidationResult.Approved
    }

    /**
     * Validates that an arbitrary package identifier conforms to valid format and is not wildcard/empty.
     */
    fun validatePackage(targetPackage: String): PolicyValidationResult {
        val trimmed = targetPackage.trim()

        if (trimmed.isEmpty()) {
            return reject(
                reason = "Empty package identifier",
                violation = "Empty target package cannot be targeted by policy"
            )
        }

        if (trimmed == "*" || trimmed.contains("*")) {
            return reject(
                reason = "Wildcard package rejected",
                violation = "Wildcard '*' would apply policy globally across all applications"
            )
        }

        if (!PACKAGE_REGEX.matches(trimmed)) {
            return reject(
                reason = "Malformed package format",
                violation = "Package string '$trimmed' does not conform to RFC package naming specifications"
            )
        }

        return PolicyValidationResult.Approved
    }

    /**
     * Validates a batch package list for application-level policy application.
     */
    fun validatePackageList(packages: List<String>, maxAllowed: Int = 1): PolicyValidationResult {
        if (packages.isEmpty()) {
            return reject(
                reason = "Empty package list rejected",
                violation = "Passing an empty package list may cause Knox SDK to default to all installed apps"
            )
        }

        if (packages.size > maxAllowed) {
            return reject(
                reason = "Unexpectedly large package list",
                violation = "Received ${packages.size} packages, but self-protection policy accepts at most $maxAllowed"
            )
        }

        for (pkg in packages) {
            val result = validateSelfProtectionTarget(pkg)
            if (result is PolicyValidationResult.Rejected) {
                return result
            }
        }

        return PolicyValidationResult.Approved
    }

    /**
     * Inspects active user restrictions bundle to ensure global restrictions are not present.
     */
    fun validateNoGlobalRestrictions(userRestrictions: Bundle?): PolicyValidationResult {
        if (userRestrictions == null) return PolicyValidationResult.Approved

        val hasAppsControl = userRestrictions.getBoolean(UserManager.DISALLOW_APPS_CONTROL, false)
        val hasUninstall = userRestrictions.getBoolean(UserManager.DISALLOW_UNINSTALL_APPS, false)

        if (hasAppsControl) {
            return reject(
                reason = "DISALLOW_APPS_CONTROL active",
                violation = "Global user restriction DISALLOW_APPS_CONTROL disables uninstall and force-stop for all user apps"
            )
        }

        if (hasUninstall) {
            return reject(
                reason = "DISALLOW_UNINSTALL_APPS active",
                violation = "Global user restriction DISALLOW_UNINSTALL_APPS disables uninstall for all user apps"
            )
        }

        return PolicyValidationResult.Approved
    }

    private fun reject(reason: String, violation: String): PolicyValidationResult.Rejected {
        try {
            Log.w(TAG, "[AERA-SECURITY] Policy transaction rejected: $reason ($violation)")
        } catch (e: Throwable) {
            // JVM unit test fallback when android.util.Log is unmocked
        }
        SecurityEventLogger.log(
            type = SecurityEventType.POLICY_VALIDATION_REJECTED,
            details = "Policy application rejected: $reason. $violation",
            severity = SecurityEventSeverity.WARNING,
            component = "PolicyValidator",
            result = "REJECTED"
        )
        return PolicyValidationResult.Rejected(reason, violation)
    }
}
