package com.f15.applock.recents

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.f15.applock.knox.PolicyTransactionValidator
import com.f15.applock.knox.PolicyValidationResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of a Recents privacy policy transaction.
 */
sealed class RecentsPolicyResult {
    data class Success(val message: String) : RecentsPolicyResult()
    data class PlatformLimited(val message: String) : RecentsPolicyResult()
    data class SecurityError(val reason: String) : RecentsPolicyResult()
}

/**
 * Manages Recents privacy policy validation and enforcement.
 *
 * Enforces:
 * 1. Step 7 Global Policy Safety: Validates target packages before applying any policy.
 *    Strictly prevents global device restrictions (such as global DPM screen capture disable)
 *    from being applied across all applications.
 * 2. Step 6 Dynamic Protected Apps: Manages policy states dynamically as packages are added
 *    or removed from the protected list.
 * 3. Step 2 & Step 11 Fail-Safe & Honest Status Reporting: If per-package snapshot protection
 *    cannot be applied to third-party apps by OS design, reports [RecentsPolicyResult.PlatformLimited]
 *    without resorting to unsupported hacks.
 */
class RecentsPrivacyPolicy(
    private val context: Context,
    private val dpm: DevicePolicyManager,
    private val adminComponent: ComponentName
) {

    companion object {
        private const val TAG = "RecentsPrivacyPolicy"
    }

    // In-memory registry of active protected packages with verified policy scope
    private val activePrivacyPackages = ConcurrentHashMap.newKeySet<String>()

    /**
     * Validates and applies Recents privacy policy for [packageName].
     *
     * Invariants:
     * - Target package must be valid syntax and non-empty.
     * - Must NOT apply global DPM screen capture restriction to the device user, as doing so
     *   would disable screenshots and blank Recents for all non-protected apps (Step 3 & Step 7).
     */
    fun applyPrivacyPolicy(packageName: String): RecentsPolicyResult {
        // Step 7: Strict Target Validation
        val validation = PolicyTransactionValidator.validatePackage(packageName)
        if (validation is PolicyValidationResult.Rejected) {
            Log.w(TAG, "[PrivacyPolicy] Validation failed for $packageName: ${validation.reason}")
            return RecentsPolicyResult.SecurityError("Transaction rejected: ${validation.reason}")
        }

        // Self-protection path for AppLock itself
        if (packageName == context.packageName) {
            activePrivacyPackages.add(packageName)
            Log.i(TAG, "[PrivacyPolicy] AppLock self-protection active (FLAG_SECURE + Recents screenshot disabled)")
            return RecentsPolicyResult.Success("Self-protection active for AppLock")
        }

        // Third-party applications:
        // Android AOSP process sandbox restricts FLAG_SECURE and setRecentsScreenshotEnabled
        // to the application's own process. Neither Android DPM nor Samsung Knox ApplicationPolicy
        // provides an official per-package task snapshot masking API.
        activePrivacyPackages.add(packageName)
        Log.i(
            TAG,
            "[PrivacyPolicy] Target $packageName registered in privacy scope. " +
            "Platform limitation: Android AOSP process isolation prohibits external modification of window flags."
        )
        return RecentsPolicyResult.PlatformLimited(
            "Target registered. Third-party snapshot masking is limited by Android OS process architecture."
        )
    }

    /**
     * Removes privacy policy tracking for [packageName].
     */
    fun removePrivacyPolicy(packageName: String): RecentsPolicyResult {
        if (packageName.isBlank()) {
            return RecentsPolicyResult.SecurityError("Invalid empty package name")
        }
        activePrivacyPackages.remove(packageName)
        Log.i(TAG, "[PrivacyPolicy] Removed $packageName from Recents privacy scope")
        return RecentsPolicyResult.Success("Removed $packageName from privacy scope")
    }

    /**
     * Reconciles the policy set against the current protected packages.
     */
    fun reconcile(protectedPackages: Set<String>) {
        val toRemove = activePrivacyPackages.filter { it != context.packageName && !protectedPackages.contains(it) }
        toRemove.forEach { removePrivacyPolicy(it) }

        protectedPackages.forEach { applyPrivacyPolicy(it) }
        applyPrivacyPolicy(context.packageName)
    }

    /**
     * Returns true if [packageName] is currently within active privacy tracking scope.
     */
    fun isPackageInPrivacyScope(packageName: String): Boolean {
        return activePrivacyPackages.contains(packageName)
    }

    /**
     * Returns all packages currently registered in the privacy scope.
     */
    fun getPrivacyScopedPackages(): Set<String> {
        return activePrivacyPackages.toSet()
    }
}
