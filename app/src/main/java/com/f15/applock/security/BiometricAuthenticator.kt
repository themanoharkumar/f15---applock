package com.f15.applock.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Encapsulates device biometric capability states.
 */
sealed class BiometricStatus {
    object Available : BiometricStatus()
    object NoHardware : BiometricStatus()
    object HardwareUnavailable : BiometricStatus()
    object NoneEnrolled : BiometricStatus()
    object Unsupported : BiometricStatus()
}

/**
 * Biometric authentication helper utilizing AndroidX BiometricPrompt and BiometricManager.
 * Validates hardware availability and handles Samsung Galaxy F15 5G fingerprint scanner interactions.
 */
class BiometricAuthenticator(private val context: Context) {

    private val biometricManager = BiometricManager.from(context)

    companion object {
        const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
    }

    /**
     * Inspects device biometric capabilities.
     */
    fun checkBiometricStatus(): BiometricStatus {
        return when (biometricManager.canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.Available
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NoHardware
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricStatus.HardwareUnavailable
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NoneEnrolled
            else -> BiometricStatus.Unsupported
        }
    }

    /**
     * Checks if biometric hardware is ready and at least one biometric is enrolled.
     */
    fun isBiometricAvailable(): Boolean {
        return checkBiometricStatus() is BiometricStatus.Available
    }

    /**
     * Displays the system biometric prompt attached to the hosting [FragmentActivity].
     */
    fun showBiometricPrompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeButtonText: String,
        onSuccess: () -> Unit,
        onError: (errorCode: Int, errString: CharSequence) -> Unit,
        onFailed: () -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errorCode, errString)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onFailed()
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()

        prompt.authenticate(promptInfo)
    }
}
