package com.f15.applock.detection

/**
 * Diagnostic and operational state of the foreground application monitor.
 *
 * @property currentPackage Current active foreground package name.
 * @property previousPackage Previous active foreground package name.
 * @property isMonitoring Whether the polling monitor is currently running.
 * @property isProtected Whether the current package is marked as protected in DataStore.
 * @property lastLockRequestPackage Package name that last triggered a lock request.
 * @property lastLockRequestTimestamp Timestamp of the last emitted lock request.
 * @property hasUsageAccess Whether Usage Access permission is currently granted.
 */
data class DetectionState(
    val currentPackage: String? = null,
    val previousPackage: String? = null,
    val isMonitoring: Boolean = false,
    val isProtected: Boolean = false,
    val lastLockRequestPackage: String? = null,
    val lastLockRequestTimestamp: Long = 0L,
    val hasUsageAccess: Boolean = false
)
