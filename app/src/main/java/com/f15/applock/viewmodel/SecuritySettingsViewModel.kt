package com.f15.applock.viewmodel

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.f15.applock.accessibility.AppLockAccessibilityService
import com.f15.applock.data.repository.AppRepository
import com.f15.applock.data.security.SecurityEvent
import com.f15.applock.data.security.SecurityEventLogger
import com.f15.applock.data.security.SecurityEventType
import com.f15.applock.data.storage.AppLockPreferences
import com.f15.applock.data.storage.SecureCredentialStore
import com.f15.applock.detection.ForegroundAppDetector
import com.f15.applock.device.DeviceManagementDiagnostics
import com.f15.applock.device.DeviceOwnerManager
import com.f15.applock.device.DevicePolicyState
import com.f15.applock.domain.model.LockEngineMode
import com.f15.applock.domain.model.MonitoringStatus
import com.f15.applock.domain.model.SessionTimeout
import com.f15.applock.domain.security.AuthenticationManager
import com.f15.applock.security.BiometricAuthenticator
import com.f15.applock.security.BiometricStatus
import com.f15.applock.security.LockController
import com.f15.applock.security.SecurityHealthChecker
import com.f15.applock.security.SecurityHealthReport
import com.f15.applock.security.SessionManager
import com.f15.applock.service.AppMonitorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the Security and Monitoring Settings screen.
 */
data class SecuritySettingsUiState(
    val isBiometricEnabled: Boolean = true,
    val biometricStatus: BiometricStatus = BiometricStatus.Unsupported,
    val sessionTimeout: SessionTimeout = SessionTimeout.MINUTE_1,
    val isAccessibilityEnabled: Boolean = false,
    val isAccessibilityRunning: Boolean = false,
    val hasUsageAccess: Boolean = false,
    val isMonitoringRunning: Boolean = false,
    val isIgnoringBatteryOptimizations: Boolean = false,
    val protectedAppsCount: Int = 0,
    val engineMode: LockEngineMode = LockEngineMode.DISABLED,
    val monitoringStatus: MonitoringStatus = MonitoringStatus(),
    val authorizationStatus: String = "NONE",
    val healthReport: SecurityHealthReport? = null,
    val securityEvents: List<SecurityEvent> = emptyList(),
    val showSecurityLogDialog: Boolean = false,
    val showChangePinDialog: Boolean = false,
    val showRemovePinDialog: Boolean = false,
    val showUsageAccessExplanation: Boolean = false,
    val showAccessibilityExplanation: Boolean = false,
    val actionSuccessMessage: String? = null,
    val actionErrorMessage: String? = null,
    // Phase 6: Device Management
    val devicePolicyState: DevicePolicyState = DevicePolicyState.NotProvisioned,
    val deviceManagementDiagnostics: DeviceManagementDiagnostics? = null,
    // Phase 7: Samsung Knox Application Protection
    val knoxPolicyState: com.f15.applock.knox.KnoxPolicyState = com.f15.applock.knox.KnoxPolicyState(),
    val knoxCapability: com.f15.applock.knox.KnoxCapability = com.f15.applock.knox.KnoxCapability.Unavailable,
    // Phase 8: Centralized Security Posture & Recovery
    val securityPostureReport: com.f15.applock.security.SecurityPostureReport? = null,
    val showRecoveryDialog: Boolean = false,
    val recoveryErrorMessage: String? = null,
    val recoverySuccessMessage: String? = null
)

/**
 * ViewModel managing security preferences, monitoring controls, and diagnostic health audits.
 * Hardened for Phase 5:
 * - Real-time security posture health evaluations
 * - Tamper-resistant security audit event stream
 * - Stale package purging for uninstalled apps
 * - Master-session protected configuration changes
 */
