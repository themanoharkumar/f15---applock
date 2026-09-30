package com.f15.applock.device

/**
 * Represents the device-management provisioning state of the App Lock application.
 *
 * The source of truth is always [android.app.admin.DevicePolicyManager] — never cached
 * DataStore values. DataStore may cache the last known state for display optimization,
 * but [DevicePolicyManagerWrapper.queryState] must be authoritative.
 */
sealed class DevicePolicyState {

    /**
     * The application has not been provisioned as a Device Admin or Device Owner.
     * This is the default state after installation.
     */
    data object NotProvisioned : DevicePolicyState()

    /**
     * The application is an active Device Admin but NOT the Device Owner.
     * Limited management policies are available.
     */
    data object DeviceAdminActive : DevicePolicyState()

    /**
     * The application is the Device Owner (full DPC).
     * All supported device-management policies are available.
     */
    data object DeviceOwnerActive : DevicePolicyState()

    /**
     * Device Owner provisioning is unavailable on this device/configuration.
     * Possible reasons: another DPC already owns the device, device is already
     * set up with user accounts, or the Android version does not support it.
     */
    data class DeviceOwnerUnavailable(val reason: String) : DevicePolicyState()

    /**
     * Human-readable label for UI display.
     */
    val displayLabel: String
        get() = when (this) {
            is NotProvisioned -> "Not Provisioned"
            is DeviceAdminActive -> "Device Admin Active"
            is DeviceOwnerActive -> "Device Owner Active"
            is DeviceOwnerUnavailable -> "Device Owner Unavailable"
        }

    /**
     * Whether any management capability is currently active.
     */
    val isManaged: Boolean
        get() = this is DeviceAdminActive || this is DeviceOwnerActive

    /**
     * Whether full Device Owner capabilities are available.
     */
    val isDeviceOwner: Boolean
        get() = this is DeviceOwnerActive
}
