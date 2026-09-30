package com.f15.applock.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.f15.applock.data.storage.AppLockPreferences
import com.f15.applock.domain.security.AuthenticationManager
import com.f15.applock.security.BiometricAuthenticator
import com.f15.applock.security.BiometricStatus
import com.f15.applock.security.PinAuthenticator
import com.f15.applock.security.SessionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Screen state representation for authentication flow.
 */
sealed class AuthScreenState {
    object Checking : AuthScreenState()
    object SetupPin : AuthScreenState()
    object EnterPin : AuthScreenState()
    object Authenticated : AuthScreenState()
}

enum class PinSetupStep {
    CREATE,
    CONFIRM
}

/**
 * UI State for authentication and PIN setup.
 */
data class AuthUiState(
    val screenState: AuthScreenState = AuthScreenState.Checking,
    val isPinConfigured: Boolean = false,
    val enteredPin: String = "",
    val firstEnteredPin: String = "",
    val setupStep: PinSetupStep = PinSetupStep.CREATE,
    val errorMessage: String? = null,
    val failedAttempts: Int = 0,
    val lockoutRemainingSeconds: Int = 0,
    val isBiometricEnabled: Boolean = true,
    val biometricStatus: BiometricStatus = BiometricStatus.Unsupported
) {
    val isLockedOut: Boolean
        get() = lockoutRemainingSeconds > 0
}

/**
 * ViewModel governing PIN setup, verification, failed-attempt throttling, and biometric triggers.
 */
