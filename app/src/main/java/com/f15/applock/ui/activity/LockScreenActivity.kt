package com.f15.applock.ui.activity

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import com.f15.applock.data.repository.AppTargetCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.f15.applock.R
import com.f15.applock.data.storage.AppLockPreferences
import com.f15.applock.data.storage.SecureCredentialStore
import com.f15.applock.security.BiometricAuthenticator
import com.f15.applock.security.BiometricStatus
import com.f15.applock.security.LockController
import com.f15.applock.security.PinAuthenticator
import com.f15.applock.security.SessionManager
import com.f15.applock.service.AppMonitorService
import com.f15.applock.ui.component.AppIcon
import com.f15.applock.ui.component.PinDotsIndicator
import com.f15.applock.ui.component.PinKeypad
import com.f15.applock.ui.theme.AppLockTheme
import com.f15.applock.viewmodel.AuthScreenState
import com.f15.applock.viewmodel.AuthenticationViewModel

/**
 * Dedicated lock screen displayed over protected applications.
 * Dynamically extracts target application metadata (icon & label) and requires
 * PIN or Biometric authentication before granting access.
 */
class LockScreenActivity : FragmentActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_target_package_name"
        private const val TAG = "LockScreenActivity"

        @Volatile
        var isResumed: Boolean = false
            private set

        @Volatile
        var currentTargetPackage: String = ""
            private set
    }

    private val preferences by lazy { AppLockPreferences(applicationContext) }
    private val credentialStore by lazy { SecureCredentialStore(applicationContext) }
    private val pinAuthenticator by lazy { PinAuthenticator(credentialStore) }
    private val biometricAuthenticator by lazy { BiometricAuthenticator(applicationContext) }
    private val sessionManager by lazy { SessionManager() }

    private val authViewModel: AuthenticationViewModel by viewModels {
        AuthenticationViewModel.Factory(
            pinAuthenticator,
            biometricAuthenticator,
            preferences,
            sessionManager
        )
    }

    private var targetPackage: String = ""
    private var isAuthenticated = false
    private var isAuthenticatingBiometric = false

    private val appLabelState = mutableStateOf("")
    private val appIconState = mutableStateOf<Drawable?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyInstantTransitions()
        // Hardening: Prevent screen capture, recents snapshot preview, and screen recording
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        enableEdgeToEdge()

        val initialPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
        currentTargetPackage = initialPackage
        Log.d(TAG, "[AERA/AppLock] LOCK_ACTIVITY: onCreate | package=$initialPackage")
        updateTargetPackage(initialPackage)
        authViewModel.resetForLockScreen()

        setContent {
            AppLockTheme {
                val authUiState by authViewModel.uiState.collectAsStateWithLifecycle()
                val currentLabel by appLabelState
                val currentIcon by appIconState

                // Intercept back presses: redirect to Android Home so protected app is not exposed
                BackHandler {
                    navigateToHome()
                }

                LaunchedEffect(authUiState.screenState) {
                    if (authUiState.screenState is AuthScreenState.Authenticated) {
                        onUnlockSuccess()
                    }
                }

                LockScreenContent(
                    targetAppName = currentLabel,
                    targetAppIcon = currentIcon,
                    targetPackageName = targetPackage,
                    uiState = authUiState,
                    onDigitEntered = authViewModel::onDigitEntered,
                    onBackspace = authViewModel::onBackspace,
                    onClear = authViewModel::onClear,
                    onRequestBiometric = ::triggerBiometricPrompt,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    private fun updateTargetPackage(pkg: String) {
        targetPackage = pkg
        currentTargetPackage = pkg
        if (pkg.isNotEmpty()) {
            val targetInfo = AppTargetCache.getTargetInfo(this, pkg)
            appLabelState.value = targetInfo.label.ifBlank { pkg }
            appIconState.value = targetInfo.icon
        } else {
            appLabelState.value = getString(R.string.app_name)
            appIconState.value = null
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyInstantTransitions()
        setIntent(intent)
        isAuthenticated = false
        val newPkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
        Log.d(TAG, "[AERA/AppLock] LOCK_ACTIVITY: onNewIntent | package=$newPkg (current=$targetPackage)")
        if (newPkg.isNotEmpty()) {
            currentTargetPackage = newPkg
            updateTargetPackage(newPkg)
            authViewModel.resetForLockScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        currentTargetPackage = targetPackage
        if (targetPackage.isNotEmpty()) {
            Log.i(TAG, "[AERA/AppLock] LOCK_ACTIVITY: visible | package=$targetPackage")
            LockController.getInstance(applicationContext).onLockScreenVisible(targetPackage)
        }
    }

    override fun onPause() {
        super.onPause()
        isResumed = false
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isAuthenticated) {
            Log.d(TAG, "[AERA/AppLock] LOCK_ACTIVITY: user left (Home/Recents) | package=$targetPackage")
            LockController.getInstance(applicationContext).onAuthenticationCancelled(targetPackage)
            applyInstantTransitions()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isResumed = false
        authViewModel.onClear() // Wipe entered PIN from memory
        Log.d(TAG, "[AERA/AppLock] LOCK_ACTIVITY: finish | package=$targetPackage")
        LockController.getInstance(applicationContext).onLockScreenDestroyed(targetPackage)
        if (!isChangingConfigurations && !isAuthenticated && targetPackage.isNotEmpty()) {
            LockController.getInstance(applicationContext).onAuthenticationCancelled(targetPackage)
        }
    }

    private fun triggerBiometricPrompt() {
        isAuthenticatingBiometric = true
        LockController.getInstance(applicationContext).onAuthenticating(targetPackage)
        biometricAuthenticator.showBiometricPrompt(
            activity = this,
            title = getString(R.string.biometric_prompt_title),
            subtitle = getString(R.string.biometric_prompt_subtitle),
            negativeButtonText = getString(R.string.biometric_prompt_negative),
            onSuccess = {
                isAuthenticatingBiometric = false
                authViewModel.onBiometricSuccess()
                onUnlockSuccess()
            },
            onError = { _, _ ->
                isAuthenticatingBiometric = false
            },
            onFailed = {
                isAuthenticatingBiometric = false
            }
        )
    }

    private fun onUnlockSuccess() {
        if (isAuthenticated) return
        isAuthenticated = true
        authViewModel.onClear() // Wipe entered PIN from memory
        LockController.getInstance(applicationContext).onAuthenticationSuccess(targetPackage)
        applyInstantTransitions()
        finish()
    }

    private fun navigateToHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        LockController.getInstance(applicationContext).onAuthenticationCancelled(targetPackage)
        applyInstantTransitions()
        finish()
    }

    private fun applyInstantTransitions() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}

@Composable
private fun LockScreenContent(
    targetAppName: String,
    targetAppIcon: Drawable?,
    targetPackageName: String,
    uiState: com.f15.applock.viewmodel.AuthUiState,
    onDigitEntered: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onRequestBiometric: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canUseBiometric = uiState.isBiometricEnabled &&
            uiState.biometricStatus is BiometricStatus.Available &&
            !uiState.isLockedOut

    LaunchedEffect(canUseBiometric) {
        if (canUseBiometric) {
            onRequestBiometric()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Context header: Protected App Icon, App Name & Lock Shield
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.size(72.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AppIcon(
                        drawable = targetAppIcon,
                        contentDescription = targetAppName,
                        modifier = Modifier.size(64.dp)
                    )

                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = targetAppName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.app_locked_desc),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // PIN Dots
                PinDotsIndicator(
                    pinLength = uiState.enteredPin.length,
                    maxDigits = 4,
                    isError = uiState.errorMessage != null
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Lockout cooldown or error message
                if (uiState.isLockedOut) {
                    Text(
                        text = "${stringResource(R.string.locked_out_prefix)} ${uiState.lockoutRemainingSeconds}s",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                } else if (uiState.errorMessage != null) {
                    Text(
                        text = uiState.errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Keypad & Biometric Trigger
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                PinKeypad(
                    onDigitClick = onDigitEntered,
                    onBackspaceClick = onBackspace,
                    onClearClick = onClear,
                    enabled = !uiState.isLockedOut
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (canUseBiometric) {
                    OutlinedButton(
                        onClick = onRequestBiometric,
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.use_fingerprint),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }
        }
    }
}
