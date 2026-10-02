package com.f15.applock.detection

import android.content.Context
import android.util.Log
import com.f15.applock.domain.model.LockRequest
import com.f15.applock.security.LockDecisionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Battery-conscious foreground application monitor.
 *
 * Responsibilities:
 * - Periodically inspects [ForegroundAppDetector] on a 500ms cadence.
 * - Tracks application transitions (`previousPackage` -> `currentPackage`).
 * - Suppresses duplicate events while an app remains in the foreground.
 * - Delegates lock decisions to [LockDecisionManager].
 * - Emits [LockRequest] when a protected application is accessed without an active session.
 */
class ForegroundAppMonitor(
    private val context: Context,
    private val detector: ForegroundAppDetector,
    private val lockDecisionManager: LockDecisionManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    companion object {
        private const val TAG = "ForegroundAppMonitor"
        private const val POLL_INTERVAL_MS = 500L
    }

    private var monitorJob: Job? = null

    private var previousPackage: String? = null
    private var currentPackage: String? = null

    private val _detectionState = MutableStateFlow(DetectionState())
    val detectionState: StateFlow<DetectionState> = _detectionState.asStateFlow()

    private val _lockRequests = MutableSharedFlow<LockRequest>(extraBufferCapacity = 1)
    val lockRequests: SharedFlow<LockRequest> = _lockRequests.asSharedFlow()

    fun isMonitoring(): Boolean = monitorJob?.isActive == true

    /**
     * Starts foreground polling if not already running.
     */
    @Synchronized
    fun startMonitoring() {
        if (monitorJob?.isActive == true) return

        monitorJob = scope.launch {
            _detectionState.value = _detectionState.value.copy(
                isMonitoring = true,
                hasUsageAccess = detector.hasUsageAccessPermission()
            )

            Log.i(TAG, "Foreground monitoring started (interval: ${POLL_INTERVAL_MS}ms)")

            while (isActive) {
                try {
                    // When AccessibilityService is running, it provides real-time event-driven detection.
                    // Suspend UsageStats polling to preserve battery and prevent asynchronous stale UsageStats
                    // events (1-5s delay) from overriding real-time Accessibility state.
                    val isA11yActive = com.f15.applock.accessibility.AppLockAccessibilityService.isServiceRunning
                    if (isA11yActive) {
                        delay(2500L)
                        continue
                    }

                    val activePackage = detector.getForegroundPackage()

                    if (activePackage != null && activePackage != currentPackage) {
                        val prev = currentPackage
                        previousPackage = prev
                        currentPackage = activePackage

                        // Route through central LockController as fallback
                        com.f15.applock.security.LockController.getInstance(context)
                            .onForegroundPackageChanged(activePackage, com.f15.applock.domain.model.DetectionSource.USAGE_STATS)

                        lockDecisionManager.onPackageTransition(prev, activePackage)
                        val requiresLock = lockDecisionManager.shouldLock(activePackage)

                        Log.d(TAG, "UsageStats fallback transition: $prev -> $activePackage | requiresLock: $requiresLock")

                        _detectionState.value = _detectionState.value.copy(
                            currentPackage = activePackage,
                            previousPackage = prev,
                            isProtected = requiresLock,
                            hasUsageAccess = true
                        )

                        if (requiresLock) {
                            val request = LockRequest(activePackage)
                            _detectionState.value = _detectionState.value.copy(
                                lastLockRequestPackage = activePackage,
                                lastLockRequestTimestamp = request.timestamp
                            )
                            _lockRequests.emit(request)
                        }
                    }

                    delay(POLL_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in foreground polling cycle", e)
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    /**
     * Stops the polling loop and cancels the underlying coroutine job.
     */
    @Synchronized
    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        currentPackage = null
        previousPackage = null
        _detectionState.value = _detectionState.value.copy(isMonitoring = false)
        Log.i(TAG, "Foreground monitoring stopped")
    }

    /**
     * Refreshes the Usage Access permission flag in the diagnostic state.
     */
    fun refreshPermissionState() {
        _detectionState.value = _detectionState.value.copy(
            hasUsageAccess = detector.hasUsageAccessPermission()
        )
    }
}
