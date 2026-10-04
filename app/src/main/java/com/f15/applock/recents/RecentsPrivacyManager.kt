package com.f15.applock.recents

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import com.f15.applock.data.storage.AppLockPreferences
import com.f15.applock.device.AppLockDeviceAdminReceiver
import com.f15.applock.knox.KnoxManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Central manager coordinating Recent Apps task preview protection and platform privacy capabilities.
 *
 * Architecture:
 * 1. Self-Protection: AppLock's own authentication screens ([com.f15.applock.ui.activity.LockScreenActivity])
 *    and dashboard ([com.f15.applock.MainActivity]) enforce `FLAG_SECURE` and `setRecentsScreenshotEnabled(false)`
 *    to prevent any leakage of PIN, biometric, or configuration data in Recents previews.
 * 2. Third-Party Managed Applications: Android OS isolates window flags (FLAG_SECURE) within
 *    the application process sandbox. Neither Android DevicePolicyManager nor Samsung Knox
 *    ApplicationPolicy provides an official API to set window flags or mask snapshots for an
 *    arbitrary external package without globally disabling screen capture across all applications.
 * 3. Step 7 Safety Invariant: Does NOT apply device-wide screen capture restrictions globally,
 *    ensuring that unprotected apps and system utilities remain completely unaffected.
 */
class RecentsPrivacyManager private constructor(
    private val context: Context,
    private val preferences: AppLockPreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    companion object {
        private const val TAG = "RecentsPrivacyMgr"

        @Volatile
        private var INSTANCE: RecentsPrivacyManager? = null

        fun getInstance(context: Context): RecentsPrivacyManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appContext = context.applicationContext
                    val prefs = AppLockPreferences(appContext)
                    RecentsPrivacyManager(appContext, prefs).also { INSTANCE = it }
                }
            }
        }
    }

    private val dpm by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    private val adminComponent by lazy {
        ComponentName(context, AppLockDeviceAdminReceiver::class.java)
    }

    val policy = RecentsPrivacyPolicy(context, dpm, adminComponent)

    private val _capabilityFlow = MutableStateFlow(evaluateCapability())
    val capabilityFlow: StateFlow<RecentsPrivacyCapability> = _capabilityFlow.asStateFlow()

    val capability: RecentsPrivacyCapability
        get() = _capabilityFlow.value

    init {
        // Step 6: Dynamically reconcile privacy policy whenever the user updates protectedPackages
        scope.launch {
            preferences.lockedPackagesFlow.collect { lockedSet ->
                policy.reconcile(lockedSet)
                _capabilityFlow.value = evaluateCapability()
            }
        }
    }

    /**
     * Evaluates the device platform and Knox capabilities for Recents privacy.
     */
    fun evaluateCapability(): RecentsPrivacyCapability {
        val isDeviceOwner = try {
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (e: Exception) {
            false
        }

        // AppLock's own activities enforce FLAG_SECURE and disable recents snapshots
        val isSelfProtectionActive = true

        // Global DPM screen capture capability exists if Device Owner is active,
        // but applying it globally to all apps would violate Step 3 & Step 7.
        val isDpmGlobalSupported = isDeviceOwner

        // Samsung Knox verification on this device
        val knoxManager = try {
            KnoxManagerImpl.getInstance(context)
        } catch (e: Exception) {
            null
        }
        val isKnoxActive = knoxManager?.isAvailable() == true

        // Neither AOSP nor Knox ApplicationPolicy provides per-package FLAG_SECURE
        // or snapshot masking for 3rd-party apps in User 0.
        val isPerPackageThirdPartySupported = false
        val isKnoxPerPackageSupported = false

        val status = if (isDeviceOwner) {
            RecentsPrivacyStatus.PLATFORM_LIMITED
        } else {
            RecentsPrivacyStatus.UNSUPPORTED
        }

        val explanation = when (status) {
            RecentsPrivacyStatus.SUPPORTED -> {
                "Per-package Recents snapshot masking is officially supported."
            }
            RecentsPrivacyStatus.PLATFORM_LIMITED -> {
                "Android process isolation prohibits external modification of third-party window flags. " +
                "AppLock screens (PIN, Auth, Dashboard) are 100% protected via FLAG_SECURE and disabled task snapshots."
            }
            RecentsPrivacyStatus.DEGRADED -> {
                "Recents privacy policy is degraded."
            }
            RecentsPrivacyStatus.UNSUPPORTED -> {
                "Device Owner authority is required for device management policies."
            }
        }

        return RecentsPrivacyCapability(
            isSelfProtectionActive = isSelfProtectionActive,
            isPerPackageThirdPartySupported = isPerPackageThirdPartySupported,
            isKnoxPerPackageSupported = isKnoxPerPackageSupported,
            isDpmGlobalSupported = isDpmGlobalSupported,
            status = status,
            explanation = explanation
        )
    }

    /**
     * Re-evaluates capability status and publishes updates to observers.
     */
    fun refresh() {
        _capabilityFlow.value = evaluateCapability()
    }
}
