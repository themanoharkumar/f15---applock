package com.f15.applock

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.f15.applock.data.repository.AppRepository
import com.f15.applock.data.storage.AppLockPreferences
import com.f15.applock.data.storage.SecureCredentialStore
import com.f15.applock.detection.ForegroundAppDetector
import com.f15.applock.security.BiometricAuthenticator
import com.f15.applock.security.PinAuthenticator
import com.f15.applock.security.SessionManager
import com.f15.applock.ui.screen.AppSelectionScreen
import com.f15.applock.ui.screen.AuthenticationScreen
import com.f15.applock.ui.screen.PinSetupScreen
import com.f15.applock.ui.screen.SecuritySettingsScreen
import com.f15.applock.ui.theme.AppLockTheme
import com.f15.applock.viewmodel.AppSelectionViewModel
import com.f15.applock.viewmodel.AuthScreenState
import com.f15.applock.viewmodel.AuthenticationViewModel
import com.f15.applock.viewmodel.SecuritySettingsViewModel
import kotlinx.coroutines.launch

enum class MainNavigationDestination {
    Dashboard,
    Settings
}

class MainActivity : FragmentActivity() {

    private val preferences by lazy { AppLockPreferences(applicationContext) }
    private val credentialStore by lazy { SecureCredentialStore(applicationContext) }
    private val pinAuthenticator by lazy { PinAuthenticator(credentialStore) }
    private val biometricAuthenticator by lazy { BiometricAuthenticator(applicationContext) }
    private val sessionManager by lazy { SessionManager() }
    private val appRepository by lazy { AppRepository(applicationContext) }
    private val detector by lazy { ForegroundAppDetector(applicationContext) }

    private val appSelectionViewModel: AppSelectionViewModel by viewModels {
        AppSelectionViewModel.Factory(appRepository, preferences, sessionManager)
    }

    private val authViewModel: AuthenticationViewModel by viewModels {
        AuthenticationViewModel.Factory(
            pinAuthenticator,
            biometricAuthenticator,
            preferences,
            sessionManager
        )
    }