class SecuritySettingsViewModel(
    private val authenticationManager: AuthenticationManager,
    private val credentialStore: SecureCredentialStore,
    private val biometricAuthenticator: BiometricAuthenticator,
    private val preferences: AppLockPreferences,
    private val sessionManager: SessionManager,
    private val detector: ForegroundAppDetector,
    private val appRepository: AppRepository,
    private val context: Context
) : ViewModel() {

    private val lockController = LockController.getInstance(context)
    private val deviceOwnerManager = DeviceOwnerManager.getInstance(context)
    private val knoxManager = com.f15.applock.knox.KnoxManagerImpl.getInstance(context)
    private val securityStateManager = com.f15.applock.security.SecurityStateManager.getInstance(context)
    private val securityRecoveryManager = com.f15.applock.security.SecurityRecoveryManager(
        context,
        credentialStore,
        preferences,
        securityStateManager
    )
    private val healthChecker = SecurityHealthChecker(
        context,
        preferences,
        credentialStore,
        biometricAuthenticator,
        detector
    )

    private val _uiState = MutableStateFlow(
        SecuritySettingsUiState(
            biometricStatus = biometricAuthenticator.checkBiometricStatus(),
            hasUsageAccess = detector.hasUsageAccessPermission(),
            isAccessibilityEnabled = AppLockAccessibilityService.isAccessibilityPermissionGranted(context),
            isAccessibilityRunning = AppLockAccessibilityService.isServiceRunning,
            knoxPolicyState = knoxManager.getAppProtectionStatus(context.packageName),
            knoxCapability = knoxManager.getCapabilities()
        )
    )
    val uiState: StateFlow<SecuritySettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.isBiometricEnabledFlow.collect { enabled ->
                _uiState.value = _uiState.value.copy(isBiometricEnabled = enabled)
                refreshHealthReport()
            }
        }
        viewModelScope.launch {
            preferences.sessionTimeoutFlow.collect { timeout ->
                _uiState.value = _uiState.value.copy(sessionTimeout = timeout)
            }
        }
        viewModelScope.launch {
            preferences.lockedPackagesFlow.collect { lockedSet ->
                _uiState.value = _uiState.value.copy(protectedAppsCount = lockedSet.size)
                refreshHealthReport()
            }
        }
        viewModelScope.launch {
            AppMonitorService.isRunning.collect { running ->
                _uiState.value = _uiState.value.copy(isMonitoringRunning = running)
                refreshHealthReport()
            }
        }
        viewModelScope.launch {
            lockController.monitoringStatus.collect { monStatus ->
                val authStatus = lockController.lockDecisionManager.getAuthorizationStatus(monStatus.currentPackage)
                _uiState.value = _uiState.value.copy(
                    monitoringStatus = monStatus,
                    authorizationStatus = authStatus
                )
            }
        }
        viewModelScope.launch {
            SecurityEventLogger.eventsFlow.collect { events ->
                _uiState.value = _uiState.value.copy(securityEvents = events)
            }
        }
        viewModelScope.launch {
            knoxManager.policyState.collect { kState ->
                _uiState.value = _uiState.value.copy(
                    knoxPolicyState = kState,
                    knoxCapability = knoxManager.getCapabilities()
                )
            }
        }
        viewModelScope.launch {
            securityStateManager.postureFlow.collect { posture ->
                _uiState.value = _uiState.value.copy(securityPostureReport = posture)
            }
        }
        refreshMonitoringStatus()
    }

    /**
     * Refreshes dynamic diagnostic permissions, monitor states, and security health.
     */
    fun refreshMonitoringStatus() {
        val hasUsage = detector.hasUsageAccessPermission()
        val isA11yGranted = AppLockAccessibilityService.isAccessibilityPermissionGranted(context)
        val isA11yRunning = AppLockAccessibilityService.isServiceRunning

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isIgnoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false

        lockController.setAccessibilityEnabled(isA11yGranted)
        lockController.setUsageAccess(hasUsage)

        val engineMode = when {
            isA11yGranted -> LockEngineMode.ACTIVE_ACCESSIBILITY
            hasUsage -> LockEngineMode.ACTIVE_USAGE_STATS
            else -> LockEngineMode.DISABLED
        }

        _uiState.value = _uiState.value.copy(
            isAccessibilityEnabled = isA11yGranted,
            isAccessibilityRunning = isA11yRunning,
            hasUsageAccess = hasUsage,
            isIgnoringBatteryOptimizations = isIgnoringBattery,
            engineMode = engineMode
        )

        // Phase 6: Refresh device management state from platform
        refreshDeviceManagementState()

        refreshHealthReport()

        // Phase 8: Evaluate overall security posture
        viewModelScope.launch {
            securityStateManager.evaluateSecurityPosture()
        }
    }

    private fun refreshHealthReport() {
        viewModelScope.launch {
            val report = healthChecker.evaluateHealth()
            _uiState.value = _uiState.value.copy(healthReport = report)
        }
    }

    /**
     * Queries DevicePolicyManager for the authoritative device management state
     * and updates the UI. The platform state always wins over any cached value.
     */
    private fun refreshDeviceManagementState() {
        val state = deviceOwnerManager.refreshState()
        val diagnostics = deviceOwnerManager.getDiagnostics()
        _uiState.value = _uiState.value.copy(
            devicePolicyState = state,
            deviceManagementDiagnostics = diagnostics
        )
    }

    fun onToggleMonitoring(enable: Boolean) {
        if (!sessionManager.isAuthenticated.value) return

        if (enable) {
            if (!_uiState.value.hasUsageAccess) {
                _uiState.value = _uiState.value.copy(showUsageAccessExplanation = true)
                return
            }
            AppMonitorService.start(context)
            SecurityEventLogger.log(SecurityEventType.SERVICE_LIFECYCLE, "Background monitoring started")
        } else {
            AppMonitorService.stop(context)
            SecurityEventLogger.log(SecurityEventType.SERVICE_LIFECYCLE, "Background monitoring stopped")
        }
    }

    fun dismissUsageAccessExplanation() {
        _uiState.value = _uiState.value.copy(showUsageAccessExplanation = false)
    }

    fun showAccessibilityExplanation() {
        _uiState.value = _uiState.value.copy(showAccessibilityExplanation = true)
    }

    fun dismissAccessibilityExplanation() {
        _uiState.value = _uiState.value.copy(showAccessibilityExplanation = false)
    }

    fun openSecurityLogDialog() {
        _uiState.value = _uiState.value.copy(showSecurityLogDialog = true)
    }

    fun closeSecurityLogDialog() {
        _uiState.value = _uiState.value.copy(showSecurityLogDialog = false)
    }

    fun clearSecurityLogs() {
        if (!sessionManager.isAuthenticated.value) return
        SecurityEventLogger.clearLogs()
    }

    fun cleanupStalePackages(onComplete: (Int) -> Unit = {}) {
        if (!sessionManager.isAuthenticated.value) {
            onComplete(0)
            return
        }

        viewModelScope.launch {
            val installedApps = appRepository.getInstalledApps().map { it.packageName }.toSet()
            val purged = preferences.cleanupStalePackages(installedApps)
            if (purged > 0) {
                SecurityEventLogger.log(
                    SecurityEventType.CLEANUP_STALE_PACKAGES,
                    "Purged $purged uninstalled protected packages from database"
                )
                _uiState.value = _uiState.value.copy(
                    actionSuccessMessage = "Purged $purged uninstalled package(s)"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    actionSuccessMessage = "No stale packages found"
                )
            }
            refreshHealthReport()
            onComplete(purged)
        }
    }

    fun onToggleBiometric(enabled: Boolean) {
        if (!sessionManager.isAuthenticated.value) return
        viewModelScope.launch {
            preferences.setBiometricEnabled(enabled)
            SecurityEventLogger.log(
                SecurityEventType.SERVICE_LIFECYCLE,
                "Biometric authentication preference set to: $enabled"
            )
        }
    }

    fun onSelectSessionTimeout(timeout: SessionTimeout) {
        if (!sessionManager.isAuthenticated.value) return
        viewModelScope.launch {
            preferences.setSessionTimeout(timeout)
            SecurityEventLogger.log(
                SecurityEventType.SERVICE_LIFECYCLE,
                "Session timeout configured to: ${timeout.name}"
            )
        }
    }

    fun openChangePinDialog() {
        _uiState.value = _uiState.value.copy(
            showChangePinDialog = true,
            actionErrorMessage = null,
            actionSuccessMessage = null
        )
    }

    fun closeChangePinDialog() {
        _uiState.value = _uiState.value.copy(showChangePinDialog = false)
    }

    fun openRemovePinDialog() {
        _uiState.value = _uiState.value.copy(
            showRemovePinDialog = true,
            actionErrorMessage = null,
            actionSuccessMessage = null
        )
    }

    fun closeRemovePinDialog() {
        _uiState.value = _uiState.value.copy(showRemovePinDialog = false)
    }

    suspend fun verifyCurrentPin(pin: String): Boolean {
        return authenticationManager.verifyPin(pin)
    }

    fun changePin(currentPin: String, newPin: String, onComplete: (Boolean) -> Unit) {
        if (!sessionManager.isAuthenticated.value) {
            onComplete(false)
            return
        }
        viewModelScope.launch {
            val result = authenticationManager.changePin(currentPin, newPin)
            if (result.isSuccess) {
                SecurityEventLogger.log(SecurityEventType.PIN_CHANGED, "Master PIN successfully changed")
                _uiState.value = _uiState.value.copy(
                    showChangePinDialog = false,
                    actionSuccessMessage = "PIN changed successfully",
                    actionErrorMessage = null
                )
                refreshHealthReport()
                onComplete(true)
            } else {
                _uiState.value = _uiState.value.copy(
                    actionErrorMessage = result.exceptionOrNull()?.message ?: "Failed to change PIN"
                )
                onComplete(false)
            }
        }
    }

    fun removePin(currentPin: String, onComplete: (Boolean) -> Unit) {
        if (!sessionManager.isAuthenticated.value) {
            onComplete(false)
            return
        }
        viewModelScope.launch {
            val result = authenticationManager.removePin(currentPin)
            if (result.isSuccess) {
                sessionManager.lockSession()
                SecurityEventLogger.log(SecurityEventType.PIN_REMOVED, "Master PIN removed")
                _uiState.value = _uiState.value.copy(
                    showRemovePinDialog = false,
                    actionSuccessMessage = "PIN removed",
                    actionErrorMessage = null
                )
                refreshHealthReport()
                onComplete(true)
            } else {
                _uiState.value = _uiState.value.copy(
                    actionErrorMessage = result.exceptionOrNull()?.message ?: "Failed to remove PIN"
                )
                onComplete(false)
            }
        }
    }

    /**
     * Applies Samsung Knox and Device Owner application protection to App Lock itself.
     */
    fun applyKnoxProtection() {
        if (!sessionManager.isAuthenticated.value) return
        viewModelScope.launch {
            val result = knoxManager.applyAppProtection(context.packageName)
            refreshMonitoringStatus()
            when (result) {
                is com.f15.applock.knox.KnoxResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        actionSuccessMessage = result.message,
                        actionErrorMessage = null
                    )
                }
                is com.f15.applock.knox.KnoxResult.LicenseRequired -> {
                    _uiState.value = _uiState.value.copy(
                        actionErrorMessage = result.details
                    )
                }
                is com.f15.applock.knox.KnoxResult.Failed -> {
                    _uiState.value = _uiState.value.copy(
                        actionErrorMessage = result.reason
                    )
                }
                is com.f15.applock.knox.KnoxResult.SecurityError -> {
                    _uiState.value = _uiState.value.copy(
                        actionErrorMessage = result.message
                    )
                }
                is com.f15.applock.knox.KnoxResult.NotDeviceOwner -> {
                    _uiState.value = _uiState.value.copy(
                        actionErrorMessage = "Cannot apply: App Lock is not Device Owner"
                    )
                }
                is com.f15.applock.knox.KnoxResult.Unsupported -> {
                    _uiState.value = _uiState.value.copy(
                        actionErrorMessage = "Knox policy unsupported on this device"
                    )
                }
            }
        }
    }

    /**
     * Removes Knox and Device Owner application protection (Developer / Recovery path).
     */
    fun removeKnoxProtection() {
        if (!sessionManager.isAuthenticated.value) return
        viewModelScope.launch {
            val result = knoxManager.removeAppProtection(context.packageName)
            refreshMonitoringStatus()
            _uiState.value = _uiState.value.copy(
                actionSuccessMessage = "Application protection removed",
                actionErrorMessage = null
            )
        }
    }

    /**
     * Opens the Administrator Recovery dialog.
     */
    fun openRecoveryDialog() {
        _uiState.value = _uiState.value.copy(
            showRecoveryDialog = true,
            recoveryErrorMessage = null,
            recoverySuccessMessage = null
        )
    }

    /**
     * Closes the Administrator Recovery dialog.
     */
    fun closeRecoveryDialog() {
        _uiState.value = _uiState.value.copy(
            showRecoveryDialog = false,
            recoveryErrorMessage = null
        )
    }

    /**
     * Executes the secure Administrator Recovery procedure with Master PIN verification.
     */
    fun executeRecovery(adminPin: String, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val result = securityRecoveryManager.executeRecovery(adminPin)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    showRecoveryDialog = false,
                    recoverySuccessMessage = result.message,
                    recoveryErrorMessage = null
                )
                refreshMonitoringStatus()
                onComplete?.invoke(true)
            } else {
                _uiState.value = _uiState.value.copy(
                    recoveryErrorMessage = result.message
                )
                onComplete?.invoke(false)
            }
        }
    }

    /**
     * Factory for constructing [SecuritySettingsViewModel].
     */
    class Factory(
        private val authenticationManager: AuthenticationManager,
        private val credentialStore: SecureCredentialStore,
        private val biometricAuthenticator: BiometricAuthenticator,
        private val preferences: AppLockPreferences,
        private val sessionManager: SessionManager,
        private val detector: ForegroundAppDetector,
        private val appRepository: AppRepository,
        private val context: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SecuritySettingsViewModel::class.java)) {
                return SecuritySettingsViewModel(
                    authenticationManager,
                    credentialStore,
                    biometricAuthenticator,
                    preferences,
                    sessionManager,
                    detector,
                    appRepository,
                    context
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
