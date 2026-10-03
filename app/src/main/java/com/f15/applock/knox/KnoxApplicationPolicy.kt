package com.f15.applock.knox

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import android.util.Log
import com.f15.applock.security.SecurityEventLogger
import com.f15.applock.security.SecurityEventSeverity
import com.f15.applock.security.SecurityEventType
import com.samsung.android.knox.EnterpriseDeviceManager
import com.samsung.android.knox.application.ApplicationPolicy

/**
 * Concrete management policy implementation for Samsung Knox and Android Enterprise.
 *
 * Implements:
 * 1. Force Stop Blocklist (Knox ApplicationPolicy + Android DPM DISALLOW_APPS_CONTROL)
 * 2. Uninstallation Protection (Knox ApplicationPolicy + Android DPM setUninstallBlocked)
 * 3. Application Disabling Protection (Knox ApplicationPolicy setApplicationState + DPM)
 * 4. Administrator Removability Control (Knox EnterpriseDeviceManager setAdminRemovable)
 * 5. Battery Optimization Exemption (Android PowerManager / DPM)
 *
 * Every operation enforces apply(), verify(), and remove() paths.
 */
class KnoxApplicationPolicy(
    private val context: Context,
    private val dpm: DevicePolicyManager,
    private val adminComponent: ComponentName
) {

    companion object {
        private const val TAG = "KnoxAppPolicy"
    }

    private val powerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }

    /**
     * Resolves the Samsung Knox [EnterpriseDeviceManager] instance if available on this device.
     */
    fun getEnterpriseDeviceManager(): EnterpriseDeviceManager? {
        return try {
            EnterpriseDeviceManager.getInstance(context)
        } catch (e: NoClassDefFoundError) {
            Log.d(TAG, "Knox EnterpriseDeviceManager class not found on this device")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get EnterpriseDeviceManager instance", e)
            null
        }
    }

    /**
     * Resolves the Samsung Knox [ApplicationPolicy] from [EnterpriseDeviceManager].
     */
    fun getApplicationPolicy(): ApplicationPolicy? {
        return try {
            getEnterpriseDeviceManager()?.applicationPolicy
        } catch (e: NoClassDefFoundError) {
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get ApplicationPolicy", e)
            null
        }
    }

    // ========================================================================
    // 1. Force Stop Protection (Package-Specific ONLY)
    // ========================================================================

    /**
     * Adds [packageName] to Knox's Force Stop blocklist.
     *
     * Phase 7.1 Hardening: Strictly package-specific. NEVER applies global user restrictions
     * (such as DISALLOW_APPS_CONTROL) which would inadvertently block force-stop and uninstall
     * on normal user applications (WhatsApp, Instagram, Chrome, etc.).
     */
    fun applyForceStopProtection(packageName: String): KnoxResult {
        // Pre-transaction validation
        val validation = PolicyTransactionValidator.validateSelfProtectionTarget(packageName)
        if (validation is PolicyValidationResult.Rejected) {
            return KnoxResult.SecurityError("Transaction rejected: ${validation.reason} (${validation.violation})")
        }

        // Ensure any lingering global restrictions are purged
        clearGlobalRestrictions()

        var knoxApplied = false
        var knoxError: String? = null

        // 1. Samsung Knox ApplicationPolicy Force Stop Blocklist (package-specific list)
        val appPolicy = getApplicationPolicy()
        if (appPolicy != null) {
            try {
                val success = appPolicy.addPackagesToForceStopBlackList(listOf(packageName))
                if (success) {
                    knoxApplied = true
                    Log.i(TAG, "[Knox] Added $packageName to Force Stop Blocklist")
                } else {
                    knoxError = "addPackagesToForceStopBlackList returned false"
                    Log.w(TAG, "[Knox] Failed to add $packageName to Force Stop Blocklist")
                }
            } catch (e: SecurityException) {
                val msg = e.message ?: "SecurityException"
                Log.e(TAG, "[Knox] Force Stop Blocklist SecurityException: $msg", e)
                knoxError = if (msg.contains("KNOX_APP_MGMT") || msg.contains("license", ignoreCase = true)) {
                    "Knox KPE license required for Knox API ($msg)"
                } else {
                    msg
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Knox] Force Stop Blocklist Exception", e)
                knoxError = e.message ?: e.javaClass.simpleName
            }
        } else {
            knoxError = "ApplicationPolicy unavailable"
        }

        // Post-transaction verification
        val verified = verifyForceStopProtection(packageName)
        if (verified.isConfirmedActive) {
            SecurityEventLogger.log(
                type = SecurityEventType.KNOX_POLICY_VERIFIED,
                details = "Force stop blocklist verified active for $packageName",
                component = "SamsungKnox",
                result = "ACTIVE",
                packageName = packageName
            )
        } else if (knoxError != null) {
            SecurityEventLogger.log(
                type = SecurityEventType.POLICY_APPLICATION_FAILED,
                details = "Force stop blocklist verification failed for $packageName: $knoxError",
                severity = SecurityEventSeverity.WARNING,
                component = "SamsungKnox",
                result = "FAILED",
                packageName = packageName
            )
        }

        return when {
            knoxApplied -> KnoxResult.Success("Force Stop protection active (Knox Blocklist for $packageName)")
            knoxError != null -> KnoxResult.Failed("Knox Force Stop blocklist not applied: $knoxError")
            else -> KnoxResult.Unsupported
        }
    }

    /**
     * Removes Force Stop protection for [packageName].
     */
    fun removeForceStopProtection(packageName: String): KnoxResult {
        val appPolicy = getApplicationPolicy()
        if (appPolicy != null) {
            try {
                appPolicy.removePackagesFromForceStopBlackList(listOf(packageName))
                Log.i(TAG, "[Knox] Removed $packageName from Force Stop Blocklist")
            } catch (e: Exception) {
                Log.w(TAG, "[Knox] Error removing from Force Stop Blocklist", e)
            }
        }
        clearGlobalRestrictions()
        return KnoxResult.Success("Force Stop protection removed for $packageName")
    }

    /**
     * Authoritatively verifies whether Force Stop protection is active for [packageName].
     * Only returns [PolicyStatus.Applied] if [packageName] is authoritatively in Knox's blocklist.
     */
    fun verifyForceStopProtection(packageName: String): PolicyStatus {
        val appPolicy = getApplicationPolicy()
        if (appPolicy != null) {
            try {
                val blocklist = appPolicy.packagesFromForceStopBlackList
                if (blocklist != null && blocklist.contains(packageName)) {
                    return PolicyStatus.Applied
                }
            } catch (e: SecurityException) {
                val msg = e.message ?: ""
                Log.d(TAG, "Knox Force Stop verify SecurityException: $msg")
                return PolicyStatus.LicenseRequired("Knox KPE license required for Force Stop blocklist")
            } catch (e: Exception) {
                Log.d(TAG, "Knox Force Stop verify Exception: ${e.message}")
            }
        }

        return PolicyStatus.NotApplied
    }

    // ========================================================================
    // 2. Uninstallation Protection (Package-Specific ONLY)
    // ========================================================================

    /**
     * Prevents [packageName] from being uninstalled via Knox and Android Enterprise DPM.
     *
     * Phase 7.1 Scope Enforcement:
     * - Policy is strictly scoped to [packageName] (App Lock: "com.f15.applock").
     * - Never blocks other user apps (WhatsApp, Instagram, Chrome, etc.).
     * - Never applies global user restrictions (DISALLOW_UNINSTALL_APPS, DISALLOW_APPS_CONTROL).
     */
    fun applyUninstallProtection(packageName: String): KnoxResult {
        // Pre-transaction validation
        val validation = PolicyTransactionValidator.validateSelfProtectionTarget(packageName)
        if (validation is PolicyValidationResult.Rejected) {
            return KnoxResult.SecurityError("Transaction rejected: ${validation.reason} (${validation.violation})")
        }

        // Ensure no global restrictions exist on the user
        clearGlobalRestrictions()

        var knoxSuccess = false
        var knoxError: String? = null

        // 1. Samsung Knox ApplicationPolicy (package-specific)
        val appPolicy = getApplicationPolicy()
        if (appPolicy != null) {
            try {
                appPolicy.setApplicationUninstallationDisabled(packageName)
                knoxSuccess = true
                Log.i(TAG, "[Knox] setApplicationUninstallationDisabled applied for $packageName")
            } catch (e: SecurityException) {
                val msg = e.message ?: "SecurityException"
                Log.e(TAG, "[Knox] Uninstall disable SecurityException: $msg", e)
                knoxError = if (msg.contains("KNOX_APP_MGMT") || msg.contains("license", ignoreCase = true)) {
                    "Knox KPE license required for Knox API ($msg)"
                } else {
                    msg
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Knox] Uninstall disable Exception", e)
                knoxError = e.message ?: e.javaClass.simpleName
            }
        }

        // 2. Android Enterprise DevicePolicyManager: setUninstallBlocked (package-specific)
        val dpmSuccess = try {
            dpm.setUninstallBlocked(adminComponent, packageName, true)
            Log.i(TAG, "[DPM] setUninstallBlocked(true) applied for $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[DPM] Failed to setUninstallBlocked for $packageName", e)
            false
        }

        // Post-transaction verification
        val verified = verifyUninstallProtection(packageName)
        if (verified.isConfirmedActive) {
            SecurityEventLogger.log(
                type = SecurityEventType.KNOX_POLICY_VERIFIED,
                details = "Uninstall protection verified active for $packageName",
                component = "SamsungKnox",
                result = "ACTIVE",
                packageName = packageName
            )
        } else {
            SecurityEventLogger.log(
                type = SecurityEventType.POLICY_APPLICATION_FAILED,
                details = "Uninstall protection could not be verified active for $packageName",
                severity = SecurityEventSeverity.WARNING,
                component = "SamsungKnox",
                result = "FAILED",
                packageName = packageName
            )
        }

        return when {
            knoxSuccess && dpmSuccess -> KnoxResult.Success("Uninstall protection active (Knox + Android DPM for $packageName)")
            dpmSuccess -> KnoxResult.Success("Uninstall protection active (Android DPM setUninstallBlocked for $packageName)" + if (knoxError != null) " (Knox API: $knoxError)" else "")
            knoxSuccess -> KnoxResult.Success("Uninstall protection active (Samsung Knox for $packageName)")
            knoxError != null -> KnoxResult.Failed("Uninstall protection failed: $knoxError")
            else -> KnoxResult.Unsupported
        }
    }

    /**
     * Removes uninstallation protection for [packageName].
     */
    fun removeUninstallProtection(packageName: String): KnoxResult {
        val appPolicy = getApplicationPolicy()
        if (appPolicy != null) {
            try {
                appPolicy.setApplicationUninstallationEnabled(packageName)
                Log.i(TAG, "[Knox] setApplicationUninstallationEnabled for $packageName")
            } catch (e: Exception) {
                Log.w(TAG, "[Knox] Error re-enabling uninstallation", e)
            }
        }
        try {
            dpm.setUninstallBlocked(adminComponent, packageName, false)
            Log.i(TAG, "[DPM] setUninstallBlocked(false) for $packageName")
        } catch (e: Exception) {
            Log.w(TAG, "[DPM] Error clearing setUninstallBlocked", e)
        }
        clearGlobalRestrictions()
        return KnoxResult.Success("Uninstall protection removed for $packageName")
    }

    /**
     * Authoritatively verifies whether uninstallation is blocked for [packageName].
     */
    fun verifyUninstallProtection(packageName: String): PolicyStatus {
        var knoxBlocked = false
        var dpmBlocked = false

        // Check Knox ApplicationPolicy
        val appPolicy = getApplicationPolicy()
        if (appPolicy != null) {
            try {
                // getApplicationUninstallationEnabled returns true if uninstallation is allowed.
                // False means uninstallation is blocked!
                val allowed = appPolicy.getApplicationUninstallationEnabled(packageName)
                if (!allowed) {
                    knoxBlocked = true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Knox getApplicationUninstallationEnabled query failed: ${e.message}")
            }
        }

        // Check Android Enterprise DPM (package-specific query)
        try {
            dpmBlocked = dpm.isUninstallBlocked(adminComponent, packageName)
        } catch (e: Exception) {
            Log.d(TAG, "DPM isUninstallBlocked query failed: ${e.message}")
        }

        return when {
            knoxBlocked || dpmBlocked -> PolicyStatus.Applied
            else -> PolicyStatus.NotApplied
        }
    }

    // ========================================================================
    // 3. Application Disabling Protection (Package-Specific ONLY)
    // ========================================================================

    /**
     * Ensures [packageName] cannot be disabled by user or background mechanisms.
     *
     * In Android Enterprise, the framework intrinsically disallows disabling active Device Owner
     * packages (throwing SecurityException: "Cannot disable a protected package").
     * Phase 7.1 Hardening: NEVER applies DISALLOW_APPS_CONTROL.
     */
    fun applyDisableProtection(packageName: String): KnoxResult {
        // Pre-transaction validation
        val validation = PolicyTransactionValidator.validateSelfProtectionTarget(packageName)
        if (validation is PolicyValidationResult.Rejected) {
            return KnoxResult.SecurityError("Transaction rejected: ${validation.reason} (${validation.violation})")
        }

        clearGlobalRestrictions()

        var knoxSuccess = false
        var knoxError: String? = null

        val appPolicy = getApplicationPolicy()
        if (appPolicy != null) {
            try {
                // setApplicationState(packageName, true) ensures application state is ENABLED
                val success = appPolicy.setApplicationState(packageName, true)
                if (success) {
                    knoxSuccess = true
                    Log.i(TAG, "[Knox] setApplicationState(true) applied for $packageName")
                } else {
                    knoxError = "setApplicationState returned false"
                }
            } catch (e: SecurityException) {
                val msg = e.message ?: ""
                Log.e(TAG, "[Knox] setApplicationState SecurityException: $msg", e)
                knoxError = if (msg.contains("KNOX_APP_MGMT") || msg.contains("license", ignoreCase = true)) {
                    "Knox KPE license required for Knox API ($msg)"
                } else {
                    msg
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Knox] setApplicationState Exception", e)
                knoxError = e.message ?: e.javaClass.simpleName
            }
        }

        val dpmProtected = dpm.isDeviceOwnerApp(context.packageName)

        // Post-transaction verification
        val verified = verifyDisableProtection(packageName)
        if (verified.isConfirmedActive) {
            SecurityEventLogger.log(
                type = SecurityEventType.KNOX_POLICY_VERIFIED,
                details = "Disable protection verified active for $packageName",
                component = "SamsungKnox",
                result = "ACTIVE",
                packageName = packageName
            )
        }

        return when {
            knoxSuccess -> KnoxResult.Success("Disable protection active (Samsung Knox for $packageName)")
            dpmProtected -> KnoxResult.Success("Disable protection active (Android Enterprise DO protection for $packageName)" + if (knoxError != null) " (Knox API: $knoxError)" else "")
            knoxError != null -> KnoxResult.Failed("Disable protection failed: $knoxError")
            else -> KnoxResult.Unsupported
        }
    }

    /**
     * Authoritatively verifies application enable/disable protection.
     */
    fun verifyDisableProtection(packageName: String): PolicyStatus {
        val appPolicy = getApplicationPolicy()
        if (appPolicy != null) {
            try {
                val isEnabled = appPolicy.getApplicationStateEnabled(packageName)
                if (isEnabled) {
                    return PolicyStatus.Applied
                }
            } catch (e: Exception) {
                Log.d(TAG, "Knox getApplicationStateEnabled query: ${e.message}")
            }
        }

        return if (dpm.isDeviceOwnerApp(packageName)) {
            PolicyStatus.Applied
        } else {
            PolicyStatus.NotApplied
        }
    }

    // ========================================================================
    // 4. Administrator Removability Protection
    // ========================================================================

    /**
     * Prevents administrator removal via Knox [EnterpriseDeviceManager.setAdminRemovable].
     */
    fun setAdminRemovable(removable: Boolean, packageName: String): KnoxResult {
        // Pre-transaction validation
        val validation = PolicyTransactionValidator.validateSelfProtectionTarget(packageName)
        if (validation is PolicyValidationResult.Rejected) {
            return KnoxResult.SecurityError("Transaction rejected: ${validation.reason} (${validation.violation})")
        }

        val edm = getEnterpriseDeviceManager()
        var knoxSuccess = false
        var knoxError: String? = null

        if (edm != null) {
            try {
                val success = edm.setAdminRemovable(removable, packageName)
                if (success) {
                    Log.i(TAG, "[Knox] setAdminRemovable($removable) succeeded for $packageName")
                    knoxSuccess = true
                } else {
                    Log.w(TAG, "[Knox] setAdminRemovable returned false")
                    knoxError = "setAdminRemovable returned false"
                }
            } catch (e: SecurityException) {
                val msg = e.message ?: ""
                Log.e(TAG, "[Knox] setAdminRemovable SecurityException: $msg", e)
                knoxError = if (msg.contains("KNOX_ENTERPRISE_DEVICE_ADMIN") || msg.contains("license", ignoreCase = true)) {
                    "Knox KPE license required for Knox API ($msg)"
                } else {
                    msg
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Knox] setAdminRemovable Exception", e)
                knoxError = e.message ?: e.javaClass.simpleName
            }
        }

        return when {
            knoxSuccess -> KnoxResult.Success("Admin removability set to $removable via Knox")
            dpm.isDeviceOwnerApp(context.packageName) -> KnoxResult.Success("Admin is non-removable (enforced by Android Enterprise Device Owner)" + if (knoxError != null) " (Knox API: $knoxError)" else "")
            knoxError != null -> KnoxResult.Failed(knoxError)
            else -> KnoxResult.Unsupported
        }
    }

    /**
     * Authoritatively queries whether the administrator is removable.
     */
    fun verifyAdminRemovable(packageName: String): PolicyStatus {
        val edm = getEnterpriseDeviceManager()
        if (edm != null) {
            try {
                // If getAdminRemovable returns false, admin removability is BLOCKED (Protected)
                val isRemovable = edm.getAdminRemovable(packageName)
                if (!isRemovable) {
                    return PolicyStatus.Applied
                }
            } catch (e: Exception) {
                Log.d(TAG, "Knox getAdminRemovable query: ${e.message}")
            }
        }

        // On Android Enterprise Device Owner, Android by default prohibits removing the Device Owner
        // without an explicit wipe / factory reset or adb command.
        return if (dpm.isDeviceOwnerApp(context.packageName)) {
            PolicyStatus.Applied
        } else {
            PolicyStatus.NotApplied
        }
    }

    // ========================================================================
    // 5. Battery / Background Protection
    // ========================================================================

    /**
     * Verifies battery optimization exemption status for [packageName].
     */
    fun verifyBatteryProtection(packageName: String): PolicyStatus {
        val isIgnoring = powerManager?.isIgnoringBatteryOptimizations(packageName) == true
        return if (isIgnoring) {
            PolicyStatus.Applied
        } else {
            PolicyStatus.NotApplied
        }
    }

    // ========================================================================
    // 6. Global Restriction Management & Verification (Phase 7.1)
    // ========================================================================

    /**
     * Explicitly clears any accidental global application restrictions
     * (DISALLOW_APPS_CONTROL, DISALLOW_UNINSTALL_APPS, DISALLOW_CONFIG_NOTIFICATIONS)
     * and resets keyguard/status bar restrictions to ensure normal user applications
     * (like WhatsApp) can receive notifications and can always be controlled.
     */
    fun clearGlobalRestrictions() {
        try {
            val restrictions = dpm.getUserRestrictions(adminComponent)
            for (key in restrictions.keySet()) {
                if (restrictions.getBoolean(key, false)) {
                    dpm.clearUserRestriction(adminComponent, key)
                    Log.i(TAG, "[DPM] Explicitly cleared active user restriction: $key")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[DPM] Error inspecting/clearing user restrictions", e)
        }
        try {
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_APPS_CONTROL)
        } catch (_: Exception) {}
        try {
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS)
        } catch (_: Exception) {}
        try {
            dpm.setKeyguardDisabledFeatures(adminComponent, 0)
            Log.i(TAG, "[DPM] Reset keyguard disabled features to 0 (all notifications allowed)")
        } catch (e: Exception) {
            Log.d(TAG, "[DPM] setKeyguardDisabledFeatures query/set: ${e.message}")
        }
        try {
            dpm.setStatusBarDisabled(adminComponent, false)
            Log.i(TAG, "[DPM] Ensured status bar & notification shade are enabled")
        } catch (e: Exception) {
            Log.d(TAG, "[DPM] setStatusBarDisabled query/set: ${e.message}")
        }
    }

    /**
     * Authoritatively verifies whether a global application uninstall restriction is active.
     * Desired state: [PolicyStatus.Disabled] (Uninstall ALLOWED for normal applications).
     */
    fun verifyGlobalUninstallRestriction(): PolicyStatus {
        return try {
            val userRestrictions = dpm.getUserRestrictions(adminComponent)
            val hasDisallowUninstall = userRestrictions.getBoolean(UserManager.DISALLOW_UNINSTALL_APPS, false)
            val hasDisallowAppsControl = userRestrictions.getBoolean(UserManager.DISALLOW_APPS_CONTROL, false)
            if (hasDisallowUninstall || hasDisallowAppsControl) {
                PolicyStatus.Applied // Overly broad restriction is active!
            } else {
                PolicyStatus.Disabled // Global restriction is disabled (normal behavior)
            }
        } catch (e: Exception) {
            PolicyStatus.Disabled
        }
    }

    /**
     * Authoritatively verifies whether a global Force Stop restriction is active.
     * Desired state: [PolicyStatus.Disabled] (Force Stop ALLOWED for normal applications).
     */
    fun verifyGlobalForceStopRestriction(): PolicyStatus {
        return try {
            val userRestrictions = dpm.getUserRestrictions(adminComponent)
            val hasDisallowAppsControl = userRestrictions.getBoolean(UserManager.DISALLOW_APPS_CONTROL, false)
            if (hasDisallowAppsControl) {
                PolicyStatus.Applied // Global force stop blocked!
            } else {
                PolicyStatus.Disabled // Global force stop restriction disabled (normal behavior)
            }
        } catch (e: Exception) {
            PolicyStatus.Disabled
        }
    }

    /**
     * Authoritatively verifies that no overly broad or global restrictions exist on the device.
     * Guarantees App Lock policies remain strictly package-isolated to "com.f15.applock".
     *
     * @return True if policy scope is clean and package-isolated; false if broad/global restrictions detected.
     */
    fun verifyScopeIntegrity(): Boolean {
        // 1. Check user restrictions for global blocks
        val restrictions = try { dpm.getUserRestrictions(adminComponent) } catch (e: Exception) { null }
        val restrictionCheck = PolicyTransactionValidator.validateNoGlobalRestrictions(restrictions)
        if (restrictionCheck is PolicyValidationResult.Rejected) {
            Log.e(TAG, "[AERA-SECURITY] Scope integrity failure: ${restrictionCheck.reason}")
            SecurityEventLogger.log(
                type = SecurityEventType.KNOX_POLICY_MISMATCH,
                details = "Policy scope integrity failure: ${restrictionCheck.violation}",
                severity = SecurityEventSeverity.CRITICAL,
                component = "PolicyValidator",
                result = "COMPROMISED"
            )
            return false
        }

        // 2. Check Knox force stop blocklist scope if available
        val appPolicy = getApplicationPolicy()
        if (appPolicy != null) {
            try {
                val blocklist = appPolicy.packagesFromForceStopBlackList
                if (blocklist != null) {
                    for (pkg in blocklist) {
                        if (pkg == "*" || pkg.contains("*") || (!pkg.equals(context.packageName, ignoreCase = true) && !pkg.startsWith("com.f15."))) {
                            Log.e(TAG, "[AERA-SECURITY] Unexpected package in Knox blocklist: $pkg")
                            SecurityEventLogger.log(
                                type = SecurityEventType.KNOX_POLICY_MISMATCH,
                                details = "Knox force stop blocklist contains unexpected package: $pkg",
                                severity = SecurityEventSeverity.CRITICAL,
                                component = "SamsungKnox",
                                result = "COMPROMISED",
                                packageName = pkg
                            )
                            return false
                        }
                    }
                }
            } catch (e: Exception) {
                // License or query limitation
            }
        }
        return true
    }
}
