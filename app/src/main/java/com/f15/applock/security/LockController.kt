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
import com.f15.applock.data.repository.AppTargetCache
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
        private const val RECENTS_RETURN_GRACE_MS = 30_000L

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
    var isRecentsScreenActive: Boolean = false
        private set

    @Volatile
    private var pendingRecentsProtectedPackage: String? = null

    @Volatile
    private var pendingRecentsTimestamp: Long = 0L

    @Volatile
    private var currentForegroundPackage: String? = null

    @Volatile
    private var activeProtectedPackage: String? = null

    // Track active package currently being locked to prevent duplicate activity launches
    @Volatile
    private var activeLockPackage: String? = null

    @Volatile
    private var lastLockLaunchTime: Long = 0L

    @Volatile
    private var lastExitedProtectedPackage: String? = null

    @Volatile
    private var lastExitedProtectedTimestamp: Long = 0L

    private val activePopUpPackages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Checks if [packageName] is currently registered or running in Pop-up View or Multi-Window.
     */
    fun isPackageInPopUpOrMultiWindow(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (activePopUpPackages.contains(packageName)) return true
        val a11y = com.f15.applock.accessibility.AppLockAccessibilityService.instance
        if (a11y != null && a11y.isPackageInMultiWindowOrFreeform(packageName)) {
            activePopUpPackages.add(packageName)
            return true
        }
        return false
    }

    /**
     * Explicitly registers or removes a package from active Pop-up View tracking.
     */
    fun markPackageInPopUpView(packageName: String, inPopUp: Boolean) {
        if (inPopUp) {
            activePopUpPackages.add(packageName)
        } else {
            activePopUpPackages.remove(packageName)
        }
    }

    @Volatile
    private var lastAuthSuccessTimestamp: Long = 0L

    @Volatile
    private var lastAuthSuccessPackage: String? = null

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
            hideRecentsPrivacyOverlay()
            if (pendingRecentsProtectedPackage != null) {
                val recentsExited = pendingRecentsProtectedPackage!!
                pendingRecentsProtectedPackage = null
                lockDecisionManager.onPackageExited(recentsExited)
            }
            isRecentsScreenActive = false
            val exited = activeProtectedPackage ?: currentForegroundPackage
            lockDecisionManager.onScreenOff(exited)
            securityState = ForegroundSecurityState.UNKNOWN
            currentForegroundPackage = null
            activeProtectedPackage = null
            activeLockPackage = null
            activePopUpPackages.clear()
            lastAuthSuccessPackage = null
            lastAuthSuccessTimestamp = 0L
            isLockScreenVisible.set(false)
            _engineState.value = LockEngineState.Idle
            updateMonitoringStatus()
        }
    }

    /**
     * Called when accessibility event TYPE_WINDOWS_CHANGED arrives.
     * Evaluates whether any active pop-up package was closed, minimized, or removed from screen.
     */
    fun onWindowsStructureChanged() {
        if (activePopUpPackages.isEmpty()) return
        val a11y = com.f15.applock.accessibility.AppLockAccessibilityService.instance ?: return

        val windowList = a11y.windows
        if (windowList.isNullOrEmpty()) return // Do not make negative decisions on transient empty lists

        val snapshot = activePopUpPackages.toList()
        for (pkg in snapshot) {
            if (!a11y.isPackageWindowPresent(pkg)) {
                Log.i(TAG, "[AppLock] Pop-up window for $pkg closed/dismissed from screen → revoking session")
                activePopUpPackages.remove(pkg)
                if (activeProtectedPackage == pkg) {
                    activeProtectedPackage = null
                }
                lastExitedProtectedPackage = pkg
                lastExitedProtectedTimestamp = SystemClock.uptimeMillis()
                lockDecisionManager.onPackageExited(pkg)
            }
        }
    }

    /**
     * Early detection hook triggered by AccessibilityEvent.TYPE_WINDOWS_CHANGED before window draw.
     */
    fun onEarlyWindowDetected(
        packageName: String?,
        source: DetectionSource,
        eventTimeUptime: Long = SystemClock.uptimeMillis(),
        receivedTimeUptime: Long = SystemClock.uptimeMillis()
    ) {
        if (packageName.isNullOrBlank()) return
        if (lockDecisionManager.isPackageProtected(packageName)) {
            onForegroundPackageChanged(packageName, source, eventTimeUptime, receivedTimeUptime)
        }
    }

    /**
     * Detailed window state change handler evaluating window class and full-screen state
     * to eliminate ghost triggers during teardown of backgrounded apps and ensure notifications
     * are never blocked.
     */
    fun onWindowStateChanged(
        packageName: String?,
        className: String?,
        isFullScreen: Boolean,
        source: DetectionSource,
        eventTimeUptime: Long = SystemClock.uptimeMillis(),
        receivedTimeUptime: Long = SystemClock.uptimeMillis()
    ) {
        if (packageName.isNullOrBlank()) return

        val isProtected = lockDecisionManager.isPackageProtected(packageName)

        // Track pop-up view (freeform window) / multi-window mode dynamically:
        // Any non-fullscreen event (activity, decor frame, or container) for a protected package
        // signifies that the application is operating in Samsung Pop-up View or Multi-Window.
        if (isProtected) {
            if (!isFullScreen) {
                activePopUpPackages.add(packageName)
                Log.d(TAG, "[$source] Protected package $packageName active in Pop-up / Freeform View (!isFullScreen, class=$className)")
            } else if (AppTargetCache.isActivity(packageName, className)) {
                // If an Activity explicitly launches in full-screen, verify it's not still in multi-window
                val a11y = com.f15.applock.accessibility.AppLockAccessibilityService.instance
                if (a11y == null || !a11y.isPackageInMultiWindowOrFreeform(packageName)) {
                    activePopUpPackages.remove(packageName)
                }
            }
        }

        // 0. Recents / Task Switcher Detection:
        val isRecents = isRecentsActivity(packageName, className)
        if (isRecents) {
            handleRecentsEntered(packageName, source)
            return
        }

        // 1. Notification & Floating Overlay Suppression:
        // Heads-up notifications (HUN), popup banners, toasts, and floating overlays are NOT full-screen
        // and are NOT activity launches. If a protected app emits a non-full-screen event that is not an Activity,
        // it must NEVER trigger AppLock, as doing so would block the user from seeing/receiving notifications!
        if (isProtected && !isFullScreen && !AppTargetCache.isActivity(packageName, className)) {
            Log.d(TAG, "[$source] Suppressing non-fullscreen notification/overlay for $packageName ($className)")
            return
        }

        // 2. Non-activity widget & teardown view filter:
        // Popups, toasts, menus, and common layout wrappers should never trigger app lock if the package
        // is not already the active authorized protected app.
        if (isProtected && AppTargetCache.isCommonView(className) && activeProtectedPackage != packageName) {
            // If user just exited this package to HOME or an unprotected app, suppress residual teardown views:
            if (packageName == lastExitedProtectedPackage &&
                (securityState == ForegroundSecurityState.HOME || securityState == ForegroundSecurityState.UNPROTECTED_APP)
            ) {
                Log.d(TAG, "[$source] Suppressing exit residual teardown view for $packageName ($className)")
                return
            }
            if (!isFullScreen) {
                Log.d(TAG, "[$source] Suppressing non-fullscreen common view for $packageName ($className)")
                return
            }
        }

        // 3. Teardown / Home Exit Filter:
        // When user exits to HOME or an unprotected app, suppress any residual events from the exited package
        // unless it's a genuine Activity launch.
        if ((securityState == ForegroundSecurityState.HOME || securityState == ForegroundSecurityState.UNPROTECTED_APP) &&
            packageName == lastExitedProtectedPackage &&
            !AppTargetCache.isActivity(packageName, className)
        ) {
            Log.d(TAG, "[$source] Suppressing exit residual non-activity event for $packageName ($className)")
            return
        }

        onForegroundPackageChanged(packageName, source, eventTimeUptime, receivedTimeUptime)
    }

    /**
     * Checks whether an incoming window event corresponds to the Recent Apps overview / Task Switcher.
     */
    fun isRecentsActivity(packageName: String?, className: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val lowerClass = className?.lowercase() ?: ""
        if (lowerClass.contains("recents") ||
            lowerClass.contains("quickstep") ||
            lowerClass.contains("overview") ||
            lowerClass.contains("taskview")
        ) {
            return true
        }
        if (packageName == "com.android.systemui" && lowerClass.contains("recents")) {
            return true
        }
        return false
    }

    private fun handleRecentsEntered(recentsPackage: String, source: DetectionSource) {
        val now = SystemClock.uptimeMillis()
        val exited = activeProtectedPackage

        isRecentsScreenActive = true
        currentForegroundPackage = recentsPackage

        if (exited != null) {
            // Protected application was active right before entering Recents!
            // Establish pending return session without wiping authorization
            pendingRecentsProtectedPackage = exited
            pendingRecentsTimestamp = now
            activeProtectedPackage = null
            Log.i(TAG, "[$source] Protected app $exited entered Recents overview → pending return grace active")
        }

        // Show the privacy overlay to mask protected app thumbnail preview in Recents
        val forceCenter = (pendingRecentsProtectedPackage != null) ||
                (lastExitedProtectedPackage != null && (now - lastExitedProtectedTimestamp < 300_000L))

        showRecentsPrivacyOverlay(forceCenterIfNoBounds = forceCenter)

        if (activeLockPackage != null) {
            activeLockPackage = null
            isLockScreenVisible.set(false)
        }

        _engineState.value = LockEngineState.Idle
        scope.launch {
            updateMonitoringStatus(current = recentsPackage, prev = exited, source = source, requiresLock = false)
        }
    }

    private fun showRecentsPrivacyOverlay(forceCenterIfNoBounds: Boolean) {
        val a11y = com.f15.applock.accessibility.AppLockAccessibilityService.instance
        val ctx = a11y ?: context
        val protectedPkgs = lockDecisionManager.getProtectedPackages()
        if (protectedPkgs.isEmpty()) return
        val labels = getProtectedAppLabels(protectedPkgs)
        val bounds = a11y?.findProtectedCardBoundsInRecents(labels)
        if (bounds != null || forceCenterIfNoBounds) {
            com.f15.applock.ui.overlay.RecentsPrivacyOverlay.show(ctx, bounds)
        }
    }

    private fun hideRecentsPrivacyOverlay() {
        val a11y = com.f15.applock.accessibility.AppLockAccessibilityService.instance
        com.f15.applock.ui.overlay.RecentsPrivacyOverlay.hide(a11y ?: context)
    }

    fun onRecentsScrolled() {
        val a11y = com.f15.applock.accessibility.AppLockAccessibilityService.instance ?: return
        val protectedPkgs = lockDecisionManager.getProtectedPackages()
        if (protectedPkgs.isEmpty()) return
        val labels = getProtectedAppLabels(protectedPkgs)
        val bounds = a11y.findProtectedCardBoundsInRecents(labels)
        if (bounds != null) {
            com.f15.applock.ui.overlay.RecentsPrivacyOverlay.updatePosition(a11y, bounds)
        }
    }

    private fun getProtectedAppLabels(packages: Set<String>): List<String> {
        val pm = context.packageManager
        val labels = mutableListOf<String>()
        for (pkg in packages) {
            labels.add(pkg)
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(info).toString()
                if (label.isNotBlank()) labels.add(label)
            } catch (_: Exception) {
            }
        }
        return labels
    }

    private fun isNonActivityWidget(className: String?): Boolean {
        return AppTargetCache.isCommonView(className)
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

        // Drop delayed/stale UsageStats events if real-time AccessibilityService is actively running
        if (source == DetectionSource.USAGE_STATS && com.f15.applock.accessibility.AppLockAccessibilityService.isServiceRunning) {
            Log.d(TAG, "[$source] Suppressing stale UsageStats event for $packageName (Accessibility is primary)")
            return
        }

        val prevPackage = currentForegroundPackage
        val isNewPackage = (packageName != prevPackage)

        // STEP 0 FAST PATH: If package is in protected set, handle immediately (O(1) in-memory check, <0.001ms)
        val isProtected = lockDecisionManager.isPackageProtected(packageName)
        if (isProtected) {
            val nowUptime = SystemClock.uptimeMillis()

            if (isRecentsScreenActive) {
                isRecentsScreenActive = false
                hideRecentsPrivacyOverlay()
            }

            // 0.5. RECENTS ROUND-TRIP RETURN RULE:
            // If user moved to Recents overview and now returns to the same protected app within grace window:
            if (pendingRecentsProtectedPackage == packageName &&
                (nowUptime - pendingRecentsTimestamp < RECENTS_RETURN_GRACE_MS) &&
                lockDecisionManager.isPackageAuthorized(packageName)
            ) {
                Log.i(TAG, "[AppLock] RETURN FROM RECENTS → ALLOW package=$packageName (seamless return, session preserved)")
                pendingRecentsProtectedPackage = null
                pendingRecentsTimestamp = 0L
                activeProtectedPackage = packageName
                currentForegroundPackage = packageName
                securityState = ForegroundSecurityState.PROTECTED_APP_AUTHORIZED
                _engineState.value = LockEngineState.AccessGranted(packageName, System.currentTimeMillis())
                scope.launch {
                    updateMonitoringStatus(current = packageName, prev = prevPackage, source = source, requiresLock = false)
                }
                return
            }

            // If user returned from Recents to a DIFFERENT protected app, invalidate the previous one
            if (pendingRecentsProtectedPackage != null && pendingRecentsProtectedPackage != packageName) {
                val previousPkg = pendingRecentsProtectedPackage!!
                pendingRecentsProtectedPackage = null
                lastExitedProtectedPackage = previousPkg
                lastExitedProtectedTimestamp = nowUptime
                Log.i(TAG, "[AppLock] Switched from Recents to different protected app $packageName → revoking $previousPkg")
                lockDecisionManager.onPackageExited(previousPkg)
            }

            val t1 = eventTimeUptime
            val t2 = receivedTimeUptime
            val t3 = nowUptime

            // 1. SAME-PACKAGE RULE (Section 10):
            // If user is already inside this protected application, state is PROTECTED_APP_AUTHORIZED,
            // and authorization is valid, this is internal activity navigation (e.g. Chat -> Settings -> Back).
            if (!isNewPackage &&
                activeProtectedPackage == packageName &&
                securityState == ForegroundSecurityState.PROTECTED_APP_AUTHORIZED &&
                lockDecisionManager.isPackageAuthorized(packageName)
            ) {
                _engineState.value = LockEngineState.AccessGranted(packageName, System.currentTimeMillis())
                return
            }

            // 2. New or Re-entry Protected App Launch (e.g. from HOME, Recents, or another app)
            currentForegroundPackage = packageName

            // 3. Authorization & Fail-Closed Security Decision (Section 8)
            val shouldLock = lockDecisionManager.shouldLock(packageName)
            val isAuthorized = lockDecisionManager.isPackageAuthorized(packageName)
            val t4 = SystemClock.uptimeMillis()

            if (isAuthorized && !shouldLock) {
                // PROTECTED + AUTHORIZED → ALLOW
                activeProtectedPackage = packageName
                securityState = ForegroundSecurityState.PROTECTED_APP_AUTHORIZED
                _engineState.value = LockEngineState.AccessGranted(packageName, System.currentTimeMillis())
                Log.d(TAG, "[AppLock] AUTHORIZATION VALID → ALLOW package=$packageName")
                scope.launch {
                    updateMonitoringStatus(current = packageName, prev = prevPackage, source = source, requiresLock = false)
                }
                return
            }

            // 4. Duplicate Event Protection (Section 13)
            val now = System.currentTimeMillis()
            val isScreenAlreadyResumed = LockScreenActivity.isResumed && LockScreenActivity.currentTargetPackage == packageName
            val isLaunchInProgress = (activeLockPackage == packageName && (now - lastLockLaunchTime < 350L))

            if (isScreenAlreadyResumed || isLaunchInProgress) {
                return
            }

            // 5. Zero-Delay Lock Presentation: Launch LockScreenActivity IMMEDIATELY before logging or allocations
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

            // CRITICAL: Launch activity as the very first operation
            launchLockActivity(packageName)

            // Defer non-critical logging, event tracking, and monitoring status updates
            _engineState.value = LockEngineState.LockScreenShown(packageName, now)
            Log.i(TAG, "[AppLock] LOCK TRIGGERED → package=$packageName (Detection=${t2 - t1}ms, Decision=${t4 - t3}ms)")

            SecurityEventLogger.log(
                SecurityEventType.AUTH_REQUESTED,
                "Protected app '$packageName' detected via $source. Authentication required."
            )

            scope.launch {
                updateMonitoringStatus(current = packageName, prev = prevPackage, source = source, requiresLock = true)
            }
            return
        }

        // NON-PROTECTED PATH (Launchers, System Overlays, and Unprotected Apps)

        // 1. Transient overlay check (soft keyboard, biometric prompt, self, system UI pull-down)
        if (lockDecisionManager.isTransientOverlay(packageName)) {
            Log.d(TAG, "[$source] Event: TYPE_WINDOW_STATE_CHANGED | Package: $packageName | State: TRANSIENT_OVERLAY | Decision: IGNORE — OVERLAY")
            return
        }

        // 2. HOME / Launcher Detection (Section 5)
        if (lockDecisionManager.isLauncherPackage(packageName)) {
            val now = SystemClock.uptimeMillis()

            if (isRecentsScreenActive) {
                isRecentsScreenActive = false
                hideRecentsPrivacyOverlay()
            }

            // If user exited Recents to Home, invalidate pending recents protected package
            if (pendingRecentsProtectedPackage != null) {
                val recentsExited = pendingRecentsProtectedPackage!!
                pendingRecentsProtectedPackage = null
                lastExitedProtectedPackage = recentsExited
                lastExitedProtectedTimestamp = now
                Log.i(TAG, "[$source] Exited Recents to HOME → invalidating session for $recentsExited")
                lockDecisionManager.onPackageExited(recentsExited)
            }

            // A. LockScreenActivity dismissal settle grace window:
            // When user authenticates, LockScreenActivity finishes and uncovers the underlying launcher/app.
            // Do NOT treat this transition settle event as user exiting the protected app!
            if (activeProtectedPackage != null &&
                activeProtectedPackage == lastAuthSuccessPackage &&
                (now - lastAuthSuccessTimestamp < 2000L)
            ) {
                Log.d(TAG, "[$source] Suppressing false exit during unlock dismissal settle window for $activeProtectedPackage")
                return
            }

            val exited = activeProtectedPackage
            currentForegroundPackage = packageName
            securityState = ForegroundSecurityState.HOME

            if (exited != null) {
                if (isPackageInPopUpOrMultiWindow(exited)) {
                    // Protected package is active in Pop-up View floating over Home desktop!
                    // The user is multi-tasking outside the pop-up view.
                    // Preserve its session and do NOT wipe authorization!
                    Log.i(TAG, "[AppLock] Focus shifted to HOME, but $exited remains active in Pop-up View on screen")
                    activeProtectedPackage = null
                } else {
                    activeProtectedPackage = null
                    lastExitedProtectedPackage = exited
                    lastExitedProtectedTimestamp = now
                    Log.i(TAG, "[AppLock] FOREGROUND → HOME | PROTECTED EXIT → package=$exited")
                    lockDecisionManager.onPackageExited(exited)
                }
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
        val now = SystemClock.uptimeMillis()

        if (isRecentsScreenActive) {
            isRecentsScreenActive = false
            hideRecentsPrivacyOverlay()
        }

        // If user exited Recents to an unprotected app, invalidate pending recents protected package
        if (pendingRecentsProtectedPackage != null) {
            val recentsExited = pendingRecentsProtectedPackage!!
            pendingRecentsProtectedPackage = null
            lastExitedProtectedPackage = recentsExited
            lastExitedProtectedTimestamp = now
            Log.i(TAG, "[$source] Exited Recents to unprotected app $packageName → invalidating session for $recentsExited")
            lockDecisionManager.onPackageExited(recentsExited)
        }

        // A. LockScreenActivity dismissal settle grace window:
        if (activeProtectedPackage != null &&
            activeProtectedPackage == lastAuthSuccessPackage &&
            (now - lastAuthSuccessTimestamp < 2000L)
        ) {
            Log.d(TAG, "[$source] Suppressing false exit during unlock dismissal settle window for $activeProtectedPackage")
            return
        }

        val exited = activeProtectedPackage
        currentForegroundPackage = packageName
        securityState = ForegroundSecurityState.UNPROTECTED_APP

        if (exited != null) {
            if (isPackageInPopUpOrMultiWindow(exited)) {
                // Protected package is active in Pop-up View floating over this backdrop application!
                // The user is multi-tasking outside the pop-up view (e.g. Dialer, Chrome, Notes).
                // Preserve its session and do NOT wipe authorization!
                Log.i(TAG, "[AppLock] Focus shifted to $packageName, but $exited remains active in Pop-up View on screen")
                activeProtectedPackage = null
            } else {
                activeProtectedPackage = null
                lastExitedProtectedPackage = exited
                lastExitedProtectedTimestamp = now
                Log.i(TAG, "[AppLock] PROTECTED EXIT → package=$exited")
                lockDecisionManager.onPackageExited(exited)
            }
        }

        if (activeLockPackage != null && activeLockPackage != packageName) {
            activeLockPackage = null
            isLockScreenVisible.set(false)
        }

        _engineState.value = LockEngineState.Idle
        scope.launch {
            updateMonitoringStatus(current = packageName, prev = prevPackage, source = source, requiresLock = false)
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
        lastExitedProtectedPackage = null
        lastExitedProtectedTimestamp = 0L
        lastAuthSuccessPackage = packageName
        lastAuthSuccessTimestamp = SystemClock.uptimeMillis()
        pendingRecentsProtectedPackage = null
        pendingRecentsTimestamp = 0L
        securityState = ForegroundSecurityState.PROTECTED_APP_AUTHORIZED
        _engineState.value = LockEngineState.AccessGranted(packageName, System.currentTimeMillis())
        Log.i(TAG, "[AppLock] AUTH_SESSION: active | package=$packageName")

        if (isPackageInPopUpOrMultiWindow(packageName)) {
            activePopUpPackages.add(packageName)
            Log.i(TAG, "[AppLock] Active pop-up session confirmed for $packageName")
        }
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
        pendingRecentsProtectedPackage = null
        pendingRecentsTimestamp = 0L
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
