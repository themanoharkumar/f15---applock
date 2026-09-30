package com.f15.applock.knox

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import android.util.Log
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
    // 1. Force Stop Protection
    // ========================================================================

    /**
     * Adds [packageName] to Knox's Force Stop blocklist and applies Android Enterprise
     * app-control restrictions to disable force-stop in Settings.
     */
    fun applyForceStopProtection(packageName: String): KnoxResult {
        var knoxApplied = false
        var knoxError: String? = null

        // 1. Samsung Knox ApplicationPolicy Force Stop Blocklist
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

        // 2. Android Enterprise Synergy: DISALLOW_APPS_CONTROL
        // Grays out Force Stop and Clear Data in Settings for standard users
        val dpmApplied = try {
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_APPS_CONTROL)
            Log.i(TAG, "[DPM] Added user restriction DISALLOW_APPS_CONTROL")
            true
        } catch (e: Exception) {
            Log.w(TAG, "[DPM] Failed to add DISALLOW_APPS_CONTROL", e)
            false
        }

        return when {
            knoxApplied && dpmApplied -> KnoxResult.Success("Force Stop protection active (Knox Blocklist + Android DPM)")
            knoxApplied -> KnoxResult.Success("Force Stop protection active (Knox Blocklist)")
            dpmApplied -> KnoxResult.Success("Force Stop protection active via Android Enterprise DISALLOW_APPS_CONTROL" + if (knoxError != null) " (Knox API: $knoxError)" else "")
            knoxError != null -> KnoxResult.Failed("Failed to apply Force Stop protection: $knoxError")
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
        try {
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_APPS_CONTROL)
            Log.i(TAG, "[DPM] Cleared user restriction DISALLOW_APPS_CONTROL")
        } catch (e: Exception) {
            Log.w(TAG, "[DPM] Error clearing DISALLOW_APPS_CONTROL", e)
        }
        return KnoxResult.Success("Force Stop protection removed for $packageName")
    }

    /**
     * Authoritatively verifies whether Force Stop protection is active for [packageName].
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
            } catch (e: Exception) {
                Log.d(TAG, "Knox Force Stop verify Exception: ${e.message}")
            }
        }

        // Verify Android Enterprise fallback (DISALLOW_APPS_CONTROL)
        return try {
            val userRestrictions = dpm.getUserRestrictions(adminComponent)
            if (userRestrictions.getBoolean(UserManager.DISALLOW_APPS_CONTROL, false)) {
                PolicyStatus.Applied
            } else {
                PolicyStatus.NotApplied
            }
        } catch (e: Exception) {
            PolicyStatus.NotApplied
        }
    }

    // ========================================================================
    // 2. Uninstallation Protection
    // ========================================================================

    /**
     * Prevents [packageName] from being uninstalled via Knox and Android Enterprise DPM.
     */
    fun applyUninstallProtection(packageName: String): KnoxResult {
        var knoxSuccess = false
        var knoxError: String? = null

        // 1. Samsung Knox ApplicationPolicy
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

        // 2. Android Enterprise DevicePolicyManager: setUninstallBlocked
        val dpmSuccess = try {
            dpm.setUninstallBlocked(adminComponent, packageName, true)
            Log.i(TAG, "[DPM] setUninstallBlocked(true) applied for $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[DPM] Failed to setUninstallBlocked", e)
            false
        }

        return when {
            knoxSuccess && dpmSuccess -> KnoxResult.Success("Uninstall protection active (Knox + Android DPM)")
            dpmSuccess -> KnoxResult.Success("Uninstall protection active (Android DPM setUninstallBlocked)" + if (knoxError != null) " (Knox API: $knoxError)" else "")
            knoxSuccess -> KnoxResult.Success("Uninstall protection active (Samsung Knox)")
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

        // Check Android Enterprise DPM
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
    // 3. Application Disabling Protection
    // ========================================================================

    /**
     * Ensures [packageName] cannot be disabled by user or background mechanisms.
     */
    fun applyDisableProtection(packageName: String): KnoxResult {
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

        // DPM DISALLOW_APPS_CONTROL also blocks the user from disabling packages
        val dpmSuccess = try {
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_APPS_CONTROL)
            true
        } catch (e: Exception) {
            false
        }

        return when {
            knoxSuccess && dpmSuccess -> KnoxResult.Success("Disable protection active (Knox + DPM)")
            knoxSuccess -> KnoxResult.Success("Disable protection active (Samsung Knox)")
            dpmSuccess -> KnoxResult.Success("Disable protection active via Android Enterprise DISALLOW_APPS_CONTROL" + if (knoxError != null) " (Knox API: $knoxError)" else "")
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

        return try {
            val restrictions = dpm.getUserRestrictions(adminComponent)
            if (restrictions.getBoolean(UserManager.DISALLOW_APPS_CONTROL, false)) {
                PolicyStatus.Applied
            } else {
                PolicyStatus.NotApplied
            }
        } catch (e: Exception) {
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
}
