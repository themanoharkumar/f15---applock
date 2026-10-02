package com.f15.applock.knox

/**
 * Verification state of an individual security policy.
 */
sealed class PolicyStatus {
    data object Applied : PolicyStatus()
    data object Disabled : PolicyStatus()
    data object NotApplied : PolicyStatus()
    data object Unknown : PolicyStatus()
    data class Failed(val reason: String) : PolicyStatus()
    data class Unsupported(val reason: String) : PolicyStatus()
    data class LicenseRequired(val details: String) : PolicyStatus()

    val isConfirmedActive: Boolean get() = this is Applied

    val displayLabel: String
        get() = when (this) {
            is Applied -> "Active"
            is Disabled -> "Disabled"
            is NotApplied -> "Inactive"
            is Unknown -> "Unknown"
            is Failed -> "Failed ($reason)"
            is Unsupported -> "Unsupported"
            is LicenseRequired -> "License Required"
        }
}

/**
 * Composite state model representing all Samsung Knox & Device Owner application protection policies.
 *
 * Source of truth is always verified live via platform / Knox queries.
 * Phase 7.1: Explicitly tracks package-level self-protection vs global device restrictions.
 */
data class KnoxPolicyState(
    val isKnoxAvailable: Boolean = false,
    val knoxVersion: String? = null,
    val knoxApiLevel: Int? = null,
    val isDeviceOwner: Boolean = false,
    val forceStopProtection: PolicyStatus = PolicyStatus.NotApplied,
    val globalForceStopRestriction: PolicyStatus = PolicyStatus.Disabled,
    val uninstallProtection: PolicyStatus = PolicyStatus.NotApplied,
    val globalUninstallRestriction: PolicyStatus = PolicyStatus.Disabled,
    val disableProtection: PolicyStatus = PolicyStatus.NotApplied,
    val adminRemovableProtection: PolicyStatus = PolicyStatus.NotApplied,
    val batteryProtection: PolicyStatus = PolicyStatus.NotApplied
) {
    val isAnyPolicyActive: Boolean
        get() = forceStopProtection.isConfirmedActive ||
                uninstallProtection.isConfirmedActive ||
                disableProtection.isConfirmedActive ||
                adminRemovableProtection.isConfirmedActive ||
                batteryProtection.isConfirmedActive
}

