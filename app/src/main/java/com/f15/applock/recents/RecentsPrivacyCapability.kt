package com.f15.applock.recents

/**
 * Diagnostic representation of Recents privacy capabilities on the target device.
 *
 * @param isSelfProtectionActive True when AppLock's own activities (LockScreenActivity & MainActivity)
 *                               actively enforce FLAG_SECURE and disable Recents preview snapshots.
 * @param isPerPackageThirdPartySupported True if the OS provides a supported per-package API to mask
 *                                        task snapshots of third-party apps without global impact.
 * @param isKnoxPerPackageSupported True if Samsung Knox ApplicationPolicy provides per-package snapshot masking.
 * @param isDpmGlobalSupported True if DevicePolicyManager can disable screen capture globally (not applied to avoid collateral damage).
 * @param status Overall high-level Recents privacy status.
 * @param explanation Human-readable architectural rationale explaining current platform capabilities and limits.
 */
data class RecentsPrivacyCapability(
    val isSelfProtectionActive: Boolean,
    val isPerPackageThirdPartySupported: Boolean,
    val isKnoxPerPackageSupported: Boolean,
    val isDpmGlobalSupported: Boolean,
    val status: RecentsPrivacyStatus,
    val explanation: String
)