class AuthenticationViewModel(
    private val authenticationManager: AuthenticationManager,
    private val biometricAuthenticator: BiometricAuthenticator,
    private val preferences: AppLockPreferences,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private var lockoutJob: Job? = null

    init {
        checkInitialAuthState()
    }

    /**
     * Inspects configuration and session to route to Setup, Login, or Dashboard.
     */
    fun checkInitialAuthState() {
        viewModelScope.launch {
            val configured = authenticationManager.isPinConfigured()
            val biometricEnabled = preferences.isBiometricEnabledFlow.first()
            val bioStatus = biometricAuthenticator.checkBiometricStatus()
            val isAuthenticated = sessionManager.isAuthenticated.value

            _uiState.value = _uiState.value.copy(
                isPinConfigured = configured,
                isBiometricEnabled = biometricEnabled,
                biometricStatus = bioStatus,
                screenState = when {
                    !configured -> AuthScreenState.SetupPin
                    isAuthenticated -> AuthScreenState.Authenticated
                    else -> AuthScreenState.EnterPin
                },
                enteredPin = "",
                errorMessage = null
            )
        }
    }

    /**
     * Appends a digit (up to 8 digits).
     */
    fun onDigitEntered(digit: String) {
        if (_uiState.value.isLockedOut) return
        if (_uiState.value.enteredPin.length >= PinAuthenticator.MAX_PIN_LENGTH) return

        val newPin = _uiState.value.enteredPin + digit
        _uiState.value = _uiState.value.copy(enteredPin = newPin, errorMessage = null)

        // If in EnterPin mode and length matches typical 4 digits or user hits limit
        if (_uiState.value.screenState == AuthScreenState.EnterPin) {
            if (newPin.length in PinAuthenticator.MIN_PIN_LENGTH..PinAuthenticator.MAX_PIN_LENGTH) {
                // If user entered 4 digits, attempt verification
                verifyPinCandidate(newPin)
            }
        }
    }

    /**
     * Removes the last entered digit.
     */
    fun onBackspace() {
        if (_uiState.value.isLockedOut) return
        val current = _uiState.value.enteredPin
        if (current.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(
                enteredPin = current.dropLast(1),
                errorMessage = null
            )
        }
    }

    /**
     * Clears all entered digits.
     */
    fun onClear() {
        if (_uiState.value.isLockedOut) return
        _uiState.value = _uiState.value.copy(
            enteredPin = "",
            errorMessage = null
        )
    }

    /**
     * Advances from Create step to Confirm step during first-run setup.
     */
    fun onSetupNextStep() {
        val current = _uiState.value.enteredPin
        if (current.length < PinAuthenticator.MIN_PIN_LENGTH) {
            _uiState.value = _uiState.value.copy(errorMessage = "PIN must be at least ${PinAuthenticator.MIN_PIN_LENGTH} digits")
            return
        }

        _uiState.value = _uiState.value.copy(
            setupStep = PinSetupStep.CONFIRM,
            firstEnteredPin = current,
            enteredPin = "",
            errorMessage = null
        )
    }

    /**
     * Confirms and saves the new PIN during setup.
     */
    fun onSetupConfirm() {
        val confirmation = _uiState.value.enteredPin
        val first = _uiState.value.firstEnteredPin

        if (confirmation != first) {
            _uiState.value = _uiState.value.copy(
                setupStep = PinSetupStep.CREATE,
                firstEnteredPin = "",
                enteredPin = "",
                errorMessage = "PINs do not match. Try again."
            )
            return
        }

        viewModelScope.launch {
            val result = authenticationManager.createPin(confirmation)
            if (result.isSuccess) {
                sessionManager.onAuthenticationSuccess()
                _uiState.value = _uiState.value.copy(
                    isPinConfigured = true,
                    screenState = AuthScreenState.Authenticated,
                    enteredPin = "",
                    firstEnteredPin = "",
                    errorMessage = null
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    errorMessage = result.exceptionOrNull()?.message ?: "Failed to save PIN"
                )
            }
        }
    }

    /**
     * Verifies the input PIN against the secure credential store.
     */
    fun verifyPinCandidate(pin: String = _uiState.value.enteredPin) {
        if (_uiState.value.isLockedOut) return
        if (pin.length < PinAuthenticator.MIN_PIN_LENGTH) return

        viewModelScope.launch {
            val isValid = authenticationManager.verifyPin(pin)
            if (isValid) {
                // Success: reset attempts and unlock session
                _uiState.value = _uiState.value.copy(
                    failedAttempts = 0,
                    lockoutRemainingSeconds = 0,
                    errorMessage = null,
                    screenState = AuthScreenState.Authenticated,
                    enteredPin = ""
                )
                sessionManager.onAuthenticationSuccess()
            } else {
                // Failure: track failed attempts and calculate lockout
                val newFailed = _uiState.value.failedAttempts + 1
                val delaySeconds = when {
                    newFailed >= 6 -> 30
                    newFailed >= 4 -> 10
                    else -> 0
                }

                _uiState.value = _uiState.value.copy(
                    failedAttempts = newFailed,
                    enteredPin = "",
                    errorMessage = if (delaySeconds > 0) null else "Incorrect PIN"
                )

                if (delaySeconds > 0) {
                    startLockoutCountdown(delaySeconds)
                }
            }
        }
    }

    /**
     * Called when BiometricPrompt succeeds.
     */
    fun onBiometricSuccess() {
        _uiState.value = _uiState.value.copy(
            failedAttempts = 0,
            lockoutRemainingSeconds = 0,
            errorMessage = null,
            screenState = AuthScreenState.Authenticated,
            enteredPin = ""
        )
        sessionManager.onAuthenticationSuccess()
    }

    private fun startLockoutCountdown(seconds: Int) {
        lockoutJob?.cancel()
        lockoutJob = viewModelScope.launch {
            var remaining = seconds
            while (remaining > 0) {
                _uiState.value = _uiState.value.copy(lockoutRemainingSeconds = remaining)
                delay(1000L)
                remaining--
            }
            _uiState.value = _uiState.value.copy(lockoutRemainingSeconds = 0)
        }
    }

    /**
     * Factory for constructing [AuthenticationViewModel].
     */
    class Factory(
        private val authenticationManager: AuthenticationManager,
        private val biometricAuthenticator: BiometricAuthenticator,
        private val preferences: AppLockPreferences,
        private val sessionManager: SessionManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AuthenticationViewModel::class.java)) {
                return AuthenticationViewModel(
                    authenticationManager,
                    biometricAuthenticator,
                    preferences,
                    sessionManager
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
