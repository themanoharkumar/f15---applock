package com.f15.applock.device

import android.content.Context
import android.util.Log
import com.f15.applock.security.SecurityEventLogger
import com.f15.applock.security.SecurityEventSeverity
import com.f15.applock.security.SecurityEventType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central device-management layer coordinating Device Owner / Device Admin operations.
 *
 * Architecture:
 * ```
 * Device Policy Layer
 *         │
 *         ▼
 * DeviceOwnerManager  ←─ single management entry point
 *         │
 *         ├── DevicePolicyManagerWrapper  ←─ platform queries
 *         └── DeviceSecurityPolicy[]      ←─ future policy implementations
 * ```
 *
 * This class:
 * - Queries the platform for authoritative device-management state
 * - Exposes a reactive [StateFlow] for UI observation
 * - Manages the lifecycle of [DeviceSecurityPolicy] implementations
 * - Reconciles cached state with platform truth on every query
 *
 * It does NOT:
 * - Store device-management state as the source of truth
 * - Initiate provisioning automatically
 * - Execute destructive operations (wipe, factory reset)
 */
sealed class DeviceOwnerIntegrityResult {
    data object VerifiedActive : DeviceOwnerIntegrityResult()
    data object NotProvisioned : DeviceOwnerIntegrityResult()
    data class Lost(val message: String) : DeviceOwnerIntegrityResult()
    data class IdentityMismatch(val expected: String, val actual: String) : DeviceOwnerIntegrityResult()

    val isActive: Boolean get() = this is VerifiedActive
}