    private val securitySettingsViewModel: SecuritySettingsViewModel by viewModels {
        SecuritySettingsViewModel.Factory(
            pinAuthenticator,
            credentialStore,
            biometricAuthenticator,
            preferences,
            sessionManager,
            detector,
            appRepository,
            applicationContext
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Phase 7 & 8: Reconcile Samsung Knox & Device Owner protection and security posture on startup
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.f15.applock.knox.KnoxManagerImpl.getInstance(applicationContext).reconcilePolicies(packageName)
            com.f15.applock.security.SecurityStateManager.getInstance(applicationContext).reconcileOnStartup()
        }

        setContent {
            AppLockTheme {
                val authUiState by authViewModel.uiState.collectAsStateWithLifecycle()
                var currentDestination by remember { mutableStateOf(MainNavigationDestination.Dashboard) }

                when (authUiState.screenState) {
                    is AuthScreenState.Checking -> {
                        // Brief transition
                    }
                    is AuthScreenState.SetupPin -> {
                        PinSetupScreen(
                            uiState = authUiState,
                            onDigitEntered = authViewModel::onDigitEntered,
                            onBackspace = authViewModel::onBackspace,
                            onClear = authViewModel::onClear,
                            onNextStep = authViewModel::onSetupNextStep,
                            onConfirm = authViewModel::onSetupConfirm,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    is AuthScreenState.EnterPin -> {
                        AuthenticationScreen(
                            uiState = authUiState,
                            onDigitEntered = authViewModel::onDigitEntered,
                            onBackspace = authViewModel::onBackspace,
                            onClear = authViewModel::onClear,
                            onRequestBiometric = ::triggerBiometricPrompt,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    is AuthScreenState.Authenticated -> {
                        when (currentDestination) {
                            MainNavigationDestination.Dashboard -> {
                                val appSelectionUiState by appSelectionViewModel.uiState.collectAsStateWithLifecycle()
                                AppSelectionScreen(
                                    uiState = appSelectionUiState,
                                    onQueryChanged = appSelectionViewModel::onSearchQueryChanged,
                                    onClearSearch = appSelectionViewModel::onClearSearch,
                                    onToggleApp = appSelectionViewModel::toggleAppProtection,
                                    onRefresh = { appSelectionViewModel.loadInstalledApps(forceRefresh = true) },
                                    onOpenSettings = { currentDestination = MainNavigationDestination.Settings },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            MainNavigationDestination.Settings -> {
                                val settingsUiState by securitySettingsViewModel.uiState.collectAsStateWithLifecycle()
                                BackHandler {
                                    currentDestination = MainNavigationDestination.Dashboard
                                }
                                SecuritySettingsScreen(
                                    uiState = settingsUiState,
                                    onNavigateBack = { currentDestination = MainNavigationDestination.Dashboard },
                                    onToggleBiometric = securitySettingsViewModel::onToggleBiometric,
                                    onSelectSessionTimeout = securitySettingsViewModel::onSelectSessionTimeout,
                                    onToggleMonitoring = securitySettingsViewModel::onToggleMonitoring,
                                    onRequestUsageAccess = {
                                        startActivity(detector.getUsageAccessSettingsIntent())
                                    },
                                    onDismissUsageAccessExplanation = securitySettingsViewModel::dismissUsageAccessExplanation,
                                    onRequestAccessibility = {
                                        startActivity(com.f15.applock.accessibility.AppLockAccessibilityService.getAccessibilitySettingsIntent())
                                    },
                                    onDismissAccessibilityExplanation = securitySettingsViewModel::dismissAccessibilityExplanation,
                                    onRequestBatteryOptimizationSettings = {
                                        try {
                                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                        } catch (e: Exception) {
                                            // Fallback
                                        }
                                    },
                                    onOpenChangePin = securitySettingsViewModel::openChangePinDialog,
                                    onCloseChangePin = securitySettingsViewModel::closeChangePinDialog,
                                    onOpenRemovePin = securitySettingsViewModel::openRemovePinDialog,
                                    onCloseRemovePin = securitySettingsViewModel::closeRemovePinDialog,
                                    onOpenSecurityLogs = securitySettingsViewModel::openSecurityLogDialog,
                                    onCloseSecurityLogs = securitySettingsViewModel::closeSecurityLogDialog,
                                    onClearSecurityLogs = securitySettingsViewModel::clearSecurityLogs,
                                    onCleanupStalePackages = { securitySettingsViewModel.cleanupStalePackages() },
                                    onChangePin = securitySettingsViewModel::changePin,
                                    onRemovePin = { currentPin, callback ->
                                        securitySettingsViewModel.removePin(currentPin) { success ->
                                            callback(success)
                                            if (success) {
                                                authViewModel.checkInitialAuthState()
                                            }
                                        }
                                    },
                                    onVerifyCurrentPin = securitySettingsViewModel::verifyCurrentPin,
                                    onApplyKnoxProtection = securitySettingsViewModel::applyKnoxProtection,
                                    onRemoveKnoxProtection = securitySettingsViewModel::removeKnoxProtection,
                                    onOpenRecovery = securitySettingsViewModel::openRecoveryDialog,
                                    onCloseRecovery = securitySettingsViewModel::closeRecoveryDialog,
                                    onExecuteRecovery = { pin -> securitySettingsViewModel.executeRecovery(pin) },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun triggerBiometricPrompt() {
        biometricAuthenticator.showBiometricPrompt(
            activity = this,
            title = getString(R.string.biometric_prompt_title),
            subtitle = getString(R.string.biometric_prompt_subtitle),
            negativeButtonText = getString(R.string.biometric_prompt_negative),
            onSuccess = {
                authViewModel.onBiometricSuccess()
            },
            onError = { _, _ -> },
            onFailed = {}
        )
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            val timeout = preferences.getSessionTimeout()
            sessionManager.onAppForegrounded(timeout)
            authViewModel.checkInitialAuthState()
            securitySettingsViewModel.refreshMonitoringStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        securitySettingsViewModel.refreshMonitoringStatus()
    }

    override fun onStop() {
        super.onStop()
        sessionManager.onAppBackgrounded()
    }
}
