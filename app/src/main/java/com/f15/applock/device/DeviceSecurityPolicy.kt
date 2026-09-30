package com.f15.applock.device

/**
 * Clean policy abstraction for future device-management policies.
 *
 * Each concrete policy (e.g., UninstallProtectionPolicy, ForceStopProtectionPolicy)
 * will implement this interface and operate through the [DeviceOwnerManager].
 *
 * Phase 6 establishes the architecture only. Concrete policy implementations
 * will be added in Phase 7 (Samsung Knox) and beyond.
 */
interface DeviceSecurityPolicy {

    /**
     * Human-readable name of this policy for diagnostics and UI display.
     */
    val name: String

    /**
     * Applies this management policy using the active DPC.
     *
     * @return `true` if the policy was successfully applied, `false` otherwise.
     * @throws SecurityException if the required admin/owner privilege is not held.
     */
    fun apply(): Boolean

    /**
     * Removes this management policy.
     *
     * @return `true` if the policy was successfully removed, `false` otherwise.
     */
    fun remove(): Boolean

    /**
     * Checks whether this policy is currently applied and enforced.
     *
     * The check must query the platform, not cached state.
     */
    fun isApplied(): Boolean
}