class DeviceOwnerManager private constructor(
    private val context: Context,
    private val wrapper: DevicePolicyManagerWrapper
) {

    private val doHistoryFile by lazy {
        java.io.File(context.noBackupFilesDir, "do_provisioned_flag.bin")
    }

    companion object {
        private const val TAG = "DeviceOwnerManager"

        @Volatile
        private var INSTANCE: DeviceOwnerManager? = null

        fun getInstance(context: Context): DeviceOwnerManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appContext = context.applicationContext
                    val wrapper = DevicePolicyManagerWrapper(appContext)
                    DeviceOwnerManager(appContext, wrapper).also { INSTANCE = it }
                }
            }
        }
    }

    private val _state = MutableStateFlow<DevicePolicyState>(DevicePolicyState.NotProvisioned)

    /**
     * Observable device-management state.
     * Always reflects the last [refreshState] result.
     */
    val state: StateFlow<DevicePolicyState> = _state.asStateFlow()

    /**
     * Registered security policies.
     * Populated in future phases as concrete policies are implemented.
     */
    private val policies = mutableListOf<DeviceSecurityPolicy>()

    init {
        // Query platform state on initialization
        refreshState()
    }

    /**
     * Queries the platform [DevicePolicyManager] and updates the observable state.
     *
     * This is the reconciliation point: DataStore or UI cached state is overridden
     * by whatever the platform reports.
     *
     * @return The current authoritative [DevicePolicyState].
     */
    fun refreshState(): DevicePolicyState {
        val platformState = wrapper.queryState()
        _state.value = platformState
        Log.i(TAG, "[AERA/DevicePolicy] State refreshed: ${platformState.displayLabel}")
        return platformState
    }

    /**
     * Returns `true` if this application is currently the Device Owner.
     *
     * Always queries the platform — never a cached flag.
     */
    fun isDeviceOwner(): Boolean = wrapper.isDeviceOwner()

    /**
     * Returns `true` if this application is an active Device Admin.
     */
    fun isDeviceAdminActive(): Boolean = wrapper.isDeviceAdminActive()

    /**
     * Returns diagnostic information for the verification screen.
     */
    fun getDiagnostics(): DeviceManagementDiagnostics = wrapper.getDiagnosticInfo()

    /**
     * Returns the underlying wrapper for policy implementations that need
     * direct access to [android.app.admin.DevicePolicyManager].
     */
    fun getWrapper(): DevicePolicyManagerWrapper = wrapper

    /**
     * Registers a [DeviceSecurityPolicy] for management.
     */
    fun registerPolicy(policy: DeviceSecurityPolicy) {
        if (policies.none { it.name == policy.name }) {
            policies.add(policy)
            Log.i(TAG, "Policy registered: ${policy.name}")
        }
    }

    /**
     * Returns the list of registered policies and their current status.
     */
    fun getPolicyStatuses(): List<Pair<String, Boolean>> {
        return policies.map { it.name to it.isApplied() }
    }

    /**
     * Applies all registered policies.
     * Only effective when Device Owner is active.
     *
     * @return Number of policies successfully applied.
     */
    fun applyAllPolicies(): Int {
        val currentState = refreshState()
        if (!currentState.isDeviceOwner) {
            Log.w(TAG, "Cannot apply policies: not Device Owner (state=${currentState.displayLabel})")
            return 0
        }

        var applied = 0
        for (policy in policies) {
            try {
                if (policy.apply()) {
                    applied++
                    Log.i(TAG, "Policy applied: ${policy.name}")
                } else {
                    Log.w(TAG, "Policy failed to apply: ${policy.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error applying policy: ${policy.name}", e)
            }
        }
        return applied
    }

    /**
     * Removes all registered policies.
     *
     * @return Number of policies successfully removed.
     */
    fun removeAllPolicies(): Int {
        var removed = 0
        for (policy in policies) {
            try {
                if (policy.remove()) {
                    removed++
                    Log.i(TAG, "Policy removed: ${policy.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing policy: ${policy.name}", e)
            }
        }
        return removed
    }

    /**
     * Authoritatively verifies Device Owner state against the platform truth
     * and performs package identity validation.
     */
    fun verifyDeviceOwnerIntegrity(): DeviceOwnerIntegrityResult {
        // 1. Verify package identity
        val expectedPackage = "com.f15.applock"
        val actualPackage = context.packageName
        if (!actualPackage.equals(expectedPackage, ignoreCase = true)) {
            Log.e(TAG, "[AERA-SECURITY] Package identity violation! Expected $expectedPackage, found $actualPackage")
            SecurityEventLogger.log(
                type = SecurityEventType.DEVICE_OWNER_LOST,
                details = "Package identity mismatch: runtime package '$actualPackage' does not match expected '$expectedPackage'",
                severity = SecurityEventSeverity.CRITICAL,
                component = "DeviceOwner",
                result = "COMPROMISED"
            )
            return DeviceOwnerIntegrityResult.IdentityMismatch(expectedPackage, actualPackage)
        }

        // 2. Authoritative platform query
        val isDO = wrapper.isDeviceOwner()
        if (isDO) {
            // Record persistent flag that this device was successfully provisioned as DO
            try {
                if (!doHistoryFile.exists()) {
                    doHistoryFile.writeText("PROVISIONED", Charsets.UTF_8)
                }
            } catch (e: Exception) {
                // Ignore file write error
            }

            SecurityEventLogger.log(
                type = SecurityEventType.DEVICE_OWNER_VERIFIED,
                details = "Device Owner integrity verified: $actualPackage is active Device Owner",
                severity = SecurityEventSeverity.INFO,
                component = "DeviceOwner",
                result = "ACTIVE",
                packageName = actualPackage
            )
            return DeviceOwnerIntegrityResult.VerifiedActive
        }

        // 3. If not DO, check if it was previously provisioned
        val wasProvisioned = try { doHistoryFile.exists() } catch (e: Exception) { false }
        if (wasProvisioned) {
            Log.e(TAG, "[AERA-SECURITY] Device Owner unexpectedly lost! Application was previously provisioned as DO.")
            SecurityEventLogger.log(
                type = SecurityEventType.DEVICE_OWNER_LOST,
                details = "Device Owner privilege was unexpectedly lost or revoked by the platform",
                severity = SecurityEventSeverity.CRITICAL,
                component = "DeviceOwner",
                result = "LOST",
                packageName = actualPackage
            )
            return DeviceOwnerIntegrityResult.Lost("Device Owner privilege was unexpectedly lost or revoked")
        }

        return DeviceOwnerIntegrityResult.NotProvisioned
    }

    /**
     * Called on application start and after reboot to reconcile state.
     *
     * 1. Queries platform for the real Device Owner state.
     * 2. Updates the observable flow.
     * 3. Verifies Device Owner integrity.
     * 4. Does NOT trust any cached/persisted flag.
     */
    fun reconcileOnStartup() {
        val state = refreshState()
        Log.i(TAG, "[AERA/DevicePolicy] Startup reconciliation: ${state.displayLabel}")
        verifyDeviceOwnerIntegrity()
        try {
            com.f15.applock.knox.KnoxManagerImpl.getInstance(context).reconcilePolicies(context.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Error reconciling Knox policies on startup", e)
        }
    }
}
