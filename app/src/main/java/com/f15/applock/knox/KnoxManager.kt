package com.f15.applock.knox

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.f15.applock.device.AppLockDeviceAdminReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central Knox management contract.
 */
interface KnoxManager {
    fun isAvailable(): Boolean
    fun isDeviceOwner(): Boolean
    fun getSdkVersion(): Int?
    fun getKnoxVersionString(): String?
    fun getCapabilities(): KnoxCapability
    fun applyAppProtection(packageName: String): KnoxResult
    fun removeAppProtection(packageName: String): KnoxResult
    fun getAppProtectionStatus(packageName: String): KnoxPolicyState
    fun reconcilePolicies(packageName: String): KnoxPolicyState
}

/**
 * Singleton implementation of [KnoxManager].
 *
 * Coordinates between Samsung Knox SDK and Android Enterprise Device Owner.
 * Refuses privileged operations unless Device Owner is actively confirmed.
 */
class KnoxManagerImpl private constructor(private val context: Context) : KnoxManager {

    companion object {
        private const val TAG = "KnoxManager"

        @Volatile
        private var INSTANCE: KnoxManagerImpl? = null

        fun getInstance(context: Context): KnoxManagerImpl {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: KnoxManagerImpl(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val dpm by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    private val adminComponent by lazy {
        ComponentName(context, AppLockDeviceAdminReceiver::class.java)
    }

    private val applicationPolicy by lazy {
        KnoxApplicationPolicy(context, dpm, adminComponent)
    }

    private val _policyState = MutableStateFlow(KnoxPolicyState())
    val policyState: StateFlow<KnoxPolicyState> = _policyState.asStateFlow()

    init {
        // Initial capability query
        refreshStatus(context.packageName)
    }

    override fun isAvailable(): Boolean {
        return try {
            val pm = context.packageManager
            pm.hasSystemFeature("com.samsung.android.knox.knoxsdk") ||
                    applicationPolicy.getEnterpriseDeviceManager() != null
        } catch (e: Exception) {
            false
        }
    }

    override fun isDeviceOwner(): Boolean {
        return try {
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    override fun getSdkVersion(): Int? {
        return try {
            val pm = context.packageManager
            // Check Knox API levels from 40 down to 30
            for (level in 40 downTo 30) {
                if (pm.hasSystemFeature("com.samsung.android.knox.knoxsdk.api.level.$level")) {
                    return level
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    override fun getKnoxVersionString(): String? {
        return try {
            val apiLevel = getSdkVersion()
            when (apiLevel) {
                40 -> "Knox 3.11 (API 40)"
                39 -> "Knox 3.10 (API 39)"
                38 -> "Knox 3.9 (API 38)"
                37 -> "Knox 3.8 (API 37)"
                36 -> "Knox 3.7.1 (API 36)"
                35 -> "Knox 3.7 (API 35)"
                34 -> "Knox 3.6 (API 34)"
                33 -> "Knox 3.5 (API 33)"
                else -> if (isAvailable()) "Knox Active (API $apiLevel)" else null
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun getCapabilities(): KnoxCapability {
        val available = isAvailable()
        val doActive = isDeviceOwner()
        val sdkVersion = getSdkVersion()
        val versionStr = getKnoxVersionString()
        val edmAvailable = applicationPolicy.getEnterpriseDeviceManager() != null
        val appPolicyAvailable = applicationPolicy.getApplicationPolicy() != null

        return KnoxCapability(
            isKnoxSupported = available,
            knoxVersionString = versionStr,
            knoxApiLevel = sdkVersion,
            standardSdkVersion = "6.13",
            premiumSdkVersion = "3.13",
            isEnterpriseDeviceManagerAvailable = edmAvailable,
            isApplicationPolicyAvailable = appPolicyAvailable,
            isDeviceOwnerActive = doActive
        )
    }

    override fun applyAppProtection(packageName: String): KnoxResult {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Cannot apply Knox protection: application is not Device Owner")
            return KnoxResult.NotDeviceOwner
        }

        Log.i(TAG, "[KnoxManager] Applying application protection to $packageName")

        val forceStopResult = applicationPolicy.applyForceStopProtection(packageName)
        val uninstallResult = applicationPolicy.applyUninstallProtection(packageName)
        val disableResult = applicationPolicy.applyDisableProtection(packageName)
        val adminRemovableResult = applicationPolicy.setAdminRemovable(false, packageName)

        // Query platform truth after application
        val updatedState = refreshStatus(packageName)
        _policyState.value = updatedState

        return when {
            forceStopResult.isSuccess || uninstallResult.isSuccess || disableResult.isSuccess -> {
                KnoxResult.Success("App protection policies applied for $packageName")
            }
            forceStopResult is KnoxResult.LicenseRequired -> forceStopResult
            uninstallResult is KnoxResult.LicenseRequired -> uninstallResult
            else -> KnoxResult.Failed("Policy application failed: forceStop=$forceStopResult, uninstall=$uninstallResult")
        }
    }

    override fun removeAppProtection(packageName: String): KnoxResult {
        Log.i(TAG, "[KnoxManager] Removing application protection for $packageName")
        applicationPolicy.removeForceStopProtection(packageName)
        applicationPolicy.removeUninstallProtection(packageName)
        applicationPolicy.setAdminRemovable(true, packageName)

        val updatedState = refreshStatus(packageName)
        _policyState.value = updatedState

        return KnoxResult.Success("App protection policies removed for $packageName")
    }

    override fun getAppProtectionStatus(packageName: String): KnoxPolicyState {
        return refreshStatus(packageName)
    }

    override fun reconcilePolicies(packageName: String): KnoxPolicyState {
        if (!isDeviceOwner()) {
            Log.d(TAG, "Reconcile skipped: not Device Owner")
            return refreshStatus(packageName)
        }

        val currentState = refreshStatus(packageName)

        // Reapply only if expected protection is missing
        if (!currentState.forceStopProtection.isConfirmedActive ||
            !currentState.uninstallProtection.isConfirmedActive ||
            !currentState.disableProtection.isConfirmedActive
        ) {
            Log.i(TAG, "[KnoxManager] Reconciling: re-asserting protection policies for $packageName")
            applyAppProtection(packageName)
        } else {
            Log.d(TAG, "[KnoxManager] Reconcile: all policies are already active and verified for $packageName")
        }

        return refreshStatus(packageName)
    }

    private fun refreshStatus(packageName: String): KnoxPolicyState {
        val available = isAvailable()
        val doActive = isDeviceOwner()
        val version = getKnoxVersionString()
        val apiLevel = getSdkVersion()

        val forceStop = applicationPolicy.verifyForceStopProtection(packageName)
        val uninstall = applicationPolicy.verifyUninstallProtection(packageName)
        val disable = applicationPolicy.verifyDisableProtection(packageName)
        val adminRemovable = applicationPolicy.verifyAdminRemovable(packageName)
        val battery = applicationPolicy.verifyBatteryProtection(packageName)

        val state = KnoxPolicyState(
            isKnoxAvailable = available,
            knoxVersion = version,
            knoxApiLevel = apiLevel,
            isDeviceOwner = doActive,
            forceStopProtection = forceStop,
            uninstallProtection = uninstall,
            disableProtection = disable,
            adminRemovableProtection = adminRemovable,
            batteryProtection = battery
        )

        _policyState.value = state
        return state
    }
}
