package com.f15.applock.knox

/**
 * Diagnostic and capability model for Samsung Knox on the host device.
 *
 * Grounded in empirical device telemetry queried via platform APIs and Knox framework.
 */
data class KnoxCapability(
    val isKnoxSupported: Boolean,
    val knoxVersionString: String?,
    val knoxApiLevel: Int?,
    val standardSdkVersion: String?,
    val premiumSdkVersion: String?,
    val isEnterpriseDeviceManagerAvailable: Boolean,
    val isApplicationPolicyAvailable: Boolean,
    val isDeviceOwnerActive: Boolean
) {
    companion object {
        val Unavailable = KnoxCapability(
            isKnoxSupported = false,
            knoxVersionString = null,
            knoxApiLevel = null,
            standardSdkVersion = null,
            premiumSdkVersion = null,
            isEnterpriseDeviceManagerAvailable = false,
            isApplicationPolicyAvailable = false,
            isDeviceOwnerActive = false
        )
    }
}
