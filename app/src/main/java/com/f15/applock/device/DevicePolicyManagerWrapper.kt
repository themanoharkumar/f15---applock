package com.f15.applock.device

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Thin wrapper around Android's [DevicePolicyManager] providing type-safe queries
 * for the App Lock device-management state.
 *
 * This class is the ONLY place that calls [DevicePolicyManager] directly.
 * All other components must go through [DeviceOwnerManager] which delegates here.
 *
 * The source of truth for device-management state is always the platform —
 * never DataStore or cached flags.
 */
class DevicePolicyManagerWrapper(private val context: Context) {

    companion object {
        private const val TAG = "DevicePolicyWrapper"
    }

    private val dpm: DevicePolicyManager by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    /**
     * The [ComponentName] of our [AppLockDeviceAdminReceiver].
     */
    val adminComponent: ComponentName by lazy {
        ComponentName(context, AppLockDeviceAdminReceiver::class.java)
    }

    /**
     * Queries the actual platform state and returns the authoritative [DevicePolicyState].
     *
     * This method NEVER returns a cached result.
     */
    fun queryState(): DevicePolicyState {
        return try {
            when {
                isDeviceOwner() -> DevicePolicyState.DeviceOwnerActive
                isDeviceAdminActive() -> DevicePolicyState.DeviceAdminActive
                else -> DevicePolicyState.NotProvisioned
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query device policy state", e)
            DevicePolicyState.NotProvisioned
        }
    }

    /**
     * Checks whether this application is the Device Owner via the platform API.
     */
    fun isDeviceOwner(): Boolean {
        return try {
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check Device Owner status", e)
            false
        }
    }

    /**
     * Checks whether this application is an active Device Admin.
     */
    fun isDeviceAdminActive(): Boolean {
        return try {
            dpm.isAdminActive(adminComponent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check Device Admin status", e)
            false
        }
    }

    /**
     * Returns the package name of the current Device Owner, if any.
     * Since `getDeviceOwner()` was removed in newer API levels, we check
     * if our own package is the device owner and return accordingly.
     */
    fun getDeviceOwnerPackage(): String? {
        return try {
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                context.packageName
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Device Owner package", e)
            null
        }
    }

    /**
     * Returns the underlying [DevicePolicyManager] for advanced policy operations.
     *
     * Should only be used by [DeviceOwnerManager] and concrete [DeviceSecurityPolicy]
     * implementations — never by UI or business logic directly.
     */
    fun getDevicePolicyManager(): DevicePolicyManager = dpm

    /**
     * Returns diagnostic information about the device management state.
     */
    fun getDiagnosticInfo(): DeviceManagementDiagnostics {
        return DeviceManagementDiagnostics(
            packageName = context.packageName,
            adminComponent = adminComponent.flattenToShortString(),
            isDeviceOwner = isDeviceOwner(),
            isDeviceAdmin = isDeviceAdminActive(),
            deviceOwnerPackage = getDeviceOwnerPackage(),
            apiLevel = Build.VERSION.SDK_INT,
            androidVersion = Build.VERSION.RELEASE,
            appVersionName = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            },
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            state = queryState()
        )
    }
}

/**
 * Diagnostic data for the Device Management verification screen.
 */
data class DeviceManagementDiagnostics(
    val packageName: String,
    val adminComponent: String,
    val isDeviceOwner: Boolean,
    val isDeviceAdmin: Boolean,
    val deviceOwnerPackage: String?,
    val apiLevel: Int,
    val androidVersion: String,
    val appVersionName: String,
    val deviceModel: String,
    val state: DevicePolicyState
)
