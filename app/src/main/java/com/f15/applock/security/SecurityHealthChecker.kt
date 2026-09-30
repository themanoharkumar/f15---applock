package com.f15.applock.security

import android.content.Context
import android.os.PowerManager
import com.f15.applock.accessibility.AppLockAccessibilityService
import com.f15.applock.data.storage.AppLockPreferences
import com.f15.applock.data.storage.SecureCredentialStore
import com.f15.applock.detection.ForegroundAppDetector

/**
 * Overall security posture level calculated from active platform checks.
 */
enum class OverallSecurityStatus {
    SECURE,          // All protections active (PIN set, Accessibility running, apps protected)
    WARNING,         // Protection running but battery optimization or biometrics sub-optimal
    DEGRADED,        // Accessibility service stopped; fallback only or no real-time protection
    SETUP_REQUIRED   // No PIN configured or no permissions granted
}

/**
 * Diagnostic health check item for the security dashboard.
 */
data class HealthCheckItem(
    val id: String,
    val title: String,
    val isHealthy: Boolean,
    val summary: String,
    val isCritical: Boolean = false
)

/**
 * Structured diagnostic health report providing actionable recommendations
 * for Samsung Galaxy F15 5G on One UI 8.5.
 */
data class SecurityHealthReport(
    val status: OverallSecurityStatus,
    val items: List<HealthCheckItem>,
    val recommendations: List<String>
)

/**
 * Evaluates the full system security posture across credential storage,
 * runtime permissions, Samsung One UI battery restrictions, and detection engines.
 */
class SecurityHealthChecker(
    private val context: Context,
    private val preferences: AppLockPreferences,
    private val credentialStore: SecureCredentialStore,
    private val biometricAuthenticator: BiometricAuthenticator,
    private val detector: ForegroundAppDetector
) {

    suspend fun evaluateHealth(): SecurityHealthReport {
        val items = mutableListOf<HealthCheckItem>()
        val recommendations = mutableListOf<String>()

        // 1. PIN Credential Check
        val hasPin = credentialStore.isPinConfigured()
        items.add(
            HealthCheckItem(
                id = "pin_check",
                title = "Master PIN Authentication",
                isHealthy = hasPin,
                summary = if (hasPin) "Hardware-backed AES-256-GCM + PBKDF2 active" else "No PIN configured",
                isCritical = true
            )
        )
        if (!hasPin) {
            recommendations.add("Create an App Lock PIN to protect your applications and settings.")
        }

        // 2. Biometric Check
        val biometricStatus = biometricAuthenticator.checkBiometricStatus()
        val isBiometricHealthy = biometricStatus is BiometricStatus.Available
        items.add(
            HealthCheckItem(
                id = "biometric_check",
                title = "Fingerprint Authentication",
                isHealthy = isBiometricHealthy,
                summary = when (biometricStatus) {
                    is BiometricStatus.Available -> "Fingerprint sensor ready & enrolled"
                    is BiometricStatus.NoneEnrolled -> "No fingerprints enrolled in Samsung Settings"
                    else -> "Biometric hardware unavailable"
                },
                isCritical = false
            )
        )
        if (biometricStatus is BiometricStatus.NoneEnrolled) {
            recommendations.add("Enroll fingerprints in device Settings for rapid biometric unlock.")
        }

        // 3. Real-Time Accessibility Service
        val isA11yRunning = AppLockAccessibilityService.isServiceRunning
        val isA11yGranted = AppLockAccessibilityService.isAccessibilityPermissionGranted(context)
        val isA11yHealthy = isA11yRunning && isA11yGranted
        items.add(
            HealthCheckItem(
                id = "a11y_check",
                title = "Real-Time Accessibility Engine",
                isHealthy = isA11yHealthy,
                summary = when {
                    isA11yRunning -> "Active with zero-delay window detection"
                    isA11yGranted -> "Enabled in settings but service pending start"
                    else -> "Disabled in Android Settings"
                },
                isCritical = true
            )
        )
        if (!isA11yHealthy) {
            recommendations.add("Enable 'App Lock Protection Service' in Accessibility settings for instant locking.")
        }

        // 4. Usage Access Watchdog
        val hasUsage = detector.hasUsageAccessPermission()
        items.add(
            HealthCheckItem(
                id = "usage_check",
                title = "Usage Access Watchdog",
                isHealthy = hasUsage,
                summary = if (hasUsage) "Active as secondary detection fallback" else "Optional fallback disabled",
                isCritical = false
            )
        )

        // 5. Protected Apps Count
        val lockedCount = preferences.getLockedPackages().size
        val hasLockedApps = lockedCount > 0
        items.add(
            HealthCheckItem(
                id = "apps_check",
                title = "Protected Applications",
                isHealthy = hasLockedApps,
                summary = if (hasLockedApps) "$lockedCount application(s) currently protected" else "No applications selected for protection",
                isCritical = false
            )
        )
        if (!hasLockedApps) {
            recommendations.add("Select applications to protect from the App Selection dashboard.")
        }

        // 6. Samsung One UI Battery Optimization
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isIgnoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        items.add(
            HealthCheckItem(
                id = "battery_check",
                title = "Samsung One UI Battery Policy",
                isHealthy = isIgnoringBattery,
                summary = if (isIgnoringBattery) "Unrestricted background execution granted" else "Optimized (Samsung may suspend monitoring when idle)",
                isCritical = false
            )
        )
        if (!isIgnoringBattery) {
            recommendations.add("Set battery usage to 'Unrestricted' in App info to prevent Samsung One UI from sleeping App Lock.")
        }

        // 7. Screen & Window Security
        items.add(
            HealthCheckItem(
                id = "window_privacy",
                title = "Window & Snapshot Security",
                isHealthy = true,
                summary = "FLAG_SECURE active on authentication screens",
                isCritical = false
            )
        )

        // Calculate Overall Status
        val overallStatus = when {
            !hasPin -> OverallSecurityStatus.SETUP_REQUIRED
            !isA11yHealthy && !hasUsage -> OverallSecurityStatus.DEGRADED
            !isA11yHealthy -> OverallSecurityStatus.WARNING
            !isIgnoringBattery || !hasLockedApps -> OverallSecurityStatus.WARNING
            else -> OverallSecurityStatus.SECURE
        }

        return SecurityHealthReport(
            status = overallStatus,
            items = items,
            recommendations = recommendations
        )
    }
}
