package com.f15.applock.device

import android.content.Context
import android.util.Log
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
class DeviceOwnerManager private constructor(
    private val context: Context,
    private val wrapper: DevicePolicyManagerWrapper
) {

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
     * Called on application start and after reboot to reconcile state.
     *
     * 1. Queries platform for the real Device Owner state.
     * 2. Updates the observable flow.
     * 3. Does NOT trust any cached/persisted flag.
     */
    fun reconcileOnStartup() {
        val state = refreshState()
        Log.i(TAG, "[AERA/DevicePolicy] Startup reconciliation: ${state.displayLabel}")
    }
}
