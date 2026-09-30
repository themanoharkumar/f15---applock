package com.f15.applock.security

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.f15.applock.data.security.SecurityEventLogger
import com.f15.applock.data.security.SecurityEventType
import com.f15.applock.data.storage.AppLockPreferences
import com.f15.applock.domain.model.DetectionSource
import com.f15.applock.domain.model.ForegroundSecurityState
import com.f15.applock.domain.model.LockEngineMode
import com.f15.applock.domain.model.LockEngineState
import com.f15.applock.domain.model.MonitoringStatus
import com.f15.applock.receiver.ScreenStateReceiver
import com.f15.applock.ui.activity.LockScreenActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central lock controller coordinating foreground window events, explicit security states,
 * fail-closed authorization decisions, duplicate suppression, and lock screen presentation.
 *
 * Architecture (Phase 5.3):
 * AppLockAccessibilityService (real-time) / ForegroundAppMonitor (fallback)
 *           ↓
 *      LockController (explicit security state machine: HOME / PROTECTED / UNPROTECTED)
 *           ↓
 *   LockDecisionManager (in-memory, synchronous, fail-closed)
 *           ↓
 *   LockScreenActivity (instant display, FLAG_SECURE)
 */
class LockController private constructor(
    private val context: Context,
    val lockDecisionManager: LockDecisionManager,
    private val preferences: AppLockPreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
) {

    companion object {
        private const val TAG = "LockController"

        @Volatile
        private var INSTANCE: LockController? = null

        fun getInstance(context: Context): LockController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appContext = context.applicationContext
                    val prefs = AppLockPreferences(appContext)
                    val sessionMgr = SessionManager()
                    val decisionMgr = LockDecisionManager(appContext, prefs, sessionMgr)
                    LockController(appContext, decisionMgr, prefs).also { INSTANCE = it }
                }
            }
        }
    }

    init {
        try {
            ScreenStateReceiver.register(context, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register ScreenStateReceiver", e)
        }
    }

    @Volatile
    var securityState: ForegroundSecurityState = ForegroundSecurityState.UNKNOWN
        private set

    @Volatile
    private var currentForegroundPackage: String? = null

    @Volatile
    private var activeProtectedPackage: String? = null

    // Track active package currently being locked to prevent duplicate activity launches
    @Volatile
    private var activeLockPackage: String? = null

    @Volatile
    private var lastLockLaunchTime: Long = 0L

    // Diagnostic timestamps for latency measurement (Section 15)
    @Volatile
    private var timingT1: Long = 0L
    @Volatile
    private var timingT2: Long = 0L
    @Volatile
    private var timingT3: Long = 0L
    @Volatile
    private var timingT4: Long = 0L
    @Volatile
    private var timingT5: Long = 0L

    private val isLockScreenVisible = AtomicBoolean(false)

    private val _engineState = MutableStateFlow<LockEngineState>(LockEngineState.Idle)
    val engineState: StateFlow<LockEngineState> = _engineState.asStateFlow()

    private val _monitoringStatus = MutableStateFlow(MonitoringStatus())
    val monitoringStatus: StateFlow<MonitoringStatus> = _monitoringStatus.asStateFlow()

    /**
     * Invoked when the phone screen turns off or locks.
     */
    fun onScreenOff() {
        scope.launch {
            val exited = activeProtectedPackage ?: currentForegroundPackage
            lockDecisionManager.onScreenOff(exited)
            securityState = ForegroundSecurityState.UNKNOWN
            currentForegroundPackage = null
            activeProtectedPackage = null
            activeLockPackage = null
            isLockScreenVisible.set(false)
            _engineState.value = LockEngineState.Idle
            updateMonitoringStatus()
        }
    }

    /**
     * Invoked when a foreground package is detected by either
     * [com.f15.applock.accessibility.AppLockAccessibilityService] (real-time) or
     * [com.f15.applock.detection.ForegroundAppMonitor] (fallback).
     *
     * Evaluates in-memory lock decisions synchronously to achieve minimum safe latency without
     * arbitrary sleep or debounce delays.
     */
    fun onForegroundPackageChanged(
        packageName: String?,
        source: DetectionSource,
        eventTimeUptime: Long = SystemClock.uptimeMillis(),
        receivedTimeUptime: Long = SystemClock.uptimeMillis()
    ) {
        if (packageName.isNullOrBlank()) return

        // 1. Transient overlay check (soft keyboard, biometric prompt, self, system UI pull-down)
        if (lockDecisionManager.isTransientOverlay(packageName)) {
            Log.d(TAG, "[$source] Event: TYPE_WINDOW_STATE_CHANGED | Package: $packageName | State: TRANSIENT_OVERLAY | Decision: IGNORE — OVERLAY")
            return
        }

        val prevPackage = currentForegroundPackage
        val isNewPackage = (packageName != prevPackage)

        // 2. HOME / Launcher Detection (Section 5)
        if (lockDecisionManager.isLauncherPackage(packageName)) {
            val exited = activeProtectedPackage
            currentForegroundPackage = packageName
            securityState = ForegroundSecurityState.HOME

            if (exited != null) {
                activeProtectedPackage = null
                Log.i(TAG, "[AppLock] FOREGROUND → HOME")
                Log.i(TAG, "[AppLock] PROTECTED EXIT → package=$exited")
                lockDecisionManager.onPackageExited(exited)
            } else {
                Log.d(TAG, "[AppLock] FOREGROUND → HOME")
            }

            if (activeLockPackage != null && activeLockPackage != packageName) {
                activeLockPackage = null
                isLockScreenVisible.set(false)
            }

            _engineState.value = LockEngineState.Idle
            scope.launch {
                updateMonitoringStatus(current = packageName, prev = prevPackage, source = source, requiresLock = false)
            }
            return
        }

        // 3. Unprotected Application Detection
        val isProtected = lockDecisionManager.isPackageProtected(packageName)
        if (!isProtected) {
            val exited = activeProtectedPackage
            currentForegroundPackage = packageName
            securityState = ForegroundSecurityState.UNPROTECTED_APP

            if (exited != null) {
                activeProtectedPackage = null
                Log.i(TAG, "[AppLock] PROTECTED EXIT → package=$exited")
                lockDecisionManager.onPackageExited(exited)
            }

            if (activeLockPackage != null && activeLockPackage != packageName) {
                activeLockPackage = null
                isLockScreenVisible.set(false)
            }

            _engineState.value = LockEngineState.Idle
            scope.launch {
                updateMonitoringStatus(current = packageName, prev = prevPackage, source = source, requiresLock = false)
            }
            return
        }

        // 4. Protected Application Foreground Event!
        val t1 = eventTimeUptime
        val t2 = receivedTimeUptime
        val t3 = SystemClock.uptimeMillis()

        Log.i(TAG, "[AppLock] FOREGROUND → PROTECTED package=$packageName")

        // 5. SAME-PACKAGE RULE (Section 10):
        // If user is already inside this protected application, state is PROTECTED_APP_AUTHORIZED,
        // and authorization is valid, this is internal activity navigation (e.g. Chat -> Settings -> Back).
        // DO NOT lock!
        if (!isNewPackage &&
            activeProtectedPackage == packageName &&
            securityState == ForegroundSecurityState.PROTECTED_APP_AUTHORIZED &&
            lockDecisionManager.isPackageAuthorized(packageName)
        ) {
            Log.d(TAG, "[AppLock] SAME-PACKAGE NAVIGATION → package=$packageName (authorized)")
            _engineState.value = LockEngineState.AccessGranted(packageName, System.currentTimeMillis())
            return
        }

        // 6. New or Re-entry Protected App Launch (e.g. from HOME, Recents, or another app)
        currentForegroundPackage = packageName

        // 7. Authorization & Fail-Closed Security Decision (Section 8)
        val shouldLock = lockDecisionManager.shouldLock(packageName)
        val isAuthorized = lockDecisionManager.isPackageAuthorized(packageName)
        val t4 = SystemClock.uptimeMillis()

        if (isAuthorized && !shouldLock) {
            // PROTECTED + AUTHORIZED → ALLOW
            Log.i(TAG, "[AppLock] AUTHORIZATION → VALID")
            Log.i(TAG, "[AppLock] LOCK DECISION → ALLOW")
            activeProtectedPackage = packageName
            securityState = ForegroundSecurityState.PROTECTED_APP_AUTHORIZED
            _engineState.value = LockEngineState.AccessGranted(packageName, System.currentTimeMillis())
            scope.launch {
                updateMonitoringStatus(current = packageName, prev = prevPackage, source = source, requiresLock = false)
            }
            return
        }

        // PROTECTED + NOT_AUTHORIZED / EXPIRED / UNKNOWN → LOCK (Fail-Closed)
        Log.i(TAG, "[AppLock] AUTHORIZATION → ${if (isAuthorized) "EXPIRED" else "INVALID"}")
        Log.i(TAG, "[AppLock] LOCK DECISION → LOCK")

        // 8. Duplicate Event Protection (Section 13)
        val now = System.currentTimeMillis()
        val isScreenAlreadyResumed = LockScreenActivity.isResumed && LockScreenActivity.currentTargetPackage == packageName
        val isLaunchInProgress = (activeLockPackage == packageName && (now - lastLockLaunchTime < 350L))

        if (isScreenAlreadyResumed) {
            Log.v(TAG, "Duplicate lock suppressed: already resumed for $packageName")
            return
        }
        if (isLaunchInProgress) {
            Log.v(TAG, "Duplicate lock suppressed: launch in progress for $packageName (${now - lastLockLaunchTime}ms ago)")
            return
        }

        // 9. Execute Lock Request & Presentation (Section 7: Immediate, fail-closed)
        timingT1 = t1
        timingT2 = t2
        timingT3 = t3
        timingT4 = t4
        timingT5 = SystemClock.uptimeMillis()

        activeProtectedPackage = null
        activeLockPackage = packageName
        lastLockLaunchTime = now
        securityState = ForegroundSecurityState.PROTECTED_APP_LOCKED
        isLockScreenVisible.set(true)

        _engineState.value = LockEngineState.LockScreenShown(packageName, now)
        SecurityEventLogger.log(
            SecurityEventType.AUTH_REQUESTED,
            "Protected app '$packageName' detected via $source. Authentication required."
        )

        Log.i(TAG, "[AppLock] LOCK REQUEST → package=$packageName")
        Log.i(TAG, "[AppLock] LOCK ACTIVITY → LAUNCH")
        launchLockActivity(packageName)

        scope.launch {
            updateMonitoringStatus(current = packageName, prev = prevPackage, source = source, requiresLock = true)
        }
    }

    /**
     * Launches [LockScreenActivity] over the protected application with zero window animation delay.
     */
    private fun launchLockActivity(packageName: String) {
        try {
            val intent = Intent(context, LockScreenActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                putExtra(LockScreenActivity.EXTRA_PACKAGE_NAME, packageName)
            }
            val options = ActivityOptions.makeCustomAnimation(context, 0, 0)
            context.startActivity(intent, options.toBundle())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch LockScreenActivity for $packageName", e)
            activeLockPackage = null
            lastLockLaunchTime = 0L
            isLockScreenVisible.set(false)
        }
    }

    /**
     * Called when [LockScreenActivity] becomes visible in the foreground.
     */
    fun onLockScreenVisible(packageName: String) {
        val t6 = SystemClock.uptimeMillis()
        isLockScreenVisible.set(true)
        securityState = ForegroundSecurityState.AUTHENTICATING
        Log.i(TAG, "[AppLock] LOCK ACTIVITY → VISIBLE package=$packageName")

        if (activeLockPackage == packageName && timingT1 > 0L) {
            val t1 = timingT1
            val t2 = timingT2
            val t3 = timingT3
            val t4 = timingT4
            val t5 = timingT5
            val detectionLatency = (t2 - t1).coerceAtLeast(0)
            val decisionLatency = (t3 - t2).coerceAtLeast(0)
            val authDecisionLatency = (t4 - t3).coerceAtLeast(0)
            val launchLatency = (t5 - t4).coerceAtLeast(0)
            val uiLatency = (t6 - t5).coerceAtLeast(0)
            val totalLatency = (t6 - t1).coerceAtLeast(0)

            Log.i(
                TAG,
                "[AppLock] TIMING: T1=$t1 T2=$t2 T3=$t3 T4=$t4 T5=$t5 T6=$t6 | " +
                "Detection=${detectionLatency}ms Decision=${decisionLatency}ms Auth=${authDecisionLatency}ms " +
                "Launch=${launchLatency}ms UI=${uiLatency}ms Total=${totalLatency}ms | " +
                "Package=$packageName"
            )
            timingT1 = 0L
        }
    }

    /**
     * Called when authentication successfully completes in [LockScreenActivity].
     */
    fun onAuthenticationSuccess(packageName: String) {
        Log.i(TAG, "[AppLock] AUTH_SUCCESS: package=$packageName")
        SecurityEventLogger.log(
            SecurityEventType.AUTH_SUCCESS,
            "Authentication successful for protected package: $packageName"
        )
        lockDecisionManager.grantAccess(packageName)
        activeLockPackage = null
        lastLockLaunchTime = 0L
        isLockScreenVisible.set(false)
        activeProtectedPackage = packageName
        currentForegroundPackage = packageName
        securityState = ForegroundSecurityState.PROTECTED_APP_AUTHORIZED
        _engineState.value = LockEngineState.AccessGranted(packageName, System.currentTimeMillis())
        Log.i(TAG, "[AppLock] AUTH_SESSION: active | package=$packageName")
    }

    /**
     * Called when authentication is cancelled (e.g. user pressed Back or Home).
     */
    fun onAuthenticationCancelled(packageName: String) {
        Log.i(TAG, "Authentication cancelled for: $packageName")
        SecurityEventLogger.log(
            SecurityEventType.AUTH_CANCELLED,
            "Authentication cancelled for: $packageName. Safely redirected to Home."
        )
        activeLockPackage = null
        lastLockLaunchTime = 0L
        isLockScreenVisible.set(false)
        activeProtectedPackage = null
        securityState = ForegroundSecurityState.HOME
        currentForegroundPackage = lockDecisionManager.getLauncherPackage()
        _engineState.value = LockEngineState.Idle
        lockDecisionManager.revokeAccess(packageName)
    }

    /**
     * Called when [LockScreenActivity] finishes/destroys.
     */
    fun onLockScreenDestroyed(packageName: String) {
        Log.i(TAG, "[AppLock] LOCK_ACTIVITY: finish | package=$packageName")
        if (activeLockPackage == packageName) {
            activeLockPackage = null
            lastLockLaunchTime = 0L
            isLockScreenVisible.set(false)
        }
    }

    /**
     * Called when user starts interacting with the lock screen (entering PIN / biometric).
     */
    fun onAuthenticating(packageName: String) {
        securityState = ForegroundSecurityState.AUTHENTICATING
        _engineState.value = LockEngineState.Authenticating(packageName)
    }

    /**
     * Refreshes UI monitoring status representation.
     */
    suspend fun updateMonitoringStatus(
        current: String? = currentForegroundPackage,
        prev: String? = null,
        source: DetectionSource = DetectionSource.ACCESSIBILITY,
        requiresLock: Boolean = false
    ) {
        val lockedCount = preferences.getLockedPackages().size
        _monitoringStatus.value = _monitoringStatus.value.copy(
            currentPackage = current,
            previousPackage = prev,
            protectedAppCount = lockedCount,
            detectionSource = source,
            engineState = _engineState.value,
            lastLockedPackage = if (requiresLock) current else _monitoringStatus.value.lastLockedPackage,
            lastLockTimestamp = if (requiresLock) System.currentTimeMillis() else _monitoringStatus.value.lastLockTimestamp
        )
    }

    fun setAccessibilityEnabled(enabled: Boolean) {
        val prev = _monitoringStatus.value.isAccessibilityEnabled
        if (prev != enabled) {
            SecurityEventLogger.log(
                SecurityEventType.ACCESSIBILITY_STATE_CHANGED,
                "Accessibility service state changed: ${if (enabled) "ENABLED" else "DISABLED"}"
            )
        }
        _monitoringStatus.value = _monitoringStatus.value.copy(
            isAccessibilityEnabled = enabled,
            engineMode = resolveEngineMode(enabled, _monitoringStatus.value.hasUsageAccess)
        )
    }

    fun setUsageAccess(hasAccess: Boolean) {
        val prev = _monitoringStatus.value.hasUsageAccess
        if (prev != hasAccess) {
            SecurityEventLogger.log(
                SecurityEventType.USAGE_ACCESS_STATE_CHANGED,
                "Usage Access permission state changed: ${if (hasAccess) "GRANTED" else "REVOKED"}"
            )
        }
        _monitoringStatus.value = _monitoringStatus.value.copy(
            hasUsageAccess = hasAccess,
            engineMode = resolveEngineMode(_monitoringStatus.value.isAccessibilityEnabled, hasAccess)
        )
    }

    private fun resolveEngineMode(accessibility: Boolean, usageAccess: Boolean): LockEngineMode {
        return when {
            accessibility -> LockEngineMode.ACTIVE_ACCESSIBILITY
            usageAccess -> LockEngineMode.ACTIVE_USAGE_STATS
            else -> LockEngineMode.DISABLED
        }
    }
}
